// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.PictureInPictureParams
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.SubtitleView
import com.oxigenplayer.app.ui.PlayerDialogActions
import com.oxigenplayer.app.ui.PlayerDialogState
import com.oxigenplayer.app.ui.PlayerDialogsOverlay
import com.oxigenplayer.app.ui.ProfessionalPlaybackDiagnostics
import com.oxigenplayer.app.ui.SubtitleSyncPanel
import com.oxigenplayer.app.ui.theme.OxigenPlayerTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private var pipModeListener: ((Boolean) -> Unit)? = null
    internal var stopPlaybackOnExit: (() -> Unit)? = null
    private var isPlaybackActive: Boolean = false
    private var pipSourceRect: Rect = Rect()
    private var pipAspectRatio: Float? = null
    private val preferences: PreferencesManager
        get() = (application as OxigenApp).prefs

    override fun attachBaseContext(newBase: Context) {
        // Activity attachment happens on the main thread: read only the ordinary
        // language preference here and never initialize Android Keystore.
        val platformLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = newBase.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) null else locales[0].toLanguageTag().substringBefore('-')
        } else null
        val lang = platformLanguage
            ?.takeIf { it in PreferencesManager.SUPPORTED_APP_LANGUAGES }
            ?: PreferencesManager.readStoredLanguage(newBase)
        val config = Configuration(newBase.resources.configuration)
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme (Theme.OxigenPlayer.Splash) paints the branded splash as the window
        // background. Switch to the steady-state theme immediately so the window background does
        // not linger behind the Compose content after the first frame is drawn.
        setTheme(R.style.Theme_OxigenPlayer)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Cache device type once. Android TV dialogs/card navigation use this process-stable
        // value instead of repeating PackageManager/UiModeManager work during composition.
        val televisionMode = isTelevisionDevice(this)

        // Ascunde automat barele de sistem (status bar + navigation bar)
        // pe telefoane și tablete (nu pe Android TV).
        if (!televisionMode) {
            hideSystemBars()
        }

        setupBackPressHandler()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        val lowRamMode = DevicePerformance.isLowRamDevice(this)
        setContent {
            CompositionLocalProvider(
                LocalLowRamMode provides lowRamMode,
                LocalTelevisionMode provides televisionMode,
            ) {
                OxigenPlayerTheme {
                    VideoPlayerScreen(
                    vm = playerViewModel,
                    onPipListenerReady = { listener -> pipModeListener = listener },
                    onPlaybackActiveChanged = { active ->
                        if (isPlaybackActive != active) {
                            isPlaybackActive = active
                            updatePictureInPictureConfiguration()
                        }
                    },
                    onVideoBoundsChanged = { bounds ->
                        if (pipSourceRect != bounds) {
                            pipSourceRect = bounds
                            updatePictureInPictureConfiguration()
                        }
                    },
                    onVideoAspectRatioChanged = { aspectRatio ->
                        if (pipAspectRatio != aspectRatio) {
                            pipAspectRatio = aspectRatio
                            updatePictureInPictureConfiguration()
                        }
                    },
                    onKeepScreenOnChanged = { keepScreenOn ->
                        if (keepScreenOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    },
                    )
                }
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var backPressCount = 0
            private var lastBackTime = 0L

            override fun handleOnBackPressed() {
                val state = playerViewModel.uiState.value
                if (state.activeDialog != null) {
                    playerViewModel.dismissDialog()
                    return
                }
                if (playerViewModel.subtitleSyncState.value.isAdjusting) {
                    playerViewModel.closeSubtitleSync()
                    return
                }
                if (state.hasMedia && (state.showControls || state.showOnlyTopBar ||
                        state.showOnlyBottomBar || state.showOnlyPlayButton)) {
                    playerViewModel.hideControls()
                    return
                }
                if (isTelevisionDevice(this@MainActivity)) {
                    playerViewModel.persistPlaybackPosition(force = true)
                    stopPlaybackOnExit?.invoke()
                    stopService(Intent(this@MainActivity, PlaybackService::class.java))
                    finish()
                    return
                }
                val currentTime = android.os.SystemClock.elapsedRealtime()
                if (currentTime - lastBackTime > 2000) {
                    backPressCount = 0
                }

                backPressCount++
                lastBackTime = currentTime

                val language = preferences.getAppLanguage()
                when (backPressCount) {
                    1 -> {
                        Toast.makeText(
                            this@MainActivity,
                            OxigenStrings.get(language, "press_back_again"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    2 -> {
                        Toast.makeText(
                            this@MainActivity,
                            OxigenStrings.get(language, "press_again_to_close_total"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    else -> {
                        stopService(Intent(this@MainActivity, PlaybackService::class.java))
                        finishAndRemoveTask()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Reaplicăm ascunderea barelor de sistem la revenirea în activitate
        if (!isTelevisionDevice(this)) {
            hideSystemBars()
        }
    }

    /**
     * The bars come back whenever another window takes focus: a Compose dialog, the keyboard,
     * the notification shade or a permission prompt. onResume does not run after those, so
     * without this the bars stayed on screen for the rest of the session on phones and tablets.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isTelevisionDevice(this)) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.hideSystemBarsImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val request = validateExternalPlaybackIntent(intent)
        // Do not retain an ACTION_VIEW that could be replayed by a later recreation.
        intent.action = null
        if (request == null) {
            val language = preferences.getAppLanguage()
            Toast.makeText(
                this,
                OxigenStrings.get(language, "external_media_not_allowed"),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val persisted = replacePersistedVideoPermission(
            request.uri,
            request.canPersistPermission,
        )
        if (request.uri.scheme.equals("content", ignoreCase = true) && !persisted) {
            showSessionOnlyFileAccessMessage()
        }
        playerViewModel.selectMedia(
            request.uri,
            autoPlay = true,
            mimeType = request.mimeType,
        )
    }

    private fun validateExternalPlaybackIntent(intent: Intent): ExternalMediaRequest? {
        val uri = intent.data ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> {
                val isContent = uri.scheme?.lowercase(Locale.ROOT) == "content"
                val carriesReadGrant = !isContent || (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val hasReadGrant = !isContent || checkUriPermission(
                    uri,
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
                val mimeTypes = buildList {
                    intent.type?.let(::add)
                    runCatching { contentResolver.getType(uri) }.getOrNull()?.let(::add)
                }.distinct()

                // Another app must not be able to point the player at this app's own private
                // storage (preferences, keystore blobs, cached updates) through a file:// Uri.
                val outsidePrivateStorage = isContent || !isInsideAppPrivateStorage(uri)
                val allowed =
                    outsidePrivateStorage &&
                        carriesReadGrant &&
                        hasReadGrant &&
                        mimeTypes.isNotEmpty() &&
                        mimeTypes.all(::isSupportedPlaybackMimeType)
                if (!allowed) null else ExternalMediaRequest(
                    uri = uri,
                    mimeType = mimeTypes.firstOrNull()?.normalizedMimeType(),
                    canPersistPermission =
                        isContent && (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0),
                )
            }
            "http", "https", "rtsp", "rtsps" -> validateExternalRemotePlaybackIntent(intent)
            "oxigenplayer" -> validateExternalPlayerDeepLink(intent)
            else -> null
        }
    }

    private fun isInsideAppPrivateStorage(uri: Uri): Boolean {
        val path = uri.path ?: return true
        val canonical = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return true
        val privateRoots = listOfNotNull(
            applicationInfo.dataDir,
            filesDir.parent,
            runCatching { java.io.File(applicationInfo.dataDir).canonicalPath }.getOrNull(),
            getExternalFilesDir(null)?.parentFile?.absolutePath,
        ).distinct()
        return isPathInsideAny(canonical, privateRoots)
    }

    override fun onStop() {
        playerViewModel.persistPlaybackPosition(force = true)
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pipModeListener = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // PictureInPictureParams exists from API 26; Android 7 simply has no PiP here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            isPlaybackActive
        ) {
            try {
                enterPictureInPictureMode(buildPictureInPictureParams(autoEnter = false))
            } catch (e: IllegalStateException) {
                Log.w("MainActivity", "Cannot enter PiP", e)
            }
        }
    }

    private fun updatePictureInPictureConfiguration() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return
        }
        try {
            setPictureInPictureParams(buildPictureInPictureParams(autoEnter = isPlaybackActive))
        } catch (exception: IllegalStateException) {
            Log.w("MainActivity", "Cannot update PiP configuration", exception)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        // A fixed 16:9 window letterboxed every 2.39:1 film and every 4:3 recording. Follow the
        // decoded video instead, clamped to the range the platform accepts.
        val (numerator, denominator) = pictureInPictureRational(pipAspectRatio)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(numerator, denominator))
        if (!pipSourceRect.isEmpty) {
            builder.setSourceRectHint(pipSourceRect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeListener?.invoke(isInPictureInPictureMode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    vm: PlayerViewModel,
    onPipListenerReady: (((Boolean) -> Unit)) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    onVideoBoundsChanged: (Rect) -> Unit,
    onVideoAspectRatioChanged: (Float?) -> Unit,
    onKeepScreenOnChanged: (Boolean) -> Unit,
) {
    val uiState by vm.uiState.collectLifecycleAware()
    // Held as a State (not delegated with `by`) on purpose: the playback position changes every
    // 250 ms, and reading it eagerly during composition would subscribe this whole screen scope to
    // it and recompose the entire control tree 4x/second. It is read lazily inside the bottom bar's
    // currentPosition lambda instead, which rememberTimelineTicker samples in a coroutine.
    val currentPosState = vm.currentPosition.collectLifecycleAware()
    val bufferedPosState = vm.bufferedPosition.collectLifecycleAware()
    val subtitleState by vm.subtitleState.collectLifecycleAware()
    val autoSyncState by vm.autoSyncState.collectLifecycleAware()
    val subtitleSyncState by vm.subtitleSyncState.collectLifecycleAware()
    val isTranslatingAll by vm.isTranslatingAll.collectLifecycleAware()
    val translatedCount by vm.translatedCount.collectLifecycleAware()
    val selectedMediaUri by vm.selectedMediaUri.collectLifecycleAware()
    val selectedMediaMimeType by vm.selectedMediaMimeType.collectLifecycleAware()
    val mediaSelectionRevision by vm.mediaSelectionRevision.collectLifecycleAware()

    val context = LocalContext.current
    val lowRamMode = LocalLowRamMode.current
    val televisionMode = LocalTelevisionMode.current
    val appLang = remember(vm) { vm.prefs.getAppLanguage() }
    val scope = rememberCoroutineScope()
    var videoQualityProfile by rememberSaveable {
        mutableStateOf(vm.prefs.getVideoQualityProfile())
    }
    var matchContentFrameRate by rememberSaveable {
        mutableStateOf(vm.prefs.shouldMatchContentFrameRate())
    }
    var audioOffloadEnabled by rememberSaveable {
        mutableStateOf(vm.prefs.shouldAudioOffload())
    }

    var audioDelayMs by rememberSaveable {
        mutableStateOf(vm.prefs.getAudioDelayMs())
    }

    var preferredAudioLanguage by rememberSaveable {
        mutableStateOf(vm.prefs.getPreferredAudioLanguage())
    }
    var repeatCurrentMedia by rememberSaveable {
        mutableStateOf(vm.prefs.shouldRepeatCurrentMedia())
    }
    var showDiagnostics by rememberSaveable {
        mutableStateOf(vm.prefs.areDiagnosticsVisible())
    }
    var skipSilenceEnabled by rememberSaveable {
        mutableStateOf(vm.prefs.shouldSkipSilence())
    }
    var videoResizeMode by rememberSaveable {
        mutableStateOf(vm.prefs.getVideoResizeMode())
    }
    var videoAspectRatioOverride by rememberSaveable {
        mutableStateOf(vm.prefs.getVideoAspectRatioOverride())
    }
    var videoZoom by remember { mutableFloatStateOf(vm.prefs.getVideoZoom()) }
    var videoPanX by remember { mutableFloatStateOf(vm.prefs.getVideoPanX()) }
    var videoPanY by remember { mutableFloatStateOf(vm.prefs.getVideoPanY()) }
    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }
    var sleepTimerDeadlineMs by rememberSaveable { mutableLongStateOf(0L) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }

    val updateManager = remember(context.applicationContext) {
        UpdateManager(context.applicationContext)
    }

    // ─── 1. MediaController Initialization (HIGH-03) ─────────────────────────
    var player by remember { mutableStateOf<Player?>(null) }
    var controllerConnectionError by remember { mutableStateOf<String?>(null) }
    var controllerGeneration by remember { mutableIntStateOf(0) }

    val sessionToken = remember {
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }

    DisposableEffect(sessionToken, controllerGeneration) {
        var disposed = false
        var connectedController: MediaController? = null
        val controllerListener = object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (disposed) return
                if (player === controller) player = null
                connectedController = null
                controllerConnectionError = OxigenStrings.get(
                    appLang,
                    "playback_service_unavailable",
                )
                // Rebind to a restarted service instead of keeping a stale controller.
                controllerGeneration++
            }
        }
        val controllerFuture = MediaController.Builder(context, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture.addListener({
            if (disposed) return@addListener
            try {
                val controller = controllerFuture.get()
                connectedController = controller
                val snapshot = controller.toPlaybackSnapshot()
                snapshot.mediaUri?.let(vm::adoptSessionMedia)
                vm.onPlaybackSnapshot(snapshot)
                onPlaybackActiveChanged(snapshot.shouldKeepScreenOn)
                onVideoAspectRatioChanged(snapshot.displayAspectRatio())
                controllerConnectionError = null
                player = controller
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to connect to MediaController", e)
                if (!disposed) {
                    player = null
                    controllerConnectionError = e.localizedMessage
                        ?.takeIf(String::isNotBlank)
                        ?: OxigenStrings.get(appLang, "playback_service_unavailable")
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onPipListenerReady { isPip -> vm.setPipMode(isPip) }
        onDispose {
            disposed = true
            if (player === connectedController) player = null
            MediaController.releaseFuture(controllerFuture)
        }
    }

    LaunchedEffect(uiState.shouldKeepScreenOn) {
        onKeepScreenOnChanged(uiState.shouldKeepScreenOn)
    }
    DisposableEffect(Unit) {
        onDispose { onKeepScreenOnChanged(false) }
    }

    val filePicker = remember { FilePickerManager() }
    val onVideoSelected: (Uri) -> Unit = { uri ->
        val persisted = context.replacePersistedVideoPermission(uri, canPersist = true)
        if (!persisted) {
            context.showSessionOnlyFileAccessMessage()
        }
        vm.selectMedia(
            uri,
            autoPlay = true,
            mimeType = context.contentResolver.getType(uri)?.normalizedMimeType(),
        )
    }
    val documentMediaPickerLauncher = filePicker.rememberVideoPickerLauncher(onVideoSelected)
    val visualVideoPickerLauncher = filePicker.rememberVisualVideoPickerLauncher(onVideoSelected)
    val videoContentPickerLauncher = filePicker.rememberVideoContentPickerLauncher(onVideoSelected)
    val isTelevision = remember(context) { isTelevisionDevice(context) }
    val openSystemVideoPicker = remember(
        context,
        isTelevision,
        documentMediaPickerLauncher,
        visualVideoPickerLauncher,
        videoContentPickerLauncher,
    ) {
        val launchVideoContentFallback = {
            try {
                videoContentPickerLauncher.launch("video/*")
            } catch (error: ActivityNotFoundException) {
                context.showMediaPickerUnavailableMessage(error)
            } catch (error: SecurityException) {
                context.showMediaPickerUnavailableMessage(error)
            }
        }

        val launchVisualVideoPicker = {
            try {
                visualVideoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } catch (error: ActivityNotFoundException) {
                Log.w("MainActivity", "Photo picker is unavailable; trying GET_CONTENT", error)
                launchVideoContentFallback()
            } catch (error: SecurityException) {
                Log.w("MainActivity", "Photo picker launch was rejected; trying GET_CONTENT", error)
                launchVideoContentFallback()
            }
        }

        {
            if (isTelevision) {
                // Avoid the Android TV DocumentsUI stub that only displays
                // "No application can perform this action".
                launchVisualVideoPicker()
            } else {
                try {
                    documentMediaPickerLauncher.launch(VIDEO_MIME_TYPES)
                } catch (error: ActivityNotFoundException) {
                    Log.w("MainActivity", "Document picker is unavailable; trying photo picker", error)
                    launchVisualVideoPicker()
                } catch (error: SecurityException) {
                    Log.w("MainActivity", "Document picker launch was rejected; trying photo picker", error)
                    launchVisualVideoPicker()
                }
            }
        }
    }

    // The media notification carries the transport controls on the lock screen and in the shade.
    // On Android 13+ it is silently dropped unless POST_NOTIFICATIONS was granted, so ask once
    // the first item is actually loaded rather than on a cold launch with an empty player.
    var didRequestNotificationPermission by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(uiState.hasMedia, didRequestNotificationPermission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (!uiState.hasMedia || didRequestNotificationPermission) return@LaunchedEffect
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        didRequestNotificationPermission = true
        if (!alreadyGranted) {
            runCatching {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure { error ->
                Log.w("MainActivity", "Notification permission request was rejected", error)
            }
        }
    }

    var localMediaSnapshot by remember { mutableStateOf(LocalMediaSnapshot()) }
    var localMediaAccess by remember { mutableStateOf(LocalMediaLibrary.access(context)) }
    var isLocalMediaLoading by remember { mutableStateOf(false) }
    var isLocalMediaPermissionRequired by remember {
        mutableStateOf(!localMediaAccess.hasAnyAccess)
    }
    var didLocalMediaLoadFail by remember { mutableStateOf(false) }
    var localMediaRevision by remember { mutableIntStateOf(0) }
    var localMediaRefreshPending by remember { mutableStateOf(false) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        localMediaAccess = LocalMediaLibrary.access(context)
        isLocalMediaPermissionRequired = !localMediaAccess.hasAnyAccess
        didLocalMediaLoadFail = false
        if (localMediaAccess.hasAnyAccess) {
            LocalMediaLibrary.invalidateMediaCache()
            localMediaRefreshPending = true
            localMediaRevision++
        }
    }
    val requestLocalMediaAccess = remember(mediaPermissionLauncher, context) {
        {
            try {
                mediaPermissionLauncher.launch(LocalMediaLibrary.requiredPermissions())
            } catch (error: SecurityException) {
                Log.e("MainActivity", "Media permission request was rejected", error)
                localMediaAccess = LocalMediaLibrary.access(context)
                isLocalMediaPermissionRequired = !localMediaAccess.hasAnyAccess
                didLocalMediaLoadFail = true
            }
        }
    }
    val openMediaBrowser = remember(vm, context, requestLocalMediaAccess) {
        {
            localMediaAccess = LocalMediaLibrary.access(context)
            isLocalMediaPermissionRequired = !localMediaAccess.hasAnyAccess
            didLocalMediaLoadFail = false
            vm.showDialog(DialogType.MediaBrowser)
            if (localMediaAccess.hasAnyAccess) {
                localMediaRevision++
            } else {
                requestLocalMediaAccess()
            }
            Unit
        }
    }

    LaunchedEffect(uiState.activeDialog, localMediaRevision) {
        if (uiState.activeDialog != DialogType.MediaBrowser) return@LaunchedEffect

        localMediaAccess = LocalMediaLibrary.access(context)
        isLocalMediaPermissionRequired = !localMediaAccess.hasAnyAccess
        if (!localMediaAccess.hasAnyAccess) {
            LocalMediaLibrary.invalidateMediaCache()
            localMediaSnapshot = LocalMediaSnapshot()
            isLocalMediaLoading = false
            return@LaunchedEffect
        }

        // Fast open path: reuse the in-process snapshot on a normal reopen instead of
        // re-scanning MediaStore. Expiry, permission changes and resume/refresh
        // trigger a fresh scan; partial access is never cached.
        val cachedSnapshot = if (!localMediaRefreshPending) {
            LocalMediaLibrary.cachedSnapshot(localMediaAccess)
        } else {
            null
        }
        if (cachedSnapshot != null) {
            localMediaSnapshot = cachedSnapshot
            didLocalMediaLoadFail = false
            isLocalMediaLoading = false
            localMediaRefreshPending = false
            return@LaunchedEffect
        }

        val forceRefresh = localMediaRefreshPending
        localMediaRefreshPending = false
        isLocalMediaLoading = true
        didLocalMediaLoadFail = false
        try {
            localMediaSnapshot = if (forceRefresh) {
                LocalMediaLibrary.query(context, localMediaAccess)
            } else {
                // If application prewarm is still scanning, join that single in-flight scan
                // instead of launching a second MediaStore query from the dialog.
                LocalMediaLibrary.ensureSnapshot(context, localMediaAccess)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("MainActivity", "Unable to load local media", error)
            didLocalMediaLoadFail = true
        } finally {
            isLocalMediaLoading = false
        }
    }
    var subtitleLoadJob by remember { mutableStateOf<Job?>(null) }
    val subtitlePickerLauncher = filePicker.rememberSubtitlePickerLauncher { uri ->
        subtitleLoadJob?.cancel()
        subtitleLoadJob = scope.launch {
            when (val result = filePicker.readSubtitleFileResult(context, uri)) {
                is SubtitleFileResult.Success<List<SubtitleEntry>> -> {
                    ensureActive()
                    vm.subtitleEngine.setSubtitles(result.value)
                    player?.let { current ->
                        current.trackSelectionParameters = current.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                    vm.setUseExternalSubtitles(true)
                    // Returning from the file picker to a still-open tracks dialog gave no
                    // sign that the subtitle had loaded; go straight back to the film.
                    vm.dismissDialog()
                }
                is SubtitleFileResult.Failure -> {
                    Toast.makeText(
                        context,
                        subtitleFileErrorMessage(result.error, appLang),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { subtitleLoadJob?.cancel() }
    }

    var pendingSrtContent by remember { mutableStateOf<String?>(null) }
    val subtitleSaverLauncher = filePicker.rememberSubtitleSaverLauncher { uri ->
        val content = pendingSrtContent
        pendingSrtContent = null
        if (uri != null && content != null) {
            scope.launch {
                when (val result = filePicker.writeSubtitlesToUriResult(context, uri, content)) {
                    is SubtitleFileResult.Success<Unit> -> Toast.makeText(
                        context,
                        OxigenStrings.get(appLang, "subtitle_saved_success"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    is SubtitleFileResult.Failure -> Toast.makeText(
                        context,
                        subtitleFileErrorMessage(result.error, appLang),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val currentPlayer = player
    DisposableEffect(currentPlayer, context) {
        val activity = context as? MainActivity
        activity?.stopPlaybackOnExit = {
            currentPlayer?.pause()
            currentPlayer?.stop()
        }
        onDispose { activity?.stopPlaybackOnExit = null }
    }
    var internalBitmapCues by remember { mutableStateOf<List<Cue>>(emptyList()) }

    LaunchedEffect(currentPlayer, videoQualityProfile, repeatCurrentMedia) {
        currentPlayer?.let { activePlayer ->
            applyVideoQualityProfile(activePlayer, videoQualityProfile)
            if (activePlayer.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
                activePlayer.repeatMode = if (repeatCurrentMedia) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
            }
        }
    }

    // Weak TVs: explain a stuttering picture instead of leaving the viewer guessing.
    LaunchedEffect(Unit) {
        DecoderLoadMonitor.overloaded.collect {
            Toast.makeText(
                context,
                OxigenStrings.get(appLang, "decoder_overloaded"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(sleepTimerDeadlineMs, currentPlayer) {
        if (sleepTimerDeadlineMs <= 0L) {
            sleepTimerMinutes = 0
            return@LaunchedEffect
        }
        val initialRemainingMs = sleepTimerDeadlineMs - SystemClock.elapsedRealtime()
        if (initialRemainingMs <= 0L || initialRemainingMs > 12L * 60L * 60_000L) {
            sleepTimerDeadlineMs = 0L
            sleepTimerMinutes = 0
            return@LaunchedEffect
        }
        while (isActive) {
            val remaining = sleepTimerRemainingMinutes(SystemClock.elapsedRealtime(), sleepTimerDeadlineMs)
            sleepTimerMinutes = remaining
            if (remaining <= 0) {
                currentPlayer?.pause()
                sleepTimerDeadlineMs = 0L
                Toast.makeText(
                    context,
                    OxigenStrings.get(appLang, "sleep_timer_finished"),
                    Toast.LENGTH_SHORT,
                ).show()
                break
            }
            delay(1_000L)
        }
    }

    // ─── 2. Decoder-friendly seek commit ─────────────────────────────────────
    // Scrubbing updates only the UI preview. The decoder receives one final seek
    // after touch release or the TV remote debounce, avoiding repeated flush/rebuffer cycles.
    LaunchedEffect(uiState.isSeeking, uiState.seekPosition, currentPlayer) {
        val p = currentPlayer ?: return@LaunchedEffect
        if (!uiState.isSeeking && uiState.seekPosition >= 0L) {
            val finalSeekPosition = uiState.seekPosition
            p.seekTo(finalSeekPosition)
            vm.consumeFinalSeek(finalSeekPosition)
        }
    }

    val currentVideoUri = remember(selectedMediaUri) {
        selectedMediaUri?.let(Uri::parse)
    }

    // Per-title picture memory: reload framing when the media changes, persist it on every change.
    LaunchedEffect(currentVideoUri) {
        val key = currentVideoUri?.toString().orEmpty()
        val saved = vm.prefs.getTitlePictureSettings(key)
        videoResizeMode = saved?.resizeMode ?: vm.prefs.getVideoResizeMode()
        videoAspectRatioOverride = saved?.aspectOverride ?: vm.prefs.getVideoAspectRatioOverride()
        videoZoom = saved?.zoom ?: vm.prefs.getVideoZoom()
        videoPanX = saved?.panX ?: vm.prefs.getVideoPanX()
        videoPanY = saved?.panY ?: vm.prefs.getVideoPanY()
    }
    LaunchedEffect(currentVideoUri, videoResizeMode, videoAspectRatioOverride, videoZoom, videoPanX, videoPanY) {
        val key = currentVideoUri?.toString().orEmpty()
        if (key.isNotBlank()) {
            vm.prefs.saveTitlePictureSettings(
                key,
                PreferencesManager.TitlePictureSettings(videoResizeMode, videoAspectRatioOverride, videoZoom, videoPanX, videoPanY),
            )
        }
    }

    // ─── Focus Management ────────────────────────────────────────────────────
    val rootFocus = remember { FocusRequester() }
    val topBarFocus = remember { FocusRequester() }
    val mediaButtonFocus = remember { FocusRequester() }
    val playButtonFocus = remember { FocusRequester() }
    val sliderFocus = remember { FocusRequester() }
    var resumeFocusRevision by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) {
                val activeDialog = vm.uiState.value.activeDialog
                if (activeDialog == null) resumeFocusRevision++
                if (activeDialog == DialogType.MediaBrowser) {
                    localMediaRefreshPending = true
                    localMediaRevision++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isAnyControlVisible by remember {
        derivedStateOf {
            uiState.showControls || uiState.showOnlyTopBar ||
                    uiState.showOnlyPlayButton || uiState.showOnlyBottomBar
        }
    }
    val isOtherUiVisible = uiState.activeDialog != null
    val hasEnabledControlTarget by remember {
        derivedStateOf {
            when {
                uiState.isPipMode || !isAnyControlVisible -> false
                uiState.showControls || uiState.showOnlyTopBar -> true
                uiState.showOnlyPlayButton -> uiState.hasMedia
                uiState.showOnlyBottomBar -> {
                    uiState.hasMedia && uiState.isSeekable && uiState.duration > 0L
                }
                else -> false
            }
        }
    }
    val shouldUseRootFocus = !isOtherUiVisible &&
        !uiState.isPipMode &&
        !hasEnabledControlTarget

    LaunchedEffect(
        shouldUseRootFocus,
        resumeFocusRevision,
    ) {
        // Run after composition so the conditional root focus target is attached.
        if (shouldUseRootFocus) rootFocus.requestFocus()
    }

    LaunchedEffect(
        uiState.showControls,
        uiState.showOnlyTopBar,
        uiState.showOnlyBottomBar,
        uiState.showOnlyPlayButton,
        uiState.hasMedia,
        uiState.isSeekable,
        uiState.duration > 0L,
        uiState.lockedMode,
        uiState.isPipMode,
        isOtherUiVisible,
        shouldUseRootFocus,
        resumeFocusRevision,
        subtitleSyncState.isAdjusting,
    ) {
        // The sync panel owns focus while it is open; re-running on close hands it back.
        if (isOtherUiVisible || uiState.isPipMode || shouldUseRootFocus ||
            subtitleSyncState.isAdjusting
        ) {
            return@LaunchedEffect
        }

        when {
            !uiState.hasMedia -> mediaButtonFocus.requestFocus()
            uiState.showOnlyTopBar -> topBarFocus.requestFocus()
            uiState.showOnlyBottomBar && uiState.isSeekable && uiState.duration > 0L -> {
                sliderFocus.requestFocus()
            }
            uiState.showControls || uiState.showOnlyPlayButton -> playButtonFocus.requestFocus()
        }
    }

    // ─── 3. Side Effects ─────────────────────────────────────────────────────
    LaunchedEffect(
        currentVideoUri,
        selectedMediaMimeType,
        currentPlayer,
        mediaSelectionRevision,
    ) {
        val uri = currentVideoUri ?: return@LaunchedEffect
        val p = currentPlayer ?: return@LaunchedEffect
        val uriString = uri.toString()
        // Resolved before the selection is consumed, so a cancellation here restarts cleanly.
        // A MediaStore Uri ends in a numeric id; without the lookup the system media
        // notification and the TV "now playing" card showed that number as the title.
        val mediaTitle = try {
            MovieNameExtractor.extractDisplayTitleAsync(context, uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("MainActivity", "Unable to resolve the media title", error)
            ""
        }.ifBlank { uri.lastPathSegment ?: "Oxigen Player" }
        val forceReload = vm.consumeExplicitSelection(uri)
        if (!forceReload && p.currentPlaybackMediaUri() == uriString && p.mediaItemCount > 0) {
            vm.onPlaybackSnapshot(p.toPlaybackSnapshot())
            return@LaunchedEffect
        }

        val playWhenReady = vm.shouldPlayWhenReady()
        val resumePosition = vm.resumePositionFor(uri)
        internalBitmapCues = emptyList()
        vm.beginMediaLoad(uri)

        p.setMediaItem(
            MediaItem.Builder()
                .setMediaId(uriString)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(mediaTitle)
                        .setArtist("Oxigen Player")
                        .build()
                )
                .apply {
                    selectedMediaMimeType
                        ?.takeIf(::isSupportedPlaybackMimeType)
                        ?.let { setMimeType(it) }
                }
                .build(),
            resumePosition,
        )
        p.playWhenReady = playWhenReady
        p.prepare()
    }

    // Dialogs on TV are interaction-heavy; while one is open the position ticker behind it
    // is invisible work. Keep PiP and A-B loop enforcement active through dialogs.
    val shouldPollPosition = (lifecycleStarted &&
        (!isOtherUiVisible || uiState.abRepeat.phase == AbRepeatPhase.LOOPING)) || uiState.isPipMode
    LaunchedEffect(currentPlayer, shouldPollPosition) {
        val p = currentPlayer ?: return@LaunchedEffect
        if (!shouldPollPosition) return@LaunchedEffect
        while (isActive) {
            try {
                val state = vm.uiState.value
                if (!state.isSeeking && p.mediaItemCount > 0) {
                    vm.onPositionChanged(p.currentPosition, p.duration, p.bufferedPosition)
                    // Loop enforcement: once past point B the poller rewinds the loop to A.
                    abRepeatSeekTarget(vm.uiState.value.abRepeat, p.currentPosition)?.let { target ->
                        p.seekTo(target)
                    }
                }
                delay(
                    if (p.isPlaying) {
                        if (lowRamMode) 500L else 250L
                    } else 1_000L,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MainActivity", "Position polling failed", error)
                delay(1_000L)
            }
        }
    }

    val latestOnPlaybackActiveChanged by rememberUpdatedState(onPlaybackActiveChanged)
    val latestOnVideoAspectRatioChanged by rememberUpdatedState(onVideoAspectRatioChanged)
    DisposableEffect(currentPlayer) {
        val p = currentPlayer ?: return@DisposableEffect onDispose {}
        // Torrent-backed HTTP sources (TorrServer and friends) stall while pieces download,
        // which surfaces as transient I/O "Source error" failures. A bounded number of
        // automatic re-prepares keeps playback alive instead of showing the error overlay.
        var sourceRecoveryAttempts = 0
        fun publishSnapshot() {
            val snapshot = p.toPlaybackSnapshot()
            if (snapshot.phase == PlaybackPhase.READY) sourceRecoveryAttempts = 0
            snapshot.mediaUri?.let(vm::adoptSessionMedia)
            vm.onPlaybackSnapshot(snapshot)
            latestOnPlaybackActiveChanged(snapshot.shouldKeepScreenOn)
            videoAspectRatio = snapshot.displayAspectRatio()
            latestOnVideoAspectRatioChanged(snapshot.displayAspectRatio())
        }
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                internalBitmapCues = cueGroup.cues.filter { it.bitmap != null }
                val text = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString("\n")
                vm.subtitleEngine.onTrackTextReceived(text)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                publishSnapshot()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isRecoverableSourceErrorCode(error.errorCode) &&
                    sourceRecoveryAttempts < MAX_SOURCE_ERROR_RECOVERY_ATTEMPTS
                ) {
                    sourceRecoveryAttempts++
                    Log.w(
                        "MainActivity",
                        "Recovering from transient source error " +
                            "(${error.errorCodeName}), attempt $sourceRecoveryAttempts",
                    )
                    p.seekTo(p.currentPosition)
                    p.prepare()
                    return
                }
                publishSnapshot()
            }

            // Screen-side half of the video trace (the service side is PlaybackVideoDiagnostics):
            // shows whether this activity's surface actually reached the player.
            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                Log.i(VIDEO_TRACE_TAG, "activity surface size: ${width}x$height")
            }

            override fun onRenderedFirstFrame() {
                Log.i(VIDEO_TRACE_TAG, "activity saw first frame")
            }
        }
        Log.i(
            VIDEO_TRACE_TAG,
            "activity attached to player ${p.javaClass.simpleName}@${System.identityHashCode(p)} " +
                "state=${p.playbackState} item=${p.mediaItemCount}",
        )
        p.addListener(listener)
        publishSnapshot()
        onDispose {
            Log.i(VIDEO_TRACE_TAG, "activity detached from player @${System.identityHashCode(p)}")
            p.removeListener(listener)
        }
    }

    val currentTime = remember { mutableStateOf("") }
    LaunchedEffect(lifecycleStarted) {
        // The clock is only rendered in the top bar; ticking it while the activity is stopped
        // wakes the process for output nobody can see.
        if (!lifecycleStarted) return@LaunchedEffect
        // One formatter per collector (SimpleDateFormat is not thread-safe) and wakeups aligned
        // to the wall-clock minute: the display updates exactly when the minute changes, which
        // halves the wakeups of a fixed 30 s poll while staying more accurate.
        val clockFormat = java.text.SimpleDateFormat("HH:mm", Locale.US)
        while (isActive) {
            currentTime.value = clockFormat.format(java.util.Date())
            val now = System.currentTimeMillis()
            delay(60_000L - (now % 60_000L) + 100L)
        }
    }

    val onPanChange: (Float, Float) -> Unit = { x: Float, y: Float ->
        videoPanX = x
        videoPanY = y
        vm.prefs.saveVideoPanX(x)
        vm.prefs.saveVideoPanY(y)
        currentVideoUri?.let { uri ->
            vm.prefs.saveTitlePictureSettings(
                uri.toString(),
                PreferencesManager.TitlePictureSettings(
                    videoResizeMode, videoAspectRatioOverride, videoZoom, x, y,
                ),
            )
        }
    }

    val keyHandler = remember(currentPlayer, vm, openMediaBrowser, showDiagnostics) {
        { event: androidx.compose.ui.input.key.KeyEvent ->
            if (event.type == KeyEventType.KeyDown) {
                val keyCode = event.nativeKeyEvent.keyCode
                val currentState = vm.uiState.value
                // Back belongs to OnBackPressedDispatcher on every Android version.
                if (keyCode == KeyEvent.KEYCODE_BACK || currentState.activeDialog != null) {
                    false
                } else {
                    val p = currentPlayer
                    if (p != null) {
                        handleGlobalKeyEvent(
                            keyCode, p, vm,
                            repeatCount = event.nativeKeyEvent.repeatCount,
                            onShowMedia = openMediaBrowser,
                            onToggleDiagnostics = {
                                showDiagnostics = !showDiagnostics
                                vm.prefs.saveDiagnosticsVisible(showDiagnostics)
                            },
                            videoZoom = videoZoom,
                            videoPanX = videoPanX,
                            videoPanY = videoPanY,
                            onPanChange = onPanChange,
                            currentVideoUri = currentVideoUri,
                        )
                    } else false
                }
            } else false
        }
    }

    val previewKeyHandler = remember(vm) {
        { event: androidx.compose.ui.input.key.KeyEvent ->
            if (event.type == KeyEventType.KeyDown) {
                val keyCode = event.nativeKeyEvent.keyCode
                val state = vm.uiState.value

                if (state.activeDialog == null && keyCode != KeyEvent.KEYCODE_BACK) {
                    vm.startHideTimer()
                }
                if (state.activeDialog == null && state.isSeekable &&
                    state.lockedMode == LockedMode.BOTTOM_BAR_ONLY) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        vm.onSeekEvent(-TV_REMOTE_SEEK_STEP_MS)
                        true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        vm.onSeekEvent(TV_REMOTE_SEEK_STEP_MS)
                        true
                    } else false
                } else false
            } else false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onPreviewKeyEvent(previewKeyHandler)
            .onKeyEvent(keyHandler)
            .then(
                if (shouldUseRootFocus) {
                    Modifier.focusRequester(rootFocus).focusable()
                } else Modifier
            )
            .then(
                if (televisionMode) {
                    Modifier
                } else {
                    // TV navigation is D-pad driven. Avoid keeping two pointer gesture
                    // recognizers alive for the whole player on television devices.
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { vm.toggleControls() })
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = { vm.onSeekFinished() },
                                onDragCancel = { vm.onSeekFinished() },
                                onHorizontalDrag = { change, dragAmount ->
                                    val duration = vm.uiState.value.duration
                                    if (duration > 0) {
                                        val deltaMs = (dragAmount / size.width.toFloat()) * duration
                                        vm.onSeekEvent(deltaMs.toLong())
                                        if (change.pressed) change.consume()
                                    }
                                },
                            )
                        }
                },
            )
    ) {
        PlayerSurface(
            player = currentPlayer,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val (scaleX, scaleY) = videoResizeScale(
                        mode = videoResizeMode,
                        videoAspectRatio = videoAspectRatio,
                        containerWidth = size.width,
                        containerHeight = size.height,
                        aspectRatioOverride = videoAspectRatioOverride,
                    )
                    this.scaleX = scaleX * videoZoom
                    this.scaleY = scaleY * videoZoom
                    this.translationX = videoPanX * size.width
                    this.translationY = videoPanY * size.height
                }
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onVideoBoundsChanged(
                        Rect(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt(),
                        )
                    )
                }
        )

        if (showDiagnostics && currentPlayer != null && !uiState.isPipMode) {
            ProfessionalPlaybackDiagnostics(
                player = currentPlayer,
                appLang = appLang,
                qualityProfile = videoQualityProfile,
                sleepTimerMinutes = sleepTimerMinutes,
                resizeMode = videoResizeMode,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }

        if (!subtitleState.useExternal && subtitleState.isVisible && internalBitmapCues.isNotEmpty()) {
            AndroidView(
                factory = { viewContext ->
                    SubtitleView(viewContext).apply {
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(true)
                    }
                },
                update = { subtitleView -> subtitleView.setCues(internalBitmapCues) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (uiState.isLoading || (currentPlayer == null && controllerConnectionError == null)) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp
            )
        }

        controllerConnectionError?.takeIf { currentPlayer == null }?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    Button(onClick = {
                        controllerConnectionError = null
                        controllerGeneration++
                    }) {
                        Text(OxigenStrings.get(appLang, "retry"))
                    }
                }
            }
        }

        uiState.playbackError?.takeIf { !uiState.isPipMode }?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    // Sits below the centre play button, which used to cover the text.
                    .offset(y = 130.dp)
                    .padding(horizontal = 48.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }

        if (!uiState.isPipMode) {
            var videoTitle by remember { mutableStateOf("Oxigen Player") }
            LaunchedEffect(currentVideoUri) {
                videoTitle = currentVideoUri
                    ?.let { MovieNameExtractor.extractDisplayTitleAsync(context, it) }
                    ?.ifBlank { "Oxigen Player" }
                    ?: "Oxigen Player"
            }

            PlayerTopBar(
                isVisible = !isOtherUiVisible && (uiState.showControls || uiState.showOnlyTopBar),
                videoTitle = videoTitle,
                currentTime = currentTime.value,
                appLang = appLang,
                isSubtitleVisible = subtitleState.isVisible,
                topBarFocusRequester = topBarFocus,
                mediaButtonFocusRequester = mediaButtonFocus,
                onToggleSubtitle = { vm.updateSubtitleVisibility(!subtitleState.isVisible) },
                onShowMedia = openMediaBrowser,
                onShowTracks = { vm.showDialog(DialogType.Tracks) },
                onShowStream = { vm.showDialog(DialogType.OpenStream) },
                onShowSearch = { vm.showDialog(DialogType.SubtitleSearch) },
                onShowSettings = { vm.showDialog(DialogType.Settings) },
                onShowSync = { if (uiState.hasMedia) vm.openSubtitleSync() },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            PlayerCenterControls(
                isVisible = !isOtherUiVisible && (uiState.showControls || uiState.showOnlyPlayButton),
                isPlaying = uiState.playWhenReady &&
                    (uiState.playbackPhase == PlaybackPhase.BUFFERING || uiState.playbackPhase == PlaybackPhase.READY),
                isMediaSelected = uiState.hasMedia || currentVideoUri != null,
                appLang = appLang,
                playButtonFocus = playButtonFocus,
                onTogglePlay = {
                    currentPlayer?.let { p -> togglePlayback(p) }
                },
                modifier = Modifier.align(Alignment.Center)
            )

            PlayerBottomBar(
                isVisible = !isOtherUiVisible &&
                    (uiState.showControls || uiState.showOnlyBottomBar) && uiState.hasMedia,
                currentPosition = {
                    if (uiState.isSeeking && uiState.seekPosition >= 0L) uiState.seekPosition
                    else currentPosState.value
                },
                duration = uiState.duration,
                bufferedPosition = { bufferedPosState.value },
                isSeeking = uiState.isSeeking,
                isSeekable = uiState.isSeekable,
                isLive = uiState.isLive,
                appLang = appLang,
                sliderFocus = sliderFocus,
                abRepeatState = uiState.abRepeat,
                onAbRepeatClick = { vm.onAbRepeatPressed() },
                onValueChange = { newPos ->
                    vm.onManualSeek(newPos)
                    vm.startHideTimer()
                },
                onValueChangeFinished = {
                    vm.onSeekFinished()
                    vm.startHideTimer()
                },
                onSeekDelta = { delta ->
                    vm.onSeekEvent(delta)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (subtitleSyncState.isAdjusting && !isOtherUiVisible) {
                // Marks read the player directly: the polled position is up to 250 ms old,
                // which is larger than the error the calibration is meant to remove.
                val livePosition = { currentPlayer?.currentPosition ?: currentPosState.value }
                SubtitleSyncPanel(
                    appLang = appLang,
                    syncOffsetMs = subtitleSyncState.syncOffset,
                    autoSyncState = autoSyncState,
                    onNudge = { vm.nudgeSubtitleSync(it) },
                    onReset = { vm.updateSubtitleSyncOffset(0L) },
                    onClose = { vm.closeSubtitleSync() },
                    onStartAutoSync = { vm.startAutoSync() },
                    onMarkAudio = { vm.markAutoSyncAudio(livePosition()) },
                    onMarkSubtitle = { vm.markAutoSyncSubtitle(livePosition()) },
                    onApplyAutoSync = { vm.applyAutoSync() },
                    onCancelAutoSync = { vm.cancelAutoSync() },
                    // Bottom-right, just above the timeline, so it never covers the centre
                    // play button.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 32.dp, bottom = 112.dp),
                )
            }

        }

        val translatedText by vm.subtitleEngine.translatedText.collectLifecycleAware()
        PlayerSubtitleSurface(
            isVisible = subtitleState.isVisible && !(lowRamMode && isOtherUiVisible),
            showControls = (uiState.showControls || uiState.showOnlyBottomBar) && !uiState.isPipMode,
            text = translatedText,
            fontSize = subtitleState.fontSize,
            color = Color(subtitleState.textColorArgb),
            bgColor = Color(subtitleState.backgroundColorArgb),
            verticalOffset = subtitleState.verticalOffset,
            edgeStyle = subtitleState.subtitleEdgeStyle
        )

        val dialogActions = remember(
            vm,
            scope,
            currentPlayer,
            currentVideoUri,
            appLang,
            context,
            requestLocalMediaAccess,
            openSystemVideoPicker,
            videoQualityProfile,
            matchContentFrameRate,
            audioOffloadEnabled,
            preferredAudioLanguage,
            repeatCurrentMedia,
            showDiagnostics,
            sleepTimerMinutes,
            skipSilenceEnabled,
            videoResizeMode,
            videoAspectRatioOverride,
            videoZoom,
            videoPanX,
            videoPanY,
        ) {
            PlayerDialogActions(
                onDismiss = { vm.dismissDialog() },
                onRequestMediaAccess = requestLocalMediaAccess,
                onRefreshLocalMedia = {
                    localMediaAccess = LocalMediaLibrary.access(context)
                    isLocalMediaPermissionRequired = !localMediaAccess.hasAnyAccess
                    if (localMediaAccess.hasAnyAccess) {
                        LocalMediaLibrary.invalidateMediaCache()
                        localMediaRefreshPending = true
                        localMediaRevision++
                    } else {
                        requestLocalMediaAccess()
                    }
                },
                onOpenSystemMediaPicker = {
                    vm.dismissDialog()
                    openSystemVideoPicker()
                },
                onLocalMediaSelected = { media ->
                    if (media.kind == LocalMediaKind.SUBTITLE) {
                        scope.launch {
                            when (val result = filePicker.readSubtitleFileResult(context, media.uri)) {
                                is SubtitleFileResult.Success<List<SubtitleEntry>> -> {
                                    vm.subtitleEngine.setSubtitles(result.value)
                                    vm.setUseExternalSubtitles(true)
                                }
                                is SubtitleFileResult.Failure -> {
                                    Toast.makeText(
                                        context,
                                        subtitleFileErrorMessage(result.error, appLang),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    } else {
                        context.replacePersistedVideoPermission(media.uri, canPersist = false)
                        vm.selectMedia(
                            media.uri,
                            autoPlay = true,
                            mimeType = media.mimeType?.normalizedMimeType(),
                        )
                    }
                    vm.dismissDialog()
                },
                onCheckUpdate = {
                    scope.launch {
                        val message = when (val result = updateManager.checkForUpdate()) {
                            is UpdateCheckResult.Available -> {
                                vm.showDialog(DialogType.Update(result.info))
                                null
                            }
                            UpdateCheckResult.UpToDate -> OxigenStrings.get(appLang, "no_update")
                            UpdateCheckResult.Disabled -> OxigenStrings.get(appLang, "update_disabled")
                            UpdateCheckResult.NetworkError -> OxigenStrings.get(appLang, "update_network_error")
                            UpdateCheckResult.InvalidMetadata -> OxigenStrings.get(appLang, "update_invalid_metadata")
                        }
                        message?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onTranslationToggle = { vm.setTranslationEnabled(it) },
                onSourceChange = { vm.setTranslationSource(it) },
                onTranslationProviderConfigChange = { vm.setTranslationProviderConfig(it) },
                onSourceLangChange = { vm.setSourceLang(it) },
                onFontSizeChange = { vm.updateFontSize(it) },
                onVerticalOffsetChange = { vm.updateVerticalOffset(it) },
                onSyncOffsetChange = { vm.updateSubtitleSyncOffset(it) },
                onStartSubtitleSyncAdjust = { vm.startSubtitleSyncAdjust() },
                onCancelSubtitleSyncAdjust = { vm.cancelSubtitleSyncAdjust() },
                onStartAutoSync = { vm.openSubtitleSync() },
                onMarkAutoSyncAudio = { vm.markAutoSyncAudio() },
                onMarkAutoSyncSubtitle = { vm.markAutoSyncSubtitle() },
                onApplyAutoSync = { vm.applyAutoSync() },
                onCancelAutoSync = { vm.cancelAutoSync() },
                onColorChange = { vm.updateSubtitleColor(it.toArgb()) },
                onBgColorChange = { vm.updateSubtitleBackgroundColor(it.toArgb()) },
                onEdgeStyleChange = { vm.updateSubtitleEdgeStyle(it) },
                onTargetLangChange = { vm.setTargetLang(it) },
                onPlaybackSpeedChange = { speed ->
                    vm.updatePlaybackSpeed(speed)
                    currentPlayer?.let { activePlayer ->
                        if (activePlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                            activePlayer.playbackParameters = PlaybackParameters(speed)
                        }
                    }
                },
                onVideoQualityProfileChange = { profile ->
                    videoQualityProfile = profile
                    vm.prefs.saveVideoQualityProfile(profile)
                    currentPlayer?.let { applyVideoQualityProfile(it, profile) }
                },
                onMatchContentFrameRateChange = { enabled ->
                    matchContentFrameRate = enabled
                    vm.prefs.saveMatchContentFrameRate(enabled)
                    (currentPlayer as? MediaController)?.let { controller ->
                        val command = PlaybackSessionCommands.setMatchContentFrameRate
                        if (controller.isSessionCommandAvailable(command)) {
                            controller.sendCustomCommand(
                                command,
                                Bundle().apply {
                                    putBoolean(PlaybackSessionCommands.ARG_ENABLED, enabled)
                                },
                            )
                        }
                    }
                },
                onAudioOffloadChange = { enabled ->
                    audioOffloadEnabled = enabled
                    vm.prefs.saveAudioOffload(enabled)
                    currentPlayer?.let { current ->
                        current.trackSelectionParameters = current.trackSelectionParameters
                            .buildUpon()
                            .setAudioOffloadPreferences(
                                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                                    .setAudioOffloadMode(
                                        if (enabled) {
                                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                                        } else {
                                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                                        },
                                    )
                                    .setIsSpeedChangeSupportRequired(false)
                                    .build(),
                            )
                            .build()
                    }
                },
                onAudioDelayChange = { ms ->
                    val delayMs = clampAudioDelayMs(ms)
                    audioDelayMs = delayMs
                    vm.prefs.saveAudioDelayMs(delayMs)
                    // The playback service owns the audio sink; it applies the offset live.
                    (currentPlayer as? MediaController)?.let { controller ->
                        val command = PlaybackSessionCommands.setAudioDelay
                        if (controller.isSessionCommandAvailable(command)) {
                            controller.sendCustomCommand(
                                command,
                                Bundle().apply {
                                    putInt(PlaybackSessionCommands.ARG_DELAY_MS, delayMs)
                                },
                            )
                        }
                    }
                },
                onPreferredAudioLanguageChange = { lang ->
                    preferredAudioLanguage = lang
                    vm.prefs.savePreferredAudioLanguage(lang)
                    currentPlayer?.let { current ->
                        val langs = listOfNotNull(
                            lang.takeIf { it.isNotBlank() },
                            java.util.Locale.getDefault().language,
                            "ro",
                            "en",
                        )
                            .map { it.lowercase(java.util.Locale.ROOT) }
                            .filter { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
                            .distinct()
                            .toTypedArray()
                        current.trackSelectionParameters = current.trackSelectionParameters
                            .buildUpon()
                            .setPreferredAudioLanguages(*langs)
                            .build()
                    }
                },
                onRepeatCurrentMediaChange = { enabled ->
                    repeatCurrentMedia = enabled
                    vm.prefs.saveRepeatCurrentMedia(enabled)
                    currentPlayer?.let { activePlayer ->
                        if (activePlayer.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
                            activePlayer.repeatMode = if (enabled) {
                                Player.REPEAT_MODE_ONE
                            } else {
                                Player.REPEAT_MODE_OFF
                            }
                        }
                    }
                },
                onShowDiagnosticsChange = { enabled ->
                    showDiagnostics = enabled
                    vm.prefs.saveDiagnosticsVisible(enabled)
                },
                onSleepTimerMinutesChange = { minutes ->
                    sleepTimerDeadlineMs = sleepTimerDeadline(SystemClock.elapsedRealtime(), minutes)
                    sleepTimerMinutes = minutes.coerceAtLeast(0)
                },
                onSkipSilenceChange = { enabled ->
                    skipSilenceEnabled = enabled
                    vm.prefs.saveSkipSilence(enabled)
                    (currentPlayer as? MediaController)?.let { controller ->
                        val command = PlaybackSessionCommands.setSkipSilence
                        if (controller.isSessionCommandAvailable(command)) {
                            controller.sendCustomCommand(
                                command,
                                Bundle().apply {
                                    putBoolean(PlaybackSessionCommands.ARG_ENABLED, enabled)
                                },
                            )
                        }
                    }
                },
                onVideoResizeModeChange = { mode ->
                    videoResizeMode = mode
                    vm.prefs.saveVideoResizeMode(mode)
                },
                onAspectRatioOverrideChange = { override ->
                    videoAspectRatioOverride = override
                    vm.prefs.saveVideoAspectRatioOverride(override)
                },
                onZoomChange = { zoom ->
                    videoZoom = zoom
                    vm.prefs.saveVideoZoom(zoom)
                },
                onPanChange = onPanChange,
                onOpenStream = { url ->
                    vm.dismissDialog()
                    vm.selectMedia(url.trim().toUri(), autoPlay = true)
                },
                onAppLanguageChange = {
                    currentPlayer?.let { p ->
                        vm.onPositionChanged(p.currentPosition, p.duration, p.bufferedPosition)
                    }
                    vm.persistPlaybackPosition(force = true)
                    vm.prefs.saveAppLanguage(it)
                    (context as? ComponentActivity)?.recreate()
                },
                onShowAbout = { vm.showDialog(DialogType.AboutDeveloper) },
                onTranslateAll = { vm.translateAll() },
                onSaveTranslated = {
                    val content = vm.getTranslatedSrtContent()
                    val targetLang = vm.prefs.getTargetLanguage()
                    val fileName = currentVideoUri?.lastPathSegment?.substringBeforeLast(".")
                        ?.let { "${it}_$targetLang.srt" } ?: "translated_$targetLang.srt"

                    pendingSrtContent = content
                    subtitleSaverLauncher.launch(fileName)
                },
                onUseExternalSubtitlesChange = { useExternal ->
                    currentPlayer?.let { current ->
                        current.trackSelectionParameters = current.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, useExternal)
                            .build()
                    }
                    vm.setUseExternalSubtitles(useExternal)
                },
                onPickExternalSubtitles = {
                    subtitlePickerLauncher.launch(SUBTITLE_MIME_TYPES)
                },
                onSubtitleSelected = {
                    vm.subtitleEngine.setSubtitles(it)
                    currentPlayer?.let { current ->
                        current.trackSelectionParameters = current.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                    vm.setUseExternalSubtitles(true)
                    vm.dismissDialog()
                },
                onUpdateDownload = updateManager::downloadAndInstall,
            )
        }

        // ML Kit builds a fresh language collection when queried. Keep it out of the
        // active-dialog transition so opening any TV card does not rebuild that list.
        val availableTranslationLanguages = remember(vm.translationManager) {
            vm.translationManager.getAvailableLanguages()
        }

        val dialogState = remember(
            uiState.activeDialog, subtitleState, translatedCount, isTranslatingAll,
            currentVideoUri, appLang, uiState.playbackSpeed, uiState.duration,
            videoQualityProfile, matchContentFrameRate, repeatCurrentMedia, showDiagnostics, sleepTimerMinutes,
            skipSilenceEnabled, videoResizeMode, videoAspectRatioOverride, videoZoom, videoPanX, videoPanY,
            localMediaSnapshot, localMediaAccess, isLocalMediaLoading,
            isLocalMediaPermissionRequired, didLocalMediaLoadFail,
            // Every value copied into PlayerDialogState must be a key here. Leaving one out
            // freezes that control at the value it had when the dialog opened: the audio-delay
            // stepper kept adding to a stale number and the auto-sync wizard never advanced.
            audioOffloadEnabled, audioDelayMs, preferredAudioLanguage,
            autoSyncState, subtitleSyncState, availableTranslationLanguages,
        ) {
            PlayerDialogState(
                activeDialog = uiState.activeDialog,
                localMediaSnapshot = localMediaSnapshot,
                localMediaAccess = localMediaAccess,
                isLocalMediaLoading = isLocalMediaLoading,
                isLocalMediaPermissionRequired = isLocalMediaPermissionRequired,
                didLocalMediaLoadFail = didLocalMediaLoadFail,
                isTranslationEnabled = subtitleState.isTranslationEnabled,
                translationSource = subtitleState.translationSource,
                translationProviderConfig = subtitleState.translationProviderConfig,
                mlKitAllowed = subtitleState.mlKitAllowed,
                geminiNanoIncluded = subtitleState.geminiNanoIncluded,
                geminiNanoAvailable = subtitleState.geminiNanoAvailable,
                sourceLang = subtitleState.sourceLang,
                subtitleFontSize = subtitleState.fontSize,
                subtitleVerticalOffset = subtitleState.verticalOffset,
                subtitleSyncOffset = subtitleState.syncOffset,
                subtitleColor = Color(subtitleState.textColorArgb),
                subtitleBgColor = Color(subtitleState.backgroundColorArgb),
                subtitleEdgeStyle = subtitleState.subtitleEdgeStyle,
                targetLang = subtitleState.targetLang,
                playbackSpeed = uiState.playbackSpeed,
                videoQualityProfile = videoQualityProfile,
                matchContentFrameRate = matchContentFrameRate,
                audioOffloadEnabled = audioOffloadEnabled,
                audioDelayMs = audioDelayMs,
                preferredAudioLanguage = preferredAudioLanguage,
                repeatCurrentMedia = repeatCurrentMedia,
                showDiagnostics = showDiagnostics,
                sleepTimerMinutes = sleepTimerMinutes,
                skipSilenceEnabled = skipSilenceEnabled,
                videoResizeMode = videoResizeMode,
                aspectRatioOverride = videoAspectRatioOverride,
                videoZoom = videoZoom,
                videoPanX = videoPanX,
                videoPanY = videoPanY,
                availableLanguages = availableTranslationLanguages,
                currentAppLang = appLang,
                externalSubtitles = vm.subtitleEngine.getAllSubtitles(),
                translatedMapSize = translatedCount,
                isTranslatingAll = isTranslatingAll,
                useExternalSubtitles = subtitleState.useExternal,
                currentVideoUri = currentVideoUri,
                autoSyncState = autoSyncState,
                subtitleSyncState = subtitleSyncState,
            )
        }

        // Construct the network-heavy subtitle service only for the screen that needs it.
        // Opening settings/media cards must not allocate extra OkHttp clients.
        val subtitleSearchService = remember(uiState.activeDialog) {
            if (uiState.activeDialog == DialogType.SubtitleSearch) {
                SubtitleSearchService(vm.prefs.getSubtitleProviderConfig())
            } else null
        }

        PlayerDialogsOverlay(
            state = dialogState,
            actions = dialogActions,
            player = currentPlayer,
            subtitleSearchService = subtitleSearchService,
            scope = scope,
            context = context,
            prefs = vm.prefs
        )
    }
}

private fun handleGlobalKeyEvent(
    keyCode: Int,
    player: Player,
    vm: PlayerViewModel,
    repeatCount: Int,
    onShowMedia: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    videoZoom: Float,
    videoPanX: Float,
    videoPanY: Float,
    onPanChange: (Float, Float) -> Unit,
    currentVideoUri: Uri?,
): Boolean {
    // Holding a transport/menu key must not repeatedly toggle playback or open dialogs.
    // Direction and seek repeats remain available for fast remote scrubbing.
    if (repeatCount > 0 && keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_MEDIA_TOP_MENU,
        )) return true
    val uiState = vm.uiState.value
    val isAnyVisible = uiState.showControls || uiState.showOnlyTopBar ||
            uiState.showOnlyBottomBar || uiState.showOnlyPlayButton
    val lockedMode = uiState.lockedMode
    val isIdle = !uiState.hasMedia

    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            when {
                isIdle -> false
                !isAnyVisible -> {
                    vm.showControlsTemporarily(all = true)
                    true
                }
                lockedMode != LockedMode.NONE -> {
                    vm.showControlsTemporarily(all = true)
                    true
                }
                else -> {
                    togglePlayback(player)
                    vm.startHideTimer()
                    true
                }
            }
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            if (videoZoom > 1f) {
                // D-pad pans the zoomed frame instead of seeking.
                onPanChange((videoPanX - PAN_STEP).coerceIn(-1f, 1f), videoPanY)
                true
            } else if (isIdle || !uiState.isSeekable) false
            else if (!isAnyVisible) {
                vm.onSeekEvent(-TV_REMOTE_SEEK_STEP_MS)
                true
            } else false
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (videoZoom > 1f) {
                onPanChange((videoPanX + PAN_STEP).coerceIn(-1f, 1f), videoPanY)
                true
            } else if (isIdle || !uiState.isSeekable) false
            else if (!isAnyVisible) {
                vm.onSeekEvent(TV_REMOTE_SEEK_STEP_MS)
                true
            } else false
        }
        KeyEvent.KEYCODE_MEDIA_REWIND -> {
            if (!uiState.isSeekable) false else { vm.onSeekEvent(-TV_REMOTE_SEEK_STEP_MS); true }
        }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            if (!uiState.isSeekable) false else { vm.onSeekEvent(TV_REMOTE_SEEK_STEP_MS); true }
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
            if (videoZoom > 1f) {
                onPanChange(videoPanX, (videoPanY - PAN_STEP).coerceIn(-1f, 1f))
                true
            } else if (isIdle) {
                false
            } else if (!isAnyVisible) {
                vm.showControlsTemporarily(top = true)
                true
            } else if (lockedMode == LockedMode.BOTTOM_BAR_ONLY) {
                vm.showControlsTemporarily(all = true)
                true
            } else {
                false
            }
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (videoZoom > 1f) {
                onPanChange(videoPanX, (videoPanY + PAN_STEP).coerceIn(-1f, 1f))
                true
            } else if (isIdle) {
                false
            } else if (lockedMode == LockedMode.TOP_BAR_ONLY) {
                vm.showControlsTemporarily(all = true)
                true
            } else {
                false
            }
        }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            togglePlayback(player)
            true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY -> { playOrReplay(player); true }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
        KeyEvent.KEYCODE_MEDIA_STOP -> {
            vm.onPositionChanged(player.currentPosition, player.duration, player.bufferedPosition)
            vm.persistPlaybackPosition(force = true)
            player.pause()
            player.stop()
            true
        }
        KeyEvent.KEYCODE_INFO -> { onToggleDiagnostics(); true }
        KeyEvent.KEYCODE_MENU -> { vm.showDialog(DialogType.Settings); true }
        KeyEvent.KEYCODE_SEARCH -> { vm.showDialog(DialogType.SubtitleSearch); true }
        KeyEvent.KEYCODE_MEDIA_TOP_MENU -> { onShowMedia(); true }
        else -> false
    }
}

private val VIDEO_MIME_TYPES = arrayOf(
    "video/*",
    "audio/*",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "application/dash+xml",
)

private val SUBTITLE_MIME_TYPES = arrayOf(
    "application/x-subrip",
    "text/plain",
    "text/*",
)

internal fun validateExternalRemotePlaybackIntent(intent: Intent): ExternalMediaRequest? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https", "rtsp", "rtsps")) return null
    if (!isSupportedRemotePlaybackUri(uri.toString())) return null
    return ExternalMediaRequest(
        uri = uri,
        mimeType = intent.type
            ?.takeIf(::isSupportedPlaybackMimeType)
            ?.normalizedMimeType(),
    )
}

internal data class ExternalMediaRequest(
    val uri: Uri,
    val mimeType: String? = null,
    val canPersistPermission: Boolean = false,
)

/** Custom scheme other media apps can deep-link into to launch OxigenPlayer as their external player. */
private const val PAN_STEP = 0.05f
private const val EXTERNAL_PLAYER_SCHEME = "oxigenplayer"
private const val VIDEO_TRACE_TAG = "OxigenPlayer-Video"

private val EXTERNAL_PLAYER_TARGET_SCHEMES = setOf("http", "https", "rtsp", "rtsps")

/**
 * Parses a `oxigenplayer://play?url=<media>[&mime=<type>]` deep link. Jellyfin, Stremio,
 * Infuse and similar apps can offer "open in external player" using this scheme instead of
 * relying on the OS media-file associations. The carried media URL still has to pass the same
 * [isSupportedRemotePlaybackUri] policy as a normal video intent, so the deep link can never
 * turn into a cleartext-host, scheme or credential bypass. [ExternalMediaRequest.uri] is the
 * real stream URI (never the deep link itself).
 */
internal fun validateExternalPlayerDeepLink(intent: Intent): ExternalMediaRequest? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val deepLink = intent.data ?: return null
    if (deepLink.scheme?.lowercase(Locale.ROOT) != EXTERNAL_PLAYER_SCHEME) return null

    val targetValue = deepLink.getQueryParameter("url") ?: return null
    val target = runCatching { targetValue.trim().toUri() }.getOrNull() ?: return null
    if (target.scheme?.lowercase(Locale.ROOT) !in EXTERNAL_PLAYER_TARGET_SCHEMES) return null
    if (!isSupportedRemotePlaybackUri(target.toString())) return null

    return ExternalMediaRequest(
        uri = target,
        mimeType = runCatching { deepLink.getQueryParameter("mime") }.getOrNull()
            ?.takeIf(::isSupportedPlaybackMimeType)
            ?.normalizedMimeType(),
    )
}

private const val URI_PERMISSION_PREFERENCES = "persisted_media_permissions"
private const val CURRENT_VIDEO_PERMISSION = "current_video_uri"

private fun Context.replacePersistedVideoPermission(uri: Uri, canPersist: Boolean): Boolean {
    val preferences = getSharedPreferences(URI_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    val isContentUri = uri.scheme.equals("content", ignoreCase = true)

    // Migrate installations of older versions by releasing every durable document
    // grant except the currently selected video. Subtitle files are consumed eagerly
    // and never need a durable grant.
    val retainedUri = uri.takeIf { isContentUri && canPersist }
    contentResolver.persistedUriPermissions
        .filter { permission -> permission.uri != retainedUri }
        .forEach { permission ->
            var flags = 0
            if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(permission.uri, flags)
                }.onFailure { error ->
                    Log.d("MainActivity", "Unable to release stale URI permission", error)
                }
            }
        }

    var persisted = false

    if (isContentUri && canPersist) {
        persisted = try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (error: SecurityException) {
            Log.w("MainActivity", "URI provider did not grant persistable read access", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.w("MainActivity", "URI provider does not support persisted permissions", error)
            false
        }
        if (!persisted) {
            persisted = contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }
    }

    preferences.edit {
        if (persisted) putString(CURRENT_VIDEO_PERMISSION, uri.toString())
        else remove(CURRENT_VIDEO_PERMISSION)
    }
    return persisted || !isContentUri
}

private val Context.appPreferences: PreferencesManager
    get() = (applicationContext as OxigenApp).prefs

private fun Context.showSessionOnlyFileAccessMessage() {
    val language = appPreferences.getAppLanguage()
    Toast.makeText(
        this,
        OxigenStrings.get(language, "session_only_access"),
        Toast.LENGTH_LONG,
    ).show()
}

private fun Context.showMediaPickerUnavailableMessage(error: Exception) {
    Log.e("MainActivity", "No usable media picker is installed", error)
    val language = appPreferences.getAppLanguage()
    Toast.makeText(
        this,
        OxigenStrings.get(language, "video_picker_unavailable"),
        Toast.LENGTH_LONG,
    ).show()
}

private fun String.normalizedMimeType(): String =
    substringBefore(';').trim().lowercase(Locale.ROOT)

private fun subtitleFileErrorMessage(error: SubtitleFileError, language: String): String = when (error) {
    SubtitleFileError.PERMISSION_DENIED -> OxigenStrings.get(language, "subtitle_permission_denied")
    SubtitleFileError.CANNOT_OPEN -> OxigenStrings.get(language, "subtitle_cannot_open")
    SubtitleFileError.TOO_LARGE -> OxigenStrings.get(language, "subtitle_too_large")
    SubtitleFileError.EMPTY_OR_INVALID -> OxigenStrings.get(language, "subtitle_empty_invalid")
    SubtitleFileError.READ_FAILED -> OxigenStrings.get(language, "subtitle_read_failed")
    SubtitleFileError.WRITE_FAILED -> OxigenStrings.get(language, "subtitle_write_failed")
}

/**
 * Resolves the URI of the current item.
 *
 * `localConfiguration` is dropped when a [MediaItem] crosses the session boundary, so the
 * request metadata and the media id are required fallbacks. Callers on both sides of the
 * session must use this helper, otherwise one side silently sees a null URI.
 */
internal fun Player.currentPlaybackMediaUri(): String? {
    val item = currentMediaItem ?: return null
    return item.localConfiguration?.uri?.toString()
        ?: item.requestMetadata.mediaUri?.toString()
        ?: item.mediaId.takeIf(String::isNotBlank)
}

private fun Player.toPlaybackSnapshot(): PlaybackSnapshot {
    val error = playerError
    val phase = when {
        error != null -> PlaybackPhase.ERROR
        playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
        playbackState == Player.STATE_READY -> PlaybackPhase.READY
        playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
        else -> PlaybackPhase.IDLE
    }
    val hasMedia = mediaItemCount > 0 || currentMediaItem != null
    return PlaybackSnapshot(
        hasMedia = hasMedia,
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        phase = phase,
        positionMs = currentPosition,
        durationMs = duration,
        bufferedPositionMs = bufferedPosition,
        // A failed or unprepared item only has a placeholder timeline, which Media3 reports
        // as live; without this the bar showed LIVE for a file that could not be opened.
        isLive = hasMedia && error == null && playbackState != Player.STATE_IDLE &&
            isCurrentMediaItemLive,
        isSeekable = hasMedia && isCurrentMediaItemSeekable,
        mediaUri = currentPlaybackMediaUri(),
        errorMessage = formatPlaybackErrorMessage(error),
        videoWidth = videoSize.width,
        videoHeight = videoSize.height,
        pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
    )
}

/**
 * Bounded automatic recovery budget for transient source errors before the error overlay
 * is shown and playback is considered failed. Kept intentionally low for torrent-backed
 * HTTP sources: re-preparing a TorrServer stream forces the extractor to rescan from the
 * resume position, so every attempt costs another long buffering spinner on a large file.
 * Two attempts still bridge a one-off stall; a persistently dead swarm should surface the
 * error instead of looping the spinner.
 */
private const val MAX_SOURCE_ERROR_RECOVERY_ATTEMPTS = 2

/**
 * Torrent-backed HTTP sources (TorrServer and friends) stall while pieces download, which
 * surfaces as I/O-flavored "Source error" playback failures. These error codes are worth an
 * automatic re-prepare instead of killing playback; fatal ones (bad HTTP status, unsupported
 * content, decoder failures) are surfaced immediately.
 */
internal fun isRecoverableSourceErrorCode(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT -> true
    else -> false
}

/** Raw "Source error" alone tells the user nothing; append the precise Media3 code name. */
internal fun formatPlaybackErrorMessage(error: PlaybackException?): String? {
    if (error == null) return null
    // Activity attachment sets the default locale to the app language.
    val friendly = playbackErrorMessageKey(error.errorCode)
        ?.let { key -> OxigenStrings.get(Locale.getDefault().language, key) }
    return composePlaybackErrorMessage(
        friendlyMessage = friendly,
        technicalMessage = error.localizedMessage ?: error.message,
        errorCodeName = error.errorCodeName,
    )
}

private fun togglePlayback(player: Player) {
    val isActivelyPlayingOrBuffering = player.playWhenReady &&
        (player.playbackState == Player.STATE_BUFFERING ||
            player.playbackState == Player.STATE_READY)
    if (isActivelyPlayingOrBuffering) {
        player.pause()
    } else {
        playOrReplay(player)
    }
}

private fun playOrReplay(player: Player) {
    when (player.playbackState) {
        Player.STATE_ENDED -> {
            player.seekToDefaultPosition()
            player.prepare()
        }
        Player.STATE_IDLE -> player.prepare()
        Player.STATE_BUFFERING, Player.STATE_READY -> Unit
    }
    player.play()
}

@Composable
private fun <T> StateFlow<T>.collectLifecycleAware(): State<T> {
    val flow = this
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(flow) { mutableStateOf(flow.value) }
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { state.value = it }
        }
    }
    return state
}

/**
 * Subtitle rendering surface for video playback. Mirrors [com.oxigenplayer.app.PlayerSubtitleSurface]
 * from PlayerComponents.kt but defined locally to guarantee visibility across all build variants.
 */
@Composable
private fun PlayerSubtitleSurface(
    isVisible: Boolean,
    showControls: Boolean,
    text: String,
    fontSize: Float,
    color: Color,
    bgColor: Color,
    verticalOffset: Float,
    edgeStyle: SubtitleEdgeStyle
) {
    if (!isVisible || text.isBlank()) return

    val lowRam = LocalLowRamMode.current
    val basePadding = if (showControls) 140.dp else 40.dp
    val targetPadding = (basePadding + verticalOffset.dp).coerceAtLeast(0.dp)
    val subPadding = if (lowRam) {
        targetPadding
    } else {
        animateDpAsState(
            targetValue = targetPadding,
            animationSpec = tween(50),
            label = "subPad",
        ).value
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = subPadding, start = 40.dp, end = 40.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = if (bgColor != Color.Transparent) bgColor else Color.Transparent,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics {
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (edgeStyle == SubtitleEdgeStyle.OUTLINE) {
                    listOf(
                        (-1).dp to 0.dp,
                        1.dp to 0.dp,
                        0.dp to (-1).dp,
                        0.dp to 1.dp,
                    ).forEach { (outlineX, outlineY) ->
                        Text(
                            text = text,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.3f).sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(letterSpacing = 0.5.sp),
                            color = Color.Black,
                            modifier = Modifier.offset(x = outlineX, y = outlineY),
                        )
                    }
                }
                Text(
                    text = text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.3f).sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    style = subtitleTextStyle(edgeStyle),
                    color = color
                )
            }
        }
    }
}

private fun subtitleTextStyle(edgeStyle: SubtitleEdgeStyle): TextStyle = when (edgeStyle) {
    SubtitleEdgeStyle.SHADOW -> TextStyle(
        letterSpacing = 0.5.sp,
        shadow = Shadow(
            color = Color.Black,
            offset = Offset(2f, 2f),
            blurRadius = 4f
        )
    )
    SubtitleEdgeStyle.OUTLINE,
    SubtitleEdgeStyle.NONE -> TextStyle(letterSpacing = 0.5.sp)
}
