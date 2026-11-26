// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.VideoQualityProfile
import com.oxigenplayer.app.VideoResizeMode
import com.oxigenplayer.app.hdrLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/** Lightweight on-screen diagnostics intended for Android TV installation and playback support. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ProfessionalPlaybackDiagnostics(
    player: Player,
    appLang: String,
    qualityProfile: VideoQualityProfile,
    sleepTimerMinutes: Int,
    resizeMode: VideoResizeMode,
    modifier: Modifier = Modifier,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(player) {
        while (isActive) {
            delay(1_000)
            refreshTick++
        }
    }

    // Reading the current values after each tick keeps the overlay useful without polling at
    // frame rate or adding another Player.Listener to the Activity.
    @Suppress("UNUSED_VARIABLE") val recompose = refreshTick
    val video = player.selectedFormat(C.TRACK_TYPE_VIDEO)
    val audio = player.selectedFormat(C.TRACK_TYPE_AUDIO)
    val state = when (player.playbackState) {
        Player.STATE_BUFFERING -> OxigenStrings.get(appLang, "state_buffering")
        Player.STATE_READY -> OxigenStrings.get(appLang, if (player.isPlaying) "playing" else "paused")
        Player.STATE_ENDED -> OxigenStrings.get(appLang, "state_ended")
        else -> OxigenStrings.get(appLang, "state_idle")
    }
    val videoDescription = if (video == null) {
        "${OxigenStrings.get(appLang, "video_label")}: —"
    } else {
        val resolution = if (video.width > 0 && video.height > 0) "${video.width}×${video.height}" else "—"
        val fps = if (video.frameRate > 0f) String.format(Locale.US, "%.2f fps", video.frameRate) else "— fps"
        val codec = video.codecs?.takeIf(String::isNotBlank) ?: video.sampleMimeType.orEmpty().ifBlank { "—" }
        "${OxigenStrings.get(appLang, "video_label")}: $resolution · $fps · $codec · ${formatBitrate(video.bitrate)} · ${video.hdrLabel()}"
    }
    val audioDescription = if (audio == null) {
        "${OxigenStrings.get(appLang, "audio_label")}: —"
    } else {
        val language = audio.language?.takeIf(String::isNotBlank)?.let { "$it · " } ?: ""
        val channels = audio.channelCount.takeIf { it > 0 }?.let { "$it ch" } ?: "— ch"
        val sampleRate = audio.sampleRate.takeIf { it > 0 }?.let { "${it / 1000f} kHz" } ?: "— kHz"
        val codec = audio.codecs?.takeIf(String::isNotBlank) ?: audio.sampleMimeType.orEmpty().ifBlank { "—" }
        "${OxigenStrings.get(appLang, "audio_label")}: $language$channels · $sampleRate · $codec · ${formatBitrate(audio.bitrate)}"
    }
    // Geometry the resize logic relies on: the display aspect ratio (DAR) already folds in the
    // pixel aspect ratio (PAR), so anamorphic sources are revealed instead of silently stretched.
    val geometryDescription = video?.takeIf { it.width > 0 && it.height > 0 }?.let { format ->
        val par = format.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val dar = format.width * par / format.height
        val parLabel = if (Math.abs(par - 1f) > 0.001f) {
            String.format(Locale.US, "%.3f (anamorphic)", par)
        } else {
            "1.000 (square)"
        }
        "DAR ${String.format(Locale.US, "%.3f", dar)}:1 · PAR $parLabel · ${resizeMode.name}"
    }

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.78f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(OxigenStrings.get(appLang, "playback_diagnostics"), color = Color.White, fontSize = 14.sp)
            Text(
                "${OxigenStrings.get(appLang, "state_label")}: $state · " +
                    "${OxigenStrings.get(appLang, "buffered_label")}: ${player.bufferedPercentage}% · " +
                    OxigenStrings.get(appLang, "playback_speed").format(
                        String.format(Locale.US, "%.2fx", player.playbackParameters.speed)
                    ),
                color = Color.White,
                fontSize = 12.sp,
            )
            Text(videoDescription, color = Color.White, fontSize = 12.sp)
            Text(audioDescription, color = Color.White, fontSize = 12.sp)
            if (geometryDescription != null) {
                Text(geometryDescription, color = Color.White, fontSize = 12.sp)
            }
            val qualityLabel = OxigenStrings.get(appLang, qualityProfile.localizationKey())
            val sleepLabel = if (sleepTimerMinutes > 0) {
                OxigenStrings.get(appLang, "minutes_format").format(sleepTimerMinutes)
            } else {
                OxigenStrings.get(appLang, "off")
            }
            Text(
                "${OxigenStrings.get(appLang, "video_quality_profile")}: $qualityLabel · " +
                    "${OxigenStrings.get(appLang, "sleep_timer")}: $sleepLabel",
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatBitrate(bitsPerSecond: Int): String = when {
    bitsPerSecond <= 0 -> "— Mbps"
    bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000f)
    else -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000f)
}

private fun Player.selectedFormat(trackType: Int): Format? {
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

private fun VideoQualityProfile.localizationKey(): String = when (this) {
    VideoQualityProfile.AUTO -> "quality_auto"
    VideoQualityProfile.UHD_4K -> "quality_uhd_4k"
    VideoQualityProfile.FULL_HD -> "quality_full_hd"
    VideoQualityProfile.HD -> "quality_hd"
    VideoQualityProfile.DATA_SAVER -> "quality_data_saver"
}
