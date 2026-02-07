package com.example.oxigenplayer

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class TranslationSource {
    MLKIT,
    ARGOS_TRANSLATE,
    MY_MEMORY,
    GOOGLE_TRANSLATE,
    LINGVA
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
    private val languageIdentifier = LanguageIdentification.getClient()
    
    private val translationCache = LruCache<String, String>(1000)
    private val translationMutex = Mutex()
    private val pendingTranslations = mutableSetOf<String>()

    /**
     * Detecteaza automat limba textului si reconfigureaza translatorul daca e necesar
     */
    suspend fun detectAndSetSourceLanguage(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val languageCode = languageIdentifier.identifyLanguage(text).await()
                if (languageCode != "und" && languageCode != sourceLanguage) {
                    Log.d(TAG, "Detected language: $languageCode")
                    setSourceLanguage(languageCode)
                    if (currentSource == TranslationSource.MLKIT) {
                        downloadModelIfNeeded()
                    }
                }
                languageCode
            } catch (e: Exception) {
                Log.e(TAG, "Language identification failed", e)
                sourceLanguage
            }
        }
    }

    fun setSourceLanguage(languageCode: String) {
        if (sourceLanguage != languageCode) {
            sourceLanguage = languageCode
            reinit()
        }
    }

    fun setTargetLanguage(languageCode: String) {
        if (targetLanguage != languageCode) {
            targetLanguage = languageCode
            reinit()
        }
    }

    fun getTargetLanguage(): String = targetLanguage

    private fun reinit() {
        translator.close()
        options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        translator = Translation.getClient(options)
        translationCache.evictAll()
    }

    suspend fun downloadModelIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
            }
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        if (sourceLanguage == targetLanguage) return text

        val cacheKey = "${currentSource.name}_${sourceLanguage}_${targetLanguage}_$text"
        translationCache.get(cacheKey)?.let { return it }

        return try {
            translationMutex.withLock {
                if (pendingTranslations.contains(cacheKey)) return text
                pendingTranslations.add(cacheKey)
            }

            withContext(Dispatchers.IO) {
                val translated = when (currentSource) {
                    TranslationSource.MLKIT -> translator.translate(text).await()
                    TranslationSource.ARGOS_TRANSLATE -> translateWithArgos(text)
                    TranslationSource.MY_MEMORY -> translateWithMyMemory(text)
                    TranslationSource.GOOGLE_TRANSLATE -> translateWithGoogle(text)
                    TranslationSource.LINGVA -> translateWithLingva(text)
                }
                translationCache.put(cacheKey, translated)
                translated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed with ${currentSource.name}", e)
            text
        } finally {
            translationMutex.withLock { pendingTranslations.remove(cacheKey) }
        }
    }

    /**
     * Traduce o listă întreagă de subtitrări în bloc (batch) pentru viteză maximă
     */
    suspend fun translateSubtitles(subtitles: List<SubtitleEntry>): List<SubtitleEntry> {
        if (subtitles.isEmpty() || sourceLanguage == targetLanguage) return subtitles
        
        return withContext(Dispatchers.IO) {
            try {
                // Grupăm textele pentru a face mai puține apeluri API (max 2000 caractere per apel)
                val translatedSubtitles = mutableListOf<SubtitleEntry>()
                val batchSize = 15 // Traducem câte 15 linii o dată
                
                for (i in subtitles.indices step batchSize) {
                    val batch = subtitles.subList(i, (i + batchSize).coerceAtMost(subtitles.size))
                    val combinedText = batch.joinToString("\n---\n") { it.text }
                    
                    val translatedBatchText = when (currentSource) {
                        TranslationSource.MLKIT -> translator.translate(combinedText).await()
                        TranslationSource.GOOGLE_TRANSLATE -> translateWithGoogle(combinedText)
                        TranslationSource.LINGVA -> translateWithLingva(combinedText)
                        else -> translate(combinedText) // Fallback la metoda standard
                    }
                    
                    val translatedLines = translatedBatchText.split(Regex("\\n---\\n|\\n--- \\n"))
                    
                    batch.forEachIndexed { index, entry ->
                        val translatedText = if (index < translatedLines.size) translatedLines[index].trim() else entry.text
                        translatedSubtitles.add(entry.copy(text = translatedText))
                        // Adăugăm și în cache pentru căutări ulterioare
                        val cacheKey = "${currentSource.name}_${sourceLanguage}_${targetLanguage}_${entry.text}"
                        translationCache.put(cacheKey, translatedText)
                    }
                }
                translatedSubtitles
            } catch (e: Exception) {
                Log.e(TAG, "Batch translation failed", e)
                subtitles
            }
        }
    }

    private fun translateWithArgos(text: String): String {
        return try {
            val url = URL("https://translate.argosopentech.com/translate") 
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 7000
            conn.readTimeout = 7000

            val jsonInput = JSONObject().apply {
                put("q", text)
                put("source", sourceLanguage)
                put("target", targetLanguage)
            }

            conn.outputStream.use { it.write(jsonInput.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getString("translatedText")
            } else {
                Log.e(TAG, "ArgosTranslate error code: ${conn.responseCode}")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "ArgosTranslate request failed", e)
            text
        }
    }

    private fun translateWithMyMemory(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val langPair = URLEncoder.encode("$sourceLanguage|$targetLanguage", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair")
            
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val responseData = JSONObject(response).getJSONObject("responseData")
                responseData.getString("translatedText")
            } else {
                Log.e(TAG, "MyMemory error code: ${conn.responseCode}")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "MyMemory request failed", e)
            text
        }
    }

    private fun translateWithGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLanguage&tl=$targetLanguage&dt=t&q=$encodedText")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = org.json.JSONArray(response)
                val sentences = jsonArray.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until sentences.length()) {
                    result.append(sentences.getJSONArray(i).getString(0))
                }
                result.toString()
            } else {
                Log.e(TAG, "Google Translate error code: ${conn.responseCode}")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Translate request failed", e)
            text
        }
    }

    private fun translateWithLingva(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            val url = URL("https://lingva.ml/api/v1/$sourceLanguage/$targetLanguage/$encodedText")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getString("translation")
            } else {
                Log.e(TAG, "Lingva error code: ${conn.responseCode}")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lingva request failed", e)
            text
        }
    }

    fun getAvailableLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    fun release() {
        translator.close()
        languageIdentifier.close()
        translationCache.evictAll()
    }
}
