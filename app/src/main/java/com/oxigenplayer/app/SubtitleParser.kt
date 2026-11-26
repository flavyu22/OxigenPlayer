// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class SubtitleEntry(
    val index: Int,
    val startTime: Long,
    val endTime: Long,
    val text: String,
)

enum class SubtitleFormat { SRT, WEBVTT, ASS, SMI, TTML }

sealed interface SubtitleParseResult {
    val entries: List<SubtitleEntry>

    data class Success(
        override val entries: List<SubtitleEntry>,
        val format: SubtitleFormat,
    ) : SubtitleParseResult

    data class Partial(
        override val entries: List<SubtitleEntry>,
        val format: SubtitleFormat,
        val rejectedCues: Int,
        val reachedEntryLimit: Boolean,
    ) : SubtitleParseResult

    data class Failure(val reason: SubtitleParseFailure) : SubtitleParseResult {
        override val entries: List<SubtitleEntry> = emptyList()
    }
}

enum class SubtitleParseFailure {
    EMPTY,
    UNSUPPORTED_ENCODING,
    UNSUPPORTED_FORMAT,
    NO_VALID_CUES,
    TOO_LARGE,
}

/** Bounded parser for SRT, WebVTT and SSA/ASS subtitle documents. */
object SubtitleParser {
    const val MAX_ENTRIES = 100_000
    const val MAX_CUE_TEXT_CHARS = 16_384
    const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024

    private val SRT_TIME by lazy {
        Regex("""^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})(?:\s+.*)?$""")
    }
    private val VTT_TIME by lazy {
        Regex("""^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.](\d{1,3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.](\d{1,3})(?:\s+.*)?$""")
    }
    private val HTML_TAG by lazy { Regex("<[^>]*>") }
    private val VTT_HTML_TAG by lazy { Regex("<[^>]+>") }
    private val BR_TAG by lazy { Regex("(?i)<br\\s*/?>") }
    private val NUMERIC_ENTITY by lazy { Regex("&#(x[0-9a-fA-F]+|[0-9]+);") }
    private val ASS_TAG by lazy { Regex("\\{[^}]*}") }
    private val ASS_NEWLINE by lazy { Regex("""\\+[Nn]""") }
    private val ASS_SPACE by lazy { Regex("""\\+[Hh]""") }
    private val TTML_BEGIN by lazy { Regex("""begin\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE) }
    private val TTML_END by lazy { Regex("""end\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE) }
    private val TTML_TICK_RATE by lazy { Regex("""ttp:tickRate\s*=\s*"(\d{1,12})"""", RegexOption.IGNORE_CASE) }
    private val TTML_FRAME_RATE by lazy { Regex("""ttp:frameRate\s*=\s*"(\d{1,4})"""", RegexOption.IGNORE_CASE) }
    private val SAMI_SYNC by lazy {
        Regex("""<SYNC\b[^>]*?\bStart\s*=\s*["']?(\d{1,10})["']?[^>]*>""", RegexOption.IGNORE_CASE)
    }
    private val SAMI_BODY_END by lazy { Regex("""</BODY\s*>""", RegexOption.IGNORE_CASE) }
    private val SAMI_PARAGRAPH by lazy { Regex("""(?i)</?P\b[^>]*>""") }
    private val WHITESPACE_RUN by lazy { Regex("""\s+""") }

    /** Duration given to the last SAMI cue, which has no following SYNC to end it. */
    private const val SAMI_LAST_CUE_MS = 5_000L

    /** Ordered longest-suffix-first so `ms` is matched before `s`. */
    private val UNIT_MULTIPLIERS = linkedMapOf(
        "ms" to 1.0,
        "s" to 1_000.0,
        "m" to 60_000.0,
        "h" to 3_600_000.0,
    )

    private val DEFAULT_ASS_FIELDS = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
    )

    internal fun detectFormat(content: String, fileName: String? = null, mimeType: String? = null): SubtitleFormat? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (content.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("WEBVTT", ignoreCase = true)) {
            return SubtitleFormat.WEBVTT
        }
        if (content.contains("[Script Info]", ignoreCase = true) ||
            content.lineSequence().take(100).any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }
        ) return SubtitleFormat.ASS
        if (looksLikeSami(content)) return SubtitleFormat.SMI
        if (content.lineSequence().take(100).any { it.contains("-->") }) {
            return when (extension) {
                "vtt" -> SubtitleFormat.WEBVTT
                // SubRip Micro (.smi) shares the `-->` timing syntax; only the decimals differ
                // (`,` in SRT versus `.` in SMI), so it is parsed with the SRT time pattern.
                "smi" -> SubtitleFormat.SMI
                else -> when {
                    normalizedMime == "text/vtt" -> SubtitleFormat.WEBVTT
                    normalizedMime == "application/smil" || normalizedMime == "application/x-smil" ->
                        SubtitleFormat.SMI
                    // No usable extension or MIME type: fall back to the SMI markers themselves.
                    // An extension-less sidecar otherwise parsed as SRT, which is only harmless while
                    // the file also happens to use comma decimals.
                    looksLikeSmi(content) -> SubtitleFormat.SMI
                    else -> SubtitleFormat.SRT
                }
            }
        }
        // TTML/DFXP is XML; detect it structurally so an extension-less or mislabelled file
        // is still recognised rather than rejected as an unknown format.
        if (looksLikeTtml(content)) return SubtitleFormat.TTML
        return when (extension) {
            "srt" -> SubtitleFormat.SRT
            "smi" -> SubtitleFormat.SMI
            "vtt" -> SubtitleFormat.WEBVTT
            "ass", "ssa" -> SubtitleFormat.ASS
            "ttml", "dfxp" -> SubtitleFormat.TTML
            else -> null
        }
    }

    /** Real SAMI documents: `<SAMI>` root and `<SYNC Start=ms>` cues, no `-->` timing lines. */
    private fun looksLikeSami(content: String): Boolean =
        content.take(16_384).contains("<SAMI", ignoreCase = true) && SAMI_SYNC.containsMatchIn(content)

    /** SMI files carry a `Sync=` header and/or an `<SMI>` root before the first cue. */
    private fun looksLikeSmi(content: String): Boolean = content.lineSequence().take(20).any { line ->
        val trimmed = line.trim()
        trimmed.startsWith("<SMI", ignoreCase = true) ||
            trimmed.startsWith("Sync=", ignoreCase = true) ||
            trimmed.startsWith("<STYLE", ignoreCase = true) ||
            trimmed.contains("<font", ignoreCase = true) ||
            trimmed.contains("<i>", ignoreCase = true)
    }

    /** TTML is XML carrying a `<tt>` root and per-cue `begin`/`end` attributes. */
    private fun looksLikeTtml(content: String): Boolean {
        // '\uFEFF' spelled as an escape, matching detectFormat, so no literal BOM lands in the source.
        val head = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!head.startsWith("<")) return false
        val sample = head.take(4_096)
        return sample.contains("<tt", ignoreCase = true) &&
            (sample.contains("begin=", ignoreCase = true) || sample.contains("<p", ignoreCase = true))
    }

