// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import java.util.Locale

/**
 * Number of HTTP load retries before surfacing an error to the UI. TorrServer keeps a stalled
 * connection open while pieces download, so each retry with exponential backoff waits tens of
 * seconds; six retries could hold the spinner for up to ~7 minutes on a dead swarm. Three
 * retries still bridge genuine piece gaps but surface a dead source in a reasonable time.
 */
private const val STREAM_LOAD_RETRIES = 3

/**
 * Helper object for building [ExoPlayer] instances tuned for Android TV and car infotainment / mobile devices.
 */
@OptIn(UnstableApi::class)
object ExoPlayerHelper {

    /**
     * Builds an [ExoPlayer] tuned for Android TV and car infotainment / mobile devices.
     *
     * Buffer sizes and load control are scaled by available RAM and CPU cores so
     * low-end hardware does not run out of memory while high-end devices can maintain
     * smooth playback even during network drops.
     *
     * @param preferSoftwareDecoders Rebuilds the renderers with FFmpeg in front of the platform codecs.
     * It is the recovery path for head units that advertise a decoder (E-AC3, HEVC Main 10)
     * they cannot actually run: renderer choice happens once at track selection, so the only way
     * to reach the software decoder is to build a new player.
     */
    fun createTvOptimizedPlayer(
        context: Context,
        preferences: PreferencesManager,
        preferSoftwareDecoders: Boolean = false,
    ): ExoPlayer {
        val (totalRamMB, isLowRamDevice, veryLowRam, cpuCores) = DevicePerformance.profile(context)

        Log.d("OxigenPlayer-TV", "Initializing player - RAM: ${totalRamMB}MB | LowRam: $isLowRamDevice | Cores: $cpuCores")
        Log.i(
            "OxigenPlayer-TV",
            "Software codec fallback included: ${BuildConfig.SOFTWARE_CODEC_PACK_INCLUDED}",
        )

        val renderersFactory = if (preferSoftwareDecoders) {
            // Recovery path: the platform decoders are deliberately omitted entirely, so the
            // packaged software decoders are the only ones that can win renderer selection.
            // extensionRendererMode is meaningless here (FFmpegOnlyRenderersFactory adds no
            // platform renderers and ignores the flag), but it is left OFF so the intent is not
            // misread as "software first, hardware as fallback" - that is the opposite of the goal.
            CodecRenderersFactory.createSoftwareFirst(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true)
            }
        } else {
            CodecRenderersFactory.create(context).apply {
                // Keep hardware/platform codecs first; use packaged software codecs only as fallback.
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                setEnableDecoderFallback(true)
            }
        }

        val preferredAudioLanguages = listOfNotNull(
            preferences.getPreferredAudioLanguage().takeIf { it.isNotBlank() },
            Locale.getDefault().language,
            "ro",
            "en",
        )
            .asSequence()
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
            .distinct()
            .toList()

