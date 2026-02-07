package com.example.oxigenplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.oxigenplayer.ui.FileExplorerDialog
import com.example.oxigenplayer.ui.SubtitleSearchDialog
import com.example.oxigenplayer.ui.theme.OxigenPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var videoUriState = mutableStateOf<Uri?>(null)
    private var exoPlayer: ExoPlayer? = null

    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferencesManager(newBase)
        val lang = prefs.getAppLanguage()
        val locale = Locale(lang)
        val config = Configuration(newBase.resources.configuration)
        Locale.setDefault(locale)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            OxigenPlayerTheme {
                VideoPlayerScreen(videoUriState) { player: ExoPlayer -> exoPlayer = player }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            videoUriState.value = intent.data
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (exoPlayer?.isPlaying == true) {
                    exoPlayer?.stop()
                    exoPlayer?.release()
                    exoPlayer = null
                    finish()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                exoPlayer?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                exoPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(it.duration)) }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(initialVideoUri: MutableState<Uri?>, onPlayerReady: (ExoPlayer) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translationManager = remember { TranslationManager() }
    val filePickerManager = remember { FilePickerManager() }
    val subtitleSearchService = remember { SubtitleSearchService() }
    val prefs = remember { PreferencesManager(context) }
    
    var originalSubtitle by remember { mutableStateOf("") }
    var translatedSubtitle by remember { mutableStateOf("") }
    var isModelDownloaded by remember { mutableStateOf(false) }
    
    var isTranslationEnabled by remember { mutableStateOf(true) }
    var translationSource by remember { mutableStateOf(TranslationSource.valueOf(prefs.getTranslationSource())) }
    var showSettings by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var showFileExplorer by remember { mutableStateOf(false) }
    var showMediaExplorer by remember { mutableStateOf(false) }
    var showAboutDeveloper by remember { mutableStateOf(false) }
    
    var subtitlesVisible by remember { mutableStateOf(prefs.isSubtitlesVisible()) }
    
    // Subtitle Customization States
    var subtitleFontSize by remember { mutableFloatStateOf(prefs.getSubtitleFontSize()) }
    var subtitleColor by remember { mutableStateOf(Color(prefs.getSubtitleColor())) }
    var subtitleBgColor by remember { mutableStateOf(Color(prefs.getSubtitleBackgroundColor())) }
    
    var sourceLang by remember { mutableStateOf("en") }
    var targetLang by remember { mutableStateOf(translationManager.getTargetLanguage()) }
    val currentVideoUri by remember { initialVideoUri }
    var externalSubtitles by remember { mutableStateOf<List<SubtitleEntry>>(emptyList()) }
    var translatedSubtitles by remember { mutableStateOf<List<SubtitleEntry>>(emptyList()) }
    var useExternalSubtitles by remember { mutableStateOf(false) }
    
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val firstButtonFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            playWhenReady = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(exoPlayer) { onPlayerReady(exoPlayer) }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            
            if (isPlaying && currentVideoUri != null) {
                prefs.saveVideoPosition(currentVideoUri.toString(), currentPosition)
            }
            delay(1000)
        }
    }

    LaunchedEffect(showControls, isPlaying, showSettings, showTracks, showSubtitleSearch, showFileExplorer, showMediaExplorer) {
        if (showControls && isPlaying && !showSettings && !showTracks && !showSubtitleSearch && !showFileExplorer && !showMediaExplorer) {
            delay(8000)
            showControls = false
        }
    }

    LaunchedEffect(currentVideoUri) {
        currentVideoUri?.let { uri ->
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            
            val savedPos = prefs.getVideoPosition(uri.toString())
            if (savedPos > 0) { exoPlayer.seekTo(savedPos) }
            
            exoPlayer.play()
            
            val uriStr = uri.toString()
            if (uriStr.contains(".")) {
                val srtUriStr = uriStr.substringBeforeLast(".") + ".srt"
                scope.launch(Dispatchers.IO) {
                    try {
                        val subs = filePickerManager.readSubtitleFile(context, Uri.parse(srtUriStr))
                        if (subs.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                externalSubtitles = subs
                                useExternalSubtitles = true
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(200)
            try { firstButtonFocusRequester.requestFocus() } catch (e: Exception) {}
        } else {
            rootFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(originalSubtitle) {
        if (originalSubtitle.length > 5 && isTranslationEnabled) {
            val detected = translationManager.detectAndSetSourceLanguage(originalSubtitle)
            sourceLang = detected
            if (translationSource == TranslationSource.MLKIT) {
                isModelDownloaded = false
                translationManager.downloadModelIfNeeded()
                isModelDownloaded = true
            }
        }
    }

    // Traducere automată la nivel de SRT întreg
    LaunchedEffect(externalSubtitles, isTranslationEnabled, translationSource, targetLang) {
        if (isTranslationEnabled && externalSubtitles.isNotEmpty()) {
            translationManager.currentSource = translationSource
            translationManager.setTargetLanguage(targetLang)
            scope.launch(Dispatchers.IO) {
                translatedSubtitles = translationManager.translateSubtitles(externalSubtitles)
            }
        } else {
            translatedSubtitles = emptyList()
        }
    }

    LaunchedEffect(originalSubtitle, isTranslationEnabled, isModelDownloaded, translationSource, targetLang, translatedSubtitles) {
        translationManager.currentSource = translationSource
        translationManager.setTargetLanguage(targetLang)
        
        // Verificăm dacă avem deja traducerea în lista pre-tradusă
        val preTranslated = SubtitleParser().getCurrentSubtitle(translatedSubtitles, exoPlayer.currentPosition)
        if (preTranslated != null) {
            translatedSubtitle = preTranslated
        } else {
            val needsModel = translationSource == TranslationSource.MLKIT
            val canTranslate = if (needsModel) isModelDownloaded else true
            
            if (isTranslationEnabled && canTranslate && originalSubtitle.isNotEmpty()) {
                translatedSubtitle = withContext(Dispatchers.IO) {
                    translationManager.translate(originalSubtitle)
                }
            } else {
                translatedSubtitle = ""
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            @Deprecated("Deprecated in Java")
            override fun onCues(cues: List<Cue>) {
                if (!useExternalSubtitles) {
                    val text = cues.firstOrNull()?.text?.toString() ?: ""
                    if (text != originalSubtitle) { originalSubtitle = text }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        exoPlayer.addListener(listener)
        onDispose { 
            currentVideoUri?.let { uri -> prefs.saveVideoPosition(uri.toString(), exoPlayer.currentPosition) }
            exoPlayer.removeListener(listener) 
        }
    }

    LaunchedEffect(useExternalSubtitles, externalSubtitles) {
        if (useExternalSubtitles && externalSubtitles.isNotEmpty()) {
            while (coroutineContext.isActive && useExternalSubtitles) {
                val pos = exoPlayer.currentPosition
                val subText = SubtitleParser().getCurrentSubtitle(externalSubtitles, pos) ?: ""
                if (subText != originalSubtitle) { originalSubtitle = subText }
                delay(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    if (!showControls) { showControls = true; return@onPreviewKeyEvent true }
                }
                false
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { showControls = !showControls }) }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    subtitleView?.visibility = android.view.View.GONE
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent))))
                
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { 
                            subtitlesVisible = !subtitlesVisible
                            prefs.saveSubtitlesVisible(subtitlesVisible)
                        }, 
                        modifier = Modifier.background(if (subtitlesVisible) Color.Red.copy(0.7f) else Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(firstButtonFocusRequester, isCircle = true)
                    ) 
                    { Icon(Icons.Default.ClosedCaption, "CC", tint = Color.White) }

                    IconButton(onClick = { showMediaExplorer = true }, modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Folder, "Media", tint = Color.White.copy(alpha = 0.7f)) }

                    IconButton(onClick = { showFileExplorer = true }, modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Add, "Adaugă SRT", tint = Color.White.copy(alpha = 0.7f)) }

                    IconButton(onClick = { showTracks = true }, modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.AutoMirrored.Filled.List, "Piste", tint = Color.White.copy(alpha = 0.7f)) }

                    IconButton(onClick = { showSubtitleSearch = true }, modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Search, "Online", tint = Color.White.copy(alpha = 0.7f)) }
                    
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.5f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Settings, "Setări", tint = Color.White.copy(alpha = 0.7f)) }
                }

                Row(modifier = Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition - 10000) }, modifier = Modifier.size(64.dp).background(Color.Black.copy(0.4f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                    
                    Spacer(modifier = Modifier.width(48.dp))
                    
                    IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, modifier = Modifier.size(90.dp).background(Color.Black.copy(0.4f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp)) }
                    
                    Spacer(modifier = Modifier.width(48.dp))
                    
                    IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10000) }, modifier = Modifier.size(64.dp).background(Color.Black.copy(0.4f), CircleShape).tvFocusable(isCircle = true)) 
                    { Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
                        .padding(bottom = 24.dp, start = 48.dp, end = 48.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { exoPlayer.seekTo(it.toLong()) },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .tvFocusable(),
                        colors = SliderDefaults.colors(thumbColor = Color.Red, activeTrackColor = Color.Red, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(currentPosition), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(" / ", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            }
        }

        // Overlay Subtitrări cu Customization
        val subPadding by animateDpAsState(if (showControls) 140.dp else 60.dp, label = "")
        val displaySubtitle = if (subtitlesVisible) {
            if (isTranslationEnabled && translatedSubtitle.isNotEmpty()) translatedSubtitle else if (!isTranslationEnabled) originalSubtitle else ""
        } else ""

        if (displaySubtitle.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = subPadding), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = displaySubtitle, 
                    color = subtitleColor, 
                    fontSize = subtitleFontSize.sp,
                    lineHeight = (subtitleFontSize * 1.4f).sp, 
                    textAlign = TextAlign.Center, 
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 10f),
                        background = subtitleBgColor
                    ),
                    modifier = Modifier.padding(horizontal = 40.dp).background(subtitleBgColor, RoundedCornerShape(4.dp)).padding(4.dp)
                )
            }
        }

        if (showSettings) MainSettingsDialog(
            onDismiss = { showSettings = false }, 
            isTranslationEnabled, { isTranslationEnabled = it }, 
            translationSource, { 
                translationSource = it
                prefs.saveTranslationSource(it.name)
            }, 
            subtitleFontSize, { 
                subtitleFontSize = it
                prefs.saveSubtitleFontSize(it)
            }, 
            subtitleColor, {
                subtitleColor = it
                prefs.saveSubtitleColor(it.toArgb())
            },
            subtitleBgColor, {
                subtitleBgColor = it
                prefs.saveSubtitleBackgroundColor(it.toArgb())
            },
            sourceLang, { sourceLang = it }, 
            targetLang, { targetLang = it },
            translationManager.getAvailableLanguages(),
            onAppLanguageChange = { lang ->
                prefs.saveAppLanguage(lang)
                (context as? ComponentActivity)?.recreate()
            },
            currentAppLang = prefs.getAppLanguage(),
            onShowAbout = { showAboutDeveloper = true },
            prefs = prefs,
            subtitleSearchService = subtitleSearchService,
            scope = scope
        )
        if (showTracks) TrackSelectionDialog(exoPlayer, onDismiss = { showTracks = false })
        if (showSubtitleSearch) SubtitleSearchDialog(onDismiss = { showSubtitleSearch = false }, { externalSubtitles = it; useExternalSubtitles = true; showSubtitleSearch = false }, subtitleSearchService, scope, context, currentVideoUri, prefs)
        
        if (showMediaExplorer) FileExplorerDialog(
            onDismiss = { showMediaExplorer = false },
            onFileSelected = { uri ->
                initialVideoUri.value = uri
                showMediaExplorer = false
            },
            title = "Selectează Media",
            allowedExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "jpg", "jpeg", "png", "webp", "gif")
        )

        if (showFileExplorer) FileExplorerDialog(
            onDismiss = { showFileExplorer = false },
            onFileSelected = { uri ->
                scope.launch(Dispatchers.IO) {
                    val subs = filePickerManager.readSubtitleFile(context, uri)
                    if (subs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            externalSubtitles = subs
                            useExternalSubtitles = true
                            showFileExplorer = false
                        }
                    }
                }
            },
            title = "Selectează Subtitrare",
            allowedExtensions = listOf("srt")
        )

        if (showAboutDeveloper) {
            AlertDialog(
                onDismissRequest = { showAboutDeveloper = false },
                title = { Text(OxigenStrings.get(prefs.getAppLanguage(), "about_dev")) },
                text = { Text(OxigenStrings.get(prefs.getAppLanguage(), "about_text")) },
                confirmButton = {
                    Button(onClick = { showAboutDeveloper = false }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(prefs.getAppLanguage(), "done")) }
                }
            )
        }
    }
}

