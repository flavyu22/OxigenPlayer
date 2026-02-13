package com.example.oxigenplayer

import java.io.InputStream
import java.util.regex.Pattern

data class SubtitleEntry(
    val index: Int,
    val startTime: Long,
    val endTime: Long,
    val text: String
)

class SubtitleParser {

    fun parseSRT(inputStream: InputStream): List<SubtitleEntry> {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseSRTFromContent(content)
    }

    fun parseSRTFromContent(content: String): List<SubtitleEntry> {
        val subtitles = mutableListOf<SubtitleEntry>()
        val normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalizedContent.split("\n\n")

        for (block in blocks) {
            if (block.trim().isEmpty()) continue
            try {
                val lines = block.trim().split("\n")
                if (lines.size < 3) continue
                val index = lines[0].trim().toIntOrNull() ?: continue
                val timePattern = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
                val matcher = timePattern.matcher(lines[1])
                if (!matcher.find()) continue

                val startTime = parseTime(matcher.group(1)!!.toInt(), matcher.group(2)!!.toInt(), matcher.group(3)!!.toInt(), matcher.group(4)!!.toInt())
                val endTime = parseTime(matcher.group(5)!!.toInt(), matcher.group(6)!!.toInt(), matcher.group(7)!!.toInt(), matcher.group(8)!!.toInt())
                val rawText = lines.drop(2).joinToString("\n").trim()
                val cleanText = rawText.replace(Regex("<[^>]*>"), "")

                if (cleanText.isNotEmpty()) {
                    subtitles.add(SubtitleEntry(index, startTime, endTime, cleanText))
                }
            } catch (e: Exception) { continue }
        }
        return subtitles.sortedBy { it.startTime }
    }

    private fun parseTime(hours: Int, minutes: Int, seconds: Int, milliseconds: Int): Long {
        return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + milliseconds
    }

    fun getCurrentSubtitleEntry(subtitles: List<SubtitleEntry>, currentPositionMs: Long): SubtitleEntry? {
        if (subtitles.isEmpty()) return null
        var low = 0
        var high = subtitles.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val entry = subtitles[mid]
            if (currentPositionMs >= entry.startTime && currentPositionMs <= entry.endTime) return entry
            if (currentPositionMs < entry.startTime) high = mid - 1 else low = mid + 1
        }
        return null
    }

    fun getCurrentSubtitle(subtitles: List<SubtitleEntry>, currentPositionMs: Long): String? {
        return getCurrentSubtitleEntry(subtitles, currentPositionMs)?.text
    }
}
