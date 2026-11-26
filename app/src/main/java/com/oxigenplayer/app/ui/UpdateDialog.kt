// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oxigenplayer.app.UpdateInfo
import com.oxigenplayer.app.UpdateInstallFailure
import com.oxigenplayer.app.UpdateInstallResult
import com.oxigenplayer.app.OxigenStrings
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    appLang: String,
    onUpdate: suspend () -> UpdateInstallResult,
    onDismiss: () -> Unit
) {
    var installStarted by remember(updateInfo) { mutableStateOf(false) }
    var statusMessage by remember(updateInfo) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FastDialog(onDismiss = { if (!installStarted) onDismiss() }) {
        OxigenCard(modifier = Modifier.standardDialogCardSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(OxigenStrings.get(appLang, "update_available"), style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    OxigenStrings.get(appLang, "new_version_available")
                        .format(updateInfo.versionName)
                )
                statusMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss, enabled = !installStarted) {
                        Text(OxigenStrings.get(appLang, "later_btn"))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !installStarted,
                        onClick = {
                            if (!installStarted) {
                                installStarted = true
                                statusMessage = null
                                scope.launch {
                                    when (val result = onUpdate()) {
                                        UpdateInstallResult.InstallerLaunched -> onDismiss()
                                        UpdateInstallResult.AlreadyInProgress -> {
                                            statusMessage = updateStatusMessage(appLang, "progress")
                                            installStarted = false
                                        }
                                        UpdateInstallResult.AlreadyInstalled -> {
                                            statusMessage = OxigenStrings.get(appLang, "no_update")
                                            installStarted = false
                                        }
                                        is UpdateInstallResult.Failed -> {
                                            statusMessage = updateFailureMessage(appLang, result.reason)
                                            installStarted = false
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        if (installStarted) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(OxigenStrings.get(appLang, "install_btn"))
                        }
                    }
                }
            }
        }
    }
}

private fun updateStatusMessage(language: String, status: String): String = when (status) {
    "progress" -> OxigenStrings.get(language, "update_in_progress")
    else -> OxigenStrings.get(language, "update_failed")
}

private fun updateFailureMessage(language: String, failure: UpdateInstallFailure): String = when (failure) {
    UpdateInstallFailure.NETWORK -> OxigenStrings.get(language, "update_download_failed")
    UpdateInstallFailure.TIMED_OUT -> OxigenStrings.get(language, "update_timed_out")
    UpdateInstallFailure.STORAGE -> OxigenStrings.get(language, "update_storage_failed")
    UpdateInstallFailure.VERIFICATION,
    UpdateInstallFailure.INVALID_REQUEST -> OxigenStrings.get(language, "update_verification_failed")
    UpdateInstallFailure.INSTALL_PERMISSION_REQUIRED -> OxigenStrings.get(language, "update_install_permission_required")
    UpdateInstallFailure.INSTALLER_UNAVAILABLE -> OxigenStrings.get(language, "update_installer_unavailable")
    UpdateInstallFailure.LOCAL_STATE -> OxigenStrings.get(language, "update_local_state_failed")
    UpdateInstallFailure.DISABLED -> OxigenStrings.get(language, "update_disabled")
}
