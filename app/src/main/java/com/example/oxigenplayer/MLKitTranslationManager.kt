package com.example.oxigenplayer

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Custom exception for unavailable translation models
class TranslationModelNotAvailableException(val source: String, val target: String) : Exception("Translation model not available for $source->$target")

@Singleton
class MLKitTranslationManager @Inject constructor(
    private val context: Context
) {
    private val translatorCache = LruCache<String, Translator>(5) // Max 5 models in memory

    // 2025: Support for 59 languages
    private val supportedLanguages = TranslateLanguage.getAllLanguages()

    suspend fun getTranslator(source: String, target: String): Translator {
        val key = "$source-$target"
        return translatorCache.get(key) ?: createTranslator(source, target).also {
            translatorCache.put(key, it)
        }
    }

    private suspend fun createTranslator(source: String, target: String): Translator {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()

        return Translation.getClient(options).apply {
            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            try {
                downloadModelIfNeeded(conditions).await()
            } catch (e: MlKitException) {
                if (e.errorCode == MlKitException.NOT_FOUND) {
                    throw TranslationModelNotAvailableException(source, target)
                }
                throw e
            }
        }
    }

    // Batch translation for subtitle entries
    suspend fun translateBatch(
        entries: List<SubtitleEntry>,
        source: String,
        target: String,
        onProgress: (Int) -> Unit
    ): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        val translator = getTranslator(source, target)
        val semaphore = Semaphore(10)
        entries.mapIndexed { index, entry ->
            async {
                semaphore.withPermit {
                    val translated = translator.translate(entry.text).await()
                    onProgress(index)
                    entry.copy(text = translated, isTranslated = true)
                }
            }
        }.awaitAll()
    }

    fun clearCache() {
        translatorCache.evictAll()
    }
}

