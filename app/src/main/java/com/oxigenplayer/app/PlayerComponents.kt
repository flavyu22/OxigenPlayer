// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.oxigenplayer.app.ui.formatSubtitleSyncOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun PlayerControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    isSelected: Boolean = false,
    selectedColor: Color = Color.Unspecified
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val activeColor = if (selectedColor == Color.Unspecified) {
        MaterialTheme.colorScheme.primary
    } else {
        selectedColor
    }
    val background = when {
        focused -> Color.White
        isSelected -> activeColor.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.10f)
    }

    // Icon-only toolbar: the label is shown under the focused button only, the way TV players
    // do it, so seven captions are not spread across the screen. The fixed-height label slot
    // keeps the row from jumping when focus moves.
    Box(
        modifier = Modifier.width(TOP_BAR_BUTTON_WIDTH).height(TOP_BAR_BUTTON_HEIGHT),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(TOP_BAR_ICON_SIZE)
                .background(background, CircleShape)
                .tvFocusable(
                    focusRequester    = focusRequester,
                    isCircle          = true,
                    isSelected        = isSelected,
                    interactionSource = interaction,
                    onClick           = onClick,
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                label,
                tint = if (focused) Color.Black else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        if (focused) {
            Text(
                text       = label,
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                softWrap   = false,
                style      = TextStyle(shadow = TOP_BAR_TEXT_SHADOW),
                modifier   = Modifier
                    .align(Alignment.BottomCenter)
                    .wrapContentWidth(unbounded = true),
            )
        }
    }
}

private val TOP_BAR_BUTTON_WIDTH = 52.dp
private val TOP_BAR_BUTTON_HEIGHT = 66.dp
private val TOP_BAR_ICON_SIZE = 44.dp
private val TOP_BAR_TEXT_SHADOW = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 8f)

