package com.ruvk.tube.data.api.tmdb

import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.data.network.NetworkClient
import com.ruvk.tube.domain.models.CastMember
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.domain.models.TvEpisode
import com.ruvk.tube.domain.models.TvSeason
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TmdbRepository {

    private val effectiveApiKey: String
        get() = AppPreferences.tmdbApiKey.ifBlank { DEFAULT_API_KEY }

    private val api: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(NetworkClient.okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    private val excludedGenreIds = setOf(
        10767, // Ток-шоу (Talk Show)
        10764, // Реалити-шоу (Reality TV / Live entertainment, WWE, etc.)
        10763  // Новости (News / Daily live news)
    )

    suspend fun getTrending(page: Int = 1): List<MediaItem> {
        return fetchFilteredList(page) { p ->
            api.getTrending(effectiveApiKey, page = p)
        }
    }

    suspend fun getPopularMovies(page: Int = 1): List<MediaItem> {
        return fetchFilteredList(page, forceType = MediaType.MOVIE) { p ->
            api.getPopularMovies(effectiveApiKey, page = p)
        }
    }

    suspend fun getPopularTv(page: Int = 1): List<MediaItem> {
        return fetchFilteredList(page, forceType = MediaType.TV) { p ->
            api.getPopularTv(effectiveApiKey, page = p)
        }
    }

    suspend fun getNewDigitalReleases(page: Int = 1): List<MediaItem> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return fetchFilteredList(page, forceType = MediaType.MOVIE) { p ->
            api.getNewDigitalReleases(
                apiKey = effectiveApiKey,
                maxReleaseDate = today,
                page = p
            )
        }
    }

    suspend fun getOnTheAirTv(page: Int = 1): List<MediaItem> {
        return fetchFilteredList(page, forceType = MediaType.TV) { p ->
            api.getOnTheAirTv(effectiveApiKey, page = p)
        }
    }

    suspend fun getMovieDetails(id: Long): MediaItem {
        val dto = api.getMovieDetails(id, effectiveApiKey)
        val (ageRating, ageDesc) = extractMovieAgeRating(dto.releaseDates)
        val castMembers = dto.credits?.cast?.take(12)?.map { castItem ->
            CastMember(
                id = castItem.id,
                name = castItem.name ?: "",
                character = castItem.character ?: "",
                profileUrl = castItem.profilePath?.let { "$IMAGE_BASE_PROFILE$it" }
            )
        } ?: emptyList()

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
            ageRatingDescription = ageDesc,
            cast = castMembers
        )
    }

    suspend fun getTvDetails(id: Long): MediaItem {
        val dto = api.getTvDetails(id, effectiveApiKey)
        val (ageRating, ageDesc) = extractTvAgeRating(dto.contentRatings)
        val castMembers = dto.credits?.cast?.take(12)?.map { castItem ->
            CastMember(
                id = castItem.id,
                name = castItem.name ?: "",
                character = castItem.character ?: "",
                profileUrl = castItem.profilePath?.let { "$IMAGE_BASE_PROFILE$it" }
            )
        } ?: emptyList()

        val tvSeasons = dto.seasons
            ?.filter { (it.episodeCount ?: 0) > 0 }
            ?.sortedBy { it.seasonNumber }
            ?.map { s ->
                TvSeason(
                    id = s.id,
                    seasonNumber = s.seasonNumber,
                    name = s.name?.takeIf { it.isNotBlank() } ?: if (s.seasonNumber == 0) "Спецматериалы" else "${s.seasonNumber} сезон",
                    episodeCount = s.episodeCount ?: 0,
                    posterUrl = s.posterPath?.let { "$IMAGE_BASE_POSTER$it" }
                )
            } ?: emptyList()

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
            ageRatingDescription = ageDesc,
            cast = castMembers,
            seasons = tvSeasons
        )
    }

    suspend fun getSeasonEpisodes(tvId: Long, seasonNumber: Int): List<TvEpisode> {
        val dto = api.getSeasonDetails(tvId, seasonNumber, effectiveApiKey)
        return dto.episodes?.map { ep ->
            TvEpisode(
                id = ep.id,
                episodeNumber = ep.episodeNumber,
                seasonNumber = ep.seasonNumber,
                name = ep.name?.takeIf { it.isNotBlank() } ?: "Серия ${ep.episodeNumber}",
                overview = ep.overview ?: "",
                stillUrl = ep.stillPath?.let { "$IMAGE_BASE_BACKDROP$it" },
                durationMinutes = ep.runtime ?: 0,
                voteAverage = ep.voteAverage ?: 0.0
            )
        } ?: emptyList()
    }

    suspend fun search(query: String, page: Int = 1): List<MediaItem> {
        val response = api.searchMulti(effectiveApiKey, query, page = page)
        return response.results
            .filter {
                (it.mediaType == "movie" || it.mediaType == "tv") && isAllowedMedia(it)
            }
            .sortedByDescending { it.voteCount ?: 0 }
            .map { it.toMediaItem() }
    }

    suspend fun getPersonFilmography(personId: Long): List<MediaItem> {
        val credits = api.getPersonCredits(personId, effectiveApiKey)
        val rawList = credits.cast ?: emptyList()
        return rawList
            .filter {
                (it.mediaType == "movie" || it.mediaType == "tv" || it.mediaType == null) &&
                        isAllowedMedia(it)
            }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<TmdbMovieDto> { dto ->
                    dto.releaseDate ?: dto.firstAirDate ?: ""
                }.thenByDescending { it.voteAverage ?: 0.0 }
            )
            .map { it.toMediaItem() }
    }

    /**
     * Фильтрация подгружаемых выборок:
     * 1. Исключаем русские и украинские фильмы и сериалы (lang: ru/uk/ua, country: RU/UA).
     * 2. Исключаем лайв и ток-шоу (жанры 10767, 10764, 10763).
     * 3. Исключаем фильмы и сериалы, для которых нет русского названия.
     */
    private fun isAllowedMedia(dto: TmdbMovieDto): Boolean {
        // 1. Постер или бэкдроп обязателен
        if (dto.posterPath.isNullOrBlank() && dto.backdropPath.isNullOrBlank()) {
            return false
        }

        // 2. Исключаем русские и украинские фильмы и сериалы
        val lang = dto.originalLanguage?.trim()?.lowercase(Locale.ROOT)
        if (lang == "ru" || lang == "uk" || lang == "ua") {
            return false
        }
        val originCountries = dto.originCountry
        if (originCountries != null && originCountries.any { it.equals("RU", ignoreCase = true) || it.equals("UA", ignoreCase = true) }) {
            return false
        }

        // 3. Исключаем лайв и ток-шоу
        val genres = dto.genreIds
        if (genres != null && genres.any { it in excludedGenreIds }) {
            return false
        }

        // 4. Исключаем фильмы и сериалы без русского названия
        val resolvedTitle = dto.title?.takeIf { it.isNotBlank() }
            ?: dto.name?.takeIf { it.isNotBlank() }
            ?: return false

        // Если в названии есть хотя бы одна буква кириллицы - название локализовано
        if (resolvedTitle.any { it in '\u0400'..'\u04FF' }) {
            return true
        }

        // Для числовых названий (напр. "1917", "2012", "1+1"): проверяем, переведен ли синопсис на русский
        val hasLatinLetters = resolvedTitle.any { (it in 'a'..'z') || (it in 'A'..'Z') }
        if (!hasLatinLetters && !dto.overview.isNullOrBlank() && dto.overview.any { it in '\u0400'..'\u04FF' }) {
            return true
        }

        return false
    }

    private suspend fun fetchFilteredList(
        startPage: Int,
        forceType: MediaType? = null,
        fetcher: suspend (page: Int) -> TmdbPageResponse<TmdbMovieDto>
    ): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        var page = startPage
        // Запрашиваем текущую страницу. Если после фильтрации она оказалась пустой (0 элементов),
        // запрашиваем следующую, предотвращая ложное прекращение пагинации в ленте.
        while (page <= startPage + 1) {
            val response = fetcher(page)
            val filtered = response.results
                .filter { isAllowedMedia(it) }
                .map { it.toMediaItem(forceType = forceType) }
            result.addAll(filtered)
            if (result.isNotEmpty() || page >= response.totalPages) {
                break
            }
            page++
        }
        return result
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
        const val IMAGE_BASE_PROFILE = "https://image.tmdb.org/t/p/w185"
    }
}
