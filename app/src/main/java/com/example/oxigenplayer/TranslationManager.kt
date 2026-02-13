package com.example.oxigenplayer

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
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
    
    // Inițializare lazy pentru a accelera pornirea aplicației
    private var _translator: Translator? = null
    private val translator: Translator
        get() {
            if (_translator == null) {
                _translator = Translation.getClient(options)
            }
            return _translator!!
        }

    private val translationCache = LruCache<String, String>(500)
    
    private var _languageIdentifier: LanguageIdentifier? = null
    private val languageIdentifier: LanguageIdentifier
        get() {
            if (_languageIdentifier == null) {
                _languageIdentifier = LanguageIdentification.getClient()
            }
            return _languageIdentifier!!
        }
    
    private val semaphore = Semaphore(2)
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
            _translator?.close()
            _translator = null
            options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
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
            Log.e(TAG, "Model download failed", e)
            false
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank() || sourceLanguage == targetLanguage) return text

        val cacheKey = "${currentSource.name}_${sourceLanguage}_${targetLanguage}_$text"
        translationCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                var result: String? = null
                var retryCount = 0
                val maxRetries = 2

                while (result == null && retryCount <= maxRetries) {
                    try {
                        result = when (currentSource) {
                            TranslationSource.MLKIT -> {
                                try {
                                    translator.translate(text).await()
                                } catch (e: Exception) {
                                    Log.w(TAG, "MLKit failed, fallback to Google")
                                    translateWithGoogle(text)
                                }
                            }
                            TranslationSource.MY_MEMORY -> translateWithMyMemory(text)
                            TranslationSource.GOOGLE_TRANSLATE -> translateWithGoogle(text)
                            TranslationSource.LIBRE_TRANSLATE -> translateWithLibreTranslate(text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Translation attempt ${retryCount + 1} failed", e)
                        retryCount++
                        if (retryCount <= maxRetries) delay(500)
                    }
                }

                if (!result.isNullOrBlank() && result != text) {
                    translationCache.put(cacheKey, result)
                    result
                } else text
            }
        }
    }

    suspend fun translateSubtitles(subtitles: List<SubtitleEntry>, onUpdate: (SubtitleEntry) -> Unit) {
        if (subtitles.isEmpty()) return
        
        val sampleText = subtitles.take(10).joinToString(" ") { it.text }
        detectAndSetSourceLanguage(sampleText)

        if (sourceLanguage == targetLanguage) return
        
        if (currentSource == TranslationSource.MLKIT) {
            downloadModelIfNeeded()
        }

        withContext(Dispatchers.Default) {
            subtitles.forEach { entry ->
                if (!isActive) return@withContext
                val translatedText = translate(entry.text)
                withContext(Dispatchers.Main) {
                    onUpdate(entry.copy(text = translatedText))
                }
                delay(20)
            }
        }
    }

    private fun translateWithMyMemory(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val langPair = URLEncoder.encode("$sourceLanguage|$targetLanguage", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getJSONObject("responseData").getString("translatedText")
            } else text
        } catch (e: Exception) { text }
    }

    private fun translateWithGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLanguage&tl=$targetLanguage&dt=t&q=$encodedText&ie=UTF-8&oe=UTF-8")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = org.json.JSONArray(response)
                val sentences = jsonArray.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val sentence = sentences.get(i)
                    if (sentence is org.json.JSONArray) {
                        result.append(sentence.getString(0))
                    }
                }
                result.toString().ifBlank { text }
            } else {
                Log.e(TAG, "Google Translate error: ${conn.responseCode}")
                text
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Google Translate exception", e)
            text 
        }
    }

    private fun translateWithLibreTranslate(text: String): String {
        return try {
            val url = URL("https://libretranslate.de/translate")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.doOutput = true

            val jsonInput = JSONObject().apply {
                put("q", text)
                put("source", sourceLanguage)
                put("target", targetLanguage)
                put("format", "text")
            }

            conn.outputStream.use { os ->
                os.write(jsonInput.toString().toByteArray())
            }

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
        try {
            _translator?.close()
            _languageIdentifier?.close()
            translationCache.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "Release failed", e)
        }
    }
}
