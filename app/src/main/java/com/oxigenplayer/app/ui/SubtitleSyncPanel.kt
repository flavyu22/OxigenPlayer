// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oxigenplayer.app.AutoSyncPhase
import com.oxigenplayer.app.AutoSyncState
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.SUBTITLE_SYNC_COARSE_STEP_MS
import com.oxigenplayer.app.SUBTITLE_SYNC_FINE_STEP_MS
import com.oxigenplayer.app.tvFocusable

/**
 * On-screen subtitle sync control.
 *
 * It is drawn over the playing video, never inside a dialog: timing can only be judged while the
 * picture, the sound and the subtitle are all visible, and the playback position has to keep
 * advancing for the two-mark calibration to mean anything.
 */
@Composable
fun SubtitleSyncPanel(
    appLang: String,
    syncOffsetMs: Long,
    autoSyncState: AutoSyncState,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onStartAutoSync: () -> Unit,
    onMarkAudio: () -> Unit,
    onMarkSubtitle: () -> Unit,
    onApplyAutoSync: () -> Unit,
    onCancelAutoSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fineFocus = remember { FocusRequester() }
    val phaseFocus = remember { FocusRequester() }
    val title = OxigenStrings.get(appLang, "subtitle_sync_label")

    // Opening lands on the fine step, the control used most. A phase change removes the button
    // that had focus, so focus is handed to the first action of the new phase instead of being
    // dropped to wherever the focus system falls back.
    LaunchedEffect(Unit) { runCatching { fineFocus.requestFocus() } }
    LaunchedEffect(autoSyncState.phase) {
        if (autoSyncState.phase != AutoSyncPhase.IDLE) runCatching { phaseFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .widthIn(max = 380.dp)
            .background(OxigenCardColor, OxigenCardShape)
            .border(
                1.dp,
                OxigenCardBorderColor,
                OxigenCardShape,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics { contentDescription = title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            formatSubtitleSyncOffset(syncOffsetMs),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncPanelButton("−0.5 s", onClick = { onNudge(-SUBTITLE_SYNC_COARSE_STEP_MS) })
            SyncPanelButton(
                "−50 ms",
                onClick = { onNudge(-SUBTITLE_SYNC_FINE_STEP_MS) },
                focusRequester = fineFocus,
            )
            SyncPanelButton("0", onClick = onReset, highlighted = syncOffsetMs == 0L)
            SyncPanelButton("+50 ms", onClick = { onNudge(SUBTITLE_SYNC_FINE_STEP_MS) })
            SyncPanelButton("+0.5 s", onClick = { onNudge(SUBTITLE_SYNC_COARSE_STEP_MS) })
        }

        Text(
            OxigenStrings.get(appLang, "subtitle_sync_hint"),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(10.dp))

        when (autoSyncState.phase) {
            AutoSyncPhase.IDLE -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncPanelButton(
                    OxigenStrings.get(appLang, "auto_sync_btn"),
                    onClick = onStartAutoSync,
                    focusRequester = phaseFocus,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SyncPanelButton(OxigenStrings.get(appLang, "close_dialog"), onClick = onClose)
            }

            AutoSyncPhase.MARKING -> {
                Text(
                    OxigenStrings.get(appLang, "auto_sync_instruction"),
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncPanelButton(
                        markLabel(OxigenStrings.get(appLang, "auto_sync_mark_audio"), autoSyncState.hasAudioMark),
                        onClick = onMarkAudio,
                        focusRequester = phaseFocus,
                        highlighted = autoSyncState.hasAudioMark,
                    )
                    SyncPanelButton(
                        markLabel(OxigenStrings.get(appLang, "auto_sync_mark_subtitle"), autoSyncState.hasSubtitleMark),
                        onClick = onMarkSubtitle,
                        highlighted = autoSyncState.hasSubtitleMark,
                    )
                    SyncPanelButton(OxigenStrings.get(appLang, "auto_sync_cancel"), onClick = onCancelAutoSync)
                }
            }

            AutoSyncPhase.SHOW_RESULT -> {
                Text(
                    "${OxigenStrings.get(appLang, "auto_sync_result")} " +
                        formatSubtitleSyncOffset(autoSyncState.suggestedOffsetMs),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncPanelButton(
                        OxigenStrings.get(appLang, "auto_sync_apply"),
                        onClick = onApplyAutoSync,
                        focusRequester = phaseFocus,
                        highlighted = true,
                    )
                    SyncPanelButton(OxigenStrings.get(appLang, "auto_sync_cancel"), onClick = onCancelAutoSync)
                }
            }
        }
    }
}

private fun markLabel(label: String, isSet: Boolean): String = if (isSet) "✓ $label" else label

@Composable
private fun SyncPanelButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .tvFocusable(focusRequester = focusRequester, isSelected = highlighted, onClick = onClick)
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.32f)
                } else {
                    Color.White.copy(alpha = 0.12f)
                },
                RoundedCornerShape(8.dp),
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}
