package com.example.oxigenplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.example.oxigenplayer.ui.FileExplorerDialog
import com.example.oxigenplayer.ui.MainSettingsDialog
import com.example.oxigenplayer.ui.SubtitleSearchDialog
import com.example.oxigenplayer.ui.TrackSelectionDialog
import com.example.oxigenplayer.ui.UpdateDialog
import com.example.oxigenplayer.ui.theme.OxigenPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        // Păstrează ecranul aprins permanent în timpul utilizării aplicației
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // Închide aplicația instantaneu la Home pentru a nu rămâne în background
    override fun onStop() {
        super.onStop()
        exoPlayer?.release()
        exoPlayer = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    val updateManager = remember { UpdateManager(context) }
    val prefs = remember { PreferencesManager(context) }
    val appLang = remember { prefs.getAppLanguage() }
    
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
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    
    var subtitlesVisible by remember { mutableStateOf(prefs.isSubtitlesVisible()) }
    
    var subtitleFontSize by remember { mutableFloatStateOf(prefs.getSubtitleFontSize()) }
    var subtitleColor by remember { mutableStateOf(Color(prefs.getSubtitleColor())) }
    var subtitleBgColor by remember { mutableStateOf(Color(prefs.getSubtitleBackgroundColor())) }
    
    var sourceLang by remember { mutableStateOf("en") }
    var targetLang by remember { mutableStateOf(translationManager.getTargetLanguage()) }
    val currentVideoUri by initialVideoUri
    var externalSubtitles by remember { mutableStateOf<List<SubtitleEntry>>(emptyList()) }
    val translatedSubtitlesMap = remember { mutableStateMapOf<Int, SubtitleEntry>() }
    var useExternalSubtitles by remember { mutableStateOf(false) }
    
    var showControls by remember { mutableStateOf(true) }
    var showOnlySeekBar by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isTranslatingAll by remember { mutableStateOf(false) }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    val firstButtonFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

    // Verificare Update
    LaunchedEffect(Unit) {
        val info = updateManager.checkForUpdate()
        if (info != null) { updateInfo = info }
    }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableAudioTrackPlaybackParams(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters().setTunnelingEnabled(true).build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 2500, 5000)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    var hideControlsJob by remember { mutableStateOf<Job?>(null) }
    val resetControlsTimer = {
        showControls = true
        showOnlySeekBar = false
        hideControlsJob?.cancel()
        if (isPlaying && !showSettings && !showTracks && !showSubtitleSearch && !showFileExplorer && !showMediaExplorer) {
            hideControlsJob = scope.launch {
                delay(5000)
                showControls = false
            }
        }
    }

    LaunchedEffect(isPlaying, showSettings, showTracks, showSubtitleSearch, showFileExplorer, showMediaExplorer) {
        resetControlsTimer()
    }

    var hideSeekBarJob by remember { mutableStateOf<Job?>(null) }
    val triggerOnlySeekBar = {
        showOnlySeekBar = true
        showControls = false
        hideSeekBarJob?.cancel()
        hideSeekBarJob = scope.launch {
            delay(3000)
            showOnlySeekBar = false
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
            delay(2000)
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
            delay(100)
            try { firstButtonFocusRequester.requestFocus() } catch (e: Exception) {}
        } else {
            rootFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(originalSubtitle) {
        if (originalSubtitle.length > 5 && isTranslationEnabled) {
            val detected = translationManager.detectAndSetSourceLanguage(originalSubtitle)
            if (detected != null) {
                sourceLang = detected
            }
            if (translationSource == TranslationSource.MLKIT) {
                isModelDownloaded = false
                translationManager.downloadModelIfNeeded()
                isModelDownloaded = true
            }
        }
    }

    LaunchedEffect(originalSubtitle, isTranslationEnabled, isModelDownloaded, translationSource, targetLang) {
        translationManager.currentSource = translationSource
        translationManager.setTargetLanguage(targetLang)
        
        val currentSubEntry = if (useExternalSubtitles) SubtitleParser().getCurrentSubtitleEntry(externalSubtitles, exoPlayer.currentPosition) else null
        val preTranslated = currentSubEntry?.let { translatedSubtitlesMap[it.index]?.text }
        
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
                delay(150)
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
                    resetControlsTimer()
                    if (!showControls) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                triggerOnlySeekBar()
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                                triggerOnlySeekBar()
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                subtitleFontSize = (subtitleFontSize + 2f).coerceAtMost(100f)
                                prefs.saveSubtitleFontSize(subtitleFontSize)
                                triggerOnlySeekBar()
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                subtitleFontSize = (subtitleFontSize - 2f).coerceAtLeast(10f)
                                prefs.saveSubtitleFontSize(subtitleFontSize)
                                triggerOnlySeekBar()
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                showControls = true
                                return@onPreviewKeyEvent true
                            }
                        }
                    } else {
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { resetControlsTimer() }) }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    subtitleView?.visibility = android.view.View.GONE
                    keepScreenOn = true
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
                    verticalAlignment = Alignment.Top
                ) {
                    PlayerControlButton(
                        icon = Icons.Default.ClosedCaption,
                        label = "CC",
                        onClick = { 
                            subtitlesVisible = !subtitlesVisible
                            prefs.saveSubtitlesVisible(subtitlesVisible)
                            resetControlsTimer()
                        },
                        isSelected = subtitlesVisible,
                        selectedColor = Color.Red.copy(0.7f),
                        focusRequester = firstButtonFocusRequester
                    )

                    PlayerControlButton(
                        icon = Icons.Default.Folder,
                        label = OxigenStrings.get(appLang, "media_label"),
                        onClick = { showMediaExplorer = true }
                    )

                    PlayerControlButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        label = OxigenStrings.get(appLang, "tracks_title"),
                        onClick = { showTracks = true }
                    )

                    PlayerControlButton(
                        icon = Icons.Default.Search,
                        label = OxigenStrings.get(appLang, "online_search"),
                        onClick = { showSubtitleSearch = true }
                    )
                    
                    PlayerControlButton(
                        icon = Icons.Default.Settings,
                        label = OxigenStrings.get(appLang, "settings_label"),
                        onClick = { showSettings = true }
                    )
                }

                IconButton(
                    onClick = { 
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        resetControlsTimer()
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(90.dp)
                        .background(Color.Black.copy(0.4f), CircleShape)
                        .tvFocusable(isCircle = true)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = showControls || showOnlySeekBar, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
                        .padding(bottom = 24.dp, start = 48.dp, end = 48.dp)
                ) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    val thumbSize by animateDpAsState(targetValue = if (isFocused) 20.dp else 12.dp, label = "")
                    
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { 
                            exoPlayer.seekTo(it.toLong())
                            resetControlsTimer()
                        },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusable(interactionSource = interactionSource),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Red,
                            activeTrackColor = Color.Red,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        thumb = {
                            Box(modifier = Modifier.size(thumbSize).background(Color.Red, CircleShape))
                        }
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
            onDismiss = { showSettings = false; resetControlsTimer() }, 
            isTranslationEnabled = isTranslationEnabled, 
            onTranslationToggle = { isTranslationEnabled = it; resetControlsTimer() }, 
            translationSource = translationSource, 
            onSourceChange = { 
                translationSource = it
                prefs.saveTranslationSource(it.name)
                resetControlsTimer()
            }, 
            subtitleFontSize = subtitleFontSize, 
            onFontSizeChange = { 
                subtitleFontSize = it
                prefs.saveSubtitleFontSize(it)
                resetControlsTimer()
            }, 
            subtitleColor = subtitleColor, 
            onColorChange = {
                subtitleColor = it
                prefs.saveSubtitleColor(it.toArgb())
                resetControlsTimer()
            },
            subtitleBgColor = subtitleBgColor, 
            onBgColorChange = {
                subtitleBgColor = it
                prefs.saveSubtitleBackgroundColor(it.toArgb())
                resetControlsTimer()
            },
            sourceLang = sourceLang, 
            onSourceLangChange = { sourceLang = it; resetControlsTimer() }, 
            targetLang = targetLang, 
            onTargetLangChange = { targetLang = it; resetControlsTimer() },
            availableLanguages = translationManager.getAvailableLanguages(),
            onAppLanguageChange = { lang ->
                prefs.saveAppLanguage(lang)
                (context as? ComponentActivity)?.recreate()
            },
            currentAppLang = prefs.getAppLanguage(),
            onShowAbout = { showAboutDeveloper = true },
            externalSubtitles = externalSubtitles,
            translatedMapSize = translatedSubtitlesMap.size,
            isTranslatingAll = isTranslatingAll,
            onTranslateAll = {
                isTranslatingAll = true
                scope.launch(Dispatchers.IO) {
                    translationManager.translateSubtitles(externalSubtitles) { updatedEntry ->
                        translatedSubtitlesMap[updatedEntry.index] = updatedEntry
                    }
                    isTranslatingAll = false
                    val movieName = currentVideoUri?.let { MovieNameExtractor.extractMovieNameWithYear(context, it) } ?: "Movie"
                    val allTranslated = externalSubtitles.map { entry -> translatedSubtitlesMap[entry.index] ?: entry }
                    val path = filePickerManager.saveSubtitlesToDevice(context, movieName, allTranslated)
                    if (path != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Saved & Applied: $path", Toast.LENGTH_LONG).show()
                            externalSubtitles = allTranslated
                            useExternalSubtitles = true
                        }
                    }
                }
            }
        )
        if (showTracks) TrackSelectionDialog(exoPlayer, onDismiss = { showTracks = false; resetControlsTimer() }, currentAppLang = appLang)
        if (showSubtitleSearch) SubtitleSearchDialog(onDismiss = { showSubtitleSearch = false; resetControlsTimer() }, { externalSubtitles = it; useExternalSubtitles = true; showSubtitleSearch = false; resetControlsTimer() }, subtitleSearchService, scope, context, currentVideoUri, prefs)
        
        if (showMediaExplorer) FileExplorerDialog(
            onDismiss = { showMediaExplorer = false; resetControlsTimer() },
            onFileSelected = { uri ->
                if (uri.toString().endsWith(".srt", ignoreCase = true)) {
                    scope.launch(Dispatchers.IO) {
                        val subs = filePickerManager.readSubtitleFile(context, uri)
                        if (subs.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                externalSubtitles = subs
                                useExternalSubtitles = true
                                showMediaExplorer = false
                                resetControlsTimer()
                            }
                        }
                    }
                } else {
                    initialVideoUri.value = uri
                    showMediaExplorer = false
                    resetControlsTimer()
                }
            },
            title = OxigenStrings.get(appLang, "select_media"),
            allowedExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "jpg", "jpeg", "png", "webp", "gif", "srt")
        )

        if (showAboutDeveloper) {
            AlertDialog(
                onDismissRequest = { showAboutDeveloper = false; resetControlsTimer() },
                title = { Text(OxigenStrings.get(appLang, "about_dev_title")) },
                text = { Text(OxigenStrings.get(appLang, "about_dev_text")) },
                confirmButton = {
                    Button(onClick = { showAboutDeveloper = false; resetControlsTimer() }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(appLang, "done_btn")) }
                }
            )
        }

        updateInfo?.let { info ->
            UpdateDialog(
                updateInfo = info,
                onUpdate = {
                    scope.launch { updateManager.downloadAndInstall(info) }
                    updateInfo = null
                },
                onDismiss = { updateInfo = null }
            )
        }
    }
}

@Composable
fun PlayerControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    isSelected: Boolean = false,
    selectedColor: Color = Color.Red.copy(0.7f)
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(IntrinsicSize.Min)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(if (isSelected) selectedColor else Color.DarkGray.copy(alpha = 0.5f), CircleShape)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .tvFocusable(isCircle = true)
        ) {
            Icon(icon, label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
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
