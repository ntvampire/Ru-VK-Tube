package com.ruvk.tube.data.api.tmdb

import com.google.gson.annotations.SerializedName

data class TmdbPageResponse<T>(
    @SerializedName("page") val page: Int,
    @SerializedName("results") val results: List<T>,
    @SerializedName("total_pages") val totalPages: Int,
    @SerializedName("total_results") val totalResults: Int
)

data class TmdbMovieDto(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("original_title") val originalTitle: String?,
    @SerializedName("original_name") val originalName: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("popularity") val popularity: Double? = null,
    @SerializedName("media_type") val mediaType: String?
)

data class TmdbMovieDetailsDto(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String?,
    @SerializedName("original_title") val originalTitle: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("runtime") val runtime: Int?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("imdb_id") val imdbId: String?,
    @SerializedName("release_dates") val releaseDates: TmdbReleaseDatesContainer?,
    @SerializedName("credits") val credits: TmdbCreditsDto? = null
)

data class TmdbTvDetailsDto(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String?,
    @SerializedName("original_name") val originalName: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int>?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int?,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int?,
    @SerializedName("content_ratings") val contentRatings: TmdbContentRatingsContainer?,
    @SerializedName("credits") val credits: TmdbCreditsDto? = null,
    @SerializedName("seasons") val seasons: List<TmdbSeasonDto>? = null
)

data class TmdbSeasonDto(
    @SerializedName("id") val id: Long,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("air_date") val airDate: String? = null
)

data class TmdbSeasonDetailsDto(
    @SerializedName("id") val id: Long,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("episodes") val episodes: List<TmdbEpisodeDto>? = null
)

data class TmdbEpisodeDto(
    @SerializedName("id") val id: Long,
    @SerializedName("episode_number") val episodeNumber: Int,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("runtime") val runtime: Int? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("air_date") val airDate: String? = null
)

data class TmdbCreditsDto(
    @SerializedName("cast") val cast: List<TmdbCastItemDto>? = null
)

data class TmdbPersonCreditsDto(
    @SerializedName("cast") val cast: List<TmdbMovieDto>? = null
)

data class TmdbCastItemDto(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String?,
    @SerializedName("character") val character: String?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("order") val order: Int? = null
)

data class TmdbReleaseDatesContainer(
    @SerializedName("results") val results: List<TmdbCountryReleaseDates>?
)

data class TmdbCountryReleaseDates(
    @SerializedName("iso_3166_1") val countryCode: String?,
    @SerializedName("release_dates") val releaseDates: List<TmdbCertificationItem>?
)

data class TmdbCertificationItem(
    @SerializedName("certification") val certification: String?
)

data class TmdbContentRatingsContainer(
    @SerializedName("results") val results: List<TmdbCountryContentRating>?
)

data class TmdbCountryContentRating(
    @SerializedName("iso_3166_1") val countryCode: String?,
    @SerializedName("rating") val rating: String?
)
