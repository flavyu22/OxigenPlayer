// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

sealed interface SubtitleFileResult<out T> {
    data class Success<T>(val value: T) : SubtitleFileResult<T>

    data class Failure(
        val error: SubtitleFileError,
        val cause: Throwable? = null,
    ) : SubtitleFileResult<Nothing>
}

enum class SubtitleFileError {
    PERMISSION_DENIED,
    CANNOT_OPEN,
    TOO_LARGE,
    EMPTY_OR_INVALID,
    READ_FAILED,
    WRITE_FAILED,
}

class FilePickerManager {

    companion object {
        /** A subtitle file should never need to be larger than this. */
        const val MAX_SUBTITLE_BYTES: Int = 5 * 1024 * 1024
    }

    @Composable
    fun rememberVideoPickerLauncher(
        onVideoSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    /**
     * Uses Android's media picker with a strict video MIME type. Android TV builds
     * commonly expose a non-functional DocumentsUI stub, while their media picker
     * is backed by a real, remote-friendly activity.
     */
    @Composable
    fun rememberVisualVideoPickerLauncher(
        onVideoSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    /** Final compatibility fallback for devices that provide only GET_CONTENT. */
    @Composable
    fun rememberVideoContentPickerLauncher(
        onVideoSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    @Composable
    fun rememberSubtitlePickerLauncher(
        onSubtitleSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onSubtitleSelected(it) }
    }

    @Composable
    fun rememberSubtitleSaverLauncher(
        onResult: (Uri?) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri: Uri? ->
        onResult(uri)
    }

    suspend fun readSubtitleFileResult(
        context: Context,
        uri: Uri,
    ): SubtitleFileResult<List<SubtitleEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext SubtitleFileResult.Failure(SubtitleFileError.CANNOT_OPEN)
                val bytes = inputStream.use { readBounded(it, MAX_SUBTITLE_BYTES) }
                val displayName = uri.lastPathSegment
                when (val parsed = SubtitleParser.parse(
                    bytes = bytes,
                    fileName = displayName,
                    mimeType = context.contentResolver.getType(uri),
                )) {
                    is SubtitleParseResult.Success -> SubtitleFileResult.Success(parsed.entries)
                    is SubtitleParseResult.Partial -> SubtitleFileResult.Success(parsed.entries)
                    is SubtitleParseResult.Failure ->
                        SubtitleFileResult.Failure(SubtitleFileError.EMPTY_OR_INVALID)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                SubtitleFileResult.Failure(SubtitleFileError.PERMISSION_DENIED, error)
            } catch (error: SubtitleTooLargeException) {
                SubtitleFileResult.Failure(SubtitleFileError.TOO_LARGE, error)
            } catch (error: FileNotFoundException) {
                SubtitleFileResult.Failure(SubtitleFileError.CANNOT_OPEN, error)
            } catch (error: IOException) {
                SubtitleFileResult.Failure(SubtitleFileError.READ_FAILED, error)
            } catch (error: Exception) {
                SubtitleFileResult.Failure(SubtitleFileError.READ_FAILED, error)
            }
        }

    /**
     * Compatibility wrapper for existing callers. New UI code should use
     * [readSubtitleFileResult] so it can report the failure to the user.
     */
    suspend fun readSubtitleFile(context: Context, uri: Uri): List<SubtitleEntry> =
        when (val result = readSubtitleFileResult(context, uri)) {
            is SubtitleFileResult.Success<List<SubtitleEntry>> -> result.value
            is SubtitleFileResult.Failure -> emptyList()
        }

    suspend fun writeSubtitlesToUriResult(
        context: Context,
        uri: Uri,
        content: String,
    ): SubtitleFileResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_SUBTITLE_BYTES) {
                    return@withContext SubtitleFileResult.Failure(SubtitleFileError.TOO_LARGE)
                }
                val stream = runCatching {
                    context.contentResolver.openOutputStream(uri, "rwt")
                }.getOrNull() ?: context.contentResolver.openOutputStream(uri, "w")
                    ?: return@withContext SubtitleFileResult.Failure(SubtitleFileError.CANNOT_OPEN)
                stream.use {
                    it.write(bytes)
                    it.flush()
                }
                SubtitleFileResult.Success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                SubtitleFileResult.Failure(SubtitleFileError.PERMISSION_DENIED, error)
            } catch (error: FileNotFoundException) {
                SubtitleFileResult.Failure(SubtitleFileError.CANNOT_OPEN, error)
            } catch (error: IOException) {
                SubtitleFileResult.Failure(SubtitleFileError.WRITE_FAILED, error)
            } catch (error: Exception) {
                SubtitleFileResult.Failure(SubtitleFileError.WRITE_FAILED, error)
            }
        }

    /**
     * Compatibility wrapper for existing callers. New UI code should use
     * [writeSubtitlesToUriResult] to distinguish cancellation and I/O failures.
     */
    suspend fun writeSubtitlesToUri(context: Context, uri: Uri, content: String): Boolean =
        writeSubtitlesToUriResult(context, uri, content) is SubtitleFileResult.Success<*>

    internal fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(64 * 1024, maxBytes))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        var consecutiveZeroReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= 8) throw IOException("Subtitle stream made no progress")
                continue
            }
            consecutiveZeroReads = 0
            total += read
            if (total > maxBytes) throw SubtitleTooLargeException(maxBytes)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

internal class SubtitleTooLargeException(maxBytes: Int) :
    IOException("Subtitle exceeds $maxBytes bytes")
