// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.ConcurrentHashMap

/**
 * MIME types whose platform `MediaCodec` decoders failed at runtime in this process.
 *
 * Some SoCs (seen on a HiSilicon Kirin 810 with MKV HEVC from TorrServer) advertise a decoder,
 * initialise it, and then fail on the first input buffer. The old recovery rebuilt the whole
 * player FFmpeg-only, which kept *every* later file - including H.264 the hardware handles
 * fine - on software decoding until the service died, and re-created the media session.
 *
 * Blocking just the failing MIME type lets [blocklistingCodecSelector] report it as
 * unsupported, so a plain re-prepare makes track selection pick the packaged FFmpeg renderer for
 * that type while every other codec stays on hardware. The list is deliberately process-scoped:
 * a failure can be file-specific, so it must not disable the hardware decoder forever.
 */
internal object PlatformDecoderBlocklist {
    private val blockedMimeTypes = ConcurrentHashMap.newKeySet<String>()

    /** Returns true if [mimeType] was newly blocked. */
    fun block(mimeType: String): Boolean = blockedMimeTypes.add(mimeType)

    fun isBlocked(mimeType: String): Boolean = mimeType in blockedMimeTypes
}

/** [MediaCodecSelector.DEFAULT] minus the MIME types in [PlatformDecoderBlocklist]. */
@OptIn(UnstableApi::class)
internal val blocklistingCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    if (PlatformDecoderBlocklist.isBlocked(mimeType)) {
        emptyList<MediaCodecInfo>()
    } else {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
    }
}

/**
 * The sample MIME type of the track whose *platform* decoder caused [error], or null when the
 * failure did not come from a `MediaCodec` decoder (FFmpeg, source or other renderer errors).
 */
@OptIn(UnstableApi::class)
internal fun failedPlatformDecoderMimeType(error: PlaybackException): String? {
    val exo = error as? ExoPlaybackException ?: return null
    if (exo.type != ExoPlaybackException.TYPE_RENDERER) return null
    var cause: Throwable? = exo.cause
    while (cause != null && cause !is MediaCodecDecoderException) cause = cause.cause
    if (cause == null) return null
    return exo.rendererFormat?.sampleMimeType
}
