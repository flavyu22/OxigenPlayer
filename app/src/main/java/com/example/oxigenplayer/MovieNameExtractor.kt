package com.example.oxigenplayer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

object MovieNameExtractor {

    /**
     * Extrage numele filmului și anul din URI pentru căutare
     */
    fun extractMovieNameWithYear(context: Context, uri: Uri?): String {
        val fileName = getFileName(context, uri)
        if (fileName.isEmpty()) return ""
        
        val year = extractYear(fileName)
        val cleanedName = cleanMovieName(fileName, keepYear = false)
        
        return if (year != null) "$cleanedName $year" else cleanedName
    }

    /**
     * Extrage numele fișierului brut din URI
     */
    fun getFileName(context: Context, uri: Uri?): String {
        if (uri == null) return ""

        return when (uri.scheme) {
            "content" -> getFileNameFromContentUri(context, uri)
            "file" -> getFileNameFromFileUri(uri)
            "http", "https" -> getFileNameFromUrl(uri)
            else -> ""
        }
    }

    private fun getFileNameFromContentUri(context: Context, uri: Uri): String {
        try {
            val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    return cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            uri.path?.let { path -> return File(path).name }
        }
        return ""
    }

    private fun getFileNameFromFileUri(uri: Uri): String {
        val path = uri.path ?: return ""
        return File(path).name
    }

    private fun getFileNameFromUrl(uri: Uri): String {
        val path = uri.path ?: return ""
        return File(path).name
    }

    /**
     * Curăță numele filmului
     */
    private fun cleanMovieName(fileName: String, keepYear: Boolean = false): String {
        var name = fileName

        // Elimină extensia
        name = name.replaceFirst(Regex("\\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|3gp)$", RegexOption.IGNORE_CASE), "")

        // Elimină rezoluția
        name = name.replace(Regex("\\b(720p|1080p|2160p|4k|uhd|hd|sd)\\b", RegexOption.IGNORE_CASE), "")

        // Elimină codec-uri
        name = name.replace(Regex("\\b(x264|x265|h264|h265|hevc|avc|xvid|divx)\\b", RegexOption.IGNORE_CASE), "")

        // Elimină surse
        name = name.replace(Regex("\\b(bluray|brrip|bdrip|web-dl|webrip|hdtv|dvdrip|cam|ts|tc)\\b", RegexOption.IGNORE_CASE), "")

        // Elimină grupuri
        name = name.replace(Regex("\\[.*?\\]", RegexOption.IGNORE_CASE), "")

        if (!keepYear) {
            // Elimină anul
            name = name.replace(Regex("\\(?\\b(19|20)\\d{2}\\b\\)?"), "")
        }

        // Înlocuiește separatori cu spații
        name = name.replace(Regex("[._-]+"), " ")

        // Elimină caractere speciale
        name = name.replace(Regex("[^a-zA-Z0-9\\s]"), "")

        // Elimină spații multiple
        name = name.replace(Regex("\\s+"), " ")

        return name.trim()
    }

    /**
     * Extrage anul din numele fișierului
     */
    fun extractYear(fileName: String): String? {
        val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
        return yearPattern.find(fileName)?.value
    }
}
