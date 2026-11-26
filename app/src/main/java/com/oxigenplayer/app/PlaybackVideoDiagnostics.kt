// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Logs the video pipeline so a "sound but black picture" report can be traced afterwards.
 *
 * Covers the three places the picture can be lost:
 *  1. track selection: no video track selected, video disabled, or a track the device reports
 *     as unsupported;
 *  2. the decoder: which codec was created, input format changes, codec errors;
 *  3. the output: surface size (0x0 = no surface attached), first rendered frame, dropped frames.
 *
 * A watchdog checks every [WATCHDOG_INTERVAL_MS] while the player is playing. If a video track
 * is selected but the rendered-frame counter has not moved, it logs one `NO VIDEO FRAMES`
 * warning with the full state, which is the signature of the audio-only symptom.
 *
 * Read with:
 *
 *     adb logcat -s OxigenPlayer-Video
 *
 * [ExoPlayerHelper] attaches it to every player it builds, including the software rebuild.
 */
@OptIn(UnstableApi::class)
@Suppress("OVERRIDE_DEPRECATION")
internal class PlaybackVideoDiagnostics(
    private val softwareDecoders: Boolean,
) : AnalyticsListener {

    private val handler = Handler(Looper.getMainLooper())
    private var counters: DecoderCounters? = null
    private var decoderName: String = "none"
    private var inputFormat: Format? = null
    private var surfaceWidth = -1
    private var surfaceHeight = -1
    private var videoSelected = false
    private var videoTrackCount = 0
    private var playing = false
    private var lastRenderedCount = -1
    private var stalledChecks = 0
    private var lastDroppedCount = -1
    private var overloadedWindows = 0
    private var overloadReported = false
    private var firstFrameOutput: String = "none"

    private val watchdog = object : Runnable {
        override fun run() {
            if (!playing) return
            checkFrames()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onTracksChanged(eventTime: EventTime, tracks: Tracks) {
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        videoTrackCount = videoGroups.sumOf { it.length }
        videoSelected = videoGroups.any { it.isSelected }
        val description = videoGroups.flatMapIndexed { groupIndex, group ->
            (0 until group.length).map { i ->
                val support = when (group.getTrackSupport(i)) {
                    C.FORMAT_HANDLED -> "ok"
                    C.FORMAT_EXCEEDS_CAPABILITIES -> "exceeds"
                    C.FORMAT_UNSUPPORTED_SUBTYPE -> "unsupported-subtype"
                    C.FORMAT_UNSUPPORTED_DRM -> "unsupported-drm"
                    else -> "unsupported"
                }
                val mark = if (group.isTrackSelected(i)) "*" else " "
                "$mark[$groupIndex.$i ${describe(group.getTrackFormat(i))} support=$support]"
            }
        }
        Log.i(
            TAG,
            "tracks: video=$videoTrackCount selected=$videoSelected " +
                description.joinToString(" ").ifEmpty { "(no video track in media)" },
        )
        videoGroups.flatMap { group -> (0 until group.length).mapNotNull { group.getTrackFormat(it).sampleMimeType } }
            .distinct()
            .forEach(::logDecoderProfilesOnce)
        if (videoTrackCount > 0 && !videoSelected) {
            Log.w(TAG, "media has video but NO video track is selected -> audio only")
        }
    }

    override fun onTrackSelectionParametersChanged(
        eventTime: EventTime,
        trackSelectionParameters: TrackSelectionParameters,
    ) {
        val videoDisabled = C.TRACK_TYPE_VIDEO in trackSelectionParameters.disabledTrackTypes
        val videoOverrides = trackSelectionParameters.overrides.values.count { it.type == C.TRACK_TYPE_VIDEO }
        Log.i(
            TAG,
            "selection params: videoDisabled=$videoDisabled videoOverrides=$videoOverrides " +
                "maxVideo=${trackSelectionParameters.maxVideoWidth}x${trackSelectionParameters.maxVideoHeight} " +
                "maxBitrate=${trackSelectionParameters.maxVideoBitrate}",
        )
    }

    override fun onVideoEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        counters = decoderCounters
        lastRenderedCount = -1
        lastDroppedCount = -1
        stalledChecks = 0
        overloadedWindows = 0
        overloadReported = false
        Log.i(TAG, "video renderer enabled (softwareDecoders=$softwareDecoders)")
    }

    override fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        Log.i(TAG, "video renderer disabled; ${countersSummary(decoderCounters)}")
        counters = null
        decoderName = "none"
    }

    override fun onVideoDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        this.decoderName = decoderName
        Log.i(TAG, "decoder initialized: $decoderName in ${initializationDurationMs}ms")
    }

    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) {
        Log.i(TAG, "decoder released: $decoderName")
    }

    override fun onVideoInputFormatChanged(
        eventTime: EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        inputFormat = format
        Log.i(TAG, "input format: ${describe(format)}")
    }

    override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
        Log.i(TAG, "video size: ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}")
    }

    override fun onSurfaceSizeChanged(eventTime: EventTime, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (width == 0 && height == 0) {
            Log.w(TAG, "surface detached (0x0): frames have nowhere to go")
        } else {
            Log.i(TAG, "surface size: ${width}x$height")
        }
    }

    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
        firstFrameOutput = output.javaClass.simpleName
        Log.i(TAG, "first frame rendered to $firstFrameOutput at ${eventTime.currentPlaybackPositionMs}ms")
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.w(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms ($decoderName)")
    }

    override fun onVideoCodecError(eventTime: EventTime, videoCodecError: Exception) {
        Log.e(TAG, "video codec error in $decoderName", videoCodecError)
    }

    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        Log.e(TAG, "player error ${error.errorCodeName}; ${stateSummary()}")
    }

    override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) {
        playing = isPlaying
        handler.removeCallbacks(watchdog)
        if (isPlaying) {
            stalledChecks = 0
            lastRenderedCount = counters?.renderedOutputBufferCount ?: -1
            lastDroppedCount = counters?.droppedBufferCount ?: -1
            overloadedWindows = 0
            handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onPlayerReleased(eventTime: EventTime) {
        playing = false
        handler.removeCallbacks(watchdog)
        Log.i(TAG, "player released; ${stateSummary()}")
    }

    /**
     * Lists the profiles each platform decoder declares for [mimeType], once per process. A track
     * whose container hides its profile (e.g. MKV HEVC with an empty hvcC) is reported as
     * supported, so this is what tells "Main 10 not supported" apart from a decoder bug.
     */
    private fun logDecoderProfilesOnce(mimeType: String) {
        if (!loggedDecoderMimeTypes.add(mimeType)) return
        val decoders = runCatching { MediaCodecUtil.getDecoderInfos(mimeType, false, false) }
            .getOrDefault(emptyList())
        decoders.forEach { info ->
            val profiles = info.capabilities?.profileLevels.orEmpty()
                .joinToString(",") { "${it.profile}/${it.level}" }
            Log.i(TAG, "decoder caps: ${info.name} hw=${info.hardwareAccelerated} $mimeType profiles=[$profiles]")
        }
    }

    private fun checkFrames() {
        val rendered = counters?.renderedOutputBufferCount ?: -1
        val dropped = counters?.droppedBufferCount ?: -1
        checkDecoderLoad(rendered, dropped)
        lastDroppedCount = dropped
        val stalled = videoTrackCount > 0 && (rendered < 0 || rendered == lastRenderedCount)
        lastRenderedCount = rendered
        if (!stalled) {
            stalledChecks = 0
            return
        }
        stalledChecks++
        // Log the first stall and then every minute, not every tick.
        if (stalledChecks == 1 || stalledChecks % 12 == 0) {
            Log.w(TAG, "NO VIDEO FRAMES while playing (${stalledChecks * WATCHDOG_INTERVAL_MS / 1000}s); ${stateSummary()}")
        }
    }

    /**
     * Tells the UI, once per renderer session, when the decoder keeps dropping a large share of
     * frames: on weak TVs that is the only explanation the viewer gets for a stuttering picture.
     */
    private fun checkDecoderLoad(rendered: Int, dropped: Int) {
        if (overloadReported || rendered < 0 || dropped < 0 ||
            lastRenderedCount < 0 || lastDroppedCount < 0
        ) return
        if (isDecoderOverloaded(rendered - lastRenderedCount, dropped - lastDroppedCount)) {
            overloadedWindows++
            if (overloadedWindows >= DECODER_LOAD_CONSECUTIVE_WINDOWS) {
                overloadReported = true
                Log.w(TAG, "decoder cannot keep up ($decoderName); ${stateSummary()}")
                DecoderLoadMonitor.report(decoderName)
            }
        } else {
            overloadedWindows = 0
        }
    }

    private fun stateSummary(): String = buildString {
        append("videoTracks=").append(videoTrackCount)
        append(" selected=").append(videoSelected)
        append(" decoder=").append(decoderName)
        append(" software=").append(softwareDecoders)
        append(" surface=").append(surfaceWidth).append('x').append(surfaceHeight)
        append(" firstFrameTo=").append(firstFrameOutput)
        append(" format=").append(inputFormat?.let(::describe) ?: "none")
        counters?.let { append(" | ").append(countersSummary(it)) }
    }

    private companion object {
        const val TAG = "OxigenPlayer-Video"
        const val WATCHDOG_INTERVAL_MS = 5_000L
        val loggedDecoderMimeTypes: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun describe(format: Format): String = buildString {
            append(format.sampleMimeType ?: "?")
            format.codecs?.let { append(" (").append(it).append(')') }
            append(' ').append(format.width).append('x').append(format.height)
            if (format.frameRate > 0f) append(" @").append(format.frameRate)
            if (format.bitrate > 0) append(" ").append(format.bitrate / 1000).append("kbps")
            format.colorInfo?.let { append(" transfer=").append(it.colorTransfer) }
        }

        fun countersSummary(c: DecoderCounters): String =
            "rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} " +
                "skipped=${c.skippedOutputBufferCount} queued=${c.queuedInputBufferCount}"
    }
}
