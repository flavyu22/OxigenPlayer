// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.Locale

/**
 * Writes one logcat line per buffering episode, carrying the numbers that decide it.
 *
 * Large, high-bitrate files stop for reasons that need opposite fixes, and the visible symptom
 * (a spinner over the video) is identical:
 *
 *  1. the storage/network cannot deliver the bitrate long enough, in which case no buffer value
 *     in [ExoPlayerHelper] helps, or
 *  2. the source is fast but the configured window (5 s / 8 s / 25 s by RAM tier) expired during
 *     a short dip, in which case a larger window would absorb it.
 *
 * A third cause is a failing loader: [MainActivity] re-prepares the item several times on
 * recoverable I/O errors and every attempt looks exactly like buffering. The `load error` and
 * `player error` lines make that visible instead of silent.
 *
 * Reproduce the problem, then read:
 *
 *     adb logcat -s OxigenPlayer-Buffer
 *
 * [ExoPlayerHelper] attaches this to every player it builds, including the software-decoder
 * rebuild, and it logs only on state changes, so a healthy session stays silent.
 */
@OptIn(UnstableApi::class)
@Suppress("OVERRIDE_DEPRECATION")
internal class PlaybackBufferDiagnostics(
    private val tier: String,
    private val minBufferMs: Int,
    private val maxBufferMs: Int,
    private val targetBufferBytes: Int,
) : AnalyticsListener {

    private var hasBeenReady = false
    private var seekPending = false
    private var episodes = 0
    private var bufferingSinceRealtimeMs = 0L
    private var bufferedAtEpisodeStartMs = 0L
    private var lastLoadBytesPerSecond = 0.0
    private var loadFailures = 0
    private var videoBitrateBps = 0
    private var scheme: String = "-"

    override fun onPositionDiscontinuity(
        eventTime: EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // A seek empties the buffer on purpose. Labeling the following gap keeps scrubbing a
            // huge file from being reported as a source that cannot keep up with the bitrate.
            seekPending = true
        }
    }

    override fun onPlaybackStateChanged(eventTime: EventTime, playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (bufferingSinceRealtimeMs != 0L) {
                    val elapsedMs = SystemClock.elapsedRealtime() - bufferingSinceRealtimeMs
                    // Media filled per wall-clock time. Unlike lastLoad, this works for
                    // progressive MKV/MP4 from torrent servers, where one HTTP load spans the
                    // whole file and never completes: below 1.0x the source is slower than
                    // real time and no buffer setting can help.
                    val filledMs = eventTime.totalBufferedDurationMs - bufferedAtEpisodeStartMs
                    val fill = if (elapsedMs > 0L) {
                        String.format(Locale.US, "%.2fx", filledMs.toDouble() / elapsedMs)
                    } else {
                        "n/a"
                    }
                    Log.i(
                        TAG,
                        "episode #$episodes ended after $elapsedMs ms; filled ${filledMs}ms " +
                            "of media (fill rate $fill real time)",
                    )
                    bufferingSinceRealtimeMs = 0L
                }
                seekPending = false
                hasBeenReady = true
            }
            Player.STATE_BUFFERING -> {
                // The first BUFFERING after selecting a file is the ordinary open/prepare path.
                if (!hasBeenReady) return
                episodes++
                bufferingSinceRealtimeMs = SystemClock.elapsedRealtime()
                bufferedAtEpisodeStartMs = eventTime.totalBufferedDurationMs
                Log.w(TAG, describe(eventTime))
            }
        }
    }

    override fun onLoadStarted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        loadEventInfo.dataSpec.uri.scheme?.let { scheme = it }
    }

    override fun onLoadCompleted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // The only place where bytes and wall-clock time of the same read are both known, so it
        // is what the verdict below compares against the media bitrate.
        if ((loadEventInfo.bytesLoaded > 0L) && (loadEventInfo.loadDurationMs > 0L)) {
            lastLoadBytesPerSecond =
                loadEventInfo.bytesLoaded * 1000.0 / loadEventInfo.loadDurationMs
        }
    }

    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        loadFailures++
        Log.w(
            TAG,
            "load error #$loadFailures " +
                "(${if (wasCanceled) "canceled" else "retrying"}): $error",
        )
    }

    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        Log.e(TAG, "player error ${error.errorCodeName} (${error.errorCode}): ${error.message}")
    }

    override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {
        val format = mediaLoadData.trackFormat ?: return
        if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO && format.bitrate > 0) {
            videoBitrateBps = format.bitrate
        }
    }

    private fun describe(eventTime: EventTime): String {
        val kind = if (seekPending) "seek" else "rebuffer"
        val requiredBytesPerSecond = if (videoBitrateBps > 0) videoBitrateBps / 8.0 else 0.0
        val verdict = when {
            requiredBytesPerSecond <= 0.0 -> "video bitrate unknown"
            lastLoadBytesPerSecond <= 0.0 -> "no completed load measured yet"
            lastLoadBytesPerSecond < requiredBytesPerSecond ->
                "throughput BELOW bitrate -> source cannot keep up, no buffer value helps"
            else -> "throughput OK -> buffer window expired or the source stalled"
        }
        return buildString {
            append("episode #$episodes ($kind) at ")
            append(formatPosition(eventTime.currentPlaybackPositionMs))
            append(" buffered=").append(eventTime.totalBufferedDurationMs).append("ms")
            append(" | tier=").append(tier)
            append(" min=").append(minBufferMs).append("ms")
            append(" max=").append(maxBufferMs).append("ms")
            append(" byteCap=").append(targetBufferBytes / BYTES_PER_MEGABYTE).append("MB")
            append(" | video=").append(formatBitrate(videoBitrateBps))
            append(" need=").append(formatRate(requiredBytesPerSecond))
            append(" lastLoad=").append(formatRate(lastLoadBytesPerSecond))
            append(" | source=").append(scheme)
            append(" loadErrors=").append(loadFailures)
            append(" | ").append(verdict)
        }
    }

    private companion object {
        private const val TAG = "OxigenPlayer-Buffer"
        private const val BYTES_PER_MEGABYTE = 1024 * 1024

        fun formatPosition(positionMs: Long): String {
            val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
            return String.format(
                Locale.US,
                "%d:%02d:%02d",
                totalSeconds / 3_600L,
                (totalSeconds % 3_600L) / 60L,
                totalSeconds % 60L,
            )
        }

        fun formatBitrate(bitrateBps: Int): String =
            if (bitrateBps <= 0) "unknown"
            else String.format(Locale.US, "%.1f Mbps", bitrateBps / 1_000_000f)

        fun formatRate(bytesPerSecond: Double): String =
            if (bytesPerSecond <= 0.0) "n/a"
            else String.format(Locale.US, "%.1f MB/s", bytesPerSecond / BYTES_PER_MEGABYTE)
    }
}
