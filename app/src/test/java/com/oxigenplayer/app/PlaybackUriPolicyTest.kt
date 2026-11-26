// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUriPolicyTest {

    @Test
    fun mimePolicy_acceptsOnlyMediaAndStreamingManifests() {
        assertTrue(isSupportedPlaybackMimeType("video/x-matroska"))
        assertTrue(isSupportedPlaybackMimeType("audio/flac"))
        assertTrue(isSupportedPlaybackMimeType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertTrue(isSupportedPlaybackMimeType("application/dash+xml"))

        assertFalse(isSupportedPlaybackMimeType(null))
        assertFalse(isSupportedPlaybackMimeType("application/octet-stream"))
        assertFalse(isSupportedPlaybackMimeType("text/plain"))
    }

    @Test
    fun remotePolicy_acceptsExplicitWebRtspAndLocalSources() {
        listOf(
            "https://cdn.example.com/movie.m3u8",
            "rtsp://camera.local:8554/live",
            "rtsps://camera.local:8555/live",
            "https://nas/media/movie.mkv",
            "https://[fd00::1]:9443/video.mp4",
        ).forEach { uri -> assertTrue(uri, isSupportedRemotePlaybackUri(uri, allowCleartextHttp = false)) }
    }

    @Test
    fun remotePolicy_cleartextHttpRequiresExplicitLanEditionAndLanTarget() {
        listOf(
            "http://192.168.1.20:8080/video.mp4",
            "http://nas.local/video.mp4",
            "http://media-box/video.mp4",
            "http://[fd00::1]/video.mp4",
        ).forEach { uri ->
            assertFalse(uri, isSupportedRemotePlaybackUri(uri, allowCleartextHttp = false))
            assertTrue(uri, isSupportedRemotePlaybackUri(uri, allowCleartextHttp = true))
        }
        assertFalse(isSupportedRemotePlaybackUri("http://example.com/video.mp4", allowCleartextHttp = true))
        assertFalse(isSupportedRemotePlaybackUri("http://1.1.1.1/video.mp4", allowCleartextHttp = true))
    }

    @Test
    fun remotePolicy_rejectsCredentialsFragmentsAndMalformedAddresses() {
        listOf(
            "file:///sdcard/movie.mkv",
            "ftp://example.com/movie.mp4",
            "https://user:secret@cdn.example.com/movie.mp4",
            "https://cdn.example.com/movie.mp4#fragment",
            "https://bad_host/movie.mp4",
            "https://-bad.example/movie.mp4",
            "https://example.com:0/movie.mp4",
            "https:///movie.mp4",
        ).forEach { uri -> assertFalse(uri, isSupportedRemotePlaybackUri(uri, allowCleartextHttp = true)) }
    }
}
