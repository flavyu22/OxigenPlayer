// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oxigenplayer.app.BuildConfig
import com.oxigenplayer.app.CodecLicenseNotice
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Repository that carries the corresponding source for this GPL-licensed binary. */
private const val SOURCE_REPOSITORY = "https://github.com/flavyu22/OxigenPlayer"
private const val LICENSE_IDENTIFIER = "GPL-3.0-only"

@Composable
fun AboutDeveloperDialog(
    onDismiss: () -> Unit,
    appLang: String
) {
    var showLicense by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // GPLv3 requires the binary to carry its licence and to point at the corresponding source.
    // The text is 35 KB, so it is read off the main thread and only once the user asks for it.
    LaunchedEffect(showLicense) {
        if (!showLicense || licenseText != null) return@LaunchedEffect
        licenseText = withContext(Dispatchers.IO) {
            @Suppress("LocalContextResourcesRead")
            fun readRaw(resourceId: Int): String = runCatching {
                context.resources.openRawResource(resourceId)
                    .bufferedReader()
                    .use { reader -> reader.readText() }
            }.getOrNull().orEmpty()

            // The full flavor links GPLv3 software decoders, whose notice must travel with the
            // binary alongside the application's own licence.
            val codecNotice = CodecLicenseNotice.rawResourceId?.let(::readRaw).orEmpty()
            buildString {
                if (codecNotice.isNotBlank()) {
                    append(codecNotice.trimEnd()).append("\n\n").append("-".repeat(60)).append("\n\n")
                }
                append(readRaw(R.raw.gpl_3_0))
            }
        }
    }

    FastDialog(onDismiss = onDismiss) {
        OxigenCard(modifier = Modifier.standardDialogCardSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.oxigen_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .padding(bottom = 12.dp),
                )
                Text(
                    OxigenStrings.get(appLang, "about_dev_btn"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(OxigenStrings.get(appLang, "about_dev_title"))
                Spacer(modifier = Modifier.height(8.dp))
                Text(OxigenStrings.get(appLang, "developed_by"))
                Spacer(modifier = Modifier.height(4.dp))
                Text(OxigenStrings.get(appLang, "about_description"))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${OxigenStrings.get(appLang, "version_label")}: " +
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${OxigenStrings.get(appLang, "license_label")}: $LICENSE_IDENTIFIER",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    SOURCE_REPOSITORY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (showLicense) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        val text = licenseText
                        if (text == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(32.dp)
                            )
                        } else {
                            Text(
                                text = text,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showLicense = !showLicense }) {
                        Text(OxigenStrings.get(appLang, "license_label"))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(OxigenStrings.get(appLang, "close_btn"))
                    }
                }
            }
        }
    }
}
