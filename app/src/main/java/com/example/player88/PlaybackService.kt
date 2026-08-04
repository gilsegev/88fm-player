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
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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

    companion object {
        const val ACTION_THUMBS_UP = "com.example.player88.THUMBS_UP"
        const val ACTION_THUMBS_DOWN = "com.example.player88.THUMBS_DOWN"
        const val TAG = "PlaybackService"
    }

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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                // Get default permissions (standard playback + library browsing)
                val connectionResult = super.onConnect(session, controller)
                Log.d(TAG, "onConnect from ${controller.packageName}. Browsable: ${connectionResult.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)}")
                
                // Add seek commands and custom curation commands
                val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
                
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_THUMBS_UP, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_THUMBS_DOWN, Bundle.EMPTY))
                    .build()
                
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

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val mediaId = player.currentMediaItem?.mediaId 
                Log.d(TAG, "onCustomCommand: ${customCommand.customAction} for mediaId: $mediaId")
                
                if (mediaId == null) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
                
                val librarySession = session as MediaLibrarySession
                
                when (customCommand.customAction) {
                    ACTION_THUMBS_UP -> {
                        serviceScope.launch {
                            dataRepository.toggleLiked(mediaId)
                            
                            // 1. Update the buttons immediately
                            updateCustomLayout()
                            
                            // 2. Update the list immediately (to show the heart)
                            librarySession.notifyChildrenChanged("root", 0, null)
                            
                            // 3. Update current item metadata immediately (to show heart in player title)
                            val isLikedNow = dataRepository.isLiked(mediaId).first()
                            val currentItem = player.currentMediaItem
                            if (currentItem != null && currentItem.mediaId == mediaId) {
                                val episodes = rssRepository.fetchEpisodes().getOrNull()
                                val updatedItem = episodes?.find { it.id == mediaId }?.toMediaItem(
                                    isPlayed = true,
                                    isLiked = isLikedNow
                                ) ?: currentItem
                                
                                val currentIndex = player.currentMediaItemIndex
                                val currentPos = player.currentPosition
                                player.replaceMediaItem(currentIndex, updatedItem)
                                player.seekTo(currentIndex, currentPos)
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                    ACTION_THUMBS_DOWN -> {
                        serviceScope.launch {
                            // 1. Persist the dislike
                            dataRepository.markDisliked(mediaId)
                            
                            // 2. Kill playback and remove from queue immediately
                            val currentIndex = player.currentMediaItemIndex
                            player.removeMediaItem(currentIndex)
                            
                            // 3. Update the list immediately (to hide the episode)
                            // Use empty LibraryParams to ensure a fresh reload
                            val librarySession = session as MediaLibrarySession
                            librarySession.notifyChildrenChanged("root", 0, null)
                            
                            // Force a notifyChildrenChanged for legacy clients or specific AA versions
                            delay(500)
                            librarySession.notifyChildrenChanged("root", 0, null)
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
                        val playedStatuses = dataRepository.getAllPlayedStatuses().first()
                        val likedStatuses = dataRepository.getAllLikedStatuses().first()
                        val dislikedIds = dataRepository.getDislikedIds().first()
                        
                        rssRepository.fetchEpisodes().onSuccess { episodes ->
                            cachedEpisodes = episodes
                            val mediaItems = episodes
                                .filter { !dislikedIds.contains(it.id) }
                                .map { episode ->
                                    episode.toMediaItem(
                                        isPlayed = playedStatuses[episode.id] == true,
                                        isLiked = likedStatuses[episode.id] == true
                                    )
                                }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params))
                        }.onFailure { error ->
                            Log.e("PlaybackService", "Failed to fetch episodes", error)
                            val items = cachedEpisodes
                                .filter { !dislikedIds.contains(it.id) }
                                .map { episode ->
                                    episode.toMediaItem(
                                        isPlayed = playedStatuses[episode.id] == true,
                                        isLiked = likedStatuses[episode.id] == true
                                    )
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
                val future = SettableFuture.create<MutableList<MediaItem>>()
                serviceScope.launch {
                    val playedStatuses = dataRepository.getAllPlayedStatuses().first()
                    val likedStatuses = dataRepository.getAllLikedStatuses().first()
                    
                    val updatedItems = mediaItems.map { item ->
                        cachedEpisodes.find { it.id == item.mediaId }?.toMediaItem(
                            isPlayed = playedStatuses[item.mediaId] == true,
                            isLiked = likedStatuses[item.mediaId] == true
                        ) ?: item
                    }.toMutableList()
                    future.set(updatedItems)
                }
                return future
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
                        val isPlayed = dataRepository.isPlayed(mediaId).first()
                        val isLiked = dataRepository.isLiked(mediaId).first()
                        
                        val resumePosition = if (savedPosition > 10000) {
                            savedPosition
                        } else {
                            0L
                        }
                        
                        val fullItem = cachedEpisodes.find { it.id == mediaId }?.toMediaItem(
                            isPlayed = isPlayed,
                            isLiked = isLiked
                        ) ?: item
                        future.set(MediaSession.MediaItemsWithStartPosition(listOf(fullItem), 0, resumePosition))
                    }
                    return future
                }
                
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
        }).build()
    }

    @OptIn(UnstableApi::class)
    private fun updateCustomLayout() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        serviceScope.launch {
            val isLiked = dataRepository.isLiked(mediaId).first()
            Log.d(TAG, "updateCustomLayout: isLiked=$isLiked for $mediaId")
            
            val thumbsUpButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(ACTION_THUMBS_UP, Bundle.EMPTY))
                .setIconResId(if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up)
                .setDisplayName(if (isLiked) "Liked" else "Like")
                .setEnabled(true)
                .build()

            val thumbsDownButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(ACTION_THUMBS_DOWN, Bundle.EMPTY))
                .setIconResId(R.drawable.ic_thumb_down)
                .setDisplayName("Dislike")
                .setEnabled(true)
                .build()

            mediaLibrarySession?.setCustomLayout(listOf(thumbsUpButton, thumbsDownButton))
        }
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
