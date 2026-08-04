package com.example.player88

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val viewModel: MainViewModel = viewModel()
                    val uiState by viewModel.uiState.collectAsState()

                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        when (val state = uiState) {
                            is MainUiState.Loading -> {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                            is MainUiState.Success -> {
                                EpisodeList(state.episodes)
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

@Composable
fun EpisodeList(episodes: List<Episode>) {
    LazyColumn {
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(episode)
        }
    }
}

@Composable
fun EpisodeRow(episode: Episode) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            AsyncImage(
                model = episode.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                Text(
                    text = episode.pubDate,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${episode.duration / 60} minutes",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Error: $message", color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
