// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaSessionService
import com.oxigenplayer.app.R

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var preferences: PreferencesManager

    /**
     * Number of software-decoder rebuilds already spent on the current media item. Reset whenever
     * the item changes so each file gets a fresh retry, but bounded within an item so genuinely
     * undecodable content cannot spin forever.
     */
    private var softwareFallbackAttempts = 0
    private var softwareDecodersActive = false
    private val checkpointHandler = Handler(Looper.getMainLooper())
    private val checkpointRunnable = object : Runnable {
        override fun run() {
            persistPlaybackCheckpoint()
            if (player != null) checkpointHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    private val checkpointListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
            ) {
                persistPlaybackCheckpoint()
            }
        }
    }

    /**
     * Turns a fatal decoder failure into one bounded attempt at software decoding.
     *
     * Gated on [CodecRenderersFactory.softwareDecodersAvailable] because the platform flavor
     * ships no third-party decoder, and skipped once [softwareDecodersActive] is set so a file
     * that FFmpeg also cannot decode surfaces its error instead of looping.
     */
    private val decoderFallbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (softwareDecodersActive) return
            if (!CodecRenderersFactory.softwareDecodersAvailable) return
            if (softwareFallbackAttempts >= MAX_SOFTWARE_FALLBACK_ATTEMPTS) return
            if (!isSoftwareFallbackErrorCode(error.errorCode, error.errorCodeName)) return

            softwareFallbackAttempts++
            // Leave the failing player's listener dispatch before touching it.
            val failing = player

            // Cheap path first: hide only the failing platform decoder type and re-prepare the
            // same player. Track selection then routes that type to FFmpeg while everything
            // else stays on hardware, and the media session / controllers are untouched.
            val failedMime = failedPlatformDecoderMimeType(error)
            if (failedMime != null && PlatformDecoderBlocklist.block(failedMime)) {
                Log.w(
                    "PlaybackService",
                    "Platform $failedMime decoder failed with ${error.errorCodeName}; " +
                        "re-preparing with it disabled for this process",
                    error,
                )
                checkpointHandler.post {
                    if (player === failing) failing?.prepare()
                }
                return
            }

            Log.w(
                "PlaybackService",
                "Decoder failed with ${error.errorCodeName}; retrying with software decoders",
                error,
            )
            checkpointHandler.post {
                if (player === failing) rebuildWithSoftwareDecoders()
            }
        }

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            @Player.MediaItemTransitionReason reason: Int,
        ) {
            // Each new file deserves its own bounded retry.
            softwareFallbackAttempts = 0
            softwareDecodersActive = false
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val isOwnApp = controller.packageName == packageName
            if (!isOwnApp && !controller.isTrusted) {
                return MediaSession.ConnectionResult.reject()
            }

            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (isOwnApp) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(PlaybackSessionCommands.setMatchContentFrameRate)
                        .add(PlaybackSessionCommands.setSkipSilence)
                        .add(PlaybackSessionCommands.setAudioDelay)
                        .build(),
                )
            }
            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val isOwnApp = controller.packageName == packageName
            if (isOwnApp && (customCommand == PlaybackSessionCommands.setMatchContentFrameRate)) {
                val enabled = args.getBoolean(PlaybackSessionCommands.ARG_ENABLED, false)
                preferences.saveMatchContentFrameRate(enabled)
                player?.videoChangeFrameRateStrategy = if (enabled) {
                    androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                } else {
                    androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (isOwnApp && (customCommand == PlaybackSessionCommands.setSkipSilence)) {
                val enabled = args.getBoolean(PlaybackSessionCommands.ARG_ENABLED, false)
                preferences.saveSkipSilence(enabled)
                player?.skipSilenceEnabled = enabled
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (isOwnApp && (customCommand == PlaybackSessionCommands.setAudioDelay)) {
                val delayMs = clampAudioDelayMs(args.getInt(PlaybackSessionCommands.ARG_DELAY_MS, 0))
                preferences.saveAudioDelayMs(delayMs)
                // The active audio sink reads this on its next clock query, so the change is
                // audible immediately without rebuilding the player.
                AudioDelayState.delayMs = delayMs
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Brand the media notification with the Oxigen small icon. Must be set before playback
        // starts so the first foreground notification already carries it.
        setMediaNotificationProvider(OxigenNotificationProvider(this))

        preferences = (application as OxigenApp).prefs
        val createdPlayer = ExoPlayerHelper.createTvOptimizedPlayer(this, preferences)
        createdPlayer.addListener(checkpointListener)
        createdPlayer.addListener(decoderFallbackListener)
        player = createdPlayer
        initSession(createdPlayer)
        checkpointHandler.postDelayed(checkpointRunnable, CHECKPOINT_INTERVAL_MS)
    }

    /**
     * Media notification that uses the Oxigen silhouette as its status-bar small icon.
     *
     * The system tints the small icon, so the source is the single-colour [R.drawable.ic_stat_oxigen].
     * Everything else (channel, actions, artwork) keeps Media3's default behaviour.
     */
    private class OxigenNotificationProvider(context: android.content.Context) :
        DefaultMediaNotificationProvider(context) {
        init {
            setSmallIcon(R.drawable.ic_stat_oxigen)
        }
    }

    /**
     * Rebuilds the player on the FFmpeg decoders when the platform decoders cannot deliver a
     * track they advertised as supported.
     *
     * A `MediaCodec` failure is fatal for the current renderer: Media3 picks the renderer once,
     * at track selection, and its own recovery only re-selects the same track against the same
     * decoder. Swapping to a software-first player is therefore the only way to keep such files
     * playing. The media item, position and play state are carried over so the viewer barely
     * notices, and a fresh `MediaSession` is published so `MediaController` clients rebind to the
     * new player.
     */
    private fun rebuildWithSoftwareDecoders() {
        val failing = player ?: return
        val item = failing.currentMediaItem ?: return
        val positionMs = failing.currentPosition.coerceAtLeast(0L)
        val wasPlaying = failing.playWhenReady

        Log.w(
            "PlaybackService",
            "Rebuilding player with software decoders at position ${positionMs}ms",
        )

        mediaSession?.release()
        mediaSession = null
        failing.removeListener(checkpointListener)
        failing.removeListener(decoderFallbackListener)
        failing.release()

        val replacement = ExoPlayerHelper.createTvOptimizedPlayer(
            context = this,
            preferences = preferences,
            preferSoftwareDecoders = true,
        )
        replacement.addListener(checkpointListener)
        replacement.addListener(decoderFallbackListener)
        player = replacement
        softwareDecodersActive = true
        initSession(replacement)

        replacement.setMediaItem(item, positionMs)
        replacement.prepare()
        replacement.playWhenReady = wasPlaying
    }

    private fun initSession(player: ExoPlayer) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPlaybackCheckpoint()
        val currentPlayer = player
        val shouldKeepRunning = currentPlayer?.let {
            (it.isPlaying) || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING)
        } == true
        if (!shouldKeepRunning) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun persistPlaybackCheckpoint() {
        if (!::preferences.isInitialized) return
        val currentPlayer = player ?: return
        if (currentPlayer.isCurrentMediaItemLive) return
        val duration = currentPlayer.duration
        if (duration <= 0L) return
        // Items arriving from a MediaController have no localConfiguration, so reading it alone
        // made every controller-driven checkpoint a no-op.
        val uri = currentPlayer.currentPlaybackMediaUri()
            ?.takeIf(String::isNotBlank)
            ?: return
        preferences.saveVideoPosition(
            uri,
            playbackCheckpoint(
                positionMs = currentPlayer.currentPosition,
                durationMs = duration,
                ended = currentPlayer.playbackState == Player.STATE_ENDED,
            ),
        )
    }

    override fun onDestroy() {
        checkpointHandler.removeCallbacksAndMessages(null)
        persistPlaybackCheckpoint()
        mediaSession?.release()
        mediaSession = null
        player?.let { currentPlayer ->
            currentPlayer.removeListener(checkpointListener)
            currentPlayer.removeListener(decoderFallbackListener)
            currentPlayer.release()
        }
        player = null
        super.onDestroy()
    }

    private companion object {
        const val CHECKPOINT_INTERVAL_MS = 10_000L
    }
}
