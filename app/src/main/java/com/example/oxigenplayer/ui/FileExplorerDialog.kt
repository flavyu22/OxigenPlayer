package com.example.oxigenplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.oxigenplayer.tvFocusable
import java.io.File

@Composable
fun FileExplorerDialog(
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit,
    title: String = "Explorator Fișiere",
    allowedExtensions: List<String> = listOf("srt")
) {
    val context = LocalContext.current
    val storageRoot = Environment.getExternalStorageDirectory()
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    // Collect possible removable storage roots (USB/OTG) using multiple strategies:
    // - scan /storage
    // - inspect context.getExternalFilesDirs(null) and trim to the storage root (before /Android)
    val usbDrives = remember {
        val drives = mutableListOf<File>()
        try {
            // 1) scan /storage for mount points
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && !file.name.equals("emulated", true) && !file.name.equals("self", true) && file.canRead()) {
                        if (!drives.any { it.absolutePath == file.absolutePath }) drives.add(file)
                    }
                }
            }

            // 2) use external files dirs to find removable storage roots
            val extDirs = context.getExternalFilesDirs(null)
            extDirs?.forEach { f ->
                if (f != null) {
                    try {
                        val path = f.absolutePath
                        // cut at /Android to get the storage root
                        val rootPath = if (path.contains("/Android/")) path.substringBefore("/Android/") else path
                        val candidate = File(rootPath)
                        if (candidate.exists() && candidate.isDirectory && candidate.canRead()) {
                            if (!drives.any { it.absolutePath == candidate.absolutePath }) drives.add(candidate)
                        }
                    } catch (e: Exception) {
                        // ignore individual failures
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        drives
    }

    // SAF: launcher to pick a tree (USB root) and persist permissions
    val safStack = remember { mutableStateListOf<DocumentFile>() }
    var currentDirFile by remember { mutableStateOf<File?>(if (usbDrives.isNotEmpty()) usbDrives.first() else storageRoot) }
    var currentDirDoc by remember { mutableStateOf<DocumentFile?>(null) }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { treeUri ->
            try {
                // persist read permission
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val doc = DocumentFile.fromTreeUri(context, treeUri)
                if (doc != null) {
                    safStack.clear()
                    safStack.add(doc)
                    currentDirDoc = doc
                    currentDirFile = null
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // Start browsing on the first available USB drive if present, otherwise fallback to storage root
    val files = remember(currentDirDoc, currentDirFile) {
        // Return a List<Any> containing either DocumentFile or File instances
        currentDirDoc?.listFiles()?.filter { d ->
            d.isDirectory || allowedExtensions.contains(d.name?.substringAfterLast('.')?.lowercase() ?: "")
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
            ?: (currentDirFile?.listFiles()?.filter { file ->
                file.isDirectory || allowedExtensions.contains(file.extension.lowercase())
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.toList() ?: emptyList())
    }

    val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "ts", "flv", "3gp", "m3u8")
    val photoExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Row de Scurtături (Shortcuts)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShortcutButton(
                        icon = Icons.Default.Home,
                        label = "Stocare",
                        onClick = { currentDirFile = storageRoot; currentDirDoc = null }
                    )
                    ShortcutButton(
                        icon = Icons.Default.Download,
                        label = "Download",
                        onClick = { currentDirFile = downloadsDir; currentDirDoc = null }
                    )
                    usbDrives.forEachIndexed { index, drive ->
                        ShortcutButton(
                            icon = Icons.Default.Usb,
                            label = "USB ${index + 1}",
                            onClick = {
                                // try filesystem access first
                                if (drive.canRead()) {
                                    currentDirFile = drive
                                    currentDirDoc = null
                                } else {
                                    // fallback to SAF picker
                                    safLauncher.launch(null)
                                }
                            }
                        )
                    }
                    // Provide SAF picker shortcut to allow user to grant access explicitly
                    ShortcutButton(
                        icon = Icons.Default.Usb,
                        label = "USB (SAF)",
                        onClick = { safLauncher.launch(null) }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val canGoBack = (currentDirDoc != null && safStack.size > 1) || (currentDirFile?.parentFile != null && currentDirFile?.absolutePath != "/")
                    if (canGoBack) {
                        IconButton(
                            onClick = {
                                if (currentDirDoc != null) {
                                    if (safStack.size > 1) {
                                        safStack.removeAt(safStack.size - 1)
                                        currentDirDoc = safStack.last()
                                    } else {
                                        // exit SAF browsing
                                        currentDirDoc = null
                                        currentDirFile = storageRoot
                                    }
                                } else {
                                    currentDirFile = currentDirFile?.parentFile ?: currentDirFile
                                }
                            },
                            modifier = Modifier.size(48.dp).tvFocusable(isCircle = true)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi", modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = (currentDirDoc?.name ?: currentDirFile?.absolutePath)?.replace(storageRoot.absolutePath, "Stocare") ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (files.isEmpty()) {
                        Text("Folderul este gol", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(files) { item ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val isFocused by interactionSource.collectIsFocusedAsState()

                                // item can be DocumentFile or File
                                val isDir = when (item) {
                                    is DocumentFile -> item.isDirectory
                                    is File -> item.isDirectory
                                    else -> false
                                }
                                val name = when (item) {
                                    is DocumentFile -> item.name ?: ""
                                    is File -> item.name
                                    else -> ""
                                }
                                val extension = name.substringAfterLast('.', "").lowercase()
                                val icon = when {
                                    isDir -> Icons.Default.Folder
                                    videoExtensions.contains(extension) -> Icons.Default.Movie
                                    photoExtensions.contains(extension) -> Icons.Default.Image
                                    else -> Icons.Default.Description
                                }

                                ListItem(
                                    headlineContent = {
                                        Text(
                                            name,
                                            color = if (isFocused) Color.Yellow else Color.White,
                                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            tint = if (isFocused) Color.Yellow else if (isDir) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            when (item) {
                                                is File -> {
                                                    if (item.isDirectory) {
                                                        currentDirFile = item
                                                        currentDirDoc = null
                                                    } else {
                                                        onFileSelected(Uri.fromFile(item))
                                                    }
                                                }
                                                is DocumentFile -> {
                                                    if (item.isDirectory) {
                                                        safStack.add(item)
                                                        currentDirDoc = item
                                                    } else {
                                                        onFileSelected(item.uri)
                                                    }
                                                }
                                            }
                                        }
                                        .tvFocusable(interactionSource = interactionSource),
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                                        headlineColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }

                // Use a small fixed spacer so the file list keeps the available area
                // instead of splitting remaining space 50/50 with a weighted spacer.
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss, 
                    modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Text("ÎNCHIDE", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ShortcutButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    Button(
        onClick = onClick,
        modifier = Modifier.height(38.dp).tvFocusable(interactionSource = interactionSource),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(0.1f)
        )
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}
