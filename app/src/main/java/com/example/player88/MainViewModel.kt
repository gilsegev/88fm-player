package com.example.player88

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class MainUiState {
    object Loading : MainUiState()
    data class Success(
        val episodes: List<Episode>,
        val playedStatuses: Map<String, Boolean>,
        val likedStatuses: Map<String, Boolean>
    ) : MainUiState()
    data class Error(val message: String) : MainUiState()
}

class MainViewModel(
    private val repository: RssRepository = RssRepository(),
    private val dataRepository: PlaybackDataRepository
) : ViewModel() {

    private val _episodes = MutableStateFlow<List<Episode>?>(null)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        _episodes,
        dataRepository.getAllPlayedStatuses(),
        dataRepository.getAllLikedStatuses(),
        dataRepository.getDislikedIds(),
        _error
    ) { episodes, playedStatuses, likedStatuses, dislikedIds, error ->
        Log.d("MainViewModel", "Combining: episodes=${episodes?.size}, error=$error")
        when {
            error != null -> MainUiState.Error(error)
            episodes != null -> {
                val filteredEpisodes = episodes.filter { !dislikedIds.contains(it.id) }
                MainUiState.Success(filteredEpisodes, playedStatuses, likedStatuses)
            }
            else -> MainUiState.Loading
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState.Loading
    )

    init {
        Log.d("MainViewModel", "Initializing and fetching feed")
        fetchFeed()
    }

    fun fetchFeed() {
        viewModelScope.launch {
            _error.value = null
            Log.d("MainViewModel", "Starting RSS fetch...")
            repository.fetchEpisodes()
                .onSuccess { episodes ->
                    Log.d("MainViewModel", "Fetch success: ${episodes.size} episodes")
                    _episodes.value = episodes
                }
                .onFailure { error ->
                    Log.e("MainViewModel", "Fetch failed", error)
                    _error.value = error.message ?: "Unknown error"
                }
        }
    }

    fun markAsPlayed(episodeId: String) {
        viewModelScope.launch {
            dataRepository.markAsPlayed(episodeId)
        }
    }

    fun clearAllCuration() {
        viewModelScope.launch {
            dataRepository.clearAllCuration()
            fetchFeed()
        }
    }
}
