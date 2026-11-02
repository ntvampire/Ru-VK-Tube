package com.ruvk.tube.data.api.tmdb

import com.ruvk.tube.data.network.NetworkClient
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TmdbRepository(
    private val apiKey: String = DEFAULT_API_KEY
) {

    private val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(NetworkClient.okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    suspend fun getTrending(): List<MediaItem> {
        val response = api.getTrending(apiKey)
        return response.results.map { it.toMediaItem() }
    }

    suspend fun getPopularMovies(page: Int = 1): List<MediaItem> {
        val response = api.getPopularMovies(apiKey, page = page)
        return response.results.map { it.toMediaItem(forceType = MediaType.MOVIE) }
    }

    suspend fun getPopularTv(page: Int = 1): List<MediaItem> {
        val response = api.getPopularTv(apiKey, page = page)
        return response.results.map { it.toMediaItem(forceType = MediaType.TV) }
    }

    suspend fun getMovieDetails(id: Long): MediaItem {
        val dto = api.getMovieDetails(id, apiKey)
        return MediaItem(
            id = dto.id,
            title = dto.title ?: dto.originalTitle ?: "",
            originalTitle = dto.originalTitle ?: "",
            overview = dto.overview ?: "",
            posterUrl = dto.posterPath?.let { "$IMAGE_BASE_POSTER$it" },
            backdropUrl = dto.backdropPath?.let { "$IMAGE_BASE_BACKDROP$it" },
            releaseDate = dto.releaseDate ?: "",
            year = parseYear(dto.releaseDate),
            voteAverage = dto.voteAverage ?: 0.0,
            runtimeMinutes = dto.runtime ?: 0,
            mediaType = MediaType.MOVIE,
            imdbId = dto.imdbId
        )
    }

    suspend fun getTvDetails(id: Long): MediaItem {
        val dto = api.getTvDetails(id, apiKey)
        return MediaItem(
            id = dto.id,
            title = dto.name ?: dto.originalName ?: "",
            originalTitle = dto.originalName ?: "",
            overview = dto.overview ?: "",
            posterUrl = dto.posterPath?.let { "$IMAGE_BASE_POSTER$it" },
            backdropUrl = dto.backdropPath?.let { "$IMAGE_BASE_BACKDROP$it" },
            releaseDate = dto.firstAirDate ?: "",
            year = parseYear(dto.firstAirDate),
            voteAverage = dto.voteAverage ?: 0.0,
            runtimeMinutes = dto.episodeRunTime?.firstOrNull() ?: 45,
            mediaType = MediaType.TV
        )
    }

    suspend fun search(query: String): List<MediaItem> {
        val response = api.searchMulti(apiKey, query)
        return response.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { it.toMediaItem() }
    }

    private fun TmdbMovieDto.toMediaItem(forceType: MediaType? = null): MediaItem {
        val isTv = forceType == MediaType.TV || mediaType == "tv"
        val resolvedTitle = title ?: name ?: originalTitle ?: originalName ?: ""
        val resolvedDate = releaseDate ?: firstAirDate ?: ""

        return MediaItem(
            id = id,
            title = resolvedTitle,
            originalTitle = originalTitle ?: originalName ?: resolvedTitle,
            overview = overview ?: "",
            posterUrl = posterPath?.let { "$IMAGE_BASE_POSTER$it" },
            backdropUrl = backdropPath?.let { "$IMAGE_BASE_BACKDROP$it" },
            releaseDate = resolvedDate,
            year = parseYear(resolvedDate),
            voteAverage = voteAverage ?: 0.0,
            runtimeMinutes = 0,
            mediaType = if (isTv) MediaType.TV else MediaType.MOVIE
        )
    }

    private fun parseYear(date: String?): Int {
        if (date.isNullOrBlank()) return 0
        return date.take(4).toIntOrNull() ?: 0
    }

    companion object {
        const val DEFAULT_API_KEY = "4f298a53e5522830ce57f3a7603272c3"
        const val IMAGE_BASE_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMAGE_BASE_BACKDROP = "https://image.tmdb.org/t/p/original"
    }
}
