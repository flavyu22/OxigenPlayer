// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/** Optional on-device generative translation engine supplied by an AI product flavor. */
interface LocalGenAiTranslator {
    /** True when this build contains the Gemini Nano integration. */
    val isIncluded: Boolean

    /** Checks whether this device can provide the local model now or download it on demand. */
    suspend fun isSupported(): Boolean

    /** Downloads or prepares the shared model when the device supports it. */
    suspend fun prepare(): Boolean

    /** Translates one short subtitle fragment without sending it to a network service. */
    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String

    fun release() = Unit
}

internal object UnavailableLocalGenAiTranslator : LocalGenAiTranslator {
    override val isIncluded: Boolean = false

    override suspend fun isSupported(): Boolean = false

    override suspend fun prepare(): Boolean = false

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String = throw IllegalStateException("Gemini Nano is not included in this build")
}
