package com.example.oxigenplayer

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.io.File
import java.io.FileOutputStream

class FilePickerManager {

    @Composable
    fun rememberVideoPickerLauncher(
        onVideoSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    @Composable
    fun rememberImagePickerLauncher(
        onImageSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    @Composable
    fun rememberSubtitlePickerLauncher(
        onSubtitleSelected: (Uri) -> Unit
    ) = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSubtitleSelected(it) }
    }

    fun readSubtitleFile(context: Context, uri: Uri): List<SubtitleEntry> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                SubtitleParser().parseSRT(inputStream)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSubtitlesToDevice(context: Context, movieName: String, subtitles: List<SubtitleEntry>, videoUri: Uri? = null): String? {
        return try {
            val fileName = "${movieName.replace(Regex("[^a-zA-Z0-9]"), "_")}_translated.srt"
            
            // Încercăm să salvăm în același folder cu filmul dacă e fișier local
            var targetFile: File? = null
            if (videoUri?.scheme == "file") {
                try {
                    val videoFile = File(videoUri.path!!)
                    targetFile = File(videoFile.parent, fileName)
                } catch (e: Exception) {}
            }
            
            // Altfel, salvăm în folderul Downloads
            if (targetFile == null) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                targetFile = File(downloadsDir, fileName)
            }
            
            val content = StringBuilder()
            subtitles.forEach { entry ->
                content.append("${entry.index}\n")
                content.append("${formatSrtTime(entry.startTime)} --> ${formatSrtTime(entry.endTime)}\n")
                content.append("${entry.text}\n\n")
            }
            
            FileOutputStream(targetFile).use { output ->
                output.write(content.toString().toByteArray())
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun formatSrtTime(timeMs: Long): String {
        val ms = timeMs % 1000
        val totalSecs = timeMs / 1000
        val secs = totalSecs % 60
        val mins = (totalSecs / 60) % 60
        val hours = totalSecs / 3600
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", hours, mins, secs, ms)
    }
}
