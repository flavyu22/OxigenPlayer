// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.tvFocusable
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun TrackSelectionDialog(
    player: Player,
    onDismiss: () -> Unit,
    currentAppLang: String,
    useExternalSubtitles: Boolean,
    onUseExternalSubtitlesChange: (Boolean) -> Unit,
    onPickExternalSubtitles: () -> Unit,
) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var parameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                tracks = player.currentTracks
                parameters = player.trackSelectionParameters
            }
        }
        player.addListener(listener)
        tracks = player.currentTracks
        parameters = player.trackSelectionParameters
        onDispose { player.removeListener(listener) }
    }
    val switchFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    fun setTextTracksDisabled(disabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
            .build()
    }

    FastDialog(onDismiss = onDismiss) {
        OxigenCard(
            modifier = Modifier.standardDialogCardSize(),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    OxigenStrings.get(currentAppLang, "tracks_title"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    item(key = "external_subtitle_toggle") {
                        // Request only after this lazy item has entered composition.
                        LaunchedEffect(Unit) {
                            if (!initialFocusRequested) {
                                switchFocusRequester.requestFocus()
                                initialFocusRequested = true
                            }
                        }
                        ListItem(
                            headlineContent = {
                                Text(
                                    OxigenStrings.get(currentAppLang, "device_subtitles"),
                                    color = Color.White,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = useExternalSubtitles,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    ),
                                )
                            },
                            modifier = Modifier.tvFocusable(
                                focusRequester = switchFocusRequester,
                                isSelected = useExternalSubtitles,
                                scaleAmount = 1.02f,
                                onClick = {
                                    val enableExternal = !useExternalSubtitles
                                    onUseExternalSubtitlesChange(enableExternal)
                                    // Prevent Media3 and the app overlay from rendering two subtitle
                                    // tracks simultaneously.
                                    setTextTracksDisabled(enableExternal)
                                },
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }

                    item(key = "pick_external_subtitles") {
                        ListItem(
                            headlineContent = {
                                Text(
                                    OxigenStrings.get(currentAppLang, "select_subtitle_file"),
                                    color = Color.White,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.tvFocusable(
                                scaleAmount = 1.02f,
                                onClick = onPickExternalSubtitles,
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }

                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

                    if (videoGroups.isNotEmpty()) {
                        item(key = "video_header") { TrackHeader(currentAppLang, "video_label") }
                        item(key = "video_auto") {
                            TrackRow(
                                label = OxigenStrings.get(currentAppLang, "automatic_track"),
                                selected = parameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO },
                                onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        .build()
                                    onDismiss()
                                },
                            )
                        }
                        videoGroups.forEachIndexed { groupIndex, group ->
                            items(
                                items = (0 until group.mediaTrackGroup.length).toList(),
                                key = { i -> "video_${groupIndex}_$i" },
                            ) { index ->
                                TrackRow(
                                    label = videoTrackLabel(
                                        format = group.mediaTrackGroup.getFormat(index),
                                        index = index,
                                        appLang = currentAppLang,
                                    ),
                                    selected = group.isTrackSelected(index),
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(group.mediaTrackGroup, index),
                                            )
                                            .build()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }

                    if (audioGroups.isNotEmpty()) {
                        item(key = "audio_header") { TrackHeader(currentAppLang, "audio_label") }
                        item(key = "audio_auto") {
                            TrackRow(
                                label = OxigenStrings.get(currentAppLang, "automatic_track"),
                                selected = parameters.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO },
                                onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                        .build()
                                    onDismiss()
                                },
                            )
                        }
                        audioGroups.forEachIndexed { groupIndex, group ->
                            items(
                                items = (0 until group.mediaTrackGroup.length).toList(),
                                key = { i -> "audio_${groupIndex}_$i" },
                            ) { index ->
                                TrackRow(
                                    label = genericTrackLabel(
                                        format = group.mediaTrackGroup.getFormat(index),
                                        index = index,
                                        appLang = currentAppLang,
                                    ),
                                    selected = group.isTrackSelected(index),
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(group.mediaTrackGroup, index),
                                            )
                                            .build()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }

                    if (subtitleGroups.isNotEmpty() || useExternalSubtitles) {
                        item(key = "subtitle_header") { TrackHeader(currentAppLang, "subtitles_label") }
                        item(key = "subtitle_auto") {
                            TrackRow(
                                label = OxigenStrings.get(currentAppLang, "automatic_track"),
                                selected = !useExternalSubtitles &&
                                    C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes &&
                                    parameters.overrides.values.none { it.type == C.TRACK_TYPE_TEXT },
                                onClick = {
                                    onUseExternalSubtitlesChange(false)
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .build()
                                    onDismiss()
                                },
                            )
                        }
                        item(key = "subtitle_disabled") {
                            TrackRow(
                                label = OxigenStrings.get(currentAppLang, "disable_subtitles"),
                                selected = !useExternalSubtitles &&
                                    C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes,
                                onClick = {
                                    onUseExternalSubtitlesChange(false)
                                    setTextTracksDisabled(true)
                                    onDismiss()
                                },
                            )
                        }
                        subtitleGroups.forEachIndexed { groupIndex, group ->
                            items(
                                items = (0 until group.mediaTrackGroup.length).toList(),
                                key = { i -> "subtitle_${groupIndex}_$i" },
                            ) { index ->
                                TrackRow(
                                    label = genericTrackLabel(
                                        format = group.mediaTrackGroup.getFormat(index),
                                        index = index,
                                        appLang = currentAppLang,
                                    ),
                                    selected = group.isTrackSelected(index) && !useExternalSubtitles,
                                    onClick = {
                                        onUseExternalSubtitlesChange(false)
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(
                                                TrackSelectionOverride(group.mediaTrackGroup, index),
                                            )
                                            .build()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp)
                        .tvFocusable(scaleAmount = 1.02f, onClick = onDismiss),
                ) {
                    Text(
                        OxigenStrings.get(currentAppLang, "close_btn"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(appLang: String, key: String) {
    Text(
        OxigenStrings.get(appLang, key),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 2.dp),
        color = Color.Gray,
    )
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(
                isSelected = selected,
                scaleAmount = 1.02f,
                onClick = onClick,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun videoTrackLabel(format: Format, index: Int, appLang: String): String {
    val details = buildList {
        if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
        if (format.frameRate > 0f) add("${format.frameRate.toInt()} fps")
        format.codecs?.takeIf(String::isNotBlank)?.let(::add)
    }
    return details.takeIf { it.isNotEmpty() }?.joinToString(" • ")
        ?: genericTrackLabel(format, index, appLang)
}

private fun genericTrackLabel(format: Format, index: Int, appLang: String): String {
    format.label?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    format.language
        ?.takeUnless { it.equals("und", ignoreCase = true) }
        ?.let { languageTag ->
            val displayLocale = Locale.forLanguageTag(appLang)
            val displayName = Locale.forLanguageTag(languageTag).getDisplayLanguage(displayLocale)
            if (displayName.isNotBlank()) return displayName.replaceFirstChar { it.titlecase(displayLocale) }
        }
    return OxigenStrings.get(appLang, "track_number").format(index + 1)
}
