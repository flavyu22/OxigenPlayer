package com.example.oxigenplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val translationManager: TranslationManager,
    private val prefs: PreferencesManager
) : ViewModel() {
    // StateFlow for player state
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // Subtitle text input
    private val subtitleFlow = MutableStateFlow("")
    val translatedSubtitle: StateFlow<String> = subtitleFlow
        .debounce(300)
        .flatMapLatest { text ->
            flow {
                if (text.isNotEmpty() && _playerState.value.isTranslationEnabled) {
                    emit(translationManager.translate(text))
                } else {
                    emit("")
                }
            }.flowOn(Dispatchers.IO)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setSubtitleText(text: String) {
        subtitleFlow.value = text
    }

    fun setTranslationEnabled(enabled: Boolean) {
        _playerState.value = _playerState.value.copy(isTranslationEnabled = enabled)
    }

    // Add other player state update methods as needed
}

// PlayerState data class for player state management
// Add more fields as needed for your player

data class PlayerState(
    val isTranslationEnabled: Boolean = false
)

