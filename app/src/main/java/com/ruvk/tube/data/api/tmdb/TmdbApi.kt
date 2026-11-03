package com.ruvk.tube.data.api.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    @GET("trending/all/day")
    suspend fun getTrending(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("discover/movie")
    suspend fun getNewDigitalReleases(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("with_release_type") releaseType: String = "4|5",
        @Query("release_date.lte") maxReleaseDate: String,
        @Query("sort_by") sortBy: String = "primary_release_date.desc",
        @Query("vote_count.gte") minVoteCount: Int = 5,
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("tv/on_the_air")
    suspend fun getOnTheAirTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "release_dates,credits"
    ): TmdbMovieDetailsDto

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "content_ratings,credits"
    ): TmdbTvDetailsDto

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getSeasonDetails(
        @Path("tv_id") tvId: Long,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU"
    ): TmdbSeasonDetailsDto

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "ru-RU",
        @Query("page") page: Int = 1
    ): TmdbPageResponse<TmdbMovieDto>

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCredits(
        @Path("person_id") personId: Long,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "ru-RU"
    ): TmdbPersonCreditsDto
}
