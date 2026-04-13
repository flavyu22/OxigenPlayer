package com.example.oxigenplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.oxigenplayer.OxigenStrings
import com.example.oxigenplayer.tvFocusable

@OptIn(UnstableApi::class)
@Composable
fun TrackSelectionDialog(
    player: ExoPlayer, 
    onDismiss: () -> Unit, 
    currentAppLang: String,
    useExternalSubtitles: Boolean,
    onUseExternalChange: (Boolean) -> Unit
) {
    val tracks = player.currentTracks
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    OxigenStrings.get(currentAppLang, "tracks_title"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .clickable { onUseExternalChange(!useExternalSubtitles) }
                                .tvFocusable(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (currentAppLang == "ro") "Folosește Subtitrări Externe (.srt)" else "Use External Subtitles (.srt)",
                                modifier = Modifier.weight(1f),
                                fontWeight = if (useExternalSubtitles) FontWeight.Bold else FontWeight.Normal,
                                color = if (useExternalSubtitles) Color.Yellow else Color.White
                            )
                            if (useExternalSubtitles) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Yellow)
                            }
                            Switch(
                                checked = useExternalSubtitles,
                                onCheckedChange = onUseExternalChange,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isNotEmpty()) {
                        item {
                            Text(
                                text = OxigenStrings.get(currentAppLang, "audio_label"),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        audioGroups.forEach { group ->
                            items(group.mediaTrackGroup.length) { i ->
                                val format = group.mediaTrackGroup.getFormat(i)
                                val label = format.label ?: format.language ?: "Track ${i + 1}"
                                val isSelected = group.isTrackSelected(i)
                                
                                ListItem(
                                    headlineContent = { 
                                        Text(
                                            label, 
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.Yellow else Color.White
                                        ) 
                                    },
                                    trailingContent = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Yellow)
                                        }
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(
                                                    TrackSelectionOverride(
                                                        group.mediaTrackGroup,
                                                        i
                                                    )
                                                )
                                                .build()
                                            onDismiss()
                                        }
                                        .tvFocusable(),
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isSelected) Color.White.copy(0.1f) else Color.Transparent
                                    )
                                )
                            }
                        }
                    }

                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    if (textGroups.isNotEmpty()) {
                        item {
                            Text(
                                text = OxigenStrings.get(currentAppLang, "subtitles_label"),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        textGroups.forEach { group ->
                            items(group.mediaTrackGroup.length) { i ->
                                val format = group.mediaTrackGroup.getFormat(i)
                                val label = format.label ?: format.language ?: "Track ${i + 1}"
                                val isSelected = group.isTrackSelected(i) && !useExternalSubtitles
                                
                                ListItem(
                                    headlineContent = { 
                                        Text(
                                            label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.Yellow else Color.White
                                        ) 
                                    },
                                    trailingContent = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Yellow)
                                        }
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            onUseExternalChange(false) // Dezactivăm externele dacă selectăm una internă
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(
                                                    TrackSelectionOverride(
                                                        group.mediaTrackGroup,
                                                        i
                                                    )
                                                )
                                                .build()
                                            onDismiss()
                                        }
                                        .tvFocusable(),
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isSelected) Color.White.copy(0.1f) else Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }
                // Prevent the list and the button from splitting the available space 50/50.
                // Use a small fixed spacer so the LazyColumn (weight=1f) keeps full content area.
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(top = 8.dp)
                        .tvFocusable(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Text(OxigenStrings.get(currentAppLang, "close_btn"), color = Color.White)
                }
            }
        }
    }
}
