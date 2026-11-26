// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAddressPolicyTest {
    @Test
    fun acceptsPublicAddresses() {
        assertTrue(isPublicNetworkAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(isPublicNetworkAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun blocksPrivateLocalAndDocumentationAddresses() {
        listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::1",
            "fe80::1",
            "fd00::1",
            "2001:db8::1",
            "64:ff9b::192.168.1.1",
        ).forEach { literal ->
            assertFalse(literal, isPublicNetworkAddress(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun allowsOnlyHttpsUrlsWithoutPrivateNumericTargets() {
        assertTrue(isAllowedPublicHttpsUrl("https://example.com/video.m3u8".toHttpUrl()))
        assertTrue(isAllowedPublicHttpsUrl("https://1.1.1.1/video.m3u8".toHttpUrl()))

        listOf(
            "http://example.com/video.m3u8",
            "https://127.0.0.1/video.m3u8",
            "https://127.1/video.m3u8",
            "https://2130706433/video.m3u8",
            "https://169.254.169.254/latest/meta-data",
            "https://192.168.1.20/video.m3u8",
            "https://[::1]/video.m3u8",
            "https://[fd00::1]/video.m3u8"
        ).forEach { rawUrl ->
            assertFalse(rawUrl, isAllowedPublicHttpsUrl(rawUrl.toHttpUrl()))
        }
    }

    @Test
    fun sharedClientRejectsUnsafeDestinationBeforeConnecting() {
        listOf("http://example.com/video", "https://127.0.0.1/video").forEach { rawUrl ->
            val failure = runCatching {
                NetworkClient.instance.newCall(Request.Builder().url(rawUrl).build())
                    .execute()
                    .close()
            }.exceptionOrNull()

            assertTrue(rawUrl, failure is UnknownHostException)
        }
    }
}
