// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val packageName: String
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Disabled : UpdateCheckResult
    data object NetworkError : UpdateCheckResult
    data object InvalidMetadata : UpdateCheckResult
}

sealed interface UpdateInstallResult {
    data object InstallerLaunched : UpdateInstallResult
    data object AlreadyInProgress : UpdateInstallResult
    data object AlreadyInstalled : UpdateInstallResult
    data class Failed(val reason: UpdateInstallFailure) : UpdateInstallResult
}

enum class UpdateInstallFailure {
    INVALID_REQUEST,
    LOCAL_STATE,
    STORAGE,
    NETWORK,
    TIMED_OUT,
    VERIFICATION,
    INSTALL_PERMISSION_REQUIRED,
    INSTALLER_UNAVAILABLE,
    DISABLED
}

private class ResponseValidationException(message: String) : IOException(message)

private val UPDATE_DEADLINE_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "Oxigen-update-deadline").apply { isDaemon = true }
}.apply {
    // Cancelled deadlines should not retain an UpdateManager operation until their original delay.
    removeOnCancelPolicy = true
}

/**
 * Owns the active blocking URL connection for one update operation.
 *
 * [cancel] is safe to call from the coroutine-cancellation watcher while an IO thread is blocked in
 * `responseCode` or `InputStream.read`; disconnecting the connection wakes that thread promptly.
 */
internal class UpdateConnectionControl(timeoutMillis: Long? = null) : AutoCloseable {
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val cancelled = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val scheduledDisconnect: ScheduledFuture<*>? = timeoutMillis?.let { timeout ->
        UPDATE_DEADLINE_EXECUTOR.schedule({
            expired.set(true)
            activeConnection.getAndSet(null)?.disconnect()
        }, timeout, TimeUnit.MILLISECONDS)
    }

    val hasExpired: Boolean
        get() = expired.get()

    fun track(connection: HttpURLConnection) {
        throwIfStopped()
        activeConnection.set(connection)
        if ((cancelled.get() || expired.get()) &&
            activeConnection.compareAndSet(connection, null)
        ) {
            connection.disconnect()
            throwIfStopped()
        }
    }

    fun untrack(connection: HttpURLConnection) {
        activeConnection.compareAndSet(connection, null)
    }

    fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    fun finishDeadline() {
        scheduledDisconnect?.cancel(false)
    }

    fun throwIfStopped() {
        if (cancelled.get()) throw CancellationException("Update operation was cancelled")
        if (expired.get()) throw SocketTimeoutException("Update download deadline exceeded")
    }

    override fun close() {
        finishDeadline()
        activeConnection.getAndSet(null)?.disconnect()
    }
}

/** Installs a cancellation watcher before any blocking network work starts. */
internal suspend fun <T> withUpdateConnectionControl(
    timeoutMillis: Long? = null,
    block: suspend (UpdateConnectionControl) -> T,
): T = coroutineScope {
    val connectionControl = UpdateConnectionControl(timeoutMillis)
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            connectionControl.cancel()
        }
    }

    try {
        block(connectionControl)
    } finally {
        connectionControl.close()
        cancellationWatcher.cancel()
    }
}

