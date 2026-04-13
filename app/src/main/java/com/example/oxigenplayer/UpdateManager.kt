package com.example.oxigenplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val downloadUrl: String)

class UpdateManager(private val context: Context) {
    private val TAG = "UpdateManager"
    
    private val UPDATE_JSON_URL = "https://raw.githubusercontent.com/flavyu22/OxigenPlayer/main/update.json"

    suspend fun checkForUpdate(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(UPDATE_JSON_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val remoteVersionCode = json.getInt("versionCode")
                    val downloadUrl = json.getString("downloadUrl")

                    val currentVersionCode = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                        }
                    } catch (e: Exception) { 0 }

                    if (remoteVersionCode > currentVersionCode) {
                        UpdateInfo(remoteVersionCode, downloadUrl)
                    } else null
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                null
            }
        }
    }

    suspend fun downloadAndInstall(updateInfo: UpdateInfo) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(updateInfo.downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()

                val file = File(context.cacheDir, "update.apk")
                file.outputStream().use { outputStream ->
                    conn.inputStream.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    context, 
                    "${context.packageName}.fileprovider", 
                    file
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Download or install failed", e)
            }
        }
    }
}
