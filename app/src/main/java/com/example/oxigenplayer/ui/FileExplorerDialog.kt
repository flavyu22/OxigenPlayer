package com.example.oxigenplayer.ui

import android.net.Uri
import android.os.Environment
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.oxigenplayer.tvFocusable
import java.io.File

@Composable
fun FileExplorerDialog(
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit,
    title: String = "Explorator Fișiere",
    allowedExtensions: List<String> = listOf("srt"),
    extraButton: @Composable (() -> Unit)? = null
) {
    val storageRoot = Environment.getExternalStorageDirectory()
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    
    var currentDir by remember { mutableStateOf(storageRoot) }
    val files = remember(currentDir) {
        currentDir.listFiles()?.filter { file ->
            file.isDirectory || allowedExtensions.contains(file.extension.lowercase())
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "webm")
    val photoExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
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
                        onClick = { currentDir = storageRoot }
                    )
                    ShortcutButton(
                        icon = Icons.Default.Download,
                        label = "Download",
                        onClick = { currentDir = downloadsDir }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentDir.absolutePath != storageRoot.absolutePath) {
                        IconButton(
                            onClick = { currentDir = currentDir.parentFile ?: currentDir },
                            modifier = Modifier.tvFocusable(isCircle = true)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Înapoi")
                        }
                    }
                    Text(
                        text = currentDir.absolutePath.replace(storageRoot.absolutePath, "Stocare"),
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
                            items(files) { file ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val isFocused by interactionSource.collectIsFocusedAsState()
                                
                                val extension = file.extension.lowercase()
                                val icon = when {
                                    file.isDirectory -> Icons.Default.Folder
                                    videoExtensions.contains(extension) -> Icons.Default.Movie
                                    photoExtensions.contains(extension) -> Icons.Default.Image
                                    else -> Icons.Default.Description
                                }

                                ListItem(
                                    headlineContent = { 
                                        Text(
                                            file.name,
                                            color = if (isFocused) Color.Yellow else Color.White,
                                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                                        ) 
                                    },
                                    leadingContent = {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            tint = if (isFocused) Color.Yellow else if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            if (file.isDirectory) {
                                                currentDir = file
                                            } else {
                                                onFileSelected(Uri.fromFile(file))
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

                if (extraButton != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    extraButton()
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss, modifier = Modifier.tvFocusable()) {
                        Text("ÎNCHIDE")
                    }
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
