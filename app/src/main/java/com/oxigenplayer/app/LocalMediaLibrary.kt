// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

enum class LocalMediaKind {
    VIDEO,
    AUDIO,
    SUBTITLE,
}

/**
 * Sidecar subtitle extensions offered by the local-media picker.
 *
 * The previous hard-coded list only covered `.srt`, `.vtt`, `.ass` and `.ssa`, so common sidecars
 * such as `.smi` and `.ttml`/`.dfxp` were never listed and therefore never reached the parser.
 *
 * Every entry must resolve to a real [SubtitleFormat] in [SubtitleParser.detectFormat]. Formats that
 * genuinely cannot be parsed are deliberately absent rather than listed-and-failing: binary VobSub
 * (`.sub`/`.idx` as a pair), MicroDVD frame-based `.sub` and LRC lyrics all need a different parser,
 * so showing them in the picker would only produce an "unsupported format" error after selection.
 */
internal val SUPPORTED_SUBTITLE_EXTENSIONS = listOf(
    ".srt",
    ".vtt",
    ".ass",
    ".ssa",
    ".smi",
    ".ttml",
    ".dfxp",
)

@Immutable
data class LocalMediaAccess(
    val canReadVideo: Boolean,
    val canReadAudio: Boolean,
    val hasPartialVideoAccess: Boolean = false,
) {
    val hasAnyAccess: Boolean
        get() = canReadVideo || canReadAudio

    val hasFullAccess: Boolean
        get() = canReadVideo && canReadAudio && !hasPartialVideoAccess
}

@Immutable
data class LocalMediaVolume(
    val id: String,
    val description: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
)

@Immutable
data class LocalMediaFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val kind: LocalMediaKind,
    val durationMs: Long,
    val sizeBytes: Long,
    val relativePath: String?,
    val modifiedAtSeconds: Long,
    val volume: LocalMediaVolume,
)

@Immutable
data class LocalMediaSnapshot(
    val volumes: List<LocalMediaVolume> = emptyList(),
    val files: List<LocalMediaFile> = emptyList(),
)

object LocalMediaLibrary {
    private const val TAG = "LocalMediaLibrary"
    private const val COLUMN_DURATION = "duration"

    /** Last successful MediaStore snapshot, reused so reopening the media picker skips a full scan. */
    private data class CachedMedia(
        val snapshot: LocalMediaSnapshot,
        val access: LocalMediaAccess,
        val generation: Long,
        val loadedAtMs: Long,
    )
    private val cacheGeneration = AtomicLong()
    @Volatile
    private var loadedSnapshot: CachedMedia? = null
    private val snapshotMutex = Mutex()