private fun formatTime(timeInMillis: Long): String {
    val totalSeconds = timeInMillis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun MainSettingsDialog(
    onDismiss: () -> Unit,
    isTranslationEnabled: Boolean, onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource, onSourceChange: (TranslationSource) -> Unit,
    subtitleFontSize: Float, onFontSizeChange: (Float) -> Unit,
    subtitleColor: Color, onColorChange: (Color) -> Unit,
    subtitleBgColor: Color, onBgColorChange: (Color) -> Unit,
    sourceLang: String, onSourceLangChange: (String) -> Unit,
    targetLang: String, onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    onAppLanguageChange: (String) -> Unit,
    currentAppLang: String,
    onShowAbout: () -> Unit,
    prefs: PreferencesManager,
    subtitleSearchService: SubtitleSearchService,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var activeCategory by remember { mutableStateOf<String?>(null) }

    if (activeCategory == null) {
        Dialog(onDismissRequest = onDismiss) {
            Card(modifier = Modifier.fillMaxWidth(0.6f), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(OxigenStrings.get(currentAppLang, "settings_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    
                    CategoryButton(OxigenStrings.get(currentAppLang, "trans_source"), Icons.Default.Translate) { activeCategory = "translation" }
                    CategoryButton(OxigenStrings.get(currentAppLang, "os_auth"), Icons.Default.Login) { activeCategory = "auth" }
                    CategoryButton(OxigenStrings.get(currentAppLang, "sub_style"), Icons.Default.ColorLens) { activeCategory = "style" }
                    CategoryButton(OxigenStrings.get(currentAppLang, "app_lang"), Icons.Default.Language) { activeCategory = "lang" }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onShowAbout, modifier = Modifier.fillMaxWidth().tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "about_dev")) }
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "close")) }
                }
            }
        }
    } else {
        when (activeCategory) {
            "translation" -> TranslationSettingsDialog(
                onBack = { activeCategory = null },
                isTranslationEnabled, onTranslationToggle,
                translationSource, onSourceChange,
                sourceLang, onSourceLangChange,
                targetLang, onTargetLangChange,
                availableLanguages, currentAppLang
            )
            "auth" -> AuthSettingsDialog(
                onBack = { activeCategory = null },
                prefs, subtitleSearchService, scope, currentAppLang
            )
            "style" -> SubtitleStyleDialog(
                onBack = { activeCategory = null },
                subtitleFontSize, onFontSizeChange,
                subtitleColor, onColorChange,
                subtitleBgColor, onBgColorChange,
                currentAppLang
            )
            "lang" -> LanguageSettingsDialog(
                onBack = { activeCategory = null },
                currentAppLang, onAppLanguageChange
            )
        }
    }
}

