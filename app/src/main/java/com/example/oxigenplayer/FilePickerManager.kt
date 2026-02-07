package com.example.oxigenplayer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

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
}