class UpdateManager(private val context: Context) {
    suspend fun checkForUpdate(): UpdateCheckResult {
        if (!BuildConfig.DIRECT_UPDATES_ENABLED) return UpdateCheckResult.Disabled
        return withUpdateConnectionControl { connectionControl ->
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    connection = openValidatedConnection(
                        initialUrl = UPDATE_JSON_URL,
                        validator = { url, _ -> UpdateSecurityPolicy.isAllowedMetadataUrl(url) },
                        accept = "application/json",
                        connectionControl = connectionControl,
                    )

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "Update metadata request failed")
                        return@withContext UpdateCheckResult.NetworkError
                    }

                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > UpdateSecurityPolicy.MAX_METADATA_BYTES) {
                        Log.w(TAG, "Update metadata exceeded the size limit")
                        return@withContext UpdateCheckResult.InvalidMetadata
                    }

                    val response = connection.inputStream.use { input ->
                        readUtf8WithLimit(
                            input,
                            UpdateSecurityPolicy.MAX_METADATA_BYTES,
                            connectionControl,
                        )
                    }
                    val json = JSONObject(response)
                    if (!json.optBoolean("enabled", true)) return@withContext UpdateCheckResult.Disabled

                    val remoteVersionCodeLong = json.optLong("versionCode", -1L)
                    if (remoteVersionCodeLong !in 1..Int.MAX_VALUE.toLong()) {
                        return@withContext UpdateCheckResult.InvalidMetadata
                    }

                    val remoteVersionName = json.optString("versionName", "").trim()
                    if (!VERSION_NAME_PATTERN.matches(remoteVersionName)) {
                        return@withContext UpdateCheckResult.InvalidMetadata
                    }

                    val updateInfo = UpdateInfo(
                        versionCode = remoteVersionCodeLong.toInt(),
                        versionName = remoteVersionName,
                        downloadUrl = json.optString("downloadUrl", ""),
                        sha256 = json.optString("sha256", "").lowercase(Locale.US),
                        sizeBytes = json.optLong("sizeBytes", -1L),
                        packageName = json.optString("packageName", "")
                    )

                    if (!UpdateSecurityPolicy.isValidMetadata(
                            versionCode = updateInfo.versionCode,
                            downloadUrl = updateInfo.downloadUrl,
                            sha256 = updateInfo.sha256,
                            sizeBytes = updateInfo.sizeBytes,
                            packageName = updateInfo.packageName,
                            expectedPackageName = context.packageName
                        )
                    ) {
                        Log.w(TAG, "Update metadata failed validation")
                        return@withContext UpdateCheckResult.InvalidMetadata
                    }

                    val currentPackage = getInstalledPackageInfo()
                        ?: return@withContext UpdateCheckResult.InvalidMetadata
                    if (updateInfo.versionCode.toLong() > versionCodeOf(currentPackage)) {
                        UpdateCheckResult.Available(updateInfo)
                    } else {
                        UpdateCheckResult.UpToDate
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: ResponseValidationException) {
                    Log.w(TAG, "Update metadata failed validation", exception)
                    UpdateCheckResult.InvalidMetadata
                } catch (exception: IOException) {
                    connectionControl.throwIfStopped()
                    Log.e(TAG, "Update check failed", exception)
                    UpdateCheckResult.NetworkError
                } catch (exception: Exception) {
                    connectionControl.throwIfStopped()
                    Log.e(TAG, "Update metadata was invalid", exception)
                    UpdateCheckResult.InvalidMetadata
                } finally {
                    connection?.let(connectionControl::untrack)
                    connection?.disconnect()
                }
            }
        }
    }

    suspend fun downloadAndInstall(updateInfo: UpdateInfo): UpdateInstallResult {
        if (!BuildConfig.DIRECT_UPDATES_ENABLED) {
            return UpdateInstallResult.Failed(UpdateInstallFailure.DISABLED)
        }
        if (!UpdateSecurityPolicy.isValidMetadata(
                versionCode = updateInfo.versionCode,
                downloadUrl = updateInfo.downloadUrl,
                sha256 = updateInfo.sha256,
                sizeBytes = updateInfo.sizeBytes,
                packageName = updateInfo.packageName,
                expectedPackageName = context.packageName,
            )
        ) {
            Log.w(TAG, "Rejected invalid update request")
            return UpdateInstallResult.Failed(UpdateInstallFailure.INVALID_REQUEST)
        }
        // Per-app install permission exists from API 26. On Android 7 "unknown sources" is a
        // global device setting that the system installer checks itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = withContext(Dispatchers.Main) {
                runCatching { context.startActivity(permissionIntent) }.isSuccess
            }
            return UpdateInstallResult.Failed(
                if (launched) UpdateInstallFailure.INSTALL_PERMISSION_REQUIRED
                else UpdateInstallFailure.INSTALLER_UNAVAILABLE,
            )
        }
        if (!DOWNLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            Log.w(TAG, "An update download is already in progress")
            return UpdateInstallResult.AlreadyInProgress
        }

        return try {
            withUpdateConnectionControl(DOWNLOAD_TOTAL_TIMEOUT_MILLIS) { connectionControl ->
                withContext(Dispatchers.IO) {
                val installedPackage = getInstalledPackageInfo()
                    ?: return@withContext UpdateInstallResult.Failed(
                        UpdateInstallFailure.LOCAL_STATE
                    )
                if (updateInfo.versionCode.toLong() <= versionCodeOf(installedPackage)) {
                    return@withContext UpdateInstallResult.AlreadyInstalled
                }

                val updateDirectory = File(context.cacheDir, "updates")
                if ((!updateDirectory.exists() && !updateDirectory.mkdirs()) ||
                    !updateDirectory.isDirectory
                ) {
                    Log.e(TAG, "Unable to create the private update directory")
                    return@withContext UpdateInstallResult.Failed(UpdateInstallFailure.STORAGE)
                }

                val cleanupTime = System.currentTimeMillis()
                updateDirectory.listFiles()?.forEach { cachedFile ->
                    if (cachedFile.isFile && UpdateCachePolicy.shouldDelete(
                            fileName = cachedFile.name,
                            lastModifiedMillis = cachedFile.lastModified(),
                            nowMillis = cleanupTime,
                        )
                    ) {
                        cachedFile.delete()
                    }
                }
                // Each handoff gets its own path. A later retry can therefore retain the URI that an
                // OEM installer may still open asynchronously while downloading a fresh artifact.
                val artifactId = "${updateInfo.versionCode}_${System.currentTimeMillis()}"
                val partialFile = File(updateDirectory, "update_$artifactId.apk.part")
                val apkFile = File(updateDirectory, "update_$artifactId.apk")

                var connection: HttpURLConnection? = null
                var keepApkForInstaller = false
                try {
                    connection = openValidatedConnection(
                        initialUrl = updateInfo.downloadUrl,
                        validator = { url, isRedirect ->
                            if (isRedirect) {
                                UpdateSecurityPolicy.isAllowedDownloadRedirect(url)
                            } else {
                                UpdateSecurityPolicy.isAllowedDownloadUrl(url)
                            }
                        },
                        accept = "application/vnd.android.package-archive",
                        connectionControl = connectionControl,
                    )

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "Update download request failed")
                        return@withContext UpdateInstallResult.Failed(UpdateInstallFailure.NETWORK)
                    }

                    val contentLength = connection.contentLengthLong
                    if (contentLength > UpdateSecurityPolicy.MAX_APK_SIZE_BYTES ||
                        (contentLength >= 0 && contentLength != updateInfo.sizeBytes)
                    ) {
                        Log.w(TAG, "Update download size did not match metadata")
                        return@withContext UpdateInstallResult.Failed(
                            UpdateInstallFailure.VERIFICATION
                        )
                    }

                    val activeConnection = connection
                    val copiedBytes = partialFile.outputStream().buffered().use { output ->
                        activeConnection.inputStream.buffered().use { input ->
                            copyWithExactLimit(
                                input,
                                output,
                                updateInfo.sizeBytes,
                                connectionControl,
                            )
                        }
                    }
                    connectionControl.untrack(activeConnection)
                    connection.disconnect()
                    connection = null
                    connectionControl.finishDeadline()
                    if (copiedBytes != updateInfo.sizeBytes ||
                        !calculateSha256(partialFile, connectionControl)
                            .equals(updateInfo.sha256, ignoreCase = true) ||
                        !looksLikeZipArchive(partialFile)
                    ) {
                        Log.w(TAG, "Downloaded update failed integrity validation")
                        return@withContext UpdateInstallResult.Failed(
                            UpdateInstallFailure.VERIFICATION
                        )
                    }

                    connectionControl.throwIfStopped()
                    if (!isTrustedUpdateApk(partialFile, updateInfo, installedPackage)) {
                        Log.w(TAG, "Downloaded APK identity or signature was rejected")
                        return@withContext UpdateInstallResult.Failed(
                            UpdateInstallFailure.VERIFICATION
                        )
                    }
                    connectionControl.throwIfStopped()

                    if (apkFile.exists() && !apkFile.delete()) {
                        Log.e(TAG, "Unable to replace an old cached update")
                        return@withContext UpdateInstallResult.Failed(UpdateInstallFailure.STORAGE)
                    }
                    if (!partialFile.renameTo(apkFile)) {
                        Log.e(TAG, "Unable to finalize the downloaded update")
                        return@withContext UpdateInstallResult.Failed(UpdateInstallFailure.STORAGE)
                    }

                    connectionControl.throwIfStopped()
                    apkFile.setLastModified(System.currentTimeMillis())
                    apkFile.setWritable(false, false)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        apkFile
                    )
                    val baseInstallIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("OxigenPlayer update", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val installIntent = explicitSystemInstallerIntent(baseInstallIntent)
                        ?: return@withContext UpdateInstallResult.Failed(
                            UpdateInstallFailure.INSTALLER_UNAVAILABLE
                        )

                    withContext(Dispatchers.Main) {
                        context.startActivity(installIntent)
                        // Set this in the same non-suspending block as the handoff. If the parent is
                        // cancelled while returning to IO, the installer's URI must remain valid.
                        keepApkForInstaller = true
                    }
                    UpdateInstallResult.InstallerLaunched
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: SocketTimeoutException) {
                    Log.e(TAG, "Update download timed out", exception)
                    UpdateInstallResult.Failed(UpdateInstallFailure.TIMED_OUT)
                } catch (exception: ResponseValidationException) {
                    Log.e(TAG, "Downloaded update exceeded its declared bounds", exception)
                    UpdateInstallResult.Failed(UpdateInstallFailure.VERIFICATION)
                } catch (exception: SecurityException) {
                    Log.e(TAG, "Update redirect or installer permission was rejected", exception)
                    UpdateInstallResult.Failed(UpdateInstallFailure.VERIFICATION)
                } catch (exception: IOException) {
                    connectionControl.throwIfStopped()
                    Log.e(TAG, "Update download failed", exception)
                    val reason = if (connectionControl.hasExpired) {
                        UpdateInstallFailure.TIMED_OUT
                    } else {
                        UpdateInstallFailure.NETWORK
                    }
                    UpdateInstallResult.Failed(reason)
                } catch (exception: Exception) {
                    connectionControl.throwIfStopped()
                    Log.e(TAG, "Download or install failed", exception)
                    UpdateInstallResult.Failed(UpdateInstallFailure.INSTALLER_UNAVAILABLE)
                } finally {
                    connection?.let(connectionControl::untrack)
                    connection?.disconnect()
                    partialFile.delete()
                    if (!keepApkForInstaller) apkFile.delete()
                }
            }
            }
        } finally {
            DOWNLOAD_IN_PROGRESS.set(false)
        }
    }

    /** Selects a trusted system package installer and makes the launch explicit. */
    private fun explicitSystemInstallerIntent(baseIntent: Intent): Intent? {
        @Suppress("DEPRECATION")
        val candidates = context.packageManager.queryIntentActivities(
            baseIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val installer = candidates
            .filter(::isTrustedSystemActivity)
            .sortedWith(
                compareByDescending<ResolveInfo> {
                    it.activityInfo.packageName.contains("packageinstaller", ignoreCase = true)
                }.thenBy { it.activityInfo.packageName }
                    .thenBy { it.activityInfo.name }
            )
            .firstOrNull()
            ?: return null

        return Intent(baseIntent).setComponent(
            ComponentName(installer.activityInfo.packageName, installer.activityInfo.name)
        )
    }

    private fun isTrustedSystemActivity(resolveInfo: ResolveInfo): Boolean {
        val activity = resolveInfo.activityInfo
        val flags = activity.applicationInfo.flags
        val isSystem = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return isSystem && activity.exported
    }

    private fun getInstalledPackageInfo(): PackageInfo? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, packageInfoFlags())
    } catch (exception: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Installed package information is unavailable", exception)
        null
    }

    private fun isTrustedUpdateApk(
        apkFile: File,
        updateInfo: UpdateInfo,
        installedPackage: PackageInfo
    ): Boolean {
        @Suppress("DEPRECATION")
        val archivePackage = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            packageInfoFlags()
        ) ?: return false

        if (archivePackage.packageName != context.packageName ||
            archivePackage.packageName != updateInfo.packageName ||
            versionCodeOf(archivePackage) != updateInfo.versionCode.toLong()
        ) {
            return false
        }

        val installedCurrentSigners = signerDigests(installedPackage, includeHistory = false)
        val updateSignerHistory = signerDigests(archivePackage, includeHistory = true)
        return installedCurrentSigners.isNotEmpty() &&
            installedCurrentSigners.all(updateSignerHistory::contains)
    }

    private fun signerDigests(packageInfo: PackageInfo, includeHistory: Boolean): Set<String> {
        @Suppress("DEPRECATION")
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (includeHistory && !signingInfo.hasMultipleSigners()) {
                signingInfo.signingCertificateHistory ?: emptyArray()
            } else {
                signingInfo.apkContentsSigners ?: emptyArray()
            }
        } else {
            packageInfo.signatures ?: emptyArray()
        }

        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHexString()
        }
    }

    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

    private fun openValidatedConnection(
        initialUrl: String,
        validator: (url: String, isRedirect: Boolean) -> Boolean,
        accept: String,
        connectionControl: UpdateConnectionControl,
    ): HttpURLConnection {
        var currentUrl = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            connectionControl.throwIfStopped()
            if (!validator(currentUrl.toExternalForm(), redirectCount > 0)) {
                throw SecurityException("Rejected update URL")
            }

            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "identity")
            }
            connectionControl.track(connection)
            val responseCode = connection.responseCode
            if (responseCode !in REDIRECT_CODES) return connection

            val location = connection.getHeaderField("Location")
            connectionControl.untrack(connection)
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Redirect without a location")
            currentUrl = URL(currentUrl, location)
        }
        throw IOException("Too many update redirects")
    }

    private fun readUtf8WithLimit(
        input: InputStream,
        maxBytes: Int,
        connectionControl: UpdateConnectionControl,
    ): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(4096)
        var total = 0
        var consecutiveZeroReads = 0
        while (true) {
            connectionControl.throwIfStopped()
            val count = input.read(buffer)
            connectionControl.throwIfStopped()
            if (count == -1) break
            if (count == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= 8) throw IOException("Update response made no progress")
                continue
            }
            consecutiveZeroReads = 0
            total += count
            if (total > maxBytes) {
                throw ResponseValidationException("Response exceeded its size limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun copyWithExactLimit(
        input: InputStream,
        output: java.io.OutputStream,
        expectedBytes: Long,
        connectionControl: UpdateConnectionControl,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var consecutiveZeroReads = 0
        while (true) {
            connectionControl.throwIfStopped()
            val count = input.read(buffer)
            connectionControl.throwIfStopped()
            if (count == -1) break
            if (count == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= 8) throw IOException("Update download made no progress")
                continue
            }
            consecutiveZeroReads = 0
            total += count
            if (total > expectedBytes || total > UpdateSecurityPolicy.MAX_APK_SIZE_BYTES) {
                throw ResponseValidationException("Download exceeded its declared size")
            }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun calculateSha256(
        file: File,
        connectionControl: UpdateConnectionControl,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                connectionControl.throwIfStopped()
                val count = input.read(buffer)
                connectionControl.throwIfStopped()
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun looksLikeZipArchive(file: File): Boolean = file.inputStream().use { input ->
        val signature = ByteArray(4)
        input.read(signature) == signature.size && hasApkZipLocalFileHeader(signature)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "UpdateManager"
        const val UPDATE_JSON_URL =
            "https://raw.githubusercontent.com/flavyu22/OxigenPlayer/main/update.json"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val DOWNLOAD_TOTAL_TIMEOUT_MILLIS = 10L * 60L * 1_000L
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val VERSION_NAME_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$")
        val DOWNLOAD_IN_PROGRESS = AtomicBoolean(false)
    }
}