@Composable
fun CategoryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tvFocusable(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(text, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null)
    }
}

@Composable
fun TranslationSettingsDialog(
    onBack: () -> Unit,
    isTranslationEnabled: Boolean, onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource, onSourceChange: (TranslationSource) -> Unit,
    sourceLang: String, onSourceLangChange: (String) -> Unit,
    targetLang: String, onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    currentAppLang: String
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onBack) {
        Card(modifier = Modifier.fillMaxWidth(0.8f), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
                    Text(OxigenStrings.get(currentAppLang, "trans_source"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                
                SettingsRow(OxigenStrings.get(currentAppLang, "active_trans"), "") { 
                    Switch(isTranslationEnabled, onTranslationToggle, modifier = Modifier.tvFocusable()) 
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(OxigenStrings.get(currentAppLang, "select_source"), fontWeight = FontWeight.Bold)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSourceChange(TranslationSource.MLKIT) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MLKIT) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.MLKIT)) { Text("ML Kit") }
                        Button(onClick = { onSourceChange(TranslationSource.GOOGLE_TRANSLATE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.GOOGLE_TRANSLATE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.GOOGLE_TRANSLATE)) { Text("Google") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSourceChange(TranslationSource.MY_MEMORY) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MY_MEMORY) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.MY_MEMORY)) { Text("MyMemory") }
                        Button(onClick = { onSourceChange(TranslationSource.LINGVA) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.LINGVA) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.LINGVA)) { Text("Lingva") }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                SettingsRow(OxigenStrings.get(currentAppLang, "source_lang"), "${sourceLang.uppercase()}") { 
                    Button(onClick = { showSourcePicker = true }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "change")) } 
                }

                SettingsRow(OxigenStrings.get(currentAppLang, "target_lang"), "${targetLang.uppercase()}") { 
                    Button(onClick = { showTargetPicker = true }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "change")) } 
                }
                
                if (showSourcePicker) LanguagePickerDialog(OxigenStrings.get(currentAppLang, "source_lang"), availableLanguages, { showSourcePicker = false }, { onSourceLangChange(it); showSourcePicker = false })
                if (showTargetPicker) LanguagePickerDialog(OxigenStrings.get(currentAppLang, "target_lang"), availableLanguages, { showTargetPicker = false }, { onTargetLangChange(it); showTargetPicker = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSettingsDialog(
    onBack: () -> Unit,
    prefs: PreferencesManager,
    subtitleSearchService: SubtitleSearchService,
    scope: kotlinx.coroutines.CoroutineScope,
    currentAppLang: String
) {
    var osUser by remember { mutableStateOf(prefs.getUsername()) }
    var osPass by remember { mutableStateOf(prefs.getPassword()) }
    var loginMessage by remember { mutableStateOf("") }
    var searchLang by remember { mutableStateOf(prefs.getSearchLanguage()) }
    var showSubtitleSearchLangPicker by remember { mutableStateOf(false) }

    val openSubtitlesLanguages = remember {
        mapOf(
            "ab" to "Abkhazian", "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic",
            "an" to "Aragonese", "hy" to "Armenian", "as" to "Assamese", "at" to "Asturian", "az-az" to "Azerbaijani",
            "eu" to "Basque", "be" to "Belarusian", "bn" to "Bengali", "bs" to "Bosnian", "br" to "Breton",
            "bg" to "Bulgarian", "my" to "Burmese", "ca" to "Catalan", "ze" to "Chinese bilingual", "zh-ca" to "Chinese (Cantonese)",
            "zh-cn" to "Chinese (simplified)", "zh-tw" to "Chinese (traditional)", "hr" to "Croatian", "cs" to "Czech", "da" to "Danish",
            "pr" to "Dari", "nl" to "Dutch", "en" to "English", "eo" to "Esperanto", "et" to "Estonian",
            "ex" to "Extremaduran", "fi" to "Finnish", "fr" to "French", "gd" to "Gaelic", "gl" to "Galician",
            "ka" to "Georgian", "de" to "German", "el" to "Greek", "he" to "Hebrew", "hi" to "Hindi",
            "hu" to "Hungarian", "is" to "Icelandic", "ig" to "Igbo", "id" to "Indonesian", "ia" to "Interlingua",
            "ga" to "Irish", "it" to "Italian", "ja" to "Japanese", "kn" to "Kannada", "kk" to "Kazakh",
            "km" to "Khmer", "ko" to "Korean", "ku" to "Kurdish", "lv" to "Latvian", "lt" to "Lithuanian",
            "lb" to "Luxembourgish", "mk" to "Macedonian", "ms" to "Malay", "ml" to "Malayalam", "ma" to "Manipuri",
            "mr" to "Marathi", "mn" to "Mongolian", "me" to "Montenegrin", "nv" to "Navajo", "ne" to "Nepali",
            "se" to "Northern Sami", "no" to "Norwegian", "oc" to "Occitan", "or" to "Odia", "fa" to "Persian",
            "pl" to "Polish", "pt-pt" to "Portuguese", "pt-br" to "Portuguese (BR)", "pm" to "Portuguese (MZ)", "ps" to "Pushto",
            "ro" to "Romanian", "ru" to "Russian", "sx" to "Santali", "sr" to "Serbian", "sd" to "Sindhi",
            "si" to "Sinhalese", "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali", "az-zb" to "South Azerbaijani",
            "es" to "Spanish", "sp" to "Spanish (EU)", "ea" to "Spanish (LA)", "sw" to "Swahili", "sv" to "Swedish",
            "sy" to "Syriac", "tl" to "Tagalog", "ta" to "Tamil", "tt" to "Tatar", "te" to "Telugu",
            "tm-td" to "Tetum", "th" to "Thai", "tp" to "Toki Pona", "tr" to "Turkish", "tk" to "Turkmen",
            "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek", "vi" to "Vietnamese", "cy" to "Welsh"
        )
    }

    Dialog(onDismissRequest = onBack) {
        Card(modifier = Modifier.fillMaxWidth(0.8f), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
                    Text(OxigenStrings.get(currentAppLang, "os_auth"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                OutlinedTextField(
                    value = osUser, 
                    onValueChange = { osUser = it }, 
                    label = { Text(OxigenStrings.get(currentAppLang, "user")) },
                    modifier = Modifier.fillMaxWidth().tvFocusable(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = osPass, 
                    onValueChange = { osPass = it }, 
                    label = { Text(OxigenStrings.get(currentAppLang, "pass")) },
                    modifier = Modifier.fillMaxWidth().tvFocusable(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            loginMessage = OxigenStrings.get(currentAppLang, "logging_in")
                            val token = subtitleSearchService.login(osUser, osPass)
                            if (token != null) {
                                prefs.saveLogin(osUser, osPass)
                                prefs.saveToken(token)
                                loginMessage = OxigenStrings.get(currentAppLang, "login_success")
                            } else {
                                loginMessage = OxigenStrings.get(currentAppLang, "login_failed")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tvFocusable()
                ) {
                    Text("LOGIN")
                }
                if (loginMessage.isNotEmpty()) {
                    Text(loginMessage, color = if (loginMessage.contains("succes") || loginMessage.contains("success")) Color.Green else Color.Red, fontSize = 12.sp)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                Text(OxigenStrings.get(currentAppLang, "search_lang"), fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { searchLang = "ro"; prefs.saveSearchLanguage("ro") }, modifier = Modifier.weight(1f).tvFocusable(isSelected = searchLang == "ro")) { Text("RO", fontSize = 11.sp) }
                    Button(onClick = { searchLang = "en"; prefs.saveSearchLanguage("en") }, modifier = Modifier.weight(1f).tvFocusable(isSelected = searchLang == "en")) { Text("EN", fontSize = 11.sp) }
                    Button(onClick = { showSubtitleSearchLangPicker = true }, modifier = Modifier.weight(1f).tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "other"), fontSize = 11.sp) }
                }

                if (showSubtitleSearchLangPicker) {
                    LanguagePickerDialog(
                        title = OxigenStrings.get(currentAppLang, "search_lang"),
                        languages = openSubtitlesLanguages.values.toList(),
                        onDismiss = { showSubtitleSearchLangPicker = false },
                        onSelect = { langName ->
                            val code = openSubtitlesLanguages.entries.find { it.value == langName }?.key ?: "ro"
                            searchLang = code
                            prefs.saveSearchLanguage(code)
                            showSubtitleSearchLangPicker = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SubtitleStyleDialog(
    onBack: () -> Unit,
    subtitleFontSize: Float, onFontSizeChange: (Float) -> Unit,
    subtitleColor: Color, onColorChange: (Color) -> Unit,
    subtitleBgColor: Color, onBgColorChange: (Color) -> Unit,
    currentAppLang: String
) {
    val colors = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Red, Color.Magenta)
    val bgColors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black, Color.DarkGray.copy(alpha = 0.5f))

    Dialog(onDismissRequest = onBack) {
        Card(modifier = Modifier.fillMaxWidth(0.8f), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
                    Text(OxigenStrings.get(currentAppLang, "sub_style"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("${OxigenStrings.get(currentAppLang, "size")}: ${subtitleFontSize.toInt()} sp")
                Slider(
                    value = subtitleFontSize, onValueChange = onFontSizeChange, valueRange = 10f..100f,
                    modifier = Modifier.fillMaxWidth().tvFocusable()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(OxigenStrings.get(currentAppLang, "text_color"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(colors) { color ->
                        Box(modifier = Modifier.size(44.dp).background(color, CircleShape).clickable { onColorChange(color) }.tvFocusable(isCircle = true, isSelected = subtitleColor == color))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(OxigenStrings.get(currentAppLang, "bg_color"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bgColors) { color ->
                        Box(modifier = Modifier.size(44.dp).background(color, CircleShape).clickable { onBgColorChange(color) }.tvFocusable(isCircle = true, isSelected = subtitleBgColor == color))
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSettingsDialog(onBack: () -> Unit, currentAppLang: String, onAppLanguageChange: (String) -> Unit) {
    val appLanguages = mapOf(
        "ro" to "Română",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
        "ru" to "Русский"
    )

    Dialog(onDismissRequest = onBack) {
        Card(modifier = Modifier.fillMaxWidth(0.6f), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
                    Text(OxigenStrings.get(currentAppLang, "app_lang"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(appLanguages.entries.toList()) { entry ->
                        Button(
                            onClick = { onAppLanguageChange(entry.key) },
                            modifier = Modifier.fillMaxWidth().tvFocusable(isSelected = currentAppLang == entry.key),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentAppLang == entry.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (currentAppLang == entry.key) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(entry.value)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsRow(title: String, sub: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall) }
        content()
    }
}

@Composable
fun LanguagePickerDialog(title: String, languages: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f)) {
                    items(languages.sorted()) { lang ->
                        var isFocused by remember { mutableStateOf(false) }
                        Box(Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.background(if (isFocused) Color.White.copy(0.1f) else Color.Transparent).clickable { onSelect(lang) }.padding(16.dp)) {
                            Text(lang.uppercase(), color = if (isFocused) Color.Yellow else Color.White)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TrackSelectionDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Tracks", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isNotEmpty()) {
                        item { Text("Audio", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        audioGroups.forEach { group ->
                            items(group.mediaTrackGroup.length) { index ->
                                val format = group.mediaTrackGroup.getFormat(index)
                                val isSelected = group.isTrackSelected(index)
                                val label = format.label ?: format.language ?: "Track ${index + 1}"
                                ListItem(
                                    headlineContent = { Text(label) },
                                    supportingContent = { if (format.bitrate > 0) Text("${format.bitrate / 1000} kbps") },
                                    modifier = Modifier.clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                        onDismiss()
                                    }.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent).tvFocusable(isSelected = isSelected)
                                )
                            }
                        }
                    }
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    if (textGroups.isNotEmpty()) {
                        item { Text("Subtitles", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        textGroups.forEach { group ->
                            items(group.mediaTrackGroup.length) { index ->
                                val format = group.mediaTrackGroup.getFormat(index)
                                val isSelected = group.isTrackSelected(index)
                                val label = format.label ?: format.language ?: "Track ${index + 1}"
                                ListItem(
                                    headlineContent = { Text(label) },
                                    modifier = Modifier.clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                        onDismiss()
                                    }.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent).tvFocusable(isSelected = isSelected)
                                )
                            }
                        }
                    }
                }
                Button(onClick = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).build(); onDismiss() }, modifier = Modifier.align(Alignment.CenterHorizontally).tvFocusable()) { Text("Disable Subtitles") }
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 16.dp).tvFocusable()) { Text("CLOSE") }
            }
        }
    }
}
