package com.example.oxigenplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun TrackSelectionDialog(player: ExoPlayer, onDismiss: () -> Unit, currentAppLang: String) {
    val tracks = player.currentTracks
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    OxigenStrings.get(currentAppLang, "tracks_title"),
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                )
                LazyColumn(Modifier.weight(1f)) {
                    tracks.groups.forEach { group ->
                        val type = when (group.type) {
                            C.TRACK_TYPE_AUDIO -> "Audio"
                            C.TRACK_TYPE_TEXT -> "Subtitles"
                            else -> null
                        }
                        if (type != null) {
                            item {
                                Text(
                                    type,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(group.mediaTrackGroup.length) { i ->
                                val format = group.mediaTrackGroup.getFormat(i)
                                val label = format.label ?: format.language ?: "Track ${i + 1}"
                                ListItem(
                                    headlineContent = { Text(label) },
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
                                        .tvFocusable()
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusable()
                ) {
                    Text(OxigenStrings.get(currentAppLang, "disable_subtitles"))
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp)
                        .tvFocusable()
                ) {
                    Text(OxigenStrings.get(currentAppLang, "close_btn"))
                }
            }
        }
    }
}
