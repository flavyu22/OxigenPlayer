// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/**
 * Pure filename-to-title parser. It deliberately removes only unambiguous release metadata so
 * legitimate titles such as "Area 51" or "Charlotte's Web" are not damaged.
 */
object MediaTitleParser {
    private val extensionRegex = Regex(
        "\\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|3gp|ts|m2ts|mpg|mpeg)$",
        RegexOption.IGNORE_CASE,
    )
    // Escape the closing delimiters explicitly. Java's desktop regex engine accepts `[^]`,
    // but Android's ICU engine rejects it and used to crash playback while deriving the title.
    private val bracketGroupRegex = Regex(
        "\\[[^\\]\\r\\n]{1,80}\\]|\\{[^}\\r\\n]{1,80}\\}",
    )

    // Metadata containing separators must be removed before dots/underscores/hyphens are normalized.
    private val compoundMetadataRegexes = listOf(
        Regex("(?i)(?<![\\p{L}\\p{N}])web[ ._-]?(?:dl|rip)(?![\\p{L}\\p{N}])"),
        Regex("(?i)(?<![\\p{L}\\p{N}])blu[ ._-]?ray(?![\\p{L}\\p{N}])"),
        Regex("(?i)(?<![\\p{L}\\p{N}])(?:br|bd|dvd)[ ._-]?rip(?![\\p{L}\\p{N}])"),
        Regex("(?i)(?<![\\p{L}\\p{N}])dolby[ ._-]?vision(?![\\p{L}\\p{N}])"),
        Regex("(?i)(?<![\\p{L}\\p{N}])(?:ddp?|eac3|ac3|dts(?:hd|x)?)[ ._-]?(?:5[ ._-]?1|7[ ._-]?1)(?!\\d)"),
        Regex("(?i)(?<!\\d)(?:5|7)[ ._-]1(?!\\d)"),
        Regex("(?i)(?<![\\p{L}\\p{N}])10[ ._-]?bit(?![\\p{L}\\p{N}])"),
    )

