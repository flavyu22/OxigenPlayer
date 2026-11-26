// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oxigenplayer.app.*
import kotlinx.coroutines.launch

@Composable
fun AccountSettingsContent(
    onBack: () -> Unit,
    currentAppLang: String,
    initialFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        (context.applicationContext as OxigenApp).prefs
    }
    val secureStorageAvailable = remember { prefs.isSecureCredentialStorageAvailable() }
    val initialProviderConfig = remember { prefs.getSubtitleProviderConfig() }
    var openSubtitlesApiKey by remember { mutableStateOf(initialProviderConfig.openSubtitlesApiKey) }
    var subsRoApiKey by remember { mutableStateOf(initialProviderConfig.subsRoApiKey) }
    var subDlApiKey by remember { mutableStateOf(initialProviderConfig.subDlApiKey) }
    var tmdbApiKey by remember { mutableStateOf(initialProviderConfig.tmdbApiKey) }

    LaunchedEffect(openSubtitlesApiKey, subsRoApiKey, subDlApiKey, tmdbApiKey) {
        prefs.saveSubtitleProviderConfig(
            SubtitleProviderConfig(
                openSubtitlesApiKey = openSubtitlesApiKey,
                subsRoApiKey = subsRoApiKey,
                subDlApiKey = subDlApiKey,
                tmdbApiKey = tmdbApiKey
            )
        )
    }

    val subtitleSearchService = remember(openSubtitlesApiKey, subsRoApiKey, subDlApiKey, tmdbApiKey) {
        SubtitleSearchService(
            SubtitleProviderConfig(
                openSubtitlesApiKey = openSubtitlesApiKey,
                subsRoApiKey = subsRoApiKey,
                subDlApiKey = subDlApiKey,
                tmdbApiKey = tmdbApiKey,
            )
        )
    }

    var username by remember { mutableStateOf(prefs.getUsername()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Status: 0=idle, 1=loading, 2=success, 3=error
    var status by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.tvFocusable(
                    focusRequester = initialFocusRequester,
                    isCircle = true,
                    onClick = onBack,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, OxigenStrings.get(currentAppLang, "back"), modifier = Modifier.padding(12.dp))
            }
            Text(OxigenStrings.get(currentAppLang, "provider_credentials"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Text(
            OxigenStrings.get(
                currentAppLang,
                if (secureStorageAvailable) "api_keys_private_notice" else "secure_storage_session_only",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (secureStorageAvailable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        ProviderKeyField(currentAppLang, "OpenSubtitles", openSubtitlesApiKey) { openSubtitlesApiKey = it }
        ProviderKeyField(currentAppLang, "Subs.ro", subsRoApiKey) { subsRoApiKey = it }
        ProviderKeyField(currentAppLang, "SubDL", subDlApiKey) { subDlApiKey = it }
        ProviderKeyField(currentAppLang, "TMDB", tmdbApiKey) { tmdbApiKey = it }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(OxigenStrings.get(currentAppLang, "opensubtitles_account"), fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; status = 0 },
            label = { Text(OxigenStrings.get(currentAppLang, "username")) },
            modifier = Modifier.fillMaxWidth().tvFocusable(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; status = 0 },
            label = { Text(OxigenStrings.get(currentAppLang, "password")) },
            modifier = Modifier.fillMaxWidth().tvFocusable(),
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        OxigenStrings.get(
                            currentAppLang,
                            if (showPassword) "hide_password" else "show_password",
                        )
                    )
                }
            }
        )

        if (status == 3) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } else if (status == 2) {
            Text(OxigenStrings.get(currentAppLang, "login_success"), color = Color.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier.fillMaxWidth().tvFocusable(onClick = {
                if (status == 1) return@tvFocusable

                if (username.isBlank() || password.isBlank()) {
                    status = 3
                    errorMessage = OxigenStrings.get(currentAppLang, "enter_credentials")
                    return@tvFocusable
                }

                scope.launch {
                    status = 1
                    val token = subtitleSearchService.loginOpenSubtitles(username, password)
                    if (token != null) {
                        prefs.saveLogin(username)
                        prefs.saveToken(token)
                        password = ""
                        status = 2
                        kotlinx.coroutines.delay(1000)
                        onBack()
                    } else {
                        status = 3
                        errorMessage = OxigenStrings.get(currentAppLang, "invalid_credentials")
                    }
                }
            })
        ) {
            if (status == 1) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.Center), color = Color.White)
            } else {
                Text(
                    OxigenStrings.get(currentAppLang, "confirm_save"),
                    modifier = Modifier.padding(8.dp).align(Alignment.Center),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (prefs.getToken().isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().tvFocusable(onClick = {
                    prefs.clearAccount()
                    username = ""
                    password = ""
                    status = 0
                    onBack()
                })
            ) {
                Text(
                    OxigenStrings.get(currentAppLang, "sign_out"),
                    modifier = Modifier.padding(8.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ProviderKeyField(
    currentAppLang: String,
    providerName: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(String.format(java.util.Locale.ROOT, OxigenStrings.get(currentAppLang, "provider_api_key"), providerName)) },
        modifier = Modifier.fillMaxWidth().tvFocusable(),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(4.dp))
}

