package com.example.player88

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            var isPlaying by remember { mutableStateOf(false) }
            
            LaunchedEffect(Unit) {
                controllerFuture?.addListener({
                    val controller = controllerFuture?.get()
                    controller?.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }
                    })
                    isPlaying = controller?.isPlaying == true
                }, MoreExecutors.directExecutor())
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "88 Player", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val player = controller
                        if (player != null) {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                if (player.mediaItemCount == 0) {
                                    val mediaItem = MediaItem.Builder()
                                        .setMediaId("loop_88_test")
                                        .setUri("https://traffic.omny.fm/d/clips/23f697a0-7e6a-4e96-a223-a82c00962b12/a888a279-9911-4085-9a92-ab3900a0c251/d24f4a07-fd81-4112-b862-b49900f8b418/audio.mp3?utm_source=Podcast&in_playlist=425d386f-3564-4ec5-95d3-ab3900a0c251")
                                        .build()
                                    player.setMediaItem(mediaItem)
                                    player.prepare()
                                }
                                player.play()
                            }
                        }
                    }) {
                        Text(text = if (isPlaying) "Pause" else "Play")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
