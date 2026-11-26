// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SubtitleConfig(
    val isEnabled: Boolean = true,
    val targetLang: String = "ro",
    val syncOffset: Long = 0L,
    val useExternal: Boolean = true
)

internal fun shouldProcessEmbeddedCue(config: SubtitleConfig, externalSubtitleCount: Int): Boolean =
    !config.useExternal || externalSubtitleCount == 0

/** Full text is deliberately part of the key: String.hashCode() alone is collision-prone. */
internal data class SubtitleTranslationKey(val targetLanguage: String, val text: String)

/** Identifies work for one exact subtitle document and language. */
internal data class SubtitleWorkKey(
    val generation: Long,
    val presentationEpoch: Long,
    val index: Int,
    val targetLanguage: String,
    val text: String
)

private data class PrefetchTask(
    val key: SubtitleWorkKey,
    val deferred: CompletableDeferred<String>
)

/**
 * Subtitle timing, translation and bounded prefetch coordinator.
 *
 * Every asynchronous result carries a document generation and cue request id. This makes file
 * changes, language changes and seeks cancellation-safe: stale work may finish, but can never
 * overwrite the currently visible cue or populate the current document cache.
 */
class SubtitleEngine(
    private val translationManager: TranslationManager,
    private val scope: CoroutineScope,
    private val lowRamMode: Boolean = false,
) {
    private val memoryCache = LruCache<SubtitleTranslationKey, String>(
        if (lowRamMode) LOW_RAM_CACHE_ENTRIES else DEFAULT_CACHE_ENTRIES
    )
    private val exportTranslations = ConcurrentHashMap<Int, String>()
    private val upcomingTranslations = ConcurrentHashMap<SubtitleWorkKey, Deferred<String>>()
    private val activePrefetchJobs = ConcurrentHashMap<SubtitleWorkKey, Job>()

    // Bounded queues prevent an entire large subtitle file from becoming retained work.
    private val prefetchQueue = Channel<PrefetchTask>(
        capacity = if (lowRamMode) LOW_RAM_PREFETCH_QUEUE else DEFAULT_PREFETCH_QUEUE
    )
    private val prefetchSignal = Channel<Long>(Channel.CONFLATED)
    private val translationSemaphore = Semaphore(if (lowRamMode) 2 else 4)
    private val isPrefetching = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val cueRequest = AtomicLong(0L)
    private val presentationEpoch = AtomicLong(0L)
    private val translateAllRun = AtomicLong(0L)
    private val released = AtomicBoolean(false)
    private val presentationActive = AtomicBoolean(true)
    private val lastPositionMs = AtomicLong(-1L)

    private val _currentSubtitle = MutableStateFlow<SubtitleEntry?>(null)
    val currentSubtitle = _currentSubtitle.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText = _translatedText.asStateFlow()

    private val _config = MutableStateFlow(SubtitleConfig())
    val config = _config.asStateFlow()

    private val _isTranslatingAll = MutableStateFlow(false)
    val isTranslatingAll = _isTranslatingAll.asStateFlow()

    private val _translatedCount = MutableStateFlow(0)
    val translatedCount = _translatedCount.asStateFlow()

    @Volatile
    private var allSubtitles = emptyList<SubtitleEntry>()

    @Volatile
    private var subtitlePrefixMaxEnd = LongArray(0)
    private var prefetchJob: Job? = null
    private var translateAllJob: Job? = null
    private var cueTranslationJob: Job? = null
    private var lastFoundIndex = -1

    @Volatile
    private var lastTrackText = ""

    init {
        translationManager.setTargetLanguage(_config.value.targetLang)
        startPrefetchWorker()
    }

    fun getAllSubtitles(): List<SubtitleEntry> = allSubtitles

    fun translateAll() {
        if (released.get()) return
        val subtitles = allSubtitles
        if (subtitles.isEmpty()) return

        val runId = translateAllRun.incrementAndGet()
        val documentGeneration = generation.get()
        val targetLanguage = _config.value.targetLang
        translateAllJob?.cancel()
        _isTranslatingAll.value = true
        _translatedCount.value = 0
        exportTranslations.clear()

        translateAllJob = scope.launch(Dispatchers.Default) {
            try {
                subtitles.chunked(12).forEach { chunk ->
                    if (!isCurrentGeneration(documentGeneration, targetLanguage)) return@launch
                    chunk.map { entry ->
                        async {
                            val translated = translateAndCache(
                                text = entry.text,
                                targetLanguage = targetLanguage,
                                documentGeneration = documentGeneration
                            )
                            if (isCurrentGeneration(documentGeneration, targetLanguage)) {
                                exportTranslations[entry.index] = translated
                            }
                        }
                    }.awaitAll()
                    // Progress is published once per chunk, not once per cue: every emission
                    // recomposes the player screen, and cached/ML Kit cues finish in microseconds.
                    if (isCurrentGeneration(documentGeneration, targetLanguage)) {
                        _translatedCount.value = exportTranslations.size
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (translateAllRun.get() == runId) {
                    _isTranslatingAll.value = false
                }
            }
        }
    }

    fun getTranslatedSrtContent(): String = buildString(allSubtitles.size * 100) {
        val targetLanguage = _config.value.targetLang
        allSubtitles.forEachIndexed { outputIndex, entry ->
            append(outputIndex + 1).append('\n')
            append(formatSrtTime(entry.startTime))
                .append(" --> ")
                .append(formatSrtTime(entry.endTime))
                .append('\n')
            append(
                exportTranslations[entry.index]
                    ?: memoryCache[SubtitleTranslationKey(targetLanguage, entry.text)]
                    ?: entry.text
            ).append("\n\n")
        }
    }

    private fun formatSrtTime(milliseconds: Long): String {
        // A negative timestamp would render as "00:00:00,-500" and break every SRT consumer.
        val ms = milliseconds.coerceAtLeast(0L)
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        val millis = ms % 1000
        return "${hours.pad(2)}:${minutes.pad(2)}:${seconds.pad(2)},${millis.pad(3)}"
    }

    private fun Long.pad(size: Int): String = toString().padStart(size, '0')

    private fun startPrefetchWorker() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            launch {
                for (position in prefetchSignal) executePrefetchStrategy(position)
            }

            repeat(3) {
                launch {
                    for (task in prefetchQueue) {
                        var execution: Deferred<String>? = null
                        try {
                            val activeExecution = async {
                                try {
                                    if (isWorkCurrent(task.key)) {
                                        translateAndCache(
                                            task.key.text,
                                            task.key.targetLanguage,
                                            task.key.generation
                                        )
                                    } else {
                                        task.key.text
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    task.key.text
                                }
                            }
                            execution = activeExecution
                            activePrefetchJobs[task.key] = activeExecution
                            if (!isWorkCurrent(task.key)) activeExecution.cancel()
                            val result = activeExecution.await()
                            task.deferred.complete(result)
                        } catch (cancelled: CancellationException) {
                            task.deferred.cancel(cancelled)
                        } catch (_: Exception) {
                            task.deferred.complete(task.key.text)
                        } finally {
                            execution?.let { activePrefetchJobs.remove(task.key, it) }
                            upcomingTranslations.remove(task.key, task.deferred)
                        }
                    }
                }
            }
        }
    }

    private fun executePrefetchStrategy(positionMs: Long) {
        if (!presentationActive.get()) return
        if (!isPrefetching.compareAndSet(false, true)) return
        try {
            val documentGeneration = generation.get()
            val subtitles = allSubtitles
            val conf = _config.value
            if (!conf.isEnabled || !conf.useExternal || subtitles.isEmpty()) return

            val adjustedPosition = positionMs - conf.syncOffset
            val currentIndex = findCurrentIndex(subtitles, adjustedPosition)
            val startIndex = (currentIndex + 1).coerceAtLeast(0)
            if (startIndex >= subtitles.size) return

            val prefetchWindow = if (lowRamMode) LOW_RAM_PREFETCH_WINDOW else PREFETCH_WINDOW
            val endIndex = (startIndex + prefetchWindow).coerceAtMost(subtitles.size)
            subtitles.subList(startIndex, endIndex).forEach { entry ->
                val cacheKey = SubtitleTranslationKey(conf.targetLang, entry.text)
                val workKey = SubtitleWorkKey(
                    documentGeneration,
                    presentationEpoch.get(),
                    entry.index,
                    conf.targetLang,
                    entry.text
                )
                if (memoryCache[cacheKey] == null && !upcomingTranslations.containsKey(workKey)) {
                    enqueuePrefetch(workKey)
                }
            }

            if (startIndex > 50) cleanupPending(documentGeneration, startIndex - 20)
        } finally {
            isPrefetching.set(false)
        }
    }

    /** A deferred is published only while its queue item is guaranteed to exist. */
    private fun enqueuePrefetch(key: SubtitleWorkKey) {
        if (!presentationActive.get()) return
        val deferred = CompletableDeferred<String>()
        if (upcomingTranslations.putIfAbsent(key, deferred) != null) return

        val result = prefetchQueue.trySend(PrefetchTask(key, deferred))
        if (result.isFailure && upcomingTranslations.remove(key, deferred)) {
            // Never leave a caller waiting for work that was not accepted by the bounded queue.
            deferred.complete(key.text)
        }
    }

    fun onPositionChanged(positionMs: Long) {
        if (released.get()) return
        lastPositionMs.set(positionMs)
        if (!presentationActive.get()) return
        val conf = _config.value
        val subtitles = allSubtitles
        if (!conf.useExternal || subtitles.isEmpty()) return

        val entry = findSubtitleOptimized(subtitles, positionMs - conf.syncOffset)
        if (entry != _currentSubtitle.value) {
            cueRequest.incrementAndGet()
            cueTranslationJob?.cancel()
            cueTranslationJob = null
            _currentSubtitle.value = entry
            if (entry == null) {
                _translatedText.value = ""
            } else if (conf.isEnabled && conf.targetLang.isNotBlank()) {
                processTranslation(entry)
            } else {
                _translatedText.value = entry.text
            }
        }

        if (conf.isEnabled) prefetchSignal.trySend(positionMs)
    }

    private fun cleanupPending(documentGeneration: Long, thresholdIndex: Int) {
        upcomingTranslations.entries.forEach { (key, deferred) ->
            if (key.generation != documentGeneration || key.index < thresholdIndex) {
                if (upcomingTranslations.remove(key, deferred)) deferred.cancel()
            }
        }
    }

    private fun findSubtitleOptimized(
        subtitles: List<SubtitleEntry>,
        positionMs: Long
    ): SubtitleEntry? {
        val count = subtitles.size
        if (count == 0) return null

        val lastIndex = lastFoundIndex
        if (lastIndex in 0 until count) {
            val previous = subtitles[lastIndex]
            if (positionMs in previous.startTime..previous.endTime) return previous

            for (distance in 1..3) {
                val index = lastIndex + distance
                if (index !in 0 until count) break
                val candidate = subtitles[index]
                if (positionMs in candidate.startTime..candidate.endTime) {
                    lastFoundIndex = index
                    return candidate
                }
                if (positionMs < candidate.startTime) break
            }
        }

        val entry = findSubtitleAt(subtitles, positionMs)
        lastFoundIndex = entry?.index ?: findCurrentIndex(subtitles, positionMs)
        return entry
    }

    private fun findSubtitleAt(
        subtitles: List<SubtitleEntry>,
        positionMs: Long
    ): SubtitleEntry? = subtitles.getOrNull(
        findActiveSubtitleIndex(subtitles, subtitlePrefixMaxEnd, positionMs)
    )

    private fun findCurrentIndex(subtitles: List<SubtitleEntry>, positionMs: Long): Int =
        findLatestStartedSubtitleIndex(subtitles, positionMs)

    /**
     * Enables or suspends on-screen subtitle work without affecting an explicitly requested
     * [translateAll] export. While presentation is hidden we still remember the latest playback
     * position/embedded cue, so showing subtitles resumes from current content rather than stale UI.
     */
    fun setPresentationActive(active: Boolean) {
        if (released.get()) return
        val changed = presentationActive.getAndSet(active) != active
        if (!changed) {
            if (!active) _translatedText.value = ""
            return
        }
        cueRequest.incrementAndGet()

        if (!active) {
            presentationEpoch.incrementAndGet()
            cueTranslationJob?.cancel()
            cueTranslationJob = null
            cancelUpcomingPrefetch()
            _translatedText.value = ""
            return
        }

        refreshCurrentPresentation()
    }

    private fun refreshCurrentPresentation() {
        if (!presentationActive.get()) return
        val conf = _config.value
        val subtitles = allSubtitles
        val latestPosition = lastPositionMs.get()

        if (conf.useExternal && subtitles.isNotEmpty() && latestPosition >= 0L) {
            val entry = findSubtitleOptimized(subtitles, latestPosition - conf.syncOffset)
            cueTranslationJob?.cancel()
            cueTranslationJob = null
            cueRequest.incrementAndGet()
            _currentSubtitle.value = entry
            when {
                entry == null -> _translatedText.value = ""
                conf.isEnabled -> processTranslation(entry)
                else -> _translatedText.value = entry.text
            }
            if (conf.isEnabled) prefetchSignal.trySend(latestPosition)
        } else {
            reprocessVisibleSubtitle(conf)
        }
    }

    private fun cancelUpcomingPrefetch() {
        activePrefetchJobs.values.forEach { it.cancel() }
        activePrefetchJobs.clear()
        upcomingTranslations.entries.forEach { (key, deferred) ->
            if (upcomingTranslations.remove(key, deferred)) deferred.cancel()
        }
    }

    fun clearCache() {
        if (released.get()) return
        invalidateTranslationSession()
        reprocessVisibleSubtitle(_config.value)
    }

    fun updateConfig(
        enabled: Boolean,
        lang: String,
        offset: Long,
        useExternal: Boolean = true
    ) {
        if (released.get()) return
        val previous = _config.value
        val next = SubtitleConfig(enabled, lang, offset, useExternal)
        if (previous == next) return

        if (previous.targetLang != next.targetLang) {
            // Update the manager before any request can capture the new engine configuration.
            translationManager.setTargetLanguage(next.targetLang)
        }
        _config.value = next

        val translationContextChanged =
            previous.targetLang != next.targetLang ||
                previous.isEnabled != next.isEnabled ||
                previous.useExternal != next.useExternal

        if (translationContextChanged) {
            invalidateTranslationSession()
            reprocessVisibleSubtitle(next)
        }
    }

    fun setSubtitles(subtitles: List<SubtitleEntry>) {
        if (released.get()) return
        // Binary search requires a sorted list and index == list position.
        allSubtitles = subtitles
            .sortedBy { it.startTime }
            .mapIndexed { index, entry -> entry.copy(index = index) }
        subtitlePrefixMaxEnd = buildSubtitlePrefixMaxEnd(allSubtitles)
        lastFoundIndex = -1
        cueRequest.incrementAndGet()
        _currentSubtitle.value = null
        _translatedText.value = ""
        invalidateTranslationSession()
        translationManager.resetAutoDetectedSourceLanguage()
    }

    /** Clears every cue/document reference that must not survive a media URI transition. */
    fun resetForNewMedia() {
        if (released.get()) return
        lastTrackText = ""
        lastPositionMs.set(-1L)
        setSubtitles(emptyList())
    }

    private fun invalidateTranslationSession() {
        generation.incrementAndGet()
        cueRequest.incrementAndGet()
        cueTranslationJob?.cancel()
        cueTranslationJob = null
        translateAllRun.incrementAndGet()
        translateAllJob?.cancel()
        translateAllJob = null
        _isTranslatingAll.value = false
        _translatedCount.value = 0
        exportTranslations.clear()
        memoryCache.evictAll()

        cancelUpcomingPrefetch()
    }

    private fun reprocessVisibleSubtitle(conf: SubtitleConfig) {
        if (!presentationActive.get()) {
            _translatedText.value = ""
            return
        }
        val entry = _currentSubtitle.value
        when {
            conf.useExternal && entry != null -> {
                if (conf.isEnabled && conf.targetLang.isNotBlank()) processTranslation(entry)
                else _translatedText.value = entry.text
            }
            (!conf.useExternal || allSubtitles.isEmpty()) && lastTrackText.isNotBlank() -> {
                if (conf.isEnabled && conf.targetLang.isNotBlank()) processTrackText(lastTrackText)
                else _translatedText.value = lastTrackText
            }
            else -> _translatedText.value = ""
        }
    }

    private fun processTranslation(entry: SubtitleEntry) {
        if (!presentationActive.get()) return
        cueTranslationJob?.cancel()
        cueTranslationJob = null
        val conf = _config.value
        val documentGeneration = generation.get()
        val requestId = cueRequest.incrementAndGet()
        val cacheKey = SubtitleTranslationKey(conf.targetLang, entry.text)
        val cached = memoryCache[cacheKey]

        if (cached != null) {
            writeExternalResultIfCurrent(entry, conf.targetLang, documentGeneration, requestId, cached)
            return
        }

        // Hide original text while translating to avoid showing two languages sequentially
        _translatedText.value = ""
        val workKey = SubtitleWorkKey(
            documentGeneration,
            presentationEpoch.get(),
            entry.index,
            conf.targetLang,
            entry.text
        )
        val prefetched = upcomingTranslations[workKey]

        cueTranslationJob = scope.launch {
            val translated = try {
                // A prefetch can be cancelled by the worker on its own (stale queue item); that
                // must not be mistaken for this job's cancellation, which used to leave the cue
                // blank. ensureActive() rethrows only when this job itself was cancelled.
                val fromPrefetch = prefetched?.let { deferred ->
                    try {
                        deferred.await()
                    } catch (prefetchCancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    }
                }
                fromPrefetch ?: translateAndCache(
                    entry.text,
                    conf.targetLang,
                    documentGeneration
                )
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (_: Exception) {
                entry.text
            }
            writeExternalResultIfCurrent(
                entry,
                conf.targetLang,
                documentGeneration,
                requestId,
                translated
            )
        }
    }

    private fun writeExternalResultIfCurrent(
        entry: SubtitleEntry,
        targetLanguage: String,
        documentGeneration: Long,
        requestId: Long,
        translated: String
    ) {
        val conf = _config.value
        if (
            generation.get() == documentGeneration &&
            cueRequest.get() == requestId &&
            presentationActive.get() &&
            conf.isEnabled &&
            conf.useExternal &&
            conf.targetLang == targetLanguage &&
            _currentSubtitle.value == entry
        ) {
            _translatedText.value = translated
        }
    }

    private suspend fun translateAndCache(
        text: String,
        targetLanguage: String,
        documentGeneration: Long
    ): String {
        val key = SubtitleTranslationKey(targetLanguage, text)
        memoryCache[key]?.let { return it }

        return translationSemaphore.withPermit {
            memoryCache[key]?.let { return@withPermit it }
            if (!isCurrentGeneration(documentGeneration, targetLanguage)) return@withPermit text

            when (val result = translationManager.translateResult(text)) {
                is TranslationResult.Success -> {
                    if (isCurrentGeneration(documentGeneration, targetLanguage)) {
                        memoryCache.put(key, result.text)
                    }
                    result.text
                }
                is TranslationResult.Failure -> result.text
            }
        }
    }

    private fun isCurrentGeneration(documentGeneration: Long, targetLanguage: String): Boolean =
        !released.get() &&
            generation.get() == documentGeneration &&
            _config.value.targetLang == targetLanguage

    private fun isWorkCurrent(key: SubtitleWorkKey): Boolean {
        if (!presentationActive.get()) return false
        if (presentationEpoch.get() != key.presentationEpoch) return false
        if (!isCurrentGeneration(key.generation, key.targetLanguage)) return false
        val entry = allSubtitles.getOrNull(key.index) ?: return false
        return entry.text == key.text
    }

    fun setSubtitleOffset(offset: Long) {
        val conf = _config.value
        updateConfig(conf.isEnabled, conf.targetLang, offset, conf.useExternal)
    }

    fun onTrackTextReceived(text: String) {
        if (released.get()) return
        val conf = _config.value
        // Embedded cues are irrelevant while an external document is active and must not advance
        // cueRequest, otherwise they can invalidate the external translation currently in flight.
        if (!shouldProcessEmbeddedCue(conf, allSubtitles.size)) return

        lastTrackText = text
        cueRequest.incrementAndGet()
        if (!presentationActive.get()) {
            _translatedText.value = ""
            return
        }

        if (conf.isEnabled && text.isNotBlank() && conf.targetLang.isNotBlank()) processTrackText(text)
        else _translatedText.value = text
    }

    private fun processTrackText(text: String) {
        if (!presentationActive.get()) return
        cueTranslationJob?.cancel()
        cueTranslationJob = null
        val conf = _config.value
        val documentGeneration = generation.get()
        val requestId = cueRequest.incrementAndGet()
        
        // Hide original text while translating to avoid showing two languages sequentially
        _translatedText.value = ""

        cueTranslationJob = scope.launch {
            val translated = try {
                translateAndCache(text, conf.targetLang, documentGeneration)
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (_: Exception) {
                text
            }

            val currentConfig = _config.value
            if (
                generation.get() == documentGeneration &&
                cueRequest.get() == requestId &&
                presentationActive.get() &&
                currentConfig.isEnabled &&
                (!currentConfig.useExternal || allSubtitles.isEmpty()) &&
                currentConfig.targetLang == conf.targetLang &&
                lastTrackText == text
            ) {
                _translatedText.value = translated
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        generation.incrementAndGet()
        cueRequest.incrementAndGet()
        translateAllRun.incrementAndGet()
        translateAllJob?.cancel()
        cueTranslationJob?.cancel()
        prefetchJob?.cancel()
        upcomingTranslations.values.forEach { it.cancel() }
        upcomingTranslations.clear()
        activePrefetchJobs.values.forEach { it.cancel() }
        activePrefetchJobs.clear()
        memoryCache.evictAll()
        exportTranslations.clear()
        prefetchSignal.close()
        prefetchQueue.close()
        _isTranslatingAll.value = false
    }

    private companion object {
        const val PREFETCH_WINDOW = 25
        const val LOW_RAM_PREFETCH_WINDOW = 10
        const val DEFAULT_PREFETCH_QUEUE = 96
        const val LOW_RAM_PREFETCH_QUEUE = 32
        const val DEFAULT_CACHE_ENTRIES = 5_000
        const val LOW_RAM_CACHE_ENTRIES = 1_200
    }
}
