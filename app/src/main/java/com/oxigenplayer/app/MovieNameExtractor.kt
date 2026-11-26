// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optimized Movie Name Extractor.
 * Uses pre-compiled regex for high performance.
 */
object MovieNameExtractor {



    fun extractMovieNameWithYear(context: Context, uri: Uri?): String =
        extract(context, uri, MediaTitleParser::titleWithName)

    /** Name shown on screen and in the media session: "Movie Name (2019)". */
    suspend fun extractDisplayTitleAsync(context: Context, uri: Uri?): String =
        withContext(Dispatchers.IO) { extract(context, uri, MediaTitleParser::displayTitle) }

    private fun extract(context: Context, uri: Uri?, format: (String) -> String): String {
        val fileName = getFileName(context, uri)
        if (fileName.isEmpty()) return ""

        return try {
            // Returnăm doar numele filmului și anul, fără detalii tehnice
            format(fileName)
        } catch (error: RuntimeException) {
            // A display-title failure is cosmetic and must never interrupt video playback.
            Log.e(TAG, "Unable to parse media title", error)
            fallbackTitle(fileName)
        } catch (error: LinkageError) {
            // Includes initializer failures from platform-specific regex implementations.
            Log.e(TAG, "Unable to initialize media title parser", error)
            fallbackTitle(fileName)
        }
    }

    /** Resolves ContentResolver metadata away from the UI/composition thread. */
    suspend fun extractMovieNameWithYearAsync(context: Context, uri: Uri?): String =
        withContext(Dispatchers.IO) { extractMovieNameWithYear(context, uri) }

    @WorkerThread
    fun getFileName(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        return when (uri.scheme) {
            "content" -> getFileNameFromContentUri(context, uri)
            "file" -> uri.path?.let { File(it).name } ?: ""
            "http", "https", "rtsp", "rtsps" -> uri.lastPathSegment
                ?.substringBefore('?')
                ?.substringBefore('#')
                .orEmpty()
            else -> ""
        }
    }

    private fun getFileNameFromContentUri(context: Context, uri: Uri): String {
        return try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else File(uri.path ?: "").name
                } else File(uri.path ?: "").name
            } ?: File(uri.path ?: "").name
        } catch (e: Exception) {
            File(uri.path ?: "").name
        }
    }


    fun extractYear(fileName: String): String? = MediaTitleParser.extractYear(fileName)

    private fun fallbackTitle(fileName: String): String {
        // The parser itself failed, so never call it again here: show the raw name without
        // its extension and separators.
        return fileName.substringBeforeLast('.', fileName).replace('.', ' ').replace('_', ' ').trim()
    }

    private const val TAG = "MovieNameExtractor"

}
