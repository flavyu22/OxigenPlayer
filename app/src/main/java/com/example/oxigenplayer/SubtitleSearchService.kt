package com.example.oxigenplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SubtitleSearchResult(
    val id: String,
    val fileName: String,
    val language: String,
    val downloadUrl: String,
    val movieName: String,
    val year: String = "",
    val source: String = "OpenSubtitles"
)

data class SearchResult(val subtitles: List<SubtitleSearchResult>, val errorMessage: String?)

class SubtitleSearchService {
    private val TAG = "SubtitleSearchService"
    private val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private val API_KEY = "2MlkBZOsN3KFUybGo5gea58LvsxzvrkI"
    private val USER_AGENT = "OxigenPlayer v1.0"

    suspend fun login(user: String, pass: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Api-Key", API_KEY)
                    setRequestProperty("User-Agent", USER_AGENT)
                    doOutput = true
                }

                val jsonInput = JSONObject().apply {
                    put("username", user)
                    put("password", pass)
                }

                conn.outputStream.use { it.write(jsonInput.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(response).optString("token", null)
                } else {
                    Log.e(TAG, "Login failed: ${conn.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login exception", e)
                null
            }
        }
    }

    suspend fun searchSubtitles(query: String, lang: String = "ro,en", token: String? = null): SearchResult {
        return withContext(Dispatchers.IO) {
            try {
                val cleanQuery = cleanQuery(query)
                if (cleanQuery.isEmpty()) return@withContext SearchResult(emptyList(), "Nume invalid.")

                val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                val url = URL("$BASE_URL/subtitles?query=$encodedQuery&languages=$lang")
                
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Api-Key", API_KEY)
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = JSONObject(response)
                    val dataArray = jsonObj.optJSONArray("data") ?: org.json.JSONArray()
                    val list = mutableListOf<SubtitleSearchResult>()

                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val attributes = item.getJSONObject("attributes")
                        val files = attributes.getJSONArray("files")
                        if (files.length() == 0) continue
                        
                        val file = files.getJSONObject(0)
                        val fileId = file.optString("file_id")
                        val fileName = file.optString("file_name")
                        val subLang = attributes.optString("language", "und")
                        val movieName = attributes.getJSONObject("feature_details").optString("title", fileName)
                        val year = attributes.getJSONObject("feature_details").optString("year", "")

                        list.add(SubtitleSearchResult(
                            id = fileId,
                            fileName = fileName,
                            language = subLang.uppercase(),
                            downloadUrl = fileId,
                            movieName = movieName,
                            year = year,
                            source = "OpenSubtitles"
                        ))
                    }
                    SearchResult(list, null)
                } else {
                    SearchResult(emptyList(), "Eroare OpenSubtitles (Cod: ${conn.responseCode}).")
                }
            } catch (e: Exception) {
                SearchResult(emptyList(), "Eroare de conexiune la OpenSubtitles.")
            }
        }
    }

    private fun cleanQuery(query: String): String {
        return query
            .replace(Regex("\\b(1080p|720p|480p|2160p|4k|bluray|web-dl|x264|x265|hevc|hdrip|h264|h265)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    suspend fun downloadSubtitle(
        context: Context,
        fileId: String, 
        movieName: String,
        videoUri: Uri?,
        token: String? = null
    ): List<SubtitleEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/download")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Api-Key", API_KEY)
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    doOutput = true
                }

                val jsonInput = JSONObject().apply {
                    put("file_id", fileId.toInt())
                }

                conn.outputStream.use { it.write(jsonInput.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val downloadLink = JSONObject(response).getString("link")
                    
                    val fileConn = URL(downloadLink).openConnection() as HttpURLConnection
                    fileConn.apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    
                    if (fileConn.responseCode == 200) {
                        val content = fileConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        
                        // Salvare fișier pe disc
                        saveSubtitleToFile(context, content, movieName, videoUri)
                        
                        return@withContext SubtitleParser().parseSRTFromContent(content)
                    }
                }
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                emptyList()
            }
        }
    }

    private fun saveSubtitleToFile(context: Context, content: String, movieName: String, videoUri: Uri?) {
        try {
            val fileName = "${movieName.replace(" ", "_")}_Oxigen.srt"
            
            // Încercăm să salvăm în același folder cu filmul dacă e fișier local
            if (videoUri?.scheme == "file") {
                val videoFile = File(videoUri.path!!)
                val subFile = File(videoFile.parent, fileName)
                subFile.writeText(content)
                Log.d(TAG, "Saved to video folder: ${subFile.absolutePath}")
                return
            }

            // Altfel, salvăm în folderul Downloads/OxigenSubtitles
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val oxigenDir = File(downloadsDir, "OxigenSubtitles")
            if (!oxigenDir.exists()) oxigenDir.mkdirs()
            
            val subFile = File(oxigenDir, fileName)
            subFile.writeText(content)
            Log.d(TAG, "Saved to Downloads: ${subFile.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file to disk", e)
        }
    }
}