    internal fun decodeText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return normalizeLegacyRomanian(String(bytes, 3, bytes.size - 3, Charsets.UTF_8))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return normalizeLegacyRomanian(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return normalizeLegacyRomanian(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))
        }

        decodeStrictUtf8(bytes)?.takeUnless(::containsSuspiciousControls)?.let { return normalizeLegacyRomanian(it) }
        val fallbacks = listOf("windows-1250", "windows-1252", "ISO-8859-2", "UTF-16LE", "UTF-16BE")
        return fallbacks
            .asSequence()
            .map { String(bytes, Charset.forName(it)) }
            .filterNot(::containsSuspiciousControls)
            .map { it to decodingScore(it) }
            .maxByOrNull { it.second }
            ?.first
            ?.let(::normalizeLegacyRomanian)
    }

    private fun normalizeLegacyRomanian(value: String): String = value
        .replace('Ş', 'Ș')
        .replace('ş', 'ș')
        .replace('Ţ', 'Ț')
        .replace('ţ', 'ț')

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun containsSuspiciousControls(value: String): Boolean =
        value.any { it.code in 0x00..0x08 || it.code in 0x0E..0x1F }

    private fun decodingScore(value: String): Int {
        var score = 0
        if (value.contains("-->")) score += 10
        if (value.contains("Dialogue:", ignoreCase = true)) score += 10
        if (value.contains("WEBVTT", ignoreCase = true)) score += 10
        score -= value.count { it == '\uFFFD' } * 20
        score -= value.count { it.code in 0x80..0x9F } * 5
        score += value.count { it in "ăâîșțĂÂÎȘȚéèêëáàäöüçžčćß" }
        return score
    }

    internal fun decodeSubtitleEntities(value: String): String {
        var current = value
        repeat(3) {
            val decoded = current
                .replace("&nbsp;", " ", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&apos;", "'", ignoreCase = true)
                .replace("&#39;", "'", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .replace(NUMERIC_ENTITY) { match ->
                    val token = match.groupValues[1]
                    val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                        token.drop(1).toIntOrNull(16)
                    } else {
                        token.toIntOrNull()
                    }
                    codePoint
                        ?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                        ?.let { String(Character.toChars(it)) }
                        ?: match.value
                }
            if (decoded == current) return current
            current = decoded
        }
        return current
    }

    fun parse(
        inputStream: InputStream,
        fileName: String? = null,
        mimeType: String? = null,
    ): SubtitleParseResult {
        val bytes = readBounded(inputStream)
            ?: return SubtitleParseResult.Failure(SubtitleParseFailure.TOO_LARGE)
        return parse(bytes, fileName, mimeType)
    }

    private fun readBounded(inputStream: InputStream): ByteArray? {
        val output = ByteArrayOutputStream(minOf(MAX_DOCUMENT_BYTES, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val singleByte = inputStream.read()
                if (singleByte < 0) break
                if (++total > MAX_DOCUMENT_BYTES) return null
                output.write(singleByte)
                continue
            }
            total += read
            if (total > MAX_DOCUMENT_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun parse(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
    ): SubtitleParseResult {
        if (bytes.isEmpty()) return SubtitleParseResult.Failure(SubtitleParseFailure.EMPTY)
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            return SubtitleParseResult.Failure(SubtitleParseFailure.TOO_LARGE)
        }
        val content = decodeText(bytes)
            ?: return SubtitleParseResult.Failure(SubtitleParseFailure.UNSUPPORTED_ENCODING)
        return parseContent(content, fileName, mimeType)
    }

    fun parseContent(
        content: String,
        fileName: String? = null,
        mimeType: String? = null,
    ): SubtitleParseResult {
        if (content.isBlank()) return SubtitleParseResult.Failure(SubtitleParseFailure.EMPTY)
        val format = detectFormat(content, fileName, mimeType)
            ?: return SubtitleParseResult.Failure(SubtitleParseFailure.UNSUPPORTED_FORMAT)
        val parsed = when (format) {
            SubtitleFormat.SRT -> parseArrowFormat(content, SRT_TIME, isWebVtt = false)
            SubtitleFormat.WEBVTT -> parseArrowFormat(content, VTT_TIME, isWebVtt = true)
            // SMI timings are `HH:MM:SS,FF` identical to SRT; only the surrounding markup differs,
            // and cleanCueText already strips the `<font>`/`<i>` tags SMI files carry.
            SubtitleFormat.SMI -> if (SAMI_SYNC.containsMatchIn(content)) {
                parseSami(content)
            } else {
                parseArrowFormat(content, SRT_TIME, isWebVtt = false)
            }
            SubtitleFormat.TTML -> parseTtml(content)
            SubtitleFormat.ASS -> parseAss(content)
        }
        if (parsed.entries.isEmpty()) {
            return SubtitleParseResult.Failure(SubtitleParseFailure.NO_VALID_CUES)
        }
        val normalized = parsed.entries
            .sortedWith(compareBy<SubtitleEntry> { it.startTime }.thenBy { it.endTime })
            .mapIndexed { index, entry -> entry.copy(index = index) }
        return if (parsed.rejected > 0 || parsed.reachedLimit) {
            SubtitleParseResult.Partial(normalized, format, parsed.rejected, parsed.reachedLimit)
        } else {
            SubtitleParseResult.Success(normalized, format)
        }
    }

    /** Compatibility methods retained for existing call sites and older tests. */
    fun parseSRT(inputStream: InputStream): List<SubtitleEntry> =
        parse(inputStream, fileName = "subtitle.srt").entries

    fun parseSRTFromContent(content: String): List<SubtitleEntry> =
        parseContent(content, fileName = "subtitle.srt").entries

    private data class Parsed(val entries: List<SubtitleEntry>, val rejected: Int, val reachedLimit: Boolean)

    private fun parseArrowFormat(content: String, timeRegex: Regex, isWebVtt: Boolean): Parsed {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val entries = ArrayList<SubtitleEntry>(minOf(lines.size / 3, 2_000))
        var rejected = 0
        var index = 0
        while (index < lines.size && entries.size < MAX_ENTRIES) {
            val line = lines[index].trim().trimStart('\uFEFF')
            if (isWebVtt && (line.equals("WEBVTT", true) || line.startsWith("NOTE") ||
                    line.startsWith("STYLE") || line.startsWith("REGION"))) {
                index++
                while (index < lines.size && lines[index].isNotBlank()) index++
                continue
            }
            var match = timeRegex.matchEntire(line)
            if (match == null && index + 1 < lines.size) {
                // Optional numeric SRT index or WebVTT cue identifier.
                match = timeRegex.matchEntire(lines[index + 1].trim())
                if (match != null) index++
            }
            if (match == null) {
                index++
                continue
            }

            val (start, end) = if (isWebVtt) parseVttRange(match) else parseSrtRange(match)
            index++
            val text = StringBuilder()
            var tooLarge = false
            while (index < lines.size && lines[index].isNotBlank()) {
                val candidate = lines[index]
                val candidateTrimmed = candidate.trim()
                val isCueIdentifier = if (isWebVtt) {
                    candidateTrimmed.isNotEmpty()
                } else {
                    candidateTrimmed.all(Char::isDigit) && candidateTrimmed.isNotEmpty()
                }
                val startsNextCue = timeRegex.matches(candidateTrimmed) ||
                    (isCueIdentifier && index + 1 < lines.size &&
                        timeRegex.matches(lines[index + 1].trim()))
                if (startsNextCue) break

                if (text.length + candidate.length + 1 > MAX_CUE_TEXT_CHARS) {
                    tooLarge = true
                } else if (!tooLarge) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(candidate)
                }
                index++
            }
            val cleaned = cleanCueText(text.toString(), isWebVtt)
            if (!tooLarge && start >= 0L && end > start && cleaned.isNotBlank()) {
                entries += SubtitleEntry(entries.size, start, end, cleaned)
            } else {
                rejected++
            }
        }
        return Parsed(entries, rejected, entries.size >= MAX_ENTRIES && index < lines.size)
    }

    private fun parseSrtRange(match: MatchResult): Pair<Long, Long> {
        fun part(offset: Int): Long? = timestamp(
            match.groupValues[offset].toIntOrNull(),
            match.groupValues[offset + 1].toIntOrNull(),
            match.groupValues[offset + 2].toIntOrNull(),
            normalizeMillis(match.groupValues[offset + 3]),
        )
        return (part(1) ?: -1L) to (part(5) ?: -1L)
    }

    private fun parseVttRange(match: MatchResult): Pair<Long, Long> {
        fun part(offset: Int): Long? = timestamp(
            match.groupValues[offset].ifBlank { "0" }.toIntOrNull(),
            match.groupValues[offset + 1].toIntOrNull(),
            match.groupValues[offset + 2].toIntOrNull(),
            normalizeMillis(match.groupValues[offset + 3]),
        )
        return (part(1) ?: -1L) to (part(5) ?: -1L)
    }

    private fun parseAss(content: String): Parsed {
        val entries = ArrayList<SubtitleEntry>(2_000)
        var rejected = 0
        var inEvents = false
        var fields = DEFAULT_ASS_FIELDS
        for (rawLine in content.lineSequence()) {
            if (entries.size >= MAX_ENTRIES) break
            val line = rawLine.trim()
            if (line.startsWith('[')) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = line.substringAfter(':').trimStart()
            val parts = payload.split(',', limit = fields.size.coerceAtLeast(3))
            val startIndex = fields.indexOf("start")
            val endIndex = fields.indexOf("end")
            val textIndex = fields.indexOf("text")
            if (startIndex !in parts.indices || endIndex !in parts.indices || textIndex !in parts.indices) {
                rejected++
                continue
            }
            val start = parseAssTime(parts[startIndex])
            val end = parseAssTime(parts[endIndex])
            val text = parts.subList(textIndex, parts.size).joinToString(",")
                // Some providers return ASS escapes with an extra JSON-style
                // backslash. Consume the complete run so it never leaks into UI.
                .replace(ASS_NEWLINE, "\n")
                .replace(ASS_SPACE, " ")
                .replace(ASS_TAG, "")
                .trim()
                .take(MAX_CUE_TEXT_CHARS + 1)
            if (start != null && end != null && end > start && text.isNotBlank() && text.length <= MAX_CUE_TEXT_CHARS) {
                entries += SubtitleEntry(entries.size, start, end, text)
            } else rejected++
        }
        return Parsed(entries, rejected, entries.size >= MAX_ENTRIES)
    }

    /**
     * Bounded TTML/DFXP reader.
     *
     * Only the cue elements this player can render are handled: a `<p>`/`<span>` carrying `begin`
     * and `end` attributes. Nested markup is flattened to its text content and the same entry and
     * text-size limits as the SRT/ASS paths apply, so a hostile document cannot allocate without
     * bound. Anything it cannot interpret counts as a rejected cue rather than aborting the file.
     */
    /**
     * SAMI: every `<SYNC Start=ms>` opens a cue that lasts until the next SYNC. A SYNC whose body
     * is only `&nbsp;` is the format's way of clearing the screen and is not a cue. Source line
     * breaks are insignificant (HTML); `<br>` and paragraph boundaries become new lines.
     */
    private fun parseSami(content: String): Parsed {
        val syncs = SAMI_SYNC.findAll(content).take(MAX_ENTRIES * 2 + 1).toList()
        val bodyEnd = SAMI_BODY_END.find(content)?.range?.first ?: content.length
        val entries = ArrayList<SubtitleEntry>(minOf(syncs.size, 2_000))
        var rejected = 0
        for (i in syncs.indices) {
            if (entries.size >= MAX_ENTRIES) break
            val sync = syncs[i]
            val next = syncs.getOrNull(i + 1)
            val textStart = sync.range.last + 1
            val textEnd = (next?.range?.first ?: bodyEnd).coerceAtLeast(textStart)
            if (textEnd - textStart > MAX_CUE_TEXT_CHARS * 4) {
                rejected++
                continue
            }
            val raw = content.substring(textStart, textEnd)
            val text = cleanCueText(
                raw.replace(WHITESPACE_RUN, " ")
                    .replace(BR_TAG, "\n")
                    .replace(SAMI_PARAGRAPH, "\n"),
                isWebVtt = false,
            )
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString("\n")
            // `&nbsp;` decodes to a space: a clear-screen marker, not a malformed cue.
            if (text.isBlank()) continue
            val start = sync.groupValues[1].toLongOrNull()
            val end = next?.groupValues?.get(1)?.toLongOrNull() ?: start?.plus(SAMI_LAST_CUE_MS)
            if (start != null && end != null && end > start && text.length <= MAX_CUE_TEXT_CHARS) {
                entries += SubtitleEntry(entries.size, start, end, text)
            } else {
                rejected++
            }
        }
        return Parsed(entries, rejected, entries.size >= MAX_ENTRIES && syncs.size > entries.size)
    }

    private fun parseTtml(content: String): Parsed {
        // Tick and frame offsets are relative to rates declared on the <tt> root. TTML defaults:
        // 30 frames per second; ticks follow the frame rate when it is declared, else 1 per second.
        val header = content.take(8_192)
        val declaredFrameRate = TTML_FRAME_RATE.find(header)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it > 0 }
        val frameRate = declaredFrameRate ?: 30L
        val tickRate = TTML_TICK_RATE.find(header)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: declaredFrameRate
            ?: 1L
        val entries = ArrayList<SubtitleEntry>(2_000)
        var rejected = 0
        var reachedLimit = false
        var cursor = 0
        while (cursor < content.length && entries.size < MAX_ENTRIES) {
            val open = content.indexOf('<', cursor)
            if (open < 0) break
            val close = content.indexOf('>', open)
            if (close < 0) break
            val tag = content.substring(open + 1, close)
            if (!tag.startsWith("p", ignoreCase = true) || tag.startsWith("/p", ignoreCase = true)) {
                cursor = close + 1
                continue
            }
            val tagEnd = close
            val selfClosing = tag.endsWith("/")
            val attributes = if (selfClosing) tag.dropLast(1) else tag
            val start = parseTtmlTime(TTML_BEGIN.find(attributes)?.groupValues?.getOrNull(1), tickRate, frameRate)
            val end = parseTtmlTime(TTML_END.find(attributes)?.groupValues?.getOrNull(1), tickRate, frameRate)
            var text = ""
            var next = close + 1
            if (!selfClosing) {
                val closing = content.indexOf("</p>", next, ignoreCase = true)
                if (closing < 0) {
                    cursor = content.length
                    continue
                }
                text = content.substring(next, closing)
                next = closing + 4
            }
            cursor = next
            val cleaned = cleanCueText(text, isWebVtt = false)
            if (start != null && end != null && end > start && cleaned.isNotBlank() &&
                cleaned.length <= MAX_CUE_TEXT_CHARS
            ) {
                entries += SubtitleEntry(entries.size, start, end, cleaned)
            } else {
                rejected++
            }
        }
        if (entries.size >= MAX_ENTRIES) reachedLimit = cursor < content.length
        return Parsed(entries, rejected, reachedLimit)
    }

    /**
     * TTML offsets are either clock times (`hh:mm:ss.fff`, `hh:mm:ss:ff` with frames, `mm:ss.fff`)
     * or a bare number with a unit (`1.5s`, `100ms`, `2m`, `1h`, `25f` frames, `10000000t` ticks).
     *
     * Anything else — most importantly a plain number with no unit, which TTML does not define — is
     * rejected rather than guessed, so a malformed offset can never be mistaken for a valid cue.
     */
    private fun parseTtmlTime(value: String?, tickRate: Long = 1L, frameRate: Long = 30L): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.length > 1 && (raw.last() == 't' || raw.last() == 'f')) {
            val count = raw.dropLast(1).toDoubleOrNull() ?: return null
            if (!count.isFinite() || count < 0.0) return null
            val perSecond = if (raw.last() == 't') tickRate else frameRate
            return (count * 1_000.0 / perSecond).toLong().takeIf { it >= 0 }
        }
        UNIT_MULTIPLIERS.entries.firstOrNull { (suffix, _) ->
            raw.endsWith(suffix, ignoreCase = true) && raw.length > suffix.length
        }?.let { (suffix, multiplier) ->
            val magnitude = raw.dropLast(suffix.length).toDoubleOrNull() ?: return null
            if (!magnitude.isFinite() || magnitude < 0.0) return null
            return (magnitude * multiplier).toLong().takeIf { it >= 0 }
        }
        // Clock form: split on ':' first so the fractional part stays attached to the last field.
        val allParts = raw.split(':')
        if (allParts.size !in 2..4) return null
        // hh:mm:ss:ff - the fourth field counts frames, not milliseconds.
        val framesMs = if (allParts.size == 4) {
            val frames = allParts[3].substringBefore('.').toLongOrNull() ?: return null
            if (frames < 0) return null
            frames * 1_000L / frameRate
        } else 0L
        val clockParts = if (allParts.size == 4) allParts.dropLast(1) else allParts
        var totalMs = 0L
        clockParts.forEachIndexed { index, field ->
            val isLast = index == clockParts.lastIndex
            val whole = if (isLast) field.substringBefore('.') else field
            val fraction = if (isLast) field.substringAfter('.', "") else ""
            val wholeValue = whole.toLongOrNull() ?: return null
            if (wholeValue < 0) return null
            totalMs += when (index) {
                0 -> if (clockParts.size == 3) wholeValue * 3_600_000 else wholeValue * 60_000
                clockParts.lastIndex -> wholeValue * 1_000
                else -> wholeValue * 60_000
            }
            if (fraction.isNotEmpty()) {
                val digits = fraction.take(3).padEnd(3, '0')
                val fractionValue = digits.toLongOrNull() ?: return null
                totalMs += fractionValue
            }
        }
        return (totalMs + framesMs).takeIf { it >= 0 }
    }

    private fun cleanCueText(value: String, isWebVtt: Boolean): String {
        var text = value.replace(BR_TAG, "\n")
        text = text.replace(HTML_TAG, "")
        if (isWebVtt) text = text.replace(VTT_HTML_TAG, "")
        return decodeSubtitleEntities(text).trim()
    }


    private fun parseAssTime(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        val secondParts = parts[2].split('.', limit = 2)
        val seconds = secondParts[0].toIntOrNull() ?: return null
        val centiseconds = secondParts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toIntOrNull() ?: 0
        return timestamp(hours, minutes, seconds, centiseconds * 10)
    }

    private fun normalizeMillis(value: String): Int? = when (value.length) {
        1 -> value.toIntOrNull()?.times(100)
        2 -> value.toIntOrNull()?.times(10)
        else -> value.take(3).toIntOrNull()
    }

    private fun timestamp(hours: Int?, minutes: Int?, seconds: Int?, millis: Int?): Long? {
        val h = hours ?: 0
        if (minutes == null || seconds == null || millis == null) return null
        if (h !in 0..999 || minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
        return h * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

}