@Composable
fun PlayerTopBar(
    isVisible: Boolean,
    videoTitle: String,
    currentTime: String,
    appLang: String,
    isSubtitleVisible: Boolean,
    topBarFocusRequester: FocusRequester,
    mediaButtonFocusRequester: FocusRequester,
    onToggleSubtitle: () -> Unit,
    onShowMedia: () -> Unit,
    onShowStream: () -> Unit,
    onShowTracks: () -> Unit,
    onShowSync: () -> Unit,
    onShowSearch: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lowRam = LocalLowRamMode.current
    AnimatedVisibility(
        visible = isVisible,
        enter = if (lowRam) EnterTransition.None else fadeIn(animationSpec = tween(100)) +
            slideInVertically(initialOffsetY = { -it }, animationSpec = tween(100)),
        exit = if (lowRam) ExitTransition.None else fadeOut(animationSpec = tween(100)) +
            slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(100)),
        modifier = modifier
    ) {
        // One row: title (left), icon toolbar, a hairline divider and the clock (right).
        // No background: text shadows keep everything readable over bright frames.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 40.dp, end = 40.dp, top = 24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text       = videoTitle,
                color      = Color.White,
                fontSize   = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                style      = TextStyle(shadow = TOP_BAR_TEXT_SHADOW),
                // Long names wrap to a second line before they are cut with an ellipsis.
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier
                    .weight(1f)
                    .padding(top = 6.dp, end = 32.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.Top,
            ) {
                PlayerControlButton(
                    icon           = Icons.Default.ClosedCaption,
                    label          = OxigenStrings.get(appLang, "subtitles_label"),
                    onClick        = onToggleSubtitle,
                    isSelected     = !isSubtitleVisible,
                    selectedColor  = Color(0xFFE5484D),
                    focusRequester = topBarFocusRequester
                )
                PlayerControlButton(
                    icon           = Icons.Default.Folder,
                    label          = OxigenStrings.get(appLang, "media_label"),
                    onClick        = onShowMedia,
                    focusRequester = mediaButtonFocusRequester
                )
                PlayerControlButton(
                    icon    = Icons.Default.Link,
                    label   = OxigenStrings.get(appLang, "open_stream"),
                    onClick = onShowStream,
                    focusRequester = remember { FocusRequester() },
                )
                PlayerControlButton(
                    icon    = Icons.AutoMirrored.Filled.List,
                    label   = OxigenStrings.get(appLang, "tracks_title"),
                    onClick = onShowTracks,
                    focusRequester = remember { FocusRequester() },
                )
                PlayerControlButton(
                    icon    = Icons.Default.Sync,
                    label   = OxigenStrings.get(appLang, "sync_button"),
                    onClick = onShowSync,
                    focusRequester = remember { FocusRequester() },
                )
                PlayerControlButton(
                    icon    = Icons.Default.Search,
                    label   = OxigenStrings.get(appLang, "online_search"),
                    onClick = onShowSearch,
                    focusRequester = remember { FocusRequester() },
                )
                PlayerControlButton(
                    icon    = Icons.Default.Settings,
                    label   = OxigenStrings.get(appLang, "settings_label"),
                    onClick = onShowSettings,
                    focusRequester = remember { FocusRequester() },
                )
            }

            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(Color.White.copy(alpha = 0.35f))
            )

            Text(
                text       = currentTime,
                color      = Color.White,
                fontSize   = 24.sp,
                fontWeight = FontWeight.Normal,
                style      = TextStyle(shadow = TOP_BAR_TEXT_SHADOW),
                modifier   = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun PlayerCenterControls(
    isVisible: Boolean,
    isPlaying: Boolean,
    isMediaSelected: Boolean,
    appLang: String,
    playButtonFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actionDescription = OxigenStrings.get(appLang, if (isPlaying) "pause" else "play")
    val lowRam = LocalLowRamMode.current
    AnimatedVisibility(
        visible = isVisible,
        enter = if (lowRam) EnterTransition.None else fadeIn(animationSpec = tween(100)) +
            scaleIn(initialScale = 0.9f, animationSpec = tween(100)),
        exit = if (lowRam) ExitTransition.None else fadeOut(animationSpec = tween(100)) +
            scaleOut(targetScale = 0.9f, animationSpec = tween(100)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .size(92.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = actionDescription
                    if (isMediaSelected) {
                        stateDescription = OxigenStrings.get(appLang, if (isPlaying) "playing" else "paused")
                    }
                }
                .tvFocusable(
                    isCircle       = true,
                    focusRequester = playButtonFocus,
                    onClick        = onTogglePlay,
                    enabled        = isMediaSelected
                ),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.15f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = actionDescription,
                    tint     = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    isVisible: Boolean,
    currentPosition: () -> Long,
    duration: Long,
    bufferedPosition: () -> Long,
    isSeeking: Boolean,
    isSeekable: Boolean,
    isLive: Boolean,
    appLang: String,
    sliderFocus: FocusRequester,
    abRepeatState: AbRepeatState,
    onAbRepeatClick: () -> Unit,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    onSeekDelta: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lowRam = LocalLowRamMode.current
    val bottomGradient = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.18f),
                Color.Black.copy(alpha = 0.94f),
            )
        )
    }

    // The player position is sampled at a bounded rate instead of once per display frame.
    // During scrubbing the interval is shorter for immediate feedback, while ordinary
    // playback uses 250 ms updates to keep CPU/GPU work extremely low on Android TV.
    val positionState = rememberTimelineTicker(
        provider = currentPosition,
        active = isVisible,
        intervalMs = if (isSeeking) 50L else if (lowRam) 500L else 250L,
    )
    val bufferedState = rememberTimelineTicker(
        provider = bufferedPosition,
        active = isVisible,
        intervalMs = if (lowRam) 1_000L else 500L,
    )

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = isVisible,
            enter = if (lowRam) EnterTransition.None else slideInVertically(
                initialOffsetY = { it / 2 }, animationSpec = tween(180),
            ) + fadeIn(animationSpec = tween(140)),
            exit = if (lowRam) ExitTransition.None else slideOutVertically(
                targetOffsetY = { it / 2 }, animationSpec = tween(140),
            ) + fadeOut(animationSpec = tween(120)),
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(bottomGradient),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = 1600.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                UltraTimelineSlider(
                    positionState = positionState,
                    bufferedState = bufferedState,
                    duration = duration,
                    isSeeking = isSeeking,
                    enabled = (isSeekable && duration > 0L && !isLive),
                    appLang = appLang,
                    focusRequester = sliderFocus,
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    onSeekDelta = onSeekDelta,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                if (duration > 0L && !isLive) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val boxMaxWidth = maxWidth
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TimeText(
                                positionState = positionState,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            val durationText = remember(duration / 1_000L) { formatTime(duration) }
                            Text(
                                text = "/ $durationText",
                                color = Color.White.copy(alpha = 0.66f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            // The A-B loop chip shares the row with the clock labels; on narrow
                            // phones it yields to the time texts and stays reachable on TV.
                            val mw = boxMaxWidth
                            if (mw >= 520.dp) {
                                Spacer(Modifier.width(16.dp))
                                AbRepeatChip(
                                    state = abRepeatState,
                                    appLang = appLang,
                                    onClick = onAbRepeatClick,
                                )
                            }
                        }

                        // On compact phones, hiding the secondary buffered label prevents it
                        // from colliding with elapsed and remaining time. TV/tablet layouts keep it.
                        val mw2 = boxMaxWidth
                        if (mw2 >= 520.dp) {
                            TimelineStatusText(
                                bufferedState = bufferedState,
                                duration = duration,
                                isSeeking = isSeeking,
                                isLive = isLive,
                                appLang = appLang,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        RemainingTimeText(
                            positionState = positionState,
                            duration = duration,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                } else if (isLive) {
                    // Only a genuinely live stream gets the badge; a failed or still-loading
                    // item has no duration either, and used to be labelled LIVE.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFFFF2D2D), CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = OxigenStrings.get(appLang, "live_label").uppercase(Locale.ROOT),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            style = TextStyle(letterSpacing = 1.sp),
                        )
                    }
                }
            }
        }
    }

}

}

