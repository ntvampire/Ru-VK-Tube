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
    val watchDurationMs: Long = 0L,
    val lastStreamUrl: String? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<TvSeason> = emptyList(),
    val lastSeason: Int = 0,
    val lastEpisode: Int = 0
) : Serializable

data class TvSeason(
    val id: Long,
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterUrl: String? = null
) : Serializable

data class TvEpisode(
    val id: Long,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String? = null,
    val durationMinutes: Int = 0,
    val voteAverage: Double = 0.0
) : Serializable

data class CastMember(
    val id: Long,
    val name: String,
    val character: String,
    val profileUrl: String?
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
