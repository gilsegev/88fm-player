package com.example.player88

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val rssRepository = RssRepository()
    private lateinit var dataRepository: PlaybackDataRepository
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var progressJob: Job? = null

    private var cachedEpisodes: List<Episode> = emptyList()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        dataRepository = PlaybackDataRepository(applicationContext)
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes,  /* handleAudioFocus= */ true)
            .setSeekForwardIncrementMs(30000)
            .setSeekBackIncrementMs(30000)
            .build()
            
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressSaving()
                } else {
                    stopProgressSaving()
                }
            }
            
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                saveCurrentPosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlaybackService", "Player error: ${error.errorCodeName}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    // Stop player on network errors to prevent infinite buffering
                    player.stop()
                }
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                // Get default permissions (standard playback + library browsing)
                val connectionResult = super.onConnect(session, controller)
                
                // Add seek forward/back commands to the player permissions
                val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SEEK_BACK)
                    .build()
                
                // Preservation of session commands is critical for loading
                val sessionCommands = connectionResult.availableSessionCommands
                
                // Use safe Bundle copy to avoid NPE
                val extras = connectionResult.sessionExtras ?: Bundle.EMPTY
                val newExtras = Bundle(extras)
                newExtras.putBoolean("android.media.playback.hint.SLOT_RESERVATION_SKIP_TO_PREVIOUS", true)
                newExtras.putBoolean("android.media.playback.hint.SLOT_RESERVATION_SKIP_TO_NEXT", true)

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .setAvailableSessionCommands(sessionCommands)
                    .setSessionExtras(newExtras)
                    .build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != "root") {
                    return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }

                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    try {
                        rssRepository.fetchEpisodes().onSuccess { episodes ->
                            cachedEpisodes = episodes
                            val mediaItems = episodes.map { it.toMediaItem() }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params))
                        }.onFailure { error ->
                            Log.e("PlaybackService", "Failed to fetch episodes", error)
                            // Graceful fallback: Return cached episodes if available, or empty list
                            val items = if (cachedEpisodes.isNotEmpty()) {
                                cachedEpisodes.map { it.toMediaItem() }
                            } else {
                                emptyList()
                            }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                        }
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Exception in onGetChildren", e)
                        future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                    }
                }
                return future
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                    cachedEpisodes.find { it.id == item.mediaId }?.toMediaItem() ?: item
                }.toMutableList()
                return Futures.immediateFuture(updatedItems)
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                
                if (mediaItems.size == 1) {
                    val item = mediaItems[0]
                    val mediaId = item.mediaId
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    
                    serviceScope.launch {
                        val savedPosition = dataRepository.getPlaybackPosition(mediaId).first()
                        
                        val resumePosition = if (savedPosition > 10000) {
                            savedPosition
                        } else {
                            0L
                        }
                        
                        val fullItem = cachedEpisodes.find { it.id == mediaId }?.toMediaItem() ?: item
                        future.set(MediaSession.MediaItemsWithStartPosition(listOf(fullItem), 0, resumePosition))
                    }
                    return future
                }
                
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
        }).build()
    }

    private fun startProgressSaving() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (true) {
                delay(5000)
                saveCurrentPosition()
            }
        }
    }

    private fun stopProgressSaving() {
        progressJob?.cancel()
        progressJob = null
        saveCurrentPosition()
    }

    private fun saveCurrentPosition() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration
        
        serviceScope.launch {
            if (duration != C.TIME_UNSET && duration > 0 && position > duration * 0.95) {
                dataRepository.markAsPlayed(mediaId)
                progressJob?.cancel()
            } else {
                dataRepository.savePlaybackPosition(mediaId, position)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        serviceJob.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