/**
 * Compact A-B loop control for the bottom bar. The label mirrors the loop state: both points
 * light up while the loop is running, and the pending end point is dimmed after A is set.
 */
@Composable
private fun AbRepeatChip(
    state: AbRepeatState,
    appLang: String,
    onClick: () -> Unit,
) {
    val description = when (state.phase) {
        AbRepeatPhase.INACTIVE -> OxigenStrings.get(appLang, "ab_repeat")
        AbRepeatPhase.POINT_A_SET -> OxigenStrings.get(appLang, "ab_repeat_set_end")
        AbRepeatPhase.LOOPING -> OxigenStrings.get(appLang, "ab_repeat_clear")
    }
    val active = state.isActive
    Box(
        modifier = Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                else Color.DarkGray.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp),
            )
            .tvFocusable(isSelected = active, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "→",
                color = Color.White.copy(alpha = if (active) 1f else 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "B",
                color = Color.White.copy(
                    alpha = if (state.phase == AbRepeatPhase.LOOPING) 1f else 0.45f,
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/**
 * Samples a fast-changing provider without forcing the complete bottom bar through composition.
 * Position changes are consumed by draw/layout phase readers only.
 */
@Composable
fun rememberTimelineTicker(
    provider: () -> Long,
    active: Boolean,
    intervalMs: Long,
): State<Long> {
    val state = remember { mutableLongStateOf(provider()) }
    val currentProvider by rememberUpdatedState(provider)

    LaunchedEffect(active, intervalMs) {
        if (!active) {
            state.longValue = currentProvider()
            return@LaunchedEffect
        }
        while (true) {
            val value = currentProvider()
            if (value != state.longValue) state.longValue = value
            kotlinx.coroutines.delay(intervalMs.coerceAtLeast(16L))
        }
    }
    return state
}

@Composable
fun UltraTimelineSlider(
    positionState: State<Long>,
    bufferedState: State<Long>,
    duration: Long,
    isSeeking: Boolean,
    enabled: Boolean,
    appLang: String,
    focusRequester: FocusRequester,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    onSeekDelta: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressColor = Color(0xFFFF2D2D)
    val bufferColor = Color.White.copy(alpha = 0.42f)
    val trackColor = Color.White.copy(alpha = 0.22f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val lowRam = LocalLowRamMode.current
    val expanded = isSeeking || isFocused
    val expansionState = if (lowRam) {
        remember(expanded) { mutableFloatStateOf(if (expanded) 1f else 0f) }
    } else animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "timelineExpansion",
    )

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnSeekDelta by rememberUpdatedState(onSeekDelta)

    Box(
        modifier = modifier.height(48.dp),
    ) {
        Spacer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                .semantics {
                    contentDescription = OxigenStrings.get(appLang, "playback_position")
                    if (enabled) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = positionState.value.coerceIn(0L, duration).toFloat(),
                            range = 0f..duration.toFloat(),
                        )
                        stateDescription = "${formatTime(positionState.value)} / ${formatTime(duration)}"
                        setProgress { requested: Float ->
                            val target = requested.toLong().coerceIn(0L, duration)
                            currentOnValueChange(target)
                            currentOnValueChangeFinished()
                            true
                        }
                    } else {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        stateDescription = OxigenStrings.get(appLang, "live_label")
                        disabled()
                    }
                }
                .focusRequester(focusRequester)
                .focusable(enabled = enabled, interactionSource = interactionSource)
                .pointerInput(duration, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The parent surface toggles the controls on taps. Claim the
                        // gesture here so seeking cannot trigger both actions.
                        down.consume()
                        val pointerId = down.id
                        val startX = down.position.x
                        var lastX = startX
                        var dragging = false
                        var changed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                lastX = change.position.x
                                if (!change.pressed) {
                                    change.consume()
                                    val target = timelinePositionFromPixels(
                                        xPx = lastX,
                                        widthPx = size.width.toFloat(),
                                        durationMs = duration,
                                    )
                                    currentOnValueChange(target)
                                    changed = true
                                    break
                                }
                                if (!dragging && kotlin.math.abs(lastX - startX) >= viewConfiguration.touchSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    val target = timelinePositionFromPixels(
                                        xPx = lastX,
                                        widthPx = size.width.toFloat(),
                                        durationMs = duration,
                                    )
                                    currentOnValueChange(target)
                                    change.consume()
                                    changed = true
                                }
                            }
                        } finally {
                            if (changed) currentOnValueChangeFinished()
                        }
                    }
                }
                .onKeyEvent { event: KeyEvent ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            currentOnSeekDelta(-TV_REMOTE_SEEK_STEP_MS)
                            true
                        }
                        Key.DirectionRight -> {
                            currentOnSeekDelta(TV_REMOTE_SEEK_STEP_MS)
                            true
                        }
                        Key.MoveHome -> {
                            currentOnValueChange(0L)
                            currentOnValueChangeFinished()
                            true
                        }
                        Key.MoveEnd -> {
                            currentOnValueChange(duration)
                            currentOnValueChangeFinished()
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (event.nativeKeyEvent.repeatCount == 0) currentOnValueChangeFinished()
                            true
                        }
                        else -> false
                    }
                }
                .drawWithCache {
                    val collapsedTrackHeight = 3.dp.toPx()
                    val expandedTrackHeight = 8.dp.toPx()
                    val maximumThumbRadius = 8.dp.toPx()
                    val haloRadius = 15.dp.toPx()
                    onDrawBehind {
                        drawTimelineTrack(
                            positionMs = positionState.value,
                            bufferedMs = bufferedState.value,
                            duration = duration,
                            expansion = expansionState.value,
                            trackHCollapsedPx = collapsedTrackHeight,
                            trackHExpandedPx = expandedTrackHeight,
                            thumbRMaxPx = maximumThumbRadius,
                            haloRMaxPx = haloRadius,
                            trackBgColor = trackColor,
                            bufferColor = bufferColor,
                            progressColor = progressColor,
                        )
                    }
                },
        )
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimelineTick(
    x: Float,
    centerY: Float,
    height: Float,
    strokeWidth: Float,
    color: Color,
) {
    drawLine(
        color = color,
        start = Offset(x, centerY - height * 0.5f),
        end = Offset(x, centerY + height * 0.5f),
        strokeWidth = strokeWidth,
    )
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimelineTrack(
    positionMs: Long,
    bufferedMs: Long,
    duration: Long,
    expansion: Float,
    trackHCollapsedPx: Float,
    trackHExpandedPx: Float,
    thumbRMaxPx: Float,
    haloRMaxPx: Float,
    trackBgColor: Color,
    bufferColor: Color,
    progressColor: Color,
) {
    val width = size.width.coerceAtLeast(1f)
    val centerY = size.height * 0.62f
    val trackHeight = trackHCollapsedPx + (trackHExpandedPx - trackHCollapsedPx) * expansion
    val trackTop = centerY - trackHeight * 0.5f
    val radius = androidx.compose.ui.geometry.CornerRadius(trackHeight * 0.5f, trackHeight * 0.5f)
    val progressFraction = timelineFraction(positionMs, duration)
    val progressX = progressFraction * width
    val bufferX = timelineFraction(bufferedMs, duration).coerceAtLeast(progressFraction) * width

    drawRoundRect(
        color = trackBgColor,
        topLeft = Offset(0f, trackTop),
        size = Size(width, trackHeight),
        cornerRadius = radius,
    )
    if (bufferX > 0f) {
        drawRoundRect(
            color = bufferColor,
            topLeft = Offset(0f, trackTop),
            size = Size(bufferX.coerceIn(0f, width), trackHeight),
            cornerRadius = radius,
        )
    }
    if (progressX > 0f) {
        drawRoundRect(
            color = progressColor,
            topLeft = Offset(0f, trackTop),
            size = Size(progressX.coerceIn(0f, width), trackHeight),
            cornerRadius = radius,
        )
    }

    if (expansion > 0.02f) {
        val tickHeight = trackHeight + 5.dp.toPx() * expansion
        val tickColor = Color.White.copy(alpha = 0.26f * expansion)
        val tickStroke = 1.dp.toPx()
        drawTimelineTick(width * 0.25f, centerY, tickHeight, tickStroke, tickColor)
        drawTimelineTick(width * 0.50f, centerY, tickHeight, tickStroke, tickColor)
        drawTimelineTick(width * 0.75f, centerY, tickHeight, tickStroke, tickColor)
    }

    val thumbRadius = thumbRMaxPx * expansion
    if (thumbRadius > 0.5f) {
        val thumbX = progressX.coerceIn(thumbRadius, (width - thumbRadius).coerceAtLeast(thumbRadius))
        drawCircle(
            color = progressColor.copy(alpha = 0.18f * expansion),
            radius = haloRMaxPx * expansion,
            center = Offset(thumbX, centerY),
        )
        drawCircle(
            color = Color.White,
            radius = thumbRadius + 1.dp.toPx(),
            center = Offset(thumbX, centerY),
        )
        drawCircle(
            color = progressColor,
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
        )
    }
}




@Composable
fun TimelineStatusText(
    bufferedState: State<Long>,
    duration: Long,
    isSeeking: Boolean,
    isLive: Boolean,
    appLang: String,
    modifier: Modifier = Modifier,
) {
    // The buffered position is sampled twice per second (once per second on low-RAM devices) but
    // the label only ever shows a whole percent. Deriving the displayed number first means this
    // scope is invalidated only when that number changes, not on every sample of the ticker.
    val bufferedPercent by remember(bufferedState, duration) {
        derivedStateOf { timelineBufferedPercent(bufferedState.value, duration) }
    }
    AnimatedContent(
        targetState = isSeeking,
        transitionSpec = { fadeIn(tween(90)) togetherWith fadeOut(tween(70)) },
        modifier = modifier,
        label = "timelineStatus",
    ) { seeking: Boolean ->
        Text(
            text = when {
                seeking -> OxigenStrings.get(appLang, "seeking_label")
                isLive -> OxigenStrings.get(appLang, "live_label").uppercase(Locale.ROOT)
                else -> "${OxigenStrings.get(appLang, "buffered_label")} $bufferedPercent%"
            },
            color = if (seeking) Color(0xFFFF5252) else Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(letterSpacing = 0.4.sp),
        )
    }
}

@Composable
fun RemainingTimeText(
    positionState: State<Long>,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    // The position ticker fires 4x/second (2x on low-RAM devices) while this label only shows
    // whole seconds. Deriving the second first keeps the scope from recomposing on samples that
    // provably cannot change the rendered text.
    val remainingSeconds by remember(positionState, duration) {
        derivedStateOf { timelineRemainingMs(positionState.value, duration) / 1_000L }
    }
    val text = remember(remainingSeconds) { "-${formatTime(remainingSeconds * 1_000L)}" }
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.72f),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** Recompose the label only when its displayed second changes. */
@Composable
fun TimeText(
    positionState: State<Long>,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    // Same reasoning as RemainingTimeText: derive the displayed second so the ticker's 4 Hz
    // sampling does not recompose this Text (and its layout) four times per second.
    val seconds by remember(positionState) {
        derivedStateOf { positionState.value / 1_000L }
    }
    val text = remember(seconds) { formatTime(seconds * 1_000L) }
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}


/**
 * Formatează o durată în milisegunde ca mm:ss sau hh:mm:ss (ex. 04:20, 1:02:15).
 * Folosit pentru etichetele de timp ale playerului.
 */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
