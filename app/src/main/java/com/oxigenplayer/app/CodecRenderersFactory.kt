// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FFmpegOnlyRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Adds FFmpeg software fallback decoders after Android's hardware/platform decoders.
 *
 * [create] keeps hardware first so the common case stays fast and power efficient.
 * [createSoftwareFirst] drops the platform decoders entirely and is used to recover playback
 * on devices that advertise a codec they cannot actually run.
 *
 * Both factories route their audio sink through [DelayedAudioSink] so the audio delay /
 * lip-sync setting applies to hardware and software decoding alike.
 */
@UnstableApi
internal object CodecRenderersFactory {

    /** True when the packaged FFmpeg decoders could really be loaded on this device. */
    val softwareDecodersAvailable: Boolean get() = FfmpegLibrary.isAvailable()

    fun create(context: Context): DefaultRenderersFactory = object : NextRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?.let { DelayedAudioSink(it) }
    }.apply {
        // Platform decoders that already failed at runtime are hidden, so track selection
        // falls through to the FFmpeg renderer for that MIME type only.
        setMediaCodecSelector(blocklistingCodecSelector)
    }

    /**
     * Renderers that decode with FFmpeg only, bypassing the platform `MediaCodec` decoders.
     *
     * Recovery path for devices whose hardware decoders claim support they cannot deliver
     * (E-AC3 rejected at `configure()`, HEVC Main 10 dying on the first 10-bit frame).
     */
    fun createSoftwareFirst(context: Context): DefaultRenderersFactory =
        object : FFmpegOnlyRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                ?.let { DelayedAudioSink(it) }
        }
}
