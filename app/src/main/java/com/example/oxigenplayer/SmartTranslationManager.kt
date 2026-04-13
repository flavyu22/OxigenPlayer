package com.example.oxigenplayer

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
// ...existing imports...
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartTranslationManager @Inject constructor(
    private val mlKitManager: MLKitTranslationManager,
    private val whisperService: WhisperTranslationService,
    private val llmTranslator: OnDeviceLLMTranslator,
    private val connectivityManager: ConnectivityManager
) {
    sealed class TranslationSource {
        object MLKit : TranslationSource()
        object WhisperAPI : TranslationSource()
        object OnDeviceLLM : TranslationSource()
        data class Hybrid(val priority: List<TranslationSource>) : TranslationSource()
    }

    suspend fun translate(
        text: String,
        sourceLang: String = "auto",
        targetLang: String,
        source: TranslationSource = TranslationSource.Hybrid(
            listOf(TranslationSource.MLKit, TranslationSource.WhisperAPI)
        )
    ): String = when (source) {
        is TranslationSource.Hybrid -> {
            var result: String? = null
            for (src in source.priority) {
                result = tryTranslateWith(src, text, sourceLang, targetLang)
                if (result != null) break
            }
            result ?: text
        }
        else -> tryTranslateWith(source, text, sourceLang, targetLang) ?: text
    }

    private suspend fun tryTranslateWith(
        source: TranslationSource,
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        try {
            when (source) {
                TranslationSource.MLKit -> {
                    if (!isOnline()) return null
                    // Folosește batch cu un singur entry pentru text simplu
                    val entry = SubtitleEntry(0, 0, 0, text)
                    val result = mlKitManager.translateBatch(
                        listOf(entry),
                        sourceLang,
                        targetLang
                    ) { }
                    return result.firstOrNull()?.text
                }
                TranslationSource.WhisperAPI -> {
                    if (!isOnline()) return null
                    // Whisper nu are endpoint text direct, deci fallback la text ca "audio" nu e posibil
                    // Poți implementa aici un fallback sau returnează null
                    return null
                }
                TranslationSource.OnDeviceLLM -> {
                    // Se presupune că modelul e deja inițializat
                    return llmTranslator.translateSubtitle(text, sourceLang, targetLang)
                }
                else -> return null
            }
        } catch (e: Exception) {
            Log.w("Translation", "Source $source failed", e)
            return null
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

