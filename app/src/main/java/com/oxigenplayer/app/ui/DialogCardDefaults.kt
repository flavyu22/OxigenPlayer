// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.oxigenplayer.app.hideSystemBarsImmersive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.oxigenplayer.app.LocalTelevisionMode
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val DIALOG_CARD_WIDTH_FRACTION = 0.94f
private const val DIALOG_CARD_HEIGHT_FRACTION = 0.90f

/**
 * Keeps cards readable on compact phones without letting them become excessively wide on TV.
 * The max constraints are applied before the fractions so both limits remain effective.
 */
fun Modifier.standardDialogCardSize(): Modifier =
    this
        .widthIn(max = 640.dp)
        .fillMaxWidth(DIALOG_CARD_WIDTH_FRACTION)
        .heightIn(max = 760.dp)
        .fillMaxHeight(DIALOG_CARD_HEIGHT_FRACTION)

/**
 * A dialog wrapper that ensures instant appearance by using specific properties.
 */
@Composable
fun FastDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val lowRam = com.oxigenplayer.app.LocalLowRamMode.current
    val televisionMode = LocalTelevisionMode.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        if (!televisionMode) {
            // The dialog is its own window; without this the system bars reappear while it is open.
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            LaunchedEffect(dialogWindow) { dialogWindow?.hideSystemBarsImmersive() }
        }
        val backdropModifier = Modifier
            .fillMaxSize()
            .background(if (lowRam) Color.Black else Color.Black.copy(alpha = 0.5f))
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .then(
                if (televisionMode) Modifier else Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            )

        Box(
            modifier = backdropModifier,
            contentAlignment = Alignment.Center,
        ) {
            // TV is D-pad driven, so two pointer-input gesture detectors per dialog are pure
            // overhead there. Keep them only on touch-capable phone/tablet layouts.
            Box(
                modifier = if (televisionMode) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) { detectTapGestures { } }
                },
            ) {
                content()
            }
        }
    }
}

/** Shared look for every dialog card: graphite surface, hairline border, generous corners. */
val OxigenCardShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
val OxigenCardColor = Color(0xF7141519)
val OxigenCardBorderColor = Color.White.copy(alpha = 0.10f)

@Composable
fun OxigenCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = OxigenCardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = OxigenCardColor,
            contentColor = Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, OxigenCardBorderColor),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}
