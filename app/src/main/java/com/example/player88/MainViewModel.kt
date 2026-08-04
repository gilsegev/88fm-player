package com.example.player88

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class MainUiState {
    object Loading : MainUiState()
    data class Success(val episodes: List<Episode>) : MainUiState()
    data class Error(val message: String) : MainUiState()
}

class MainViewModel(private val repository: RssRepository = RssRepository()) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        fetchFeed()
    }

    fun fetchFeed() {
        viewModelScope.launch {
            _uiState.value = MainUiState.Loading
            repository.fetchEpisodes()
                .onSuccess { episodes ->
                    _uiState.value = MainUiState.Success(episodes)
                }
                .onFailure { error ->
                    _uiState.value = MainUiState.Error(error.message ?: "Unknown error")
                }
        }
    }
}
