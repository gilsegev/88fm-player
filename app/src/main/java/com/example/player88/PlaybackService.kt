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
import androidx.media3.session.SessionCommands
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
import kotlinx.coroutines.withTimeoutOrNull

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
                Log.e(TAG, "Player error: ${error.errorCodeName}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
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
                val connectionResult = super.onConnect(session, controller)
                
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                    .add(SessionCommand(ACTION_THUMBS_UP, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_THUMBS_DOWN, Bundle.EMPTY))
                    .build()
                
                val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()

                val extras = Bundle(connectionResult.sessionExtras ?: Bundle.EMPTY)
                extras.putBoolean("android.media.playback.hint.SLOT_RESERVATION_SKIP_TO_PREVIOUS", true)
                extras.putBoolean("android.media.playback.hint.SLOT_RESERVATION_SKIP_TO_NEXT", true)

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .setSessionExtras(extras)
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
                            updateCustomLayout()
                            librarySession.notifyChildrenChanged("root", 0, null)
                        }
                    }
                    ACTION_THUMBS_DOWN -> {
                        serviceScope.launch {
                            Log.i(TAG, "DEBUG: ACTION_THUMBS_DOWN triggered for $mediaId")
                            
                            // 1. Mark as disliked (wait for write)
                            dataRepository.markDisliked(mediaId)
                            Log.i(TAG, "DEBUG: Episode $mediaId marked as disliked in DataStore")
                            
                            // 2. Kill playback immediately
                            val currentIndex = player.currentMediaItemIndex
                            if (player.mediaItemCount > 1) {
                                player.removeMediaItem(currentIndex)
                                Log.i(TAG, "DEBUG: Removed media item at index $currentIndex, skipping to next")
                            } else {
                                player.stop()
                                player.clearMediaItems()
                                Log.i(TAG, "DEBUG: Last item removed, stopped player")
                            }
                            
                            // 3. Force Android Auto list refresh
                            // Send multiple times to ensure we beat the car's cache
                            librarySession.notifyChildrenChanged("root", 0, null)
                            delay(300)
                            librarySession.notifyChildrenChanged("root", 0, null)
                            delay(1000)
                            librarySession.notifyChildrenChanged("root", 0, null)
                            Log.i(TAG, "DEBUG: All refresh signals sent to AA")
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
                            .setTitle("UVU fm")
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

                Log.i(TAG, "DEBUG: onGetChildren requested for $parentId")
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    try {
                        val playedStatuses = withTimeoutOrNull(2000) { dataRepository.getAllPlayedStatuses().first() } ?: emptyMap()
                        val likedStatuses = withTimeoutOrNull(2000) { dataRepository.getAllLikedStatuses().first() } ?: emptyMap()
                        val dislikedIds = withTimeoutOrNull(2000) { dataRepository.getDislikedIds().first() } ?: emptySet()
                        Log.i(TAG, "DEBUG: DataStore fetch complete. DislikedIds size: ${dislikedIds.size}")
                        
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
                            Log.i(TAG, "DEBUG: onGetChildren: Returning ${mediaItems.size} items. Filtered out ${episodes.size - mediaItems.size} disliked.")
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params))
                        }.onFailure { error ->
                            Log.e(TAG, "DEBUG: RSS fetch failed", error)
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
                        Log.e(TAG, "onGetChildren exception", e)
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
                    val playedStatuses = withTimeoutOrNull(1000) { dataRepository.getAllPlayedStatuses().first() } ?: emptyMap()
                    val likedStatuses = withTimeoutOrNull(1000) { dataRepository.getAllLikedStatuses().first() } ?: emptyMap()
                    
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
                        val savedPosition = withTimeoutOrNull(1000) { dataRepository.getPlaybackPosition(mediaId).first() } ?: 0L
                        val isPlayed = withTimeoutOrNull(1000) { dataRepository.isPlayed(mediaId).first() } ?: false
                        val isLiked = withTimeoutOrNull(1000) { dataRepository.isLiked(mediaId).first() } ?: false
                        
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
            val isLiked = withTimeoutOrNull(1000) { dataRepository.isLiked(mediaId).first() } ?: false
            
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
