// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oxigenplayer.app.BuildConfig
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.isSupportedRemotePlaybackUri

/**
 * Lets the user play a network stream (HLS, DASH, RTSP/RTSPS or a direct HTTPS/LAN media URL)
 * that is not present on the device. Validation reuses the same policy the app applies to
 * session intents, so a Play build still refuses public HTTP while the Direct/LAN edition
 * keeps its explicit LAN exception.
 */
@Composable
fun OpenStreamDialog(
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    currentAppLang: String,
) {
    var url by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val urlFieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { urlFieldFocus.requestFocus() }
    }

    FastDialog(onDismiss = onDismiss) {
        OxigenCard(
            modifier = Modifier
                .standardDialogCardSize()
                .widthIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = OxigenStrings.get(currentAppLang, "open_stream"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        showError = false
                    },
                    label = { Text(OxigenStrings.get(currentAppLang, "stream_url_label")) },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(urlFieldFocus),
                )
                if (showError) {
                    Text(
                        text = OxigenStrings.get(currentAppLang, "invalid_stream_url"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(OxigenStrings.get(currentAppLang, "close_dialog"))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val trimmed = url.trim()
                            val valid = trimmed.isNotEmpty() &&
                                isSupportedRemotePlaybackUri(
                                    rawUri = trimmed,
                                    allowCleartextHttp = BuildConfig.CLEARTEXT_LAN_ENABLED,
                                )
                            if (valid) onPlay(trimmed) else showError = true
                        },
                    ) {
                        Text(OxigenStrings.get(currentAppLang, "play"))
                    }
                }
            }
        }
    }
}
