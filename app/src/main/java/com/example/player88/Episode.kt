package com.example.player88

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Episode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val pubDate: String,
    val duration: Long,
    val imageUrl: String
)

fun Episode.toMediaItem(): MediaItem {
    val durationMs = duration * 1000L
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle("${pubDate} • ${duration / 60}m")
                .setArtworkUri(Uri.parse(imageUrl))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(Bundle().apply {
                    // Use standard metadata key for duration that Android Auto recognizes
                    putLong("android.media.metadata.DURATION", durationMs)
                })
                .build()
        )
        .build()
}
