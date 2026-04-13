package com.example.oxigenplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject

class WhisperTranslationService @Inject constructor(
    private val client: OkHttpClient,
    private val apiKey: String
) {
    // Preț 2025: $0.006/minut audio (~$0.36/oră)

    sealed class WhisperModel(val modelId: String) {
        object Whisper1 : WhisperModel("whisper-1")
        object WhisperLargeV3 : WhisperModel("whisper-large-v3") // Mai precis, dar mai lent
    }

    suspend fun transcribeAndTranslate(
        audioFile: File,
        targetLanguage: String,
        model: WhisperModel = WhisperModel.Whisper1
    ): Result<List<SubtitleEntry>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody())
                .addFormDataPart("model", model.modelId)
                .addFormDataPart("language", targetLanguage)
                .addFormDataPart("response_format", "srt")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Whisper API error: ${response.code}")
                    )
                }
                val srtContent = response.body?.string() ?: ""
                Result.success(parseSRT(srtContent))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Alternativ: Transcriere + traducere separată pentru calitate superioară
    suspend fun transcribeThenTranslate(
        audioFile: File,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<List<SubtitleEntry>> {
        // Pas 1: Transcriere în limba sursă
        val transcription = transcribe(audioFile, sourceLanguage).getOrElse {
            return Result.failure(it)
        }
        // Pas 2: Traducere text cu GPT-4o pentru context îmbunătățit
        return translateWithGPT4o(transcription, targetLanguage)
    }

    // Stub: implementare reală ar folosi GPT-4o API
    private suspend fun translateWithGPT4o(
        entries: List<SubtitleEntry>,
        targetLang: String
    ): Result<List<SubtitleEntry>> {
        return Result.failure(NotImplementedError("GPT-4o translation not implemented"))
    }

    // Helper: transcriere simplă fără traducere
    private suspend fun transcribe(
        audioFile: File,
        language: String,
        model: WhisperModel = WhisperModel.Whisper1
    ): Result<List<SubtitleEntry>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody())
                .addFormDataPart("model", model.modelId)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "srt")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Whisper API error: ${response.code}")
                    )
                }
                val srtContent = response.body?.string() ?: ""
                Result.success(parseSRT(srtContent))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Parse SRT content to SubtitleEntry list (reuse SubtitleParser logic)
    private fun parseSRT(content: String): List<SubtitleEntry> {
        return SubtitleParser().parseSRTFromContent(content)
    }
}

