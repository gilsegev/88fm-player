package com.example.player88

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val sessionToken = remember {
                SessionToken(context, ComponentName(context, PlaybackService::class.java))
            }
            var controller by remember { mutableStateOf<MediaController?>(null) }
            var currentEpisode by remember { mutableStateOf<Episode?>(null) }
            var activeMediaItem by remember { mutableStateOf<MediaItem?>(null) }

            DisposableEffect(sessionToken) {
                val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                controllerFuture.addListener({
                    val c = controllerFuture.get()
                    controller = c
                    activeMediaItem = c.currentMediaItem
                    c.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            activeMediaItem = mediaItem
                        }
                    })
                }, MoreExecutors.directExecutor())

                onDispose {
                    MediaController.releaseFuture(controllerFuture)
                    controller = null
                }
            }

            // Handle system back button
            BackHandler(enabled = currentEpisode != null) {
                currentEpisode = null
            }

            MaterialTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            val dataRepository = PlaybackDataRepository(context)
                            return MainViewModel(dataRepository = dataRepository) as T
                        }
                    }
                )
                val uiState by viewModel.uiState.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    floatingActionButton = {
                        // Only show FAB if we are on the list screen AND something is active in the player
                        if (currentEpisode == null && activeMediaItem != null) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    val state = uiState
                                    if (state is MainUiState.Success) {
                                        val episode = state.episodes.find { it.id == activeMediaItem?.mediaId }
                                        if (episode != null) {
                                            currentEpisode = episode
                                        }
                                    }
                                },
                                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                text = { Text("Now Playing") },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (currentEpisode != null) {
                            val isLiked = (uiState as? MainUiState.Success)?.likedStatuses?.get(currentEpisode!!.id) == true
                            PlayerScreen(
                                episode = currentEpisode!!,
                                controller = controller,
                                isLiked = isLiked,
                                onBack = { currentEpisode = null }
                            )
                        } else {
                            when (val state = uiState) {
                                is MainUiState.Loading -> {
                                    Log.d("MainActivity", "UI State: Loading")
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                }
                                is MainUiState.Success -> {
                                    Log.d("MainActivity", "UI State: Success (${state.episodes.size} episodes)")
                                    Column {
                                        Button(
                                            onClick = { viewModel.clearAllCuration() },
                                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                                        ) {
                                            Text("Reset Disliked/Liked Episodes")
                                        }
                                        EpisodeList(
                                            episodes = state.episodes,
                                            playedStatuses = state.playedStatuses,
                                            likedStatuses = state.likedStatuses,
                                            onEpisodeClick = { episode ->
                                                currentEpisode = episode
                                                viewModel.markAsPlayed(episode.id)
                                                controller?.apply {
                                                    val isLiked = state.likedStatuses[episode.id] == true
                                                    setMediaItem(episode.toMediaItem(isPlayed = true, isLiked = isLiked))
                                                    prepare()
                                                    play()
                                                }
                                            }
                                        )
                                    }
                                }
                                is MainUiState.Error -> {
                                    ErrorView(state.message) { viewModel.fetchFeed() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    episode: Episode,
    controller: MediaController?,
    isLiked: Boolean,
    onBack: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(controller?.isPlaying ?: false) }
    var currentPosition by remember { mutableLongStateOf(controller?.currentPosition ?: 0L) }
    var duration by remember { mutableLongStateOf(controller?.duration ?: 0L) }

    // Listen to player state changes
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = controller?.duration ?: 0L
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    // Polling progress loop
    LaunchedEffect(controller, isPlaying) {
        if (isPlaying) {
            while (true) {
                currentPosition = controller?.currentPosition ?: 0L
                val playerDuration = controller?.duration ?: 0L
                duration = if (playerDuration > 0) playerDuration else duration
                delay(1000)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        AsyncImage(
            model = episode.imageUrl,
            contentDescription = null,
            modifier = Modifier.size(300.dp).padding(vertical = 32.dp),
            contentScale = ContentScale.Crop
        )

        Text(
            text = episode.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // Liked/Disliked Curation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    controller?.sendCustomCommand(
                        SessionCommand(PlaybackService.ACTION_THUMBS_DOWN, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                    onBack()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.ThumbDown, contentDescription = "Dislike")
            }

            Spacer(modifier = Modifier.width(32.dp))

            IconButton(
                onClick = {
                    controller?.sendCustomCommand(
                        SessionCommand(PlaybackService.ACTION_THUMBS_UP, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress Bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                onValueChange = { ratio ->
                    if (duration > 0) {
                        currentPosition = (ratio * duration).toLong()
                    }
                },
                onValueChangeFinished = {
                    controller?.seekTo(currentPosition)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(currentPosition), style = MaterialTheme.typography.bodySmall)
                Text(text = formatTime(duration), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Playback Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = { controller?.seekToPrevious() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track")
            }

            IconButton(onClick = { controller?.seekBack() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Replay30,
                    contentDescription = "Seek Back 30s",
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = {
                    if (isPlaying) controller?.pause() else controller?.play()
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(onClick = { controller?.seekForward() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Forward30,
                    contentDescription = "Seek Forward 30s",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = { controller?.seekToNext() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next Track")
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun EpisodeList(
    episodes: List<Episode>,
    playedStatuses: Map<String, Boolean>,
    likedStatuses: Map<String, Boolean>,
    onEpisodeClick: (Episode) -> Unit
) {
    LazyColumn {
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                isPlayed = playedStatuses[episode.id] == true,
                isLiked = likedStatuses[episode.id] == true,
                onEpisodeClick = onEpisodeClick
            )
        }
    }
}

@Composable
fun EpisodeRow(
    episode: Episode,
    isPlayed: Boolean,
    isLiked: Boolean,
    onEpisodeClick: (Episode) -> Unit
) {
    // Clean up pubDate by removing common timezone strings for the Phone UI
    val cleanPubDate = episode.pubDate.replace(" +0000", "").replace(" GMT", "")
    val likedIndicator = if (isLiked) " ❤️" else ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onEpisodeClick(episode) }
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = episode.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${episode.title}$likedIndicator",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = if (isPlayed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = cleanPubDate,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${episode.duration / 60} minutes",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (isPlayed) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Error: $message", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
