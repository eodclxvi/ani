package com.anistream.data

import kotlinx.serialization.Serializable

data class AnimeResult(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
)

data class Episode(
    val dataId: String,
    val number: String,
)

data class StreamInfo(
    val videoUrl: String,
    val audioUrl: String?,
    val subtitleUrl: String?,
    val referer: String,
    val qualities: List<Quality>,
    /** The master playlist: lets the player pick quality itself and find separate audio/subtitle renditions. */
    val masterUrl: String = "",
)

data class Quality(
    val height: Int,
    val url: String,
)

@Serializable
data class FavoriteEntry(
    val animeId: String,
    val animeTitle: String,
    val posterUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WatchHistoryEntry(
    val animeId: String,
    val animeTitle: String,
    val posterUrl: String? = null,
    val lastEpisode: String,
    val lastEpisodeDataId: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
