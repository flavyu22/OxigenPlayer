// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** User-facing quality caps that remain predictable across phones, tablets and televisions. */
enum class VideoQualityProfile(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
) {
    AUTO(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
    UHD_4K(3840, 2160, 60_000_000),
    FULL_HD(1920, 1080, 20_000_000),
    HD(1280, 720, 8_000_000),
    DATA_SAVER(854, 480, 2_500_000);

    companion object {
        fun fromStored(value: String?): VideoQualityProfile =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

internal fun applyVideoQualityProfile(player: Player, profile: VideoQualityProfile): Boolean {
    if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return false
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setMaxVideoSize(profile.maxWidth, profile.maxHeight)
        .setMaxVideoBitrate(profile.maxBitrate)
        .build()
    return true
}

internal fun sleepTimerDeadline(nowElapsedRealtimeMs: Long, minutes: Int): Long =
    if (minutes <= 0) 0L else nowElapsedRealtimeMs + minutes.coerceAtMost(12 * 60) * 60_000L

internal fun sleepTimerRemainingMinutes(nowElapsedRealtimeMs: Long, deadlineMs: Long): Int {
    if (deadlineMs <= nowElapsedRealtimeMs) return 0
    val remainingMs = deadlineMs - nowElapsedRealtimeMs
    return ((remainingMs + 59_999L) / 60_000L).toInt()
}

/** How the video frame is fitted into the player surface. */
enum class VideoResizeMode {
    /** Letterbox: the whole frame stays visible at its original proportions, black bars fill the rest. */
    FIT,

    /** Center-crop: the frame keeps its original proportions and grows until it covers the surface, cropping the overflow. */
    FILL,

    /** Stretch each axis independently; fills the surface but distorts the picture. */
    STRETCH;

    companion object {
        fun fromStored(value: String?): VideoResizeMode =
            entries.firstOrNull { it.name == value } ?: FIT
    }
}

/**
 * Optional manual override of the picture aspect ratio.
 *
 * [ORIGINAL] follows the decoded video's own display aspect ratio — the default, correct
 * behaviour. The fixed ratios force the picture to a classic broadcast/cinema ratio regardless of
 * the source, mirroring a television's "picture format" menu.
 */
enum class VideoAspectRatioOverride(val ratio: Float?, val ratioLabel: String) {
    ORIGINAL(null, "Original"),
    RATIO_16_9(16f / 9f, "16:9"),
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_2_35_1(2.35f, "2.35:1"),
    RATIO_1_85_1(1.85f, "1.85:1");

    companion object {
        fun fromStored(value: String?): VideoAspectRatioOverride =
            entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

/**
 * Scale factors that undo the surface stretch so the picture keeps its original proportions.
 *
 * The Compose `PlayerSurface` renders a plain `SurfaceView`/`TextureView` sized by the modifier,
 * so the platform stretches the decoded frame to fill the whole container instead of
 * letterboxing it. These factors are applied around that stretched surface via `graphicsLayer`:
 *
 * - FIT undoes the stretch and shrinks the limiting axis so the whole frame stays visible at its
 *   original size and the parent's black background shows through as bars (no distortion).
 * - STRETCH leaves the surface as-is, filling the container at the cost of distortion.
 * - FILL undoes the stretch on one axis and grows the other until the frame covers the container
 *   at its original proportions; the parent clips the overflow (center-crop, no distortion).
 *
 * Unknown geometry falls back to no scaling so a transient zero size can never make the picture
 * disappear.
 */
internal fun videoResizeScale(
    mode: VideoResizeMode,
    videoAspectRatio: Float?,
    containerWidth: Float,
    containerHeight: Float,
    aspectRatioOverride: VideoAspectRatioOverride = VideoAspectRatioOverride.ORIGINAL,
): Pair<Float, Float> {
    val videoAspect = (aspectRatioOverride.ratio ?: videoAspectRatio)
        ?.takeIf { it.isFinite() && it > 0f } ?: return 1f to 1f
    if (containerWidth <= 0f || containerHeight <= 0f) return 1f to 1f
    val containerAspect = containerWidth / containerHeight
    if (videoAspect == containerAspect) return 1f to 1f
    val widerThanContainer = videoAspect > containerAspect
    return when (mode) {
        VideoResizeMode.STRETCH -> 1f to 1f
        VideoResizeMode.FIT ->
            if (widerThanContainer) 1f to containerAspect / videoAspect
            else videoAspect / containerAspect to 1f
        VideoResizeMode.FILL ->
            if (widerThanContainer) videoAspect / containerAspect to 1f
            else 1f to containerAspect / videoAspect
    }
}

/**
 * Human-readable HDR label for the selected video format, derived from the real Media3 1.11
 * colour API (there is no `hdrType`/`HDR_TYPE_*` any more).
 *
 * Dolby Vision is signalled by the `dvh1`/`dvhe` codec prefix, HLG by the HLG transfer and HDR10
 * by the PQ (ST2084) transfer. Anything else — including a null [Format.colorInfo], which Media3
 * treats as an assumed SDR default — is reported as SDR. HDR10+ is a PQ stream with dynamic
 * metadata, so it is reported as HDR10 here rather than guessed from static info.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun Format.hdrLabel(): String {
    val codec = codecs?.lowercase().orEmpty()
    if (codec.startsWith("dvh1") || codec.startsWith("dvhe")) return "Dolby Vision"
    return when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        else -> "SDR"
    }
}
