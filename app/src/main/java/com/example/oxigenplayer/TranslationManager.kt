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
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
//  Motoare disponibile
//  AUTO  = cascadă automată: MLKit -> DeepL -> Google -> MyMemory -> Lingva -> Libre -> Argos
//  Fiecare motor e gratuit și funcțional fără API key (excepție: DEEPL_FREE)
// ─────────────────────────────────────────────────────────────────────────────
enum class TranslationSource {
    AUTO,            // Cascadă automată – recomandată
    MLKIT,           // Google ML Kit – offline, instant, ~50MB model
    GOOGLE_FREE,     // Google Translate web (fără API key, nelimitat*)
    MY_MEMORY,       // MyMemory API – 5.000 cuvinte/zi gratuit
    LINGVA,          // Lingva Translate – open-source, instanțe publice
    LIBRE_TRANSLATE, // LibreTranslate – open-source, instanțe publice
    DEEPL_FREE,      // DeepL Free API – 500k char/lună (cheie gratuită)
    ARGOS,           // Argos Translate – open-source, via API publică
}

class TranslationManager {

    private val TAG = "TranslationManager"

    // ── Stare limbă ──────────────────────────────────────────────────────────
    private var sourceLanguage = TranslateLanguage.ENGLISH
    private var targetLanguage = TranslateLanguage.ROMANIAN

    // ── Sursă curentă ────────────────────────────────────────────────────────
    var currentSource = TranslationSource.AUTO

    // ── Cheie opțională DeepL Free ───────────────────────────────────────────
    // Obține GRATUIT de pe: https://www.deepl.com/pro-api  (500k char/lună)
    // Setează din Preferences: translationManager.deeplApiKey = prefs.getDeeplApiKey()
    var deeplApiKey: String = "198SLQVGeRHuNYxk"