    private val resolutionRegex = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:480|576|720|1080|1440|2160|4320)[pi](?![\\p{L}\\p{N}])|" +
            "(?i)(?<![\\p{L}\\p{N}])(?:4k|8k|uhd)(?![\\p{L}\\p{N}])",
    )
    private val technicalTokenRegex = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            "x26[45]|h26[45]|hevc|avc|av1|vvc|h266|vp9|xvid|divx|" +
            "aac|dts|dtshd|dtsx|ac3|eac3|truehd|atmos|opus|flac|alac|ddp?|" +
            "remux|hdtv|camrip|cam|telesync|telecine|hdr10\\+?|hdr|dv|sdr|" +
            "proper|repack|rerip|extended|unrated|limited|internal|readnfo|multi|dual[ ._-]?audio" +
            ")(?![\\p{L}\\p{N}])",
    )
    private val disallowedCharacterRegex = Regex("[^\\p{L}\\p{N}\\s'’&.:!-]")
    private val multipleSpaceRegex = Regex("\\s+")

    // A year that stands as its own token ("Movie.2019.1080p", "Movie (2019)").
    private val standaloneYearRegex = Regex("""(?<![\p{L}\p{N}])[(\[]?((?:19|20)\d{2})[)\]]?(?![\p{L}\p{N}])""")
    private val episodeRegex = Regex("""(?i)(?<![\p{L}\p{N}])s(\d{1,2})[ ._-]?e(\d{1,3})(?!\d)""")
    // Tokens that unambiguously start the release-metadata tail of a scene filename.
    private val metadataStartRegex = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:""" +
            """(?:480|576|720|1080|1440|2160|4320)[pi]|4k|8k|uhd|""" +
            """web[ ._-]?(?:dl|rip)|blu[ ._-]?ray|(?:br|bd|dvd)[ ._-]?rip|hdtv|remux|""" +
            """x26[45]|h26[45]|hevc|xvid|divx|hdr10\+?""" +
            """)(?![\p{L}\p{N}])""",
    )
    private val bracketedYearRegex = Regex("""\[((?:19|20)\d{2})]""")
    private val dotSeparatorRegex = Regex("""\.(?!\d{1,2}(?!\d))|(?<!\d)\.""")
    private val trailingGroupRegex = Regex("""-[\p{L}\p{N}]+\s*$""")
    private val looseHyphenRegex = Regex("""(?<![\p{L}\p{N}])-+|-+(?![\p{L}\p{N}])""")
    // Download-site prefixes: "www.1TamilMV.io - Movie", "www.Site.com.Movie", "Site.io - Movie".
    // Without "www." a domain only counts when a " - " style separator follows, so titles such
    // as "Love.In.Paris" are left alone.
    private const val SITE_TLDS = "io|com|net|org|me|in|cc|xyz|to|ws|tv|co|biz|info|lol|site|top|club|vip|link|fun|app|ru|nl|pw|se|is|li|ph|eu|uk|mx"
    private val leadingSiteRegex = Regex(
        """^\s*(?:(?i:www)[.\s][\p{L}\p{N}-]+(?:[.\s][\p{L}\p{N}-]+)*?[.\s](?i:$SITE_TLDS)(?![\p{L}\p{N}])""" +
            """|[\p{L}\p{N}-]+\.(?i:$SITE_TLDS)(?=\s*[-–_|]))\s*[-–_|.]*\s*""",
    )

    fun extractYear(fileName: String): String? = parse(fileName).second

    /**
     * Splits a filename into the bare title and year. Scene names put every piece of release
     * metadata (resolution, source, codec, group) after the year or episode tag, so the title is
     * cut there instead of trying to delete each tag one by one — that used to leave release
     * groups and stray tokens in the displayed name.
     */
    private fun parse(fileName: String): Pair<String, String?> {
        var name = fileName.substringBefore('?').substringBefore('#')
        name = extensionRegex.replace(name, "")
        // Keep a bracketed year before generic [..] groups are dropped.
        name = bracketedYearRegex.replace(name, " $1 ")
        name = bracketGroupRegex.replace(name, " ")
        name = leadingSiteRegex.replace(name, "")
        name = name.replace('_', ' ')
        // Dots are separators in scene names, but keep them in initials/numbers such as "3.5".
        name = dotSeparatorRegex.replace(name, " ")

        val metadataStart = metadataStartRegex.find(name)?.range?.first ?: name.length
        val episode = episodeRegex.find(name)?.takeIf { it.range.first < metadataStart }
        val searchEnd = episode?.range?.first ?: metadataStart

        // The last standalone year with real text before it is the release year; earlier
        // numbers belong to the title ("Blade Runner 2049 2017", "1917 2019").
        val year = standaloneYearRegex.findAll(name.substring(0, searchEnd))
            .lastOrNull { name.substring(0, it.range.first).any(Char::isLetterOrDigit) }

        var title = when {
            episode != null -> {
                val tag = "S" + episode.groupValues[1].padStart(2, '0') + "E" + episode.groupValues[2].padStart(2, '0')
                name.substring(0, year?.range?.first ?: episode.range.first) + " " + tag
            }
            year != null -> name.substring(0, year.range.first)
            else -> name.substring(0, metadataStart)
        }
        if (title.none(Char::isLetterOrDigit)) title = name

        compoundMetadataRegexes.forEach { regex -> title = regex.replace(title, " ") }
        title = resolutionRegex.replace(title, " ")
        title = technicalTokenRegex.replace(title, " ")
        if (year == null) {
            // No year cut: drop a trailing "-GROUP" release tag.
            title = trailingGroupRegex.replace(title, " ")
        }
        // Hyphens between letters are part of the title ("Spider-Man"); others are separators.
        title = looseHyphenRegex.replace(title, " ")
        title = disallowedCharacterRegex.replace(title, " ")
        title = multipleSpaceRegex.replace(title, " ").trim()
        return title to year?.groupValues?.get(1)
    }

    fun cleanTitle(fileName: String, keepYear: Boolean = false): String {
        val (title, year) = parse(fileName)
        return if (keepYear && year != null) "$title $year".trim() else title
    }

    fun titleWithYear(fileName: String): String {
        val (title, year) = parse(fileName)
        return when {
            title.isBlank() -> year.orEmpty()
            year == null -> title
            else -> "$title $year"
        }
    }

    /** Name + year without technical details; used as the online subtitle search query. */
    fun titleWithName(fileName: String): String = titleWithYear(fileName)

    /** On-screen name: "Movie Name (2019)" or "Show Name S01E02". */
    fun displayTitle(fileName: String): String {
        val (title, year) = parse(fileName)
        return when {
            title.isBlank() -> year.orEmpty()
            year == null -> title
            else -> "$title ($year)"
        }
    }
}
