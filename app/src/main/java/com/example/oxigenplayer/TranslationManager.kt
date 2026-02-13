package com.example.oxigenplayer

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class TranslationSource {
    MLKIT,
    MY_MEMORY,
    GOOGLE_TRANSLATE,
    LIBRE_TRANSLATE
}

class TranslationManager {
    private val TAG = "TranslationManager"
    private var sourceLanguage = TranslateLanguage.ENGLISH
    private var targetLanguage = TranslateLanguage.ROMANIAN
    
    var currentSource = TranslationSource.MLKIT

    private var options = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLanguage)
        .setTargetLanguage(targetLanguage)
        .build()
    
    private var translator = Translation.getClient(options)
    private val translationCache = LruCache<String, String>(500)
    private val languageIdentifier = LanguageIdentification.getClient()
    
    // Limităm strict la 2 operațiuni simultane pentru a preveni blocarea UI-ului pe TV
    private val semaphore = Semaphore(2)

    fun setSourceLanguage(languageCode: String) {
        val supportedCode = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        if (sourceLanguage != supportedCode) {
            sourceLanguage = supportedCode
            reinit()
        }
    }

    fun setTargetLanguage(languageCode: String) {
        val supportedCode = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        if (targetLanguage != supportedCode) {
            targetLanguage = supportedCode
            reinit()
        }
    }

    fun getTargetLanguage(): String = targetLanguage
    fun getSourceLanguage(): String = sourceLanguage

    private fun reinit() {
        try {
            translator.close()
            options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            translator = Translation.getClient(options)
            translationCache.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "Reinit failed", e)
        }
    }

    suspend fun detectAndSetSourceLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val languageCode = languageIdentifier.identifyLanguage(text).await()
            if (languageCode != "und" && languageCode != sourceLanguage) {
                setSourceLanguage(languageCode)
                languageCode
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Language detection failed", e)
            null
        }
    }

    suspend fun downloadModelIfNeeded(): Boolean {
        return try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank() || sourceLanguage == targetLanguage) return text

        val cacheKey = "${currentSource.name}_${sourceLanguage}_${targetLanguage}_$text"
        translationCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    val result = when (currentSource) {
                        TranslationSource.MLKIT -> {
                            try {
                                translator.translate(text).await()
                            } catch (e: Exception) {
                                translateWithGoogle(text)
                            }
                        }
                        TranslationSource.MY_MEMORY -> translateWithMyMemory(text)
                        TranslationSource.GOOGLE_TRANSLATE -> translateWithGoogle(text)
                        TranslationSource.LIBRE_TRANSLATE -> translateWithLibreTranslate(text)
                    }
                    if (!result.isNullOrBlank()) {
                        translationCache.put(cacheKey, result)
                        result
                    } else text
                } catch (e: Exception) {
                    text
                }
            }
        }
    }

    suspend fun translateSubtitles(subtitles: List<SubtitleEntry>, onUpdate: (SubtitleEntry) -> Unit) {
        if (subtitles.isEmpty()) return
        
        // Detectăm limba din prima subtitrare semnificativă
        val sampleText = subtitles.take(10).joinToString(" ") { it.text }
        detectAndSetSourceLanguage(sampleText)

        if (sourceLanguage == targetLanguage) return
        
        if (currentSource == TranslationSource.MLKIT) {
            downloadModelIfNeeded()
        }

        // Procesăm subtitrările cu grijă pentru a nu bloca procesorul G52
        withContext(Dispatchers.Default) {
            subtitles.forEach { entry ->
                if (!isActive) return@withContext
                val translatedText = translate(entry.text)
                withContext(Dispatchers.Main) {
                    onUpdate(entry.copy(text = translatedText))
                }
                // Lăsăm un mic spațiu între traduceri pentru stabilitatea sistemului
                delay(10)
            }
        }
    }

    private fun translateWithMyMemory(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val langPair = URLEncoder.encode("$sourceLanguage|$targetLanguage", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getJSONObject("responseData").getString("translatedText")
            } else text
        } catch (e: Exception) { text }
    }

    private fun translateWithGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLanguage&tl=$targetLanguage&dt=t&q=$encodedText")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = org.json.JSONArray(response)
                val sentences = jsonArray.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until sentences.length()) {
                    result.append(sentences.getJSONArray(i).getString(0))
                }
                result.toString()
            } else text
        } catch (e: Exception) { text }
    }

    private fun translateWithLibreTranslate(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            // Folosim o instanță publică de LibreTranslate. Notă: instanțele publice pot fi instabile.
            val url = URL("https://libretranslate.de/translate")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val jsonInput = JSONObject().apply {
                put("q", text)
                put("source", sourceLanguage)
                put("target", targetLanguage)
                put("format", "text")
            }
            
            conn.outputStream.use { it.write(jsonInput.toString().toByteArray()) }
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getString("translatedText")
            } else {
                translateWithGoogle(text)
            }
        } catch (e: Exception) { 
            translateWithGoogle(text)
        }
    }

    fun getAvailableLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    fun release() {
        translator.close()
        languageIdentifier.close()
        translationCache.evictAll()
    }
}
