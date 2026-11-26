// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Uses Android platform codecs and any official Media3 extensions packaged by the app.
 *
 * The audio sink is routed through [DelayedAudioSink] so the audio delay / lip-sync setting
 * behaves the same as in the flavor that bundles software decoders.
 */
@UnstableApi
internal object CodecRenderersFactory {

    /**
     * This flavor bundles no third-party decoder, so there is nothing to fall back to. Exposed as
     * a property so shared recovery logic compiles against both flavors without a BuildConfig
     * branch, and is gated so the software path is never entered here.
     */
    val softwareDecodersAvailable: Boolean = false

    fun create(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?.let { DelayedAudioSink(it) }
    }

    /** No software decoder is packaged in this flavor, so this degrades to the platform ones. */
    fun createSoftwareFirst(context: Context): DefaultRenderersFactory = create(context)
}
