package com.example.player88

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class MainUiState {
    object Loading : MainUiState()
    data class Success(
        val episodes: List<Episode>,
        val playedStatuses: Map<String, Boolean>
    ) : MainUiState()
    data class Error(val message: String) : MainUiState()
}

class MainViewModel(
    private val repository: RssRepository = RssRepository(),
    private val dataRepository: PlaybackDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        viewModelScope.launch {
            dataRepository.getAllPlayedStatuses().collect { statuses ->
                val current = _uiState.value
                if (current is MainUiState.Success) {
                    _uiState.value = current.copy(playedStatuses = statuses)
                } else if (current is MainUiState.Loading) {
                    fetchFeed()
                }
            }
        }
    }

    fun fetchFeed() {
        viewModelScope.launch {
            repository.fetchEpisodes()
                .onSuccess { episodes ->
                    val statuses = dataRepository.getAllPlayedStatuses().first()
                    _uiState.value = MainUiState.Success(episodes, statuses)
                }
                .onFailure { error ->
                    _uiState.value = MainUiState.Error(error.message ?: "Unknown error")
                }
        }
    }

    fun markAsPlayed(episodeId: String) {
        viewModelScope.launch {
            dataRepository.markAsPlayed(episodeId)
        }
    }
}
