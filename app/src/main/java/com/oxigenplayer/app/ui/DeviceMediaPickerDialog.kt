// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oxigenplayer.app.LocalMediaAccess
import com.oxigenplayer.app.LocalMediaFile
import com.oxigenplayer.app.LocalMediaKind
import com.oxigenplayer.app.LocalMediaVolume
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.tvFocusable
import java.util.Locale

@Composable
fun DeviceMediaPickerDialog(
    files: List<LocalMediaFile>,
    volumes: List<LocalMediaVolume>,
    access: LocalMediaAccess,
    isLoading: Boolean,
    permissionRequired: Boolean,
    loadFailed: Boolean,
    appLang: String,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSystemPicker: () -> Unit,
    onMediaSelected: (LocalMediaFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedVolumeId by remember { mutableStateOf<String?>(null) }
    val sourceFocus = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    // Build the volume index once per snapshot. Changing a storage chip is now O(1) instead
    // of filtering the complete library again, which matters on TVs with thousands of files.
    val filesByVolume = remember(files) { files.groupBy { it.volume.id } }
    val visibleFiles = remember(files, filesByVolume, selectedVolumeId) {
        selectedVolumeId?.let { id -> filesByVolume[id].orEmpty() } ?: files
    }
    val rowLabels = remember(appLang) {
        MediaRowLabels(
            video = OxigenStrings.get(appLang, "video_label"),
            audio = OxigenStrings.get(appLang, "audio_label"),
            subtitles = OxigenStrings.get(appLang, "subtitles_label"),
            deviceStorage = OxigenStrings.get(appLang, "device_storage"),
        )
    }

    LaunchedEffect(volumes) {
        if (selectedVolumeId != null && volumes.none { it.id == selectedVolumeId }) {
            selectedVolumeId = null
        }
    }

    FastDialog(onDismiss = onDismiss) {
        OxigenCard(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.88f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = OxigenStrings.get(appLang, "media_browser_title"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        if (!isLoading && !permissionRequired && !loadFailed) {
                            Text(
                                text = OxigenStrings.get(appLang, "media_file_count")
                                    .format(visibleFiles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                        }
                    }

                    MediaHeaderAction(
                        icon = Icons.Default.Refresh,
                        description = OxigenStrings.get(appLang, "refresh_media"),
                        onClick = onRefresh,
                    )
                    Spacer(Modifier.width(8.dp))
                    MediaHeaderAction(
                        icon = Icons.Default.FolderOpen,
                        description = OxigenStrings.get(appLang, "system_picker"),
                        onClick = onOpenSystemPicker,
                    )
                    Spacer(Modifier.width(8.dp))
                    MediaHeaderAction(
                        icon = Icons.Default.Close,
                        description = OxigenStrings.get(appLang, "close_dialog"),
                        onClick = onDismiss,
                    )
                }

                Spacer(Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "all") {
                        LaunchedEffect(Unit) {
                            if (!initialFocusRequested) {
                                sourceFocus.requestFocus()
                                initialFocusRequested = true
                            }
                        }
                        MediaSourceChip(
                            label = OxigenStrings.get(appLang, "all_storage"),
                            selected = selectedVolumeId == null,
                            isRemovable = false,
                            focusRequester = sourceFocus,
                            onClick = { selectedVolumeId = null },
                        )
                    }
                    items(volumes, key = { it.id }) { volume ->
                        MediaSourceChip(
                            label = if (volume.isPrimary) {
                                OxigenStrings.get(appLang, "device_storage")
                            } else {
                                volume.description
                            },
                            selected = selectedVolumeId == volume.id,
                            isRemovable = volume.isRemovable,
                            onClick = { selectedVolumeId = volume.id },
                        )
                    }
                }

                if (access.hasAnyAccess && !access.hasFullAccess) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = OxigenStrings.get(appLang, "media_access_partial"),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = onRequestAccess) {
                            Text(OxigenStrings.get(appLang, "change_media_access"))
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isLoading -> CircularProgressIndicator()
                        permissionRequired -> MediaPermissionState(
                            appLang = appLang,
                            onRequestAccess = onRequestAccess,
                            onOpenSystemPicker = onOpenSystemPicker,
                        )
                        loadFailed -> MediaErrorState(
                            appLang = appLang,
                            onRefresh = onRefresh,
                            onOpenSystemPicker = onOpenSystemPicker,
                        )
                        visibleFiles.isEmpty() -> Text(
                            text = OxigenStrings.get(appLang, "no_media_files"),
                            modifier = Modifier.padding(24.dp),
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(
                                items = visibleFiles,
                                key = { it.uri.toString() },
                                contentType = { it.kind },
                            ) { media ->
                                MediaFileRow(
                                    media = media,
                                    labels = rowLabels,
                                    onClick = { onMediaSelected(media) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaHeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .tvFocusable(isCircle = true, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.08f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
    }
}

@Composable
private fun MediaSourceChip(
    label: String,
    selected: Boolean,
    isRemovable: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        modifier = Modifier.tvFocusable(
            focusRequester = focusRequester,
            isSelected = selected,
            scaleAmount = 1.03f,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        } else {
            Color.White.copy(alpha = 0.06f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isRemovable) Icons.Default.Usb else Icons.Default.Storage,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.LightGray,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, maxLines = 1)
        }
    }
}

private data class MediaRowLabels(
    val video: String,
    val audio: String,
    val subtitles: String,
    val deviceStorage: String,
)

@Composable
private fun MediaFileRow(
    media: LocalMediaFile,
    labels: MediaRowLabels,
    onClick: () -> Unit,
) {
    val typeLabel = when (media.kind) {
        LocalMediaKind.VIDEO -> labels.video
        LocalMediaKind.AUDIO -> labels.audio
        LocalMediaKind.SUBTITLE -> labels.subtitles
    }
    // Metadata is immutable for this snapshot. Cache its presentation so D-pad navigation
    // performs only layer/draw work and never rebuilds strings for already composed rows.
    val details = remember(media, typeLabel) {
        buildList {
            add(typeLabel)
            if (media.kind != LocalMediaKind.SUBTITLE) {
                formatDuration(media.durationMs).takeIf(String::isNotEmpty)?.let(::add)
            }
            formatFileSize(media.sizeBytes).takeIf(String::isNotEmpty)?.let(::add)
        }.joinToString(" • ")
    }
    val location = remember(media, labels.deviceStorage) {
        buildString {
            append(if (media.volume.isPrimary) labels.deviceStorage else media.volume.description)
            media.relativePath?.takeIf(String::isNotBlank)?.let {
                append(" / ")
                append(it)
            }
        }
    }
    val icon = when (media.kind) {
        LocalMediaKind.VIDEO -> Icons.Default.Movie
        LocalMediaKind.AUDIO -> Icons.Default.Audiotrack
        LocalMediaKind.SUBTITLE -> Icons.Default.Subtitles
    }

    // Material3 ListItem is flexible but comparatively expensive for a long TV catalogue.
    // This fixed-height row has a shallower layout tree and predictable measurement cost.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .tvFocusable(scaleAmount = 1.01f, onClick = onClick)
            .background(Color.White.copy(alpha = 0.035f))
            .height(84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = location,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details,
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaPermissionState(
    appLang: String,
    onRequestAccess: () -> Unit,
    onOpenSystemPicker: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = OxigenStrings.get(appLang, "media_access_explanation"),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRequestAccess) {
            Text(OxigenStrings.get(appLang, "request_media_access"))
        }
        TextButton(onClick = onOpenSystemPicker) {
            Text(OxigenStrings.get(appLang, "system_picker"))
        }
    }
}

@Composable
private fun MediaErrorState(
    appLang: String,
    onRefresh: () -> Unit,
    onOpenSystemPicker: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = OxigenStrings.get(appLang, "media_load_error"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRefresh) {
            Text(OxigenStrings.get(appLang, "refresh_media"))
        }
        TextButton(onClick = onOpenSystemPicker) {
            Text(OxigenStrings.get(appLang, "system_picker"))
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1_024.0 && unitIndex < units.lastIndex) {
        value /= 1_024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$sizeBytes ${units[unitIndex]}"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
    }
}
