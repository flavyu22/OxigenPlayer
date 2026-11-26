// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Language
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oxigenplayer.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SubtitleSearchDialog(
    onDismiss: () -> Unit,
    onSubtitleSelected: (List<SubtitleEntry>) -> Unit,
    subtitleSearchService: SubtitleSearchService,
    context: Context,
    currentVideoUri: Uri?,
    prefs: PreferencesManager
) {
    val focusManager = LocalFocusManager.current
    val dialogScope = rememberCoroutineScope()
    val appLang = remember { prefs.getAppLanguage() }

    var searchLang by remember { mutableStateOf(prefs.getSearchLanguage()) }
    var query by remember(currentVideoUri) { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SubtitleSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showLanguagePicker by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val performSearch: (String) -> Unit = { rawQuery ->
        val requestedQuery = rawQuery.trim()
        if (requestedQuery.isNotBlank()) {
            searchJob?.cancel()
            isLoading = true
            errorMessage = null
            searchResults = emptyList()
            focusManager.clearFocus()
            val requestedLanguage = searchLang
            searchJob = dialogScope.launch {
                try {
                    val result = subtitleSearchService.searchSubtitles(
                        requestedQuery,
                        lang = requestedLanguage,
                        token = prefs.getToken()
                    )
                    if (isActive) {
                        searchResults = result.subtitles
                        errorMessage = localizedSearchResultMessage(result, appLang)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (isActive) {
                        errorMessage = OxigenStrings.get(appLang, "search_failed")
                    }
                } finally {
                    if (searchJob === coroutineContext[Job]) isLoading = false
                }
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.getOrNull(0)
            if (!spokenText.isNullOrBlank()) {
                query = spokenText
                performSearch(spokenText)
            }
        }
    }

    LaunchedEffect(currentVideoUri) {
        val resolvedQuery = MovieNameExtractor.extractMovieNameWithYearAsync(context, currentVideoUri)
        if (query.isBlank()) {
            query = resolvedQuery
            // Lansăm automat căutarea dacă avem un nume de film detectat
            if (resolvedQuery.isNotBlank()) {
                performSearch(resolvedQuery)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            searchJob?.cancel()
            downloadJob?.cancel()
        }
    }

    if (showLanguagePicker) {
        LanguagePickerOverlay(
            currentLang = searchLang,
            appLang = appLang,
            onDismiss = { showLanguagePicker = false },
            onLangSelected = {
                searchLang = it
                prefs.saveSearchLanguage(it)
                showLanguagePicker = false
            }
        )
    }

    FastDialog(onDismiss = onDismiss) {
        OxigenCard(
            modifier = Modifier
                .fillMaxWidth(0.55f) // Slightly wider for the language row
                .fillMaxHeight(0.92f)
                .padding(8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxSize()) {
                Text(
                    OxigenStrings.get(appLang, "online_search"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    OxigenStrings.get(appLang, "online_search_privacy"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Voice Search Button (moved to left)
                    Box(
                        modifier = Modifier.height(48.dp).tvFocusable(scaleAmount = 1.02f, onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                // EXTRA_LANGUAGE is read with getStringExtra; a Locale object is silently ignored.
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE,
                                    Locale.getDefault().toLanguageTag(),
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, OxigenStrings.get(appLang, "speak_now"))
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (_: Exception) {
                                errorMessage = OxigenStrings.get(appLang, "voice_search_unavailable")
                            }
                        })
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Mic,
                                OxigenStrings.get(appLang, "voice_search"),
                                tint = Color.White,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(OxigenStrings.get(appLang, "movie_name_placeholder")) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch(query) })
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Fixed Search Button
                    Box(
                        modifier = Modifier.height(48.dp).tvFocusable(scaleAmount = 1.02f, onClick = { performSearch(query) })
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, OxigenStrings.get(appLang, "search"), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(OxigenStrings.get(appLang, "search"), color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Language Selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        OxigenStrings.get(appLang, "search_lang_label"),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    val mainLangs = listOf(
                        Triple("ro", OxigenStrings.get(appLang, "lang_ro"), "🇷🇴"),
                        Triple("en", OxigenStrings.get(appLang, "lang_en"), "🇺🇸"),
                        Triple("ro,en", OxigenStrings.get(appLang, "lang_both"), "🌐")
                    )

                    mainLangs.forEach { (code, label, flag) ->
                        val isSelected = searchLang == code
                        Box(
                            modifier = Modifier
                                .tvFocusable(onClick = {
                                    searchLang = code
                                    prefs.saveSearchLanguage(code)
                                })
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(flag, fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    label,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // "More" Button
                    val isOtherSelected = mainLangs.none { it.first == searchLang }
                    Box(
                        modifier = Modifier
                            .tvFocusable(onClick = { showLanguagePicker = true })
                            .background(
                                if (isOtherSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                else Color.White.copy(alpha = 0.05f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isOtherSelected) {
                                Text(searchLang.uppercase(Locale.ROOT), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    Icons.Default.Language,
                                    OxigenStrings.get(appLang, "lang_more"),
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(OxigenStrings.get(appLang, "lang_more"), color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading && searchResults.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (errorMessage != null && searchResults.isEmpty()) {
                        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center)) }
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            lazyItems(
                            items = searchResults,
                            key = { "${it.source}_${it.id}_${it.fileName}" }
                        ) { result ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tvFocusable(
                                            scaleAmount = 1.02f,
                                            onClick = {
                                                if (isLoading) return@tvFocusable
                                                isLoading = true
                                                errorMessage = null
                                                downloadJob?.cancel()
                                                downloadJob = dialogScope.launch {
                                                    try {
                                                        val subs = subtitleSearchService.downloadSubtitle(
                                                            result = result,
                                                            token = prefs.getToken()
                                                        )
                                                        if (subs.isNotEmpty()) {
                                                            onSubtitleSelected(subs)
                                                        } else {
                                                            errorMessage = OxigenStrings.get(appLang, "subtitle_processing_error")
                                                        }
                                                    } catch (e: SubtitleDownloadException) {
                                                        Log.e("SubtitleSearchDialog", "Subtitle download failed", e)
                                                        if (e.isUnauthorized && result.source == "OpenSubtitles") {
                                                            prefs.clearAccount()
                                                            errorMessage = OxigenStrings.get(appLang, "invalid_credentials")
                                                        } else {
                                                            errorMessage = e.message ?: OxigenStrings.get(appLang, "download_failed")
                                                        }
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (e: Exception) {
                                                        Log.e("SubtitleSearchDialog", "Download failed", e)
                                                        val errorName = e.javaClass.simpleName
                                                        errorMessage = (e.message ?: OxigenStrings.get(appLang, "download_failed")) + " ($errorName)"
                                                    } finally {
                                                        if (downloadJob === coroutineContext[Job]) isLoading = false
                                                    }
                                                }
                                            }
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(result.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f), color = Color.White)
                                            Surface(
                                                color = when (result.source) {
                                                    "Subs.ro"    -> Color(0xFFE53935)   // red
                                                    "SubDL"      -> Color(0xFF1E88E5)   // albastru
                                                    "Titrari.ro" -> Color(0xFF43A047)   // verde
                                                    else         -> MaterialTheme.colorScheme.secondary // OpenSubtitles
                                                },
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(result.source, fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                            if (result.matchedByImdb) {
                                                Spacer(Modifier.width(4.dp))
                                                Surface(color = Color(0xFFFFB300), shape = RoundedCornerShape(4.dp)) {
                                                    Text("IMDB", fontSize = 9.sp, color = Color.Black,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                        val yearSuffix = result.year.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                                        Text(
                                            "${result.movieName}$yearSuffix • ${result.language}",
                                            fontSize = 14.sp,
                                            color = Color.Gray,
                                        )
                                    }
                                }
                            }
                        }

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        if (errorMessage != null) {
                            // Show error toast-like overlay if we have results but failed to download one
                            Surface(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                errorMessage?.let { Text(it, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                OxigenStrings.get(appLang, "enter_name_search"),
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Fixed Close Button
                Box(
                    modifier = Modifier.align(Alignment.End).tvFocusable(scaleAmount = 1.02f, onClick = onDismiss)
                ) {
                    Text(
                        OxigenStrings.get(appLang, "close_btn"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun localizedSearchResultMessage(result: SearchResult, appLang: String): String? {
    if (result.subtitles.isNotEmpty()) return null
    val operationalFailures = result.failures.filterNot {
        it.kind == SubtitleProviderFailureKind.NOT_CONFIGURED
    }
    return if (operationalFailures.isNotEmpty()) {
        val providers = operationalFailures.map { it.provider }.distinct().joinToString(", ")
        "${OxigenStrings.get(appLang, "search_failed")} ($providers)"
    } else {
        OxigenStrings.get(appLang, "no_subtitle_results")
    }
}

@Composable
fun LanguagePickerOverlay(
    currentLang: String,
    appLang: String,
    onDismiss: () -> Unit,
    onLangSelected: (String) -> Unit
) {
    val languages = remember(appLang) {
        val uiLocale = Locale.forLanguageTag(appLang)
        listOf(
            "sq", "ar", "hy", "az", "eu", "be", "bn", "bs", "bg", "ca", "zh", "hr",
            "cs", "da", "nl", "en", "eo", "et", "fi", "fr", "gl", "ka", "de", "el",
            "he", "hi", "hu", "is", "id", "it", "ja", "ko", "lv", "lt", "mk", "ms",
            "no", "fa", "pl", "pt", "ro", "ru", "sr", "sk", "sl", "es", "sv", "th",
            "tr", "uk", "vi",
        ).map { code ->
            code to Locale.forLanguageTag(code).getDisplayLanguage(uiLocale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(uiLocale) else it.toString() }
        }.sortedBy { it.second.lowercase(uiLocale) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .fillMaxHeight(0.7f)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        OxigenStrings.get(appLang, "select_search_lang"),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(
                            items = languages,
                            key = { it.first }
                        ) { pair ->
                            val code = pair.first
                            val name = pair.second
                            val isSelected = currentLang == code
                            Box(
                                modifier = Modifier
                                    .tvFocusable(onClick = { onLangSelected(code) })
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(OxigenStrings.get(appLang, "close_btn"))
                    }
                }
            }
        }
    }
}
