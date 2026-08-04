package com.example.player88

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    private val testMediaItem = MediaItem.Builder()
        .setMediaId("loop_88_test")
        .setUri("https://traffic.omny.fm/d/clips/23f697a0-7e6a-4e96-a223-a82c00962b12/a888a279-9911-4085-9a92-ab3900a0c251/d24f4a07-fd81-4112-b862-b49900f8b418/audio.mp3?utm_source=Podcast&in_playlist=425d386f-3564-4ec5-95d3-ab3900a0c251")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Loop 88 Test Stream")
                .setArtist("KAN 88")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT,  /* handleAudioFocus= */ true)
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
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
                return if (parentId == "root") {
                    Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(testMediaItem), params))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }
            
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                   if (item.mediaId == "loop_88_test") testMediaItem else item
                }.toMutableList()
                return Futures.immediateFuture(updatedItems)
            }
        }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
