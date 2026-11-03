package com.ruvk.tube.domain.models

import java.io.Serializable

enum class MediaType {
    MOVIE, TV
}

enum class VideoPlatform {
    RUTUBE, VK
}

data class MediaItem(
    val id: Long,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseDate: String,
    val year: Int,
    val voteAverage: Double,
    val runtimeMinutes: Int = 0,
    val mediaType: MediaType = MediaType.MOVIE,
    val imdbId: String? = null,
    val ageRating: String? = null,
    val ageRatingDescription: String? = null,
    val watchPositionMs: Long = 0L,
    val watchDurationMs: Long = 0L
) : Serializable

data class VideoSource(
    val platform: VideoPlatform,
    val title: String,
    val streamUrl: String,
    val quality: String,
    val durationSeconds: Int,
    val voiceover: String? = null,
    val isTrailer: Boolean = false,
    val score: Int = 0
) : Serializable