    // ── ML Kit ───────────────────────────────────────────────────────────────
    private var mlKitOptions = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLanguage)
        .setTargetLanguage(targetLanguage)
        .build()
    private var mlKitTranslator = Translation.getClient(mlKitOptions)

    // ── Cache LRU 500 intrări ─────────────────────────────────────────────────
    private val translationCache = LruCache<String, String>(500)

    // ── Detectare limbă ───────────────────────────────────────────────────────
    private val languageIdentifier = LanguageIdentification.getClient()

    // ── Semafon: max 3 traduceri simultane ───────────────────────────────────
    private val semaphore = Semaphore(3)

    // ── HTTP client OkHttp – connection pooling, retry, timeout optim ────────
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── Instanțe publice Lingva (fallback automat) ────────────────────────────
    private val lingvaInstances = listOf(
        "https://lingva.ml",
        "https://lingva.garudalinux.org",
        "https://translate.plausibility.cloud"
    )

    // ── Instanțe publice LibreTranslate (fallback automat) ────────────────────
    private val libreInstances = listOf(
        "https://libretranslate.com",
        "https://translate.argosopentech.com",
        "https://libretranslate.de"
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  SETTERS LIMBĂ
    // ─────────────────────────────────────────────────────────────────────────

    fun setSourceLanguage(languageCode: String) {
        val code = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        if (sourceLanguage != code) {
            sourceLanguage = code
            reinitMlKit()
        }
    }

    fun setTargetLanguage(languageCode: String) {
        val code = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        if (targetLanguage != code) {
            targetLanguage = code
            reinitMlKit()
        }
    }

    fun getTargetLanguage(): String = targetLanguage
    fun getSourceLanguage(): String = sourceLanguage

    private fun reinitMlKit() {
        try {
            mlKitTranslator.close()
            mlKitOptions = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
            mlKitTranslator = Translation.getClient(mlKitOptions)
            translationCache.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "reinitMlKit failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DETECTARE LIMBĂ AUTOMATĂ
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun detectAndSetSourceLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val detected = languageIdentifier.identifyLanguage(text).await()
            if (detected != "und" && detected != sourceLanguage) {
                setSourceLanguage(detected)
                detected
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Language detection failed", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DOWNLOAD MODEL ML KIT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun downloadModelIfNeeded(): Boolean {
        return try {
            val conditions = DownloadConditions.Builder().build()
            mlKitTranslator.downloadModelIfNeeded(conditions).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Model download failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PUNCT DE INTRARE PRINCIPAL
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun translate(text: String): String {
        // Curăță taguri HTML / ASS din subtitluri
        val clean = text.trim()
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\{[^}]*\\}"), "")
            .trim()

        if (clean.isBlank() || clean.length < 2) return text
        if (sourceLanguage == targetLanguage) return text

        val cacheKey = "${currentSource.name}_${sourceLanguage}_${targetLanguage}_$clean"
        translationCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                val result = try {
                    when (currentSource) {
                        TranslationSource.AUTO           -> translateAuto(clean)
                        TranslationSource.MLKIT          -> translateMlKit(clean) ?: clean
                        TranslationSource.GOOGLE_FREE    -> translateGoogle(clean) ?: clean
                        TranslationSource.MY_MEMORY      -> translateMyMemory(clean) ?: clean
                        TranslationSource.LINGVA         -> translateLingva(clean) ?: clean
                        TranslationSource.LIBRE_TRANSLATE-> translateLibre(clean) ?: clean
                        TranslationSource.DEEPL_FREE     -> translateDeepl(clean) ?: clean
                        TranslationSource.ARGOS          -> translateArgos(clean) ?: clean
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "translate() outer: ${e.message}")
                    clean
                }
                if (result.isNotBlank() && result != clean) translationCache.put(cacheKey, result)
                result
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AUTO — cascadă cu timeout per motor
    //  Ordinea: MLKit -> DeepL -> Google -> MyMemory -> Lingva -> Libre -> Argos
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateAuto(text: String): String {
        val engines: List<suspend () -> String?> = listOf(
            { translateMlKit(text) },
            { translateDeepl(text) },
            { translateGoogle(text) },
            { translateMyMemory(text) },
            { translateLingva(text) },
            { translateLibre(text) },
            { translateArgos(text) }
        )
        for (engine in engines) {
            val result = withTimeoutOrNull(7_000L) { engine() }
            if (!result.isNullOrBlank() && result != text) return result
        }
        return text
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. ML KIT — OFFLINE, INSTANT (model ~50MB, descărcat o singură dată)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateMlKit(text: String): String? {
        return try {
            mlKitTranslator.translate(text).await().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "MLKit: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. GOOGLE TRANSLATE FREE — endpoint neoficial, stabil, fără API key
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateGoogle(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(text, "UTF-8")
            val sl = if (sourceLanguage == "und") "auto" else sourceLanguage
            val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=$sl&tl=$targetLanguage&dt=t&q=$enc"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android 12)")
                .get().build()

            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            val sentences = JSONArray(body).optJSONArray(0) ?: return@withContext null
            buildString {
                for (i in 0 until sentences.length()) {
                    sentences.optJSONArray(i)?.optString(0)?.let { append(it) }
                }
            }.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Google: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. MYMEMORY — 5.000 cuvinte/zi gratuit, fără înregistrare
    //     https://mymemory.translated.net/doc/spec.php
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateMyMemory(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(text, "UTF-8")
            val sl = if (sourceLanguage == "und") "en" else sourceLanguage
            val url = "https://api.mymemory.translated.net/get?q=$enc&langpair=$sl|$targetLanguage"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "OxigenPlayer/1.1.0")
                .get().build()

            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            val json = JSONObject(body)
            if (json.optInt("responseStatus", 0) != 200) return@withContext null
            json.optJSONObject("responseData")
                ?.optString("translatedText")
                ?.takeIf { it.isNotBlank() && it != "NULL" && !it.startsWith("PLEASE") }
        } catch (e: Exception) {
            Log.w(TAG, "MyMemory: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. LINGVA TRANSLATE — open-source, instanțe publice multiple
    //     Cod sursă: https://github.com/thedaviddelta/lingva-translate
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateLingva(text: String): String? = withContext(Dispatchers.IO) {
        val sl = if (sourceLanguage == "und") "auto" else sourceLanguage
        val enc = URLEncoder.encode(text, "UTF-8")
        for (instance in lingvaInstances) {
            try {
                val req = Request.Builder()
                    .url("$instance/api/v1/$sl/$targetLanguage/$enc")
                    .header("User-Agent", "OxigenPlayer/1.1.0")
                    .get().build()

                val body = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                } ?: continue

                val result = JSONObject(body).optString("translation", "")
                if (result.isNotBlank() && result != text) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "Lingva $instance: ${e.message}")
                continue
            }
        }
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  5. LIBRETRANSLATE — open-source, instanțe publice multiple
    //     Cod sursă: https://github.com/LibreTranslate/LibreTranslate
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateLibre(text: String): String? = withContext(Dispatchers.IO) {
        val sl = if (sourceLanguage == "und") "auto" else sourceLanguage
        for (instance in libreInstances) {
            try {
                val formBody = FormBody.Builder()
                    .add("q", text)
                    .add("source", sl)
                    .add("target", targetLanguage)
                    .add("format", "text")
                    .build()

                val req = Request.Builder()
                    .url("$instance/translate")
                    .header("User-Agent", "OxigenPlayer/1.1.0")
                    .post(formBody).build()

                val body = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                } ?: continue

                val result = JSONObject(body).optString("translatedText", "")
                if (result.isNotBlank() && result != text) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "LibreTranslate $instance: ${e.message}")
                continue
            }
        }
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  6. DEEPL FREE API — CEL MAI PRECIS motor disponibil
    //     Cheie GRATUITĂ: https://www.deepl.com/pro-api  (500k char/lună)
    //     Setează: translationManager.deeplApiKey = prefs.getDeeplApiKey()
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateDeepl(text: String): String? = withContext(Dispatchers.IO) {
        if (deeplApiKey.isBlank()) return@withContext null
        try {
            val tgt = when (targetLanguage.lowercase()) {
                "en" -> "EN-US"
                "pt" -> "PT-PT"
                "zh" -> "ZH"
                else -> targetLanguage.uppercase()
            }
            val formBody = FormBody.Builder()
                .add("text", text)
                .add("target_lang", tgt)
                .apply { if (sourceLanguage != "und") add("source_lang", sourceLanguage.uppercase()) }
                .build()

            val req = Request.Builder()
                .url("https://api-free.deepl.com/v2/translate")
                .header("Authorization", "DeepL-Auth-Key $deeplApiKey")
                .post(formBody).build()

            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            JSONObject(body)
                .optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "DeepL: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  7. ARGOS TRANSLATE — open-source, instanță publică Argos
    //     Cod sursă: https://github.com/argosopentech/argos-translate
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun translateArgos(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val sl = if (sourceLanguage == "und") "en" else sourceLanguage
            val jsonBody = """{"q":"${text.replace("\"", "\\\"")}","source":"$sl","target":"$targetLanguage"}"""
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("https://translate.argosopentech.com/translate")
                .header("User-Agent", "OxigenPlayer/1.1.0")
                .post(jsonBody).build()

            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            JSONObject(body).optString("translatedText", "")
                .takeIf { it.isNotBlank() && it != text }
        } catch (e: Exception) {
            Log.w(TAG, "Argos: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TRADUCERE BATCH — pentru butonul "Traduce Tot"
    //  Procesează în grupuri de 5 pentru a nu spama API-urile
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        onUpdate: (SubtitleEntry) -> Unit
    ) {
        if (subtitles.isEmpty()) return

        val sample = subtitles.take(10).joinToString(" ") { it.text }
        detectAndSetSourceLanguage(sample)
        if (sourceLanguage == targetLanguage) return

        if (currentSource == TranslationSource.MLKIT || currentSource == TranslationSource.AUTO) {
            downloadModelIfNeeded()
        }

        withContext(Dispatchers.Default) {
            subtitles.chunked(5).forEach { chunk ->
                if (!isActive) return@withContext
                chunk.forEach { entry ->
                    if (!isActive) return@forEach
                    val translated = translate(entry.text)
                    withContext(Dispatchers.Main) { onUpdate(entry.copy(text = translated)) }
                }
                delay(20) // pauză scurtă între grupuri – respectă limitele API
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UTILITĂȚI
    // ─────────────────────────────────────────────────────────────────────────

    fun getAvailableLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    fun clearCache() = translationCache.evictAll()

    fun release() {
        mlKitTranslator.close()
        languageIdentifier.close()
        translationCache.evictAll()
    }
}