    fun requiredPermissions(): Array<String> =
        requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    @Suppress("InlinedApi")
    internal fun requiredPermissionsForSdk(sdkInt: Int): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): LocalMediaAccess {
        fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val fullVideo = granted(Manifest.permission.READ_MEDIA_VIDEO)
            val partialVideo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
                !fullVideo
            LocalMediaAccess(
                canReadVideo = fullVideo || partialVideo,
                canReadAudio = granted(Manifest.permission.READ_MEDIA_AUDIO),
                hasPartialVideoAccess = partialVideo,
            )
        } else {
            val legacyAccess = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            LocalMediaAccess(
                canReadVideo = legacyAccess,
                canReadAudio = legacyAccess,
            )
        }
    }

    /**
     * Forces a fresh MediaStore scan. Manual refresh and permission changes call this path.
     * A mutex prevents a refresh from racing an application-level background prewarm.
     */
    suspend fun query(context: Context, access: LocalMediaAccess): LocalMediaSnapshot =
        withContext(Dispatchers.IO) {
            if (!access.hasAnyAccess) return@withContext LocalMediaSnapshot()
            snapshotMutex.withLock { queryFresh(context.applicationContext, access) }
        }

    /**
     * Returns the cached snapshot or performs exactly one scan shared by all concurrent callers.
     * This is the normal fast-open path used by the Android TV media card and startup prewarm.
     */
    suspend fun ensureSnapshot(context: Context, access: LocalMediaAccess): LocalMediaSnapshot =
        withContext(Dispatchers.IO) {
            if (!access.hasAnyAccess) return@withContext LocalMediaSnapshot()
            cachedSnapshot(access)?.let { return@withContext it }
            snapshotMutex.withLock {
                cachedSnapshot(access) ?: queryFresh(context.applicationContext, access)
            }
        }

    private fun queryFresh(context: Context, access: LocalMediaAccess): LocalMediaSnapshot {
        val generation = cacheGeneration.get()
        val appLang = ((context.applicationContext as? OxigenApp)?.prefs
            ?: PreferencesManager(context.applicationContext)).getAppLanguage()
        val volumes = currentVolumes(context, appLang)
        val results = mutableListOf<LocalMediaFile>()
        // Dedup key set: "$kind:$id". MediaStore _id is unique across the whole provider,
        // so the same file returned by both a per-volume query and the aggregate "external"
        // fallback collapses to a single entry. (The Uri string differs per volume, which is
        // why keying on the Uri would let duplicates through.)
        val seenIds = HashSet<String>(512)
        var successfulQueries = 0
        var firstFailure: RuntimeException? = null

        // On some TCL / MTK Android TV builds the per-volume URIs
        // (external_primary, 1708-3425) return 0 rows for the app UID even though
        // the aggregate "external" URI returns rows (verified via adb content query).
        // Query every known volume AND the aggregate fallback; rows are de-duplicated
        // by their stable MediaStore _id inside queryCollection, so the aggregate
        // fallback only contributes files the per-volume queries missed and never
        // doubles an existing entry.
        // The aggregate volume is appended last so a file present on a real volume keeps
        // that volume's Uri (and chip label) rather than the generic "external" one.
        // Before Android 10 the volume list already is just the aggregate volume; appending it
        // again scanned every collection twice.
        val queryVolumes = if (volumes.any { it.id == MediaStore.VOLUME_EXTERNAL }) {
            volumes
        } else {
            volumes + LocalMediaVolume(
                id = MediaStore.VOLUME_EXTERNAL,
                description = OxigenStrings.get(appLang, "shared_storage"),
                isPrimary = true,
                isRemovable = false,
            )
        }

        queryVolumes.forEach { volume ->
            if (access.canReadVideo) {
                try {
                    val before = results.size
                    queryCollection(context, volume, LocalMediaKind.VIDEO, appLang, results, seenIds)
                    Log.d(TAG, "Volume ${volume.id} video rows=${results.size - before}")
                    successfulQueries++
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to query videos on ${volume.id}", error)
                    if (firstFailure == null) firstFailure = error
                }
            }
            if (access.canReadAudio) {
                try {
                    val before = results.size
                    queryCollection(context, volume, LocalMediaKind.AUDIO, appLang, results, seenIds)
                    Log.d(TAG, "Volume ${volume.id} audio rows=${results.size - before}")
                    successfulQueries++
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to query audio on ${volume.id}", error)
                    if (firstFailure == null) firstFailure = error
                }
            }
            // Android 13+ media permissions do not grant broad access to arbitrary subtitle
            // documents in MediaStore.Files. Those versions use the Storage Access Framework
            // subtitle picker instead of issuing a query that can fail or always return empty.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                (access.canReadVideo || access.canReadAudio)
            ) {
                try {
                    val before = results.size
                    queryCollection(context, volume, LocalMediaKind.SUBTITLE, appLang, results, seenIds)
                    Log.d(TAG, "Volume ${volume.id} subtitle rows=${results.size - before}")
                    successfulQueries++
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to query subtitles on ${volume.id}", error)
                    if (firstFailure == null) firstFailure = error
                }
            }
        }

        if (successfulQueries == 0) {
            firstFailure?.let { throw it }
        }

        return LocalMediaSnapshot(
            volumes = volumes,
            files = results
                .distinctBy { it.uri }
                .sortedWith(LOCAL_MEDIA_COMPARATOR),
        ).also {
            loadedSnapshot = CachedMedia(it, access, generation, SystemClock.elapsedRealtime())
        }
    }

    /** Returns the last successful MediaStore snapshot without scanning the database again. */
    fun cachedSnapshot(access: LocalMediaAccess): LocalMediaSnapshot? {
        if (!access.hasAnyAccess || access.hasPartialVideoAccess) return null
        val cached = loadedSnapshot ?: return null
        if (cached.access != access || cached.generation != cacheGeneration.get()) return null
        if (SystemClock.elapsedRealtime() - cached.loadedAtMs !in 0L..15_000L) return null
        return cached.snapshot
    }

    /** Drops the cached snapshot so the next media-picker reopen performs a fresh scan. */
    fun invalidateMediaCache() {
        cacheGeneration.incrementAndGet()
        loadedSnapshot = null
    }

    private fun currentVolumes(context: Context, appLang: String): List<LocalMediaVolume> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(
                LocalMediaVolume(
                    id = MediaStore.VOLUME_EXTERNAL,
                    description = OxigenStrings.get(appLang, "shared_storage"),
                    isPrimary = true,
                    isRemovable = false,
                )
            )
        }

        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageByMediaStoreName = storageManager.storageVolumes
            .filter(::isReadable)
            .mapNotNull { storageVolume ->
                mediaStoreName(storageVolume)?.let { name ->
                    name.lowercase(Locale.ROOT) to storageVolume
                }
            }
            .toMap()

        return MediaStore.getExternalVolumeNames(context)
            .map { volumeName ->
                val storageVolume = storageByMediaStoreName[volumeName.lowercase(Locale.ROOT)]
                LocalMediaVolume(
                    id = volumeName,
                    description = storageVolume?.getDescription(context)
                        ?.takeIf(String::isNotBlank)
                        ?: if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                            OxigenStrings.get(appLang, "device_storage")
                        } else {
                            OxigenStrings.get(appLang, "external_storage").format(volumeName)
                        },
                    isPrimary = storageVolume?.isPrimary
                        ?: (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    isRemovable = storageVolume?.isRemovable
                        ?: (volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY),
                )
            }
            .sortedWith { left, right ->
                val primaryOrder = (!left.isPrimary).compareTo(!right.isPrimary)
                if (primaryOrder != 0) primaryOrder
                else String.CASE_INSENSITIVE_ORDER.compare(left.description, right.description)
            }
    }

    private fun isReadable(volume: StorageVolume): Boolean =
        volume.state == Environment.MEDIA_MOUNTED ||
            volume.state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun mediaStoreName(volume: StorageVolume): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.mediaStoreVolumeName
        volume.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
        else -> volume.uuid
    }

    private fun queryCollection(
        context: Context,
        volume: LocalMediaVolume,
        kind: LocalMediaKind,
        appLang: String,
        target: MutableList<LocalMediaFile>,
        seenIds: MutableSet<String>,
    ) {
        val collectionUri = collectionUri(volume.id, kind)
        val projection = buildList {
            add(BaseColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (kind != LocalMediaKind.SUBTITLE) add(COLUMN_DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        val selectionArgs = mutableListOf<String>()
        val selection = buildList {
            add("${MediaStore.MediaColumns.SIZE} > 0")
            if (kind == LocalMediaKind.SUBTITLE) {
                // Keep this list in step with SubtitleParser.detectFormat: a sidecar that is not
                // listed here never reaches the parser, so a .smi/.ttml file was invisible in the
                // picker even though the parser detects both by content and by extension.
                // The patterns are bound parameters, never string-concatenated into the SQL, and the
                // OR chain stays parenthesised so it cannot bind past the surrounding AND clauses.
                SUPPORTED_SUBTITLE_EXTENSIONS.forEach { extension ->
                    selectionArgs += "%$extension"
                }
                add(
                    SUPPORTED_SUBTITLE_EXTENSIONS.joinToString(" OR ") {
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                    }.let { "($it)" },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            }
        }.joinToString(" AND ")

        context.contentResolver.query(
            collectionUri,
            projection,
            selection,
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationColumn = cursor.getColumnIndex(COLUMN_DURATION)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val itemUri = ContentUris.withAppendedId(collectionUri, id)
                // MediaStore assigns every row a database-wide unique _id, but the Uri string
                // is prefixed with the queried volume ("external_primary", "1708-3425",
                // "external"). The aggregate "external" fallback therefore returns the very
                // same rows already collected from a per-volume URI under a DIFFERENT string,
                // so deduplicating on itemUri would still list each file twice. Key on the
                // stable (_id, kind) identity instead.
                if (!seenIds.add("$kind:$id")) continue
                target += LocalMediaFile(
                    uri = itemUri,
                    displayName = cursor.stringOrNull(nameColumn)
                        ?.takeIf(String::isNotBlank)
                        ?: itemUri.lastPathSegment
                        ?: "${OxigenStrings.get(appLang, "media_label")} $id",
                    mimeType = cursor.stringOrNull(mimeColumn),
                    kind = kind,
                    durationMs = cursor.longOrZero(durationColumn),
                    sizeBytes = cursor.longOrZero(sizeColumn),
                    relativePath = cursor.stringOrNull(pathColumn)?.trimEnd('/'),
                    modifiedAtSeconds = cursor.longOrZero(modifiedColumn),
                    volume = volume,
                )
            }
        }
    }


    /**
     * Sorting is intentionally allocation-free: the previous comparator lowercased paths and
     * names on every comparison, creating thousands of temporary strings in large TV libraries.
     */
    private val LOCAL_MEDIA_COMPARATOR = Comparator<LocalMediaFile> { left, right ->
        val primaryOrder = (!left.volume.isPrimary).compareTo(!right.volume.isPrimary)
        if (primaryOrder != 0) return@Comparator primaryOrder

        var result = String.CASE_INSENSITIVE_ORDER.compare(
            left.volume.description,
            right.volume.description,
        )
        if (result != 0) return@Comparator result

        result = String.CASE_INSENSITIVE_ORDER.compare(
            left.relativePath.orEmpty(),
            right.relativePath.orEmpty(),
        )
        if (result != 0) return@Comparator result

        String.CASE_INSENSITIVE_ORDER.compare(left.displayName, right.displayName)
    }

    private fun collectionUri(volumeName: String, kind: LocalMediaKind): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return when (kind) {
                LocalMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                LocalMediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                LocalMediaKind.SUBTITLE -> MediaStore.Files.getContentUri("external")
            }
        }
        return when (kind) {
            LocalMediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volumeName)
            LocalMediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(volumeName)
            LocalMediaKind.SUBTITLE -> MediaStore.Files.getContentUri(volumeName)
        }
    }
}

private fun android.database.Cursor.stringOrNull(column: Int): String? =
    if (column < 0 || isNull(column)) null else getString(column)

private fun android.database.Cursor.longOrZero(column: Int): Long =
    if (column < 0 || isNull(column)) 0L else getLong(column)
