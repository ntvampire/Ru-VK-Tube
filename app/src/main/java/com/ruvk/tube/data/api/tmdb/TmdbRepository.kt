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
        val (ageRating, ageDesc) = extractMovieAgeRating(dto.releaseDates)

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
            imdbId = dto.imdbId,
            ageRating = ageRating,
            ageRatingDescription = ageDesc
        )
    }

    suspend fun getTvDetails(id: Long): MediaItem {
        val dto = api.getTvDetails(id, apiKey)
        val (ageRating, ageDesc) = extractTvAgeRating(dto.contentRatings)

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
            mediaType = MediaType.TV,
            ageRating = ageRating,
            ageRatingDescription = ageDesc
        )
    }

    suspend fun search(query: String): List<MediaItem> {
        val response = api.searchMulti(apiKey, query)
        return response.results
            .filter {
                (it.mediaType == "movie" || it.mediaType == "tv") &&
                        (!it.posterPath.isNullOrBlank() || !it.backdropPath.isNullOrBlank())
            }
            .sortedByDescending { it.voteCount ?: 0 }
            .map { it.toMediaItem() }
    }

    private fun TmdbMovieDto.toMediaItem(forceType: MediaType? = null): MediaItem {
        val isTv = forceType == MediaType.TV || mediaType == "tv"
        val resolvedTitle = title ?: name ?: originalTitle ?: originalName ?: ""
        val resolvedDate = releaseDate ?: firstAirDate ?: ""
        val rawPoster = posterPath ?: backdropPath
        val rawBackdrop = backdropPath ?: posterPath

        return MediaItem(
            id = id,
            title = resolvedTitle,
            originalTitle = originalTitle ?: originalName ?: resolvedTitle,
            overview = overview ?: "",
            posterUrl = rawPoster?.let { "$IMAGE_BASE_POSTER$it" },
            backdropUrl = rawBackdrop?.let { "$IMAGE_BASE_BACKDROP$it" },
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

    private fun extractMovieAgeRating(releaseDates: TmdbReleaseDatesContainer?): Pair<String?, String?> {
        val results = releaseDates?.results ?: return Pair(null, null)

        // 1. Приоритет - российская сертификация (RU)
        val ruDates = results.firstOrNull { it.countryCode.equals("RU", ignoreCase = true) }
        val ruCert = ruDates?.releaseDates?.firstOrNull { !it.certification.isNullOrBlank() }?.certification
        if (!ruCert.isNullOrBlank()) {
            return mapCertificationToRussian(ruCert)
        }

        // 2. Резерв - США (US: R, PG-13, PG, G)
        val usDates = results.firstOrNull { it.countryCode.equals("US", ignoreCase = true) }
        val usCert = usDates?.releaseDates?.firstOrNull { !it.certification.isNullOrBlank() }?.certification
        if (!usCert.isNullOrBlank()) {
            return mapCertificationToRussian(usCert)
        }

        return Pair(null, null)
    }

    private fun extractTvAgeRating(contentRatings: TmdbContentRatingsContainer?): Pair<String?, String?> {
        val results = contentRatings?.results ?: return Pair(null, null)

        // 1. Приоритет - RU
        val ruRating = results.firstOrNull { it.countryCode.equals("RU", ignoreCase = true) }?.rating
        if (!ruRating.isNullOrBlank()) {
            return mapCertificationToRussian(ruRating)
        }

        // 2. Резерв - US (TV-MA, TV-14, TV-PG)
        val usRating = results.firstOrNull { it.countryCode.equals("US", ignoreCase = true) }?.rating
        if (!usRating.isNullOrBlank()) {
            return mapCertificationToRussian(usRating)
        }

        return Pair(null, null)
    }

    private fun mapCertificationToRussian(raw: String): Pair<String, String?> {
        val c = raw.trim().uppercase()
        return when {
            c.contains("18") || c == "R" || c == "NC-17" || c == "TV-MA" -> {
                "18+" to null
            }
            c.contains("16") || c == "14" || c == "PG-13" || c == "TV-14" -> {
                "16+" to null
            }
            c.contains("12") || c == "PG" || c == "TV-PG" -> {
                "12+" to null
            }
            c.contains("6") || c == "TV-Y7" -> {
                "6+" to null
            }
            c.contains("0") || c == "G" || c == "TV-G" || c == "TV-Y" -> {
                "0+" to null
            }
            else -> {
                raw to null
            }
        }
    }

    companion object {
        const val DEFAULT_API_KEY = "4e44d9029b1270a757cddc766a1bcb63"
        const val IMAGE_BASE_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMAGE_BASE_BACKDROP = "https://image.tmdb.org/t/p/original"
    }
}