        val qualityProfile = preferences.getVideoQualityProfile()
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = parameters.buildUpon()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setAllowMultipleAdaptiveSelections(true)
                // HDR, DOLBY VISION & Advanced Audio Support
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            if (preferences.shouldAudioOffload()) {
                                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                            } else {
                                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            },
                        )
                        .setIsSpeedChangeSupportRequired(false)
                        .build(),
                )
                .setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
                .setMaxVideoSize(qualityProfile.maxWidth, qualityProfile.maxHeight)
                .setMaxVideoBitrate(qualityProfile.maxBitrate)
                // Disable tunneling because it causes stuttering on several TV implementations.
                .setTunnelingEnabled(false)
                .build()
        }

        // Buffer windows are time-based: DefaultLoadControl prioritizes time thresholds by default,
        // so the byte target only stops loading once the minimum window is filled. Torrent
        // servers (TorrServer and friends) serve pieces HTTP as peers deliver them, so throughput
        // oscillates around the bitrate instead of staying above it. Raising the minimum window is
        // bounded by heap on 1-2 GB televisions, so the tiers below keep the byte target memory-safe
        // while the higher start/resume thresholds (below) absorb the piece gaps.
        val minBufferMs = when {
            veryLowRam -> 5_000
            isLowRamDevice -> 8_000
            else -> 25_000
        }
        val maxBufferMs = when {
            veryLowRam -> 15_000
            isLowRamDevice -> 25_000
            else -> 90_000
        }
        // Wait for a larger cushion before (re)starting playback: with bursty torrent throughput,
        // resuming on 1-2 s of media depletes almost immediately and the player rebuffers in a
        // tight loop (spinner on/off every few seconds). 5 s after a rebuffer matches the
        // upstream ExoPlayer default and breaks the cycle without lengthening the initial start
        // beyond reason.
        val bufferForPlaybackMs = 2_500
        val bufferAfterRebufferMs = 5_000

        // DefaultAllocator holds buffered media in Java byte arrays, so the byte target is
        // bounded by this app's heap, not by device RAM. A 4 GB TV without largeHeap commonly
        // has a 192-256 MB heap, where a fixed 256 MB target ran the process out of memory on
        // high-bitrate streams. Keep at most ~40% of the heap for media buffering.
        val heapBudgetBytes = (Runtime.getRuntime().maxMemory() * 0.4)
            .toLong()
            .coerceIn(16L * 1024 * 1024, Int.MAX_VALUE.toLong())
            .toInt()
        val targetBufferBytes = when {
            veryLowRam -> 16 * 1024 * 1024
            isLowRamDevice -> 32 * 1024 * 1024
            else -> 256 * 1024 * 1024
        }.coerceAtMost(heapBudgetBytes)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferAfterRebufferMs,
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setBackBuffer(if (isLowRamDevice) 0 else 10_000, false)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkClient.playback)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        // Tolerant retry policy for torrent-backed streams: more load retries with the
        // default exponential backoff, but never retry a response whose content type is
        // unsupported because re-requesting cannot change it.
        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = STREAM_LOAD_RETRIES

            override fun getRetryDelayMsFor(
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long = if (loadErrorInfo.exception is HttpDataSource.InvalidContentTypeException) {
                C.TIME_UNSET
            } else {
                super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val extractorsFactory = DefaultExtractorsFactory()
            // Enabled (upstream default is disabled): torrent servers serve MPEG-TS and other
            // large containers whose duration/index is unknown until pieces arrive. Without a
            // constant-bitrate seek map every ±10 s remote jump falls back to forward scanning,
            // which on a tens-of-GB file reads and discards gigabytes and shows the buffering
            // spinner for a long time. The estimate is only approximate, but for playback position
            // it is far better than a multi-minute scan.
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .setLiveTargetOffsetMs(3_000)
            .setLiveMaxOffsetMs(10_000)
            .setLiveMinOffsetMs(1_000)

        // Audio delay / lip-sync. Media3 has no public audio-offset API, so the renderers factory
        // wraps its sink in DelayedAudioSink, which reads this shared value on every clock query.
        // It has to be published before the player (and therefore the sink) is built.
        AudioDelayState.delayMs = preferences.getAudioDelayMs()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            // Declaring movie playback and letting Media3 own audio focus makes the player
            // pause for calls, assistants and other media apps and duck for notifications,
            // instead of talking over them.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Saved playback preferences must apply to every new player, not only when the user
        // toggles them: the service creates a fresh player on each start and again on the
        // software-decoder recovery path, and the settings screen shows the stored values.
        player.videoChangeFrameRateStrategy = if (preferences.shouldMatchContentFrameRate()) {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        } else {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }
        player.skipSilenceEnabled = preferences.shouldSkipSilence()
        player.setPlaybackSpeed(preferences.getPlaybackSpeed())

        // Attribute every buffering episode in logcat: source slower than the bitrate, the
        // window configured above expiring during a dip, or a failing loader that the player is
        // silently recovering from. See PlaybackBufferDiagnostics (adb logcat -s OxigenPlayer-Buffer).
        player.addAnalyticsListener(
            PlaybackBufferDiagnostics(
                tier = when {
                    veryLowRam -> "veryLowRam"
                    isLowRamDevice -> "lowRam"
                    else -> "normal"
                },
                minBufferMs = minBufferMs,
                maxBufferMs = maxBufferMs,
                targetBufferBytes = targetBufferBytes,
            ),
        )
        // Trace the video pipeline (track selection, decoder, surface, rendered frames) so a
        // "sound but black picture" report can be diagnosed: adb logcat -s OxigenPlayer-Video.
        player.addAnalyticsListener(PlaybackVideoDiagnostics(softwareDecoders = preferSoftwareDecoders))
        return player
    }
}
