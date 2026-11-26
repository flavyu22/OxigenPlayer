// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPlaybackIntentInstrumentedTest {
    @Test
    fun mimeLessDeepLinkResolvesToMainActivity() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW,
            "oxigenplayer://play?url=https%3A%2F%2Fexample.com%2Fvideo.mp4".toUri())
            .setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull(resolved)
        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }

    @Test
    fun rtspsViewIntent_isAcceptedByTheRealIntentPath() {
        // setType() clears a previously set data URI, so build the intent with setDataAndType
        // the way real launcher/view intents carry both the stream URI and a media MIME type.
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
            "rtsps://camera.local:8555/live".toUri(),
            "video/*",
        )

        val request = validateExternalRemotePlaybackIntent(intent)

        assertNotNull(request)
        assertEquals("rtsps", request?.uri?.scheme)
    }

    @Test
    fun unsupportedOrNonViewIntent_isRejected() {
        assertNull(validateExternalRemotePlaybackIntent(Intent(Intent.ACTION_SEND, "rtsps://camera.local/live".toUri())))
        assertNull(validateExternalRemotePlaybackIntent(Intent(Intent.ACTION_VIEW, "ftp://example.com/file".toUri())))
    }

    @Test
    fun externalPlayerDeepLink_playsTheCarriedRemoteTarget() {
        val deepLink = ("oxigenplayer://play?url=" + Uri.encode("https://cdn.example.com/live/index.m3u8?token=abc")).toUri()
        val intent = Intent(Intent.ACTION_VIEW, deepLink)

        val request = validateExternalPlayerDeepLink(intent)

        assertNotNull(request)
        assertEquals("https", request?.uri?.scheme)
        assertEquals("index.m3u8", request?.uri?.lastPathSegment)
    }

    @Test
    fun externalPlayerDeepLink_rejectsMissingOrUnsupportedTargets() {
        // No url parameter.
        assertNull(validateExternalPlayerDeepLink(Intent(Intent.ACTION_VIEW, "oxigenplayer://play".toUri())))
        // Default cleartext http is outside the Play remote set and false here.
        assertNull(validateExternalPlayerDeepLink(Intent(Intent.ACTION_VIEW, ("oxigenplayer://play?url=" + Uri.encode("http://cdn.example.com/playlist.m3u8")).toUri())))
        // Unsupported target scheme.
        assertNull(validateExternalPlayerDeepLink(Intent(Intent.ACTION_VIEW, ("oxigenplayer://play?url=" + Uri.encode("ftp://example.com/file")).toUri())))
        // Wrong protocol scheme.
        assertNull(validateExternalPlayerDeepLink(Intent(Intent.ACTION_VIEW, ("unknown://play?url=" + Uri.encode("https://cdn.example.com/file")).toUri())))
        // Not an ACTION_VIEW intent.
        assertNull(validateExternalPlayerDeepLink(Intent(Intent.ACTION_SEND, ("oxigenplayer://play?url=" + Uri.encode("https://cdn.example.com/file")).toUri())))
    }
}
