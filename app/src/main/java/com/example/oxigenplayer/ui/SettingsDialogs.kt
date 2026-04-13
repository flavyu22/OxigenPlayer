
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
import androidx.compose.ui.input.key.*
import android.view.KeyEvent
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
    subtitleVerticalOffset: Float,
    onVerticalOffsetChange: (Float) -> Unit,
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                if (activeCategory == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = OxigenStrings.get(currentAppLang, "settings_title"), 
                            style = MaterialTheme.typography.headlineSmall, 
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CategoryButton(OxigenStrings.get(currentAppLang, "translation_label"), Icons.Default.Translate) { activeCategory = "translation" }
                            CategoryButton(OxigenStrings.get(currentAppLang, "style_label"), Icons.Default.ColorLens) { activeCategory = "style" }
                            CategoryButton(OxigenStrings.get(currentAppLang, "language_label"), Icons.Default.Language) { activeCategory = "lang" }
                            CategoryButton(if (currentAppLang == "ro") "Cont OpenSubtitles" else "OpenSubtitles Account", Icons.Default.AccountCircle) { activeCategory = "account" }

                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onShowAbout, 
                                modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(), 
                                shape = RoundedCornerShape(8.dp)
                            ) { 
                                Text(OxigenStrings.get(currentAppLang, "about_dev_btn")) 
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = onDismiss, 
                            modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(), 
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                        ) { 
                            Text(OxigenStrings.get(currentAppLang, "close_btn"), color = Color.White) 
                        }
                    }
                } else {
                    when (activeCategory) {
                        "translation" -> TranslationSettingsContent(
                            onBack = { activeCategory = null },
                            isTranslationEnabled = isTranslationEnabled,
                            onTranslationToggle = onTranslationToggle,
                            translationSource = translationSource,
                            onSourceChange = onSourceChange,
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
                            subtitleVerticalOffset = subtitleVerticalOffset,
                            onVerticalOffsetChange = onVerticalOffsetChange,
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
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesManager(context) }
    val searchService = remember { SubtitleSearchService() }
    
    var username by remember { mutableStateOf(prefs.getUsername()) }
    var password by remember { mutableStateOf(prefs.getPassword()) }
    var showPassword by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var loginStatus by remember { mutableStateOf<Boolean?>(null) } // null = netestat, true = succes, false = eroare

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack, 
                modifier = Modifier.size(48.dp).tvFocusable(isCircle = true)
            ) { 
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(28.dp)) 
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(if (currentAppLang == "ro") "Cont OpenSubtitles" else "OpenSubtitles Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { 
                username = it
                loginStatus = null 
            },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth().tvFocusable(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { 
                password = it
                loginStatus = null
            },
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

        if (loginStatus != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (loginStatus == true) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (loginStatus == true) Color.Green else Color.Red,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (loginStatus == true) 
                        (if (currentAppLang == "ro") "Cont verificat cu succes!" else "Account verified successfully!")
                    else 
                        (if (currentAppLang == "ro") "Date de logare incorecte." else "Invalid credentials."),
                    color = if (loginStatus == true) Color.Green else Color.Red,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) return@Button
                isChecking = true
                scope.launch {
                    val token = searchService.login(username, password)
                    isChecking = false
                    if (token != null) {
                        prefs.saveLogin(username, password)
                        prefs.saveToken(token)
                        loginStatus = true
                        // delay(1500)
                        // onBack()
                    } else {
                        loginStatus = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(),
            shape = RoundedCornerShape(8.dp),
            enabled = !isChecking
        ) {
            if (isChecking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(if (currentAppLang == "ro") "Verifică și Salvează" else "Verify and Save")
            }
        }
    }
}

@Composable
fun CategoryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .tvFocusable(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    currentAppLang: String,
    externalSubtitles: List<SubtitleEntry>,
    translatedMapSize: Int,
    isTranslatingAll: Boolean,
    onTranslateAll: () -> Unit
) {
    var showTargetPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack, 
                modifier = Modifier.size(48.dp).tvFocusable(isCircle = true)
            ) { 
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(28.dp)) 
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(OxigenStrings.get(currentAppLang, "translation_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        SettingsRow(OxigenStrings.get(currentAppLang, "enable_translation_label"), "") {
            Switch(isTranslationEnabled, onTranslationToggle, modifier = Modifier.tvFocusable())
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(OxigenStrings.get(currentAppLang, "translation_engine"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        Button(
            onClick = { 
                onSourceChange(TranslationSource.AUTO)
                onTranslationToggle(true)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (translationSource == TranslationSource.AUTO) MaterialTheme.colorScheme.primary else Color.DarkGray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(bottom = 8.dp)
                .tvFocusable(isSelected = translationSource == TranslationSource.AUTO),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text(OxigenStrings.get(currentAppLang, "auto_ai"), fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.MLKIT) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MLKIT) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.MLKIT), shape = RoundedCornerShape(8.dp)) { Text("ML Kit") }
                Button(onClick = { onSourceChange(TranslationSource.GOOGLE_FREE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.GOOGLE_FREE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.GOOGLE_FREE), shape = RoundedCornerShape(8.dp)) { Text("Google") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.MY_MEMORY) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.MY_MEMORY) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.MY_MEMORY), shape = RoundedCornerShape(8.dp)) { Text("MyMemory") }
                Button(onClick = { onSourceChange(TranslationSource.LIBRE_TRANSLATE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.LIBRE_TRANSLATE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.LIBRE_TRANSLATE), shape = RoundedCornerShape(8.dp)) { Text("Libre") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.LINGVA) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.LINGVA) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.LINGVA), shape = RoundedCornerShape(8.dp)) { Text("Lingva") }
                Button(onClick = { onSourceChange(TranslationSource.ARGOS) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.ARGOS) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.ARGOS), shape = RoundedCornerShape(8.dp)) { Text("Argos") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceChange(TranslationSource.DEEPL_FREE) }, colors = ButtonDefaults.buttonColors(containerColor = if (translationSource == TranslationSource.DEEPL_FREE) MaterialTheme.colorScheme.primary else Color.Gray), modifier = Modifier.weight(1f).height(48.dp).tvFocusable(isSelected = translationSource == TranslationSource.DEEPL_FREE), shape = RoundedCornerShape(8.dp)) { Text("DeepL") }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Button(
            onClick = onTranslateAll,
            enabled = !isTranslatingAll && externalSubtitles.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(),
            shape = RoundedCornerShape(8.dp)
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

        SettingsRow(OxigenStrings.get(currentAppLang, "target_lang"), "${targetLang.uppercase()}") {
            Button(onClick = { showTargetPicker = true }, modifier = Modifier.height(44.dp).tvFocusable(), shape = RoundedCornerShape(8.dp)) { Text(OxigenStrings.get(currentAppLang, "change")) }
        }

        if (showTargetPicker) LanguagePickerDialog(OxigenStrings.get(currentAppLang, "target_lang"), availableLanguages, { showTargetPicker = false }, { onTargetLangChange(it); showTargetPicker = false })
    }
}

