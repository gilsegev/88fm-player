package com.example.player88

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

data class Episode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val pubDate: String,
    val duration: Long,
    val imageUrl: String
)

@OptIn(UnstableApi::class)
fun Episode.toMediaItem(isPlayed: Boolean = false, isLiked: Boolean = false): MediaItem {
    val durationMs = duration * 1000L
    val playedIndicator = if (isPlayed) "  ✓ Played" else ""
    val likedIndicator = if (isLiked) " ❤️" else ""
    
    // Remove +0000 and GMT for a cleaner look in Phone and Auto UI
    val cleanPubDate = pubDate.replace(" +0000", "").replace(" GMT", "")
    
    // Use the custom branding image for all playback metadata
    val artworkUri = Uri.parse("android.resource://com.example.player88/drawable/uvu_playback_art")
    
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("$title$likedIndicator")
                .setSubtitle("${cleanPubDate} • ${duration / 60}m$playedIndicator")
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle().apply {
                    putLong("android.media.metadata.DURATION", durationMs)
                    if (isPlayed) {
                        putInt(
                            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                        )
                    }
                })
                .build()
        )
        .build()
}
