
package com.example.oxigenplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.oxigenplayer.*

@Composable
fun MainSettingsDialog(
    onDismiss: () -> Unit,
    isTranslationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource,
    onSourceChange: (TranslationSource) -> Unit,
    subtitleFontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    subtitleColor: Color,
    onColorChange: (Color) -> Unit,
    subtitleBgColor: Color,
    onBgColorChange: (Color) -> Unit,
    sourceLang: String,
    onSourceLangChange: (String) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    onAppLanguageChange: (String) -> Unit,
    currentAppLang: String,
    onShowAbout: () -> Unit,
    externalSubtitles: List<SubtitleEntry>,
    translatedMapSize: Int,
    isTranslatingAll: Boolean,
    onTranslateAll: () -> Unit
) {
    var activeCategory by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.8f), 
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                if (activeCategory == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(OxigenStrings.get(currentAppLang, "settings_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                        CategoryButton(OxigenStrings.get(currentAppLang, "translation_label"), Icons.Default.Translate) { activeCategory = "translation" }
                        CategoryButton(OxigenStrings.get(currentAppLang, "style_label"), Icons.Default.ColorLens) { activeCategory = "style" }
                        CategoryButton(OxigenStrings.get(currentAppLang, "language_label"), Icons.Default.Language) { activeCategory = "lang" }
                        CategoryButton(if (currentAppLang == "ro") "Cont OpenSubtitles" else "OpenSubtitles Account", Icons.Default.AccountCircle) { activeCategory = "account" }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onShowAbout, modifier = Modifier.fillMaxWidth().tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "about_dev_btn")) }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "close_btn")) }
                    }
                } else {
                    when (activeCategory) {
                        "translation" -> TranslationSettingsContent(
                            onBack = { activeCategory = null },
                            isTranslationEnabled = isTranslationEnabled,
                            onTranslationToggle = onTranslationToggle,
                            translationSource = translationSource,
                            onSourceChange = onSourceChange,
                            sourceLang = sourceLang,
                            onSourceLangChange = sourceLang,
                            sourceLangReal = sourceLang,
                            onSourceLangChangeReal = onSourceLangChange,
                            targetLang = targetLang,
                            onTargetLangChange = onTargetLangChange,
                            availableLanguages = availableLanguages,
                            currentAppLang = currentAppLang,
                            externalSubtitles = externalSubtitles,
                            translatedMapSize = translatedMapSize,
                            isTranslatingAll = isTranslatingAll,
                            onTranslateAll = onTranslateAll
                        )
                        "style" -> SubtitleStyleContent(
                            onBack = { activeCategory = null },
                            subtitleFontSize = subtitleFontSize,
                            onFontSizeChange = onFontSizeChange,
                            subtitleColor = subtitleColor,
                            onColorChange = onColorChange,
                            subtitleBgColor = subtitleBgColor,
                            onBgColorChange = onBgColorChange,
                            currentAppLang = currentAppLang
                        )
                        "lang" -> LanguageSettingsContent(
                            onBack = { activeCategory = null },
                            currentAppLang = currentAppLang,
                            onAppLanguageChange = onAppLanguageChange
                        )
                        "account" -> AccountSettingsContent(
                            onBack = { activeCategory = null },
                            currentAppLang = currentAppLang
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccountSettingsContent(onBack: () -> Unit, currentAppLang: String) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var username by remember { mutableStateOf(prefs.getUsername()) }
    var password by remember { mutableStateOf(prefs.getPassword()) }
    var showPassword by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
            Text(if (currentAppLang == "ro") "Cont OpenSubtitles" else "OpenSubtitles Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().tvFocusable(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth().tvFocusable(),
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                prefs.saveLogin(username, password)
                // Resetam token-ul pentru a forta o noua logare cu noile date
                prefs.saveToken("")
                onBack()
            },
            modifier = Modifier.fillMaxWidth().tvFocusable()
        ) {
            Text(if (currentAppLang == "ro") "Salvează" else "Save")
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
fun TranslationSettingsContent(
    onBack: () -> Unit,
    isTranslationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource,
    onSourceChange: (TranslationSource) -> Unit,
    sourceLang: String,
    onSourceLangChange: String,
    sourceLangReal: String,
    onSourceLangChangeReal: (String) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    currentAppLang: String,
    externalSubtitles: List<SubtitleEntry>,
    translatedMapSize: Int,
    isTranslatingAll: Boolean,
    onTranslateAll: () -> Unit
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
            Text(OxigenStrings.get(currentAppLang, "translation_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        SettingsRow(OxigenStrings.get(currentAppLang, "enable_translation_label"), "") {
            Switch(isTranslationEnabled, onTranslationToggle, modifier = Modifier.tvFocusable())
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(OxigenStrings.get(currentAppLang, "translation_engine"), fontWeight = FontWeight.Bold)
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.MLKIT) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MLKIT) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.MLKIT)) { Text("ML Kit") }
                Button(onClick = { onSourceChange(TranslationSource.GOOGLE_TRANSLATE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.GOOGLE_TRANSLATE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.GOOGLE_TRANSLATE)) { Text("Google") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.MY_MEMORY) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MY_MEMORY) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.MY_MEMORY)) { Text("MyMemory") }
                Button(onClick = { onSourceChange(TranslationSource.LIBRE_TRANSLATE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.LIBRE_TRANSLATE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).tvFocusable(isSelected = translationSource == TranslationSource.LIBRE_TRANSLATE)) { Text("Libre") }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Button(
            onClick = onTranslateAll,
            enabled = !isTranslatingAll && externalSubtitles.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().tvFocusable()
        ) {
            if (isTranslatingAll) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text(OxigenStrings.get(currentAppLang, "translate_all") + " (${externalSubtitles.size})")
            }
        }
        if (translatedMapSize > 0) {
            Text("$translatedMapSize / ${externalSubtitles.size} translated", fontSize = 12.sp)
        }


        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SettingsRow(OxigenStrings.get(currentAppLang, "source_lang"), "${sourceLangReal.uppercase()}") {
            Button(onClick = { showSourcePicker = true }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "change")) }
        }

        SettingsRow(OxigenStrings.get(currentAppLang, "target_lang"), "${targetLang.uppercase()}") {
            Button(onClick = { showTargetPicker = true }, modifier = Modifier.tvFocusable()) { Text(OxigenStrings.get(currentAppLang, "change")) }
        }

        if (showSourcePicker) LanguagePickerDialog(OxigenStrings.get(currentAppLang, "source_lang"), availableLanguages, { showSourcePicker = false }, { onSourceLangChangeReal(it); showSourcePicker = false })
        if (showTargetPicker) LanguagePickerDialog(OxigenStrings.get(currentAppLang, "target_lang"), availableLanguages, { showTargetPicker = false }, { onTargetLangChange(it); showTargetPicker = false })
    }
}