@Composable
fun SubtitleStyleContent(
    onBack: () -> Unit,
    subtitleFontSize: Float, onFontSizeChange: (Float) -> Unit,
    subtitleColor: Color, onColorChange: (Color) -> Unit,
    subtitleBgColor: Color, onBgColorChange: (Color) -> Unit,
    subtitleVerticalOffset: Float, onVerticalOffsetChange: (Float) -> Unit,
    currentAppLang: String
) {
    val colors = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Red, Color.Magenta)
    val bgColors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black, Color.DarkGray.copy(alpha = 0.5f))

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack, 
                modifier = Modifier.size(48.dp).tvFocusable(isCircle = true)
            ) { 
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(28.dp)) 
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(OxigenStrings.get(currentAppLang, "style_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        TvStyleSlider(
            label = OxigenStrings.get(currentAppLang, "font_size_label"),
            value = subtitleFontSize,
            onValueChange = onFontSizeChange,
            valueRange = 10f..100f,
            unit = "sp"
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        TvStyleSlider(
            label = OxigenStrings.get(currentAppLang, "vertical_pos_label"),
            value = subtitleVerticalOffset,
            onValueChange = onVerticalOffsetChange,
            valueRange = 0f..300f,
            unit = "dp"
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
fun TvStyleSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String
) {
    var isEditing by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "$label: ${value.toInt()} $unit" + (if (isEditing) " [OK părăsește]" else " [OK editează]"),
            color = if (isEditing) Color.Yellow else if (isFocused) Color.White else Color.Gray,
            fontWeight = if (isEditing || isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, 
                            KeyEvent.KEYCODE_ENTER -> {
                                isEditing = !isEditing
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (isEditing) {
                                    val step = (valueRange.endInclusive - valueRange.start) / 30f
                                    onValueChange((value - step).coerceIn(valueRange))
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (isEditing) {
                                    val step = (valueRange.endInclusive - valueRange.start) / 30f
                                    onValueChange((value + step).coerceIn(valueRange))
                                    true
                                } else false
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                if (isEditing) {
                                    isEditing = false
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusable(interactionSource = interactionSource, isSelected = isEditing),
            colors = SliderDefaults.colors(
                thumbColor = if (isEditing) Color.Yellow else Color.Red,
                activeTrackColor = if (isEditing) Color.Yellow else Color.Red,
                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
            )
        )
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
            IconButton(
                onClick = onBack, 
                modifier = Modifier.size(48.dp).tvFocusable(isCircle = true)
            ) { 
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(28.dp)) 
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(OxigenStrings.get(currentAppLang, "language_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(appLanguages.entries.toList()) { entry ->
                Button(
                    onClick = { onAppLanguageChange(entry.key) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).tvFocusable(isSelected = currentAppLang == entry.key),
                    shape = RoundedCornerShape(8.dp),
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
        Card(modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.85f)) {
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
