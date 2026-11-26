// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun unknownLiveDuration_isNormalizedWithoutLosingMediaState() {
        assertEquals(0L, normalizedTimelineValue(-9_223_372_036_854_775_807L))

        val snapshot = PlaybackSnapshot(
            hasMedia = true,
            isPlaying = true,
            playWhenReady = true,
            phase = PlaybackPhase.READY,
            positionMs = 12_000L,
            durationMs = 0L,
            bufferedPositionMs = 15_000L,
            isLive = true,
            isSeekable = false,
            mediaUri = "https://example.test/live.m3u8",
        )

        assertTrue(snapshot.hasMedia)
        assertTrue(snapshot.isLive)
        assertTrue(snapshot.shouldKeepScreenOn)
    }

    @Test
    fun endedOrErroredPlayback_doesNotKeepScreenOn() {
        val ended = PlaybackSnapshot(
            hasMedia = true,
            isPlaying = false,
            playWhenReady = true,
            phase = PlaybackPhase.ENDED,
            positionMs = 1L,
            durationMs = 1L,
            bufferedPositionMs = 1L,
            isLive = false,
            isSeekable = true,
            mediaUri = "content://video",
        )
        val errored = ended.copy(phase = PlaybackPhase.ERROR, errorMessage = "decoder")

        assertFalse(ended.shouldKeepScreenOn)
        assertFalse(errored.shouldKeepScreenOn)
    }

    @Test
    fun resumeCheckpoint_avoidsImmediateRestartAtEitherEdge() {
        assertEquals(0L, playbackCheckpoint(positionMs = 2_000L, durationMs = 60_000L, ended = false))
        assertEquals(30_000L, playbackCheckpoint(positionMs = 30_000L, durationMs = 60_000L, ended = false))
        assertEquals(0L, playbackCheckpoint(positionMs = 58_000L, durationMs = 60_000L, ended = false))
        assertEquals(0L, playbackCheckpoint(positionMs = 30_000L, durationMs = 60_000L, ended = true))
    }

    @Test
    fun pausedPlayback_forcesControlsVisible() {
        val paused = PlaybackSnapshot(
            hasMedia = true,
            isPlaying = false,
            playWhenReady = false,
            phase = PlaybackPhase.READY,
            positionMs = 30_000L,
            durationMs = 60_000L,
            bufferedPositionMs = 60_000L,
            isLive = false,
            isSeekable = true,
            mediaUri = "content://video",
        )

        assertTrue(shouldForcePlaybackControlsVisible(paused))
        assertFalse(
            shouldForcePlaybackControlsVisible(
                paused.copy(isPlaying = true, playWhenReady = true),
            ),
        )
    }

    @Test
    fun pendingSelection_rejectsOldAndEmptySnapshots() {
        val pendingUri = "content://new-video"

        assertFalse(shouldAcceptPlaybackSnapshot(pendingUri, "content://old-video"))
        assertFalse(shouldAcceptPlaybackSnapshot(pendingUri, null))
        assertTrue(shouldAcceptPlaybackSnapshot(pendingUri, pendingUri))
        assertTrue(shouldAcceptPlaybackSnapshot(null, "content://session-video"))
    }

    @Test
    fun pendingSelection_blocksCheckpointPersistence() {
        assertFalse(
            canPersistPlaybackPosition(
                pendingMediaUri = "content://new-video",
                hasMedia = true,
                isLive = false,
                durationMs = 60_000L,
            ),
        )
        assertTrue(
            canPersistPlaybackPosition(
                pendingMediaUri = null,
                hasMedia = true,
                isLive = false,
                durationMs = 60_000L,
            ),
        )
    }
}
