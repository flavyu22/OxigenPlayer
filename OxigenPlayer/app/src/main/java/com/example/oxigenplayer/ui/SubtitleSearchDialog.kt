package com.example.oxigenplayer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.oxigenplayer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SubtitleSearchDialog(
    onDismiss: () -> Unit,
    onSubtitleSelected: (List<SubtitleEntry>) -> Unit,
    subtitleSearchService: SubtitleSearchService,
    scope: CoroutineScope,
    context: Context,
    currentVideoUri: Uri?,
    prefs: PreferencesManager
) {
    val focusManager = LocalFocusManager.current
    val searchLang = remember { prefs.getSearchLanguage() }
    val authToken = remember { prefs.getToken() }
    
    var query by remember { mutableStateOf(currentVideoUri?.let { MovieNameExtractor.extractMovieNameWithYear(context, it) } ?: "") }
    var searchResults by remember { mutableStateOf<List<SubtitleSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val performSearch = {
        if (query.isNotBlank()) {
            isLoading = true
            errorMessage = null
            focusManager.clearFocus()
            scope.launch {
                val result = subtitleSearchService.searchSubtitles(query, lang = searchLang, token = authToken)
                searchResults = result.subtitles
                errorMessage = result.errorMessage
                isLoading = false
            }
        }
    }

    // Auto-search on open if query is present
    LaunchedEffect(Unit) {
        if (query.isNotEmpty()) {
            performSearch()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
                Text(
                    if (prefs.getAppLanguage() == "ro") "Căutare Subtitrări Online" else "Online Subtitle Search", 
                    style = MaterialTheme.typography.titleLarge, 
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (prefs.getAppLanguage() == "ro") "Nume film..." else "Movie name...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch() })
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = performSearch, modifier = Modifier.height(56.dp).tvFocusable()) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (prefs.getAppLanguage() == "ro") "Caută" else "Search")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (errorMessage != null) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(searchResults) { result ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isLoading = true
                                            scope.launch {
                                                val subs = subtitleSearchService.downloadSubtitle(
                                                    context = context,
                                                    fileId = result.downloadUrl,
                                                    movieName = result.movieName,
                                                    videoUri = currentVideoUri,
                                                    token = authToken
                                                )
                                                if (subs.isNotEmpty()) {
                                                    onSubtitleSelected(subs)
                                                } else {
                                                    errorMessage = if (prefs.getAppLanguage() == "ro") "Eroare la descărcare." else "Download error."
                                                }
                                                isLoading = false
                                            }
                                        }
                                        .tvFocusable(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(result.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                                        Text("${result.movieName} (${result.year}) • ${result.language}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (prefs.getAppLanguage() == "ro") "Introdu numele și apasă Caută" else "Enter name and press Search", 
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).tvFocusable()) {
                    Text(if (prefs.getAppLanguage() == "ro") "ÎNCHIDE" else "CLOSE")
                }
            }
        }
    }
}