@Composable
fun SubtitleStyleContent(
    onBack: () -> Unit,
    subtitleFontSize: Float, onFontSizeChange: (Float) -> Unit,
    subtitleColor: Color, onColorChange: (Color) -> Unit,
    subtitleBgColor: Color, onBgColorChange: (Color) -> Unit,
    currentAppLang: String
) {
    val colors = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Red, Color.Magenta)
    val bgColors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black, Color.DarkGray.copy(alpha = 0.5f))

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
            Text(OxigenStrings.get(currentAppLang, "style_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(OxigenStrings.get(currentAppLang, "font_size_label") + ": ${subtitleFontSize.toInt()} sp", modifier = Modifier.weight(1f))
            
            IconButton(
                onClick = { onFontSizeChange((subtitleFontSize - 2f).coerceAtLeast(10f)) },
                modifier = Modifier.tvFocusable(isCircle = true)
            ) { Icon(Icons.Default.Remove, null) }
            
            IconButton(
                onClick = { onFontSizeChange((subtitleFontSize + 2f).coerceAtMost(100f)) },
                modifier = Modifier.tvFocusable(isCircle = true)
            ) { Icon(Icons.Default.Add, null) }
        }

        Slider(
            value = subtitleFontSize, 
            onValueChange = onFontSizeChange, 
            valueRange = 10f..100f,
            modifier = Modifier.fillMaxWidth().tvFocusable()
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(OxigenStrings.get(currentAppLang, "style_label"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(colors) { color ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .tvFocusable(isCircle = true, isSelected = subtitleColor == color)
                        .background(color, CircleShape)
                        .clickable { onColorChange(color) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(OxigenStrings.get(currentAppLang, "style_label") + " Background")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(bgColors) { color ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .tvFocusable(isCircle = true, isSelected = subtitleBgColor == color)
                        .background(color, CircleShape)
                        .clickable { onBgColorChange(color) }
                )
            }
        }
    }
}

@Composable
fun LanguageSettingsContent(onBack: () -> Unit, currentAppLang: String, onAppLanguageChange: (String) -> Unit) {
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.tvFocusable(isCircle = true)) { Icon(Icons.Default.ArrowBack, null) }
            Text(OxigenStrings.get(currentAppLang, "language_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
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
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .background(if (isFocused) Color.White.copy(0.1f) else Color.Transparent)
                                .clickable { onSelect(lang) }
                                .padding(16.dp)) {
                            Text(lang.uppercase(), color = if (isFocused) Color.Yellow else Color.White)
                        }
                    }
                }
            }
        }
    }
}
