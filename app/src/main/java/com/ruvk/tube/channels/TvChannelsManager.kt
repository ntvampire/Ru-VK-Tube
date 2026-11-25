package com.ruvk.tube.channels

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.api.tmdb.TmdbRepository
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.ui.details.DetailsActivity
import com.ruvk.tube.ui.main.MainActivity
import com.ruvk.tube.ui.main.NumCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TvChannelsManager {

    private const val TAG = "TvChannelsManager"

    const val CHANNEL_KEY_TRENDING = "ruvk_channel_trending"
    const val CHANNEL_KEY_FAVORITES = "ruvk_channel_favorites"
    const val CHANNEL_KEY_HISTORY = "ruvk_channel_history"

    private val ALL_CHANNEL_KEYS = setOf(
        CHANNEL_KEY_TRENDING,
        CHANNEL_KEY_FAVORITES,
        CHANNEL_KEY_HISTORY
    )

    private val trendingMutex = Mutex()
    private val favoritesMutex = Mutex()
    private val historyMutex = Mutex()
    private val watchNextMutex = Mutex()
    private val tmdbSemaphore = Semaphore(3)

    private data class CachedDetails(
        val ageRating: String?,
        val runtimeMinutes: Int,
        val overview: String,
        val country: String?,
        val expiresAtMs: Long
    )

    private val detailsCache = ConcurrentHashMap<String, CachedDetails>()
    private val nextEpisodeCache = ConcurrentHashMap<String, Pair<TmdbRepository.NextEpisodeInfo?, Long>>()
    private const val DETAILS_CACHE_TTL_MS = 15 * 60 * 1000L

    private suspend fun fetchCachedNextEpisode(
        tmdbId: Long,
        currentSeason: Int,
        currentEpisode: Int
    ): TmdbRepository.NextEpisodeInfo? {
        val key = "$tmdbId:$currentSeason:$currentEpisode"
        val now = System.currentTimeMillis()
        nextEpisodeCache[key]?.let { (cached, expiresAt) ->
            if (now < expiresAt) return cached
        }
        return tmdbSemaphore.withPermit {
            nextEpisodeCache[key]?.let { (cached, expiresAt) ->
                if (System.currentTimeMillis() < expiresAt) return@withPermit cached
            }
            val resolved = RuVkTubeApp.instance.tmdbRepository.getNextEpisode(tmdbId, currentSeason, currentEpisode)
            nextEpisodeCache[key] = resolved to (System.currentTimeMillis() + DETAILS_CACHE_TTL_MS)
            resolved
        }
    }

    private suspend fun fetchCachedDetails(tmdbId: Long, isTv: Boolean): CachedDetails? {
        val cacheKey = "${if (isTv) "TV" else "MOVIE"}:$tmdbId"
        val now = System.currentTimeMillis()
        detailsCache[cacheKey]?.let { cached ->
            if (now < cached.expiresAtMs) return cached
        }
        return tmdbSemaphore.withPermit {
            detailsCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() < cached.expiresAtMs) return@withPermit cached
            }
            try {
                val item = if (isTv) {
                    RuVkTubeApp.instance.tmdbRepository.getTvDetails(tmdbId)
                } else {
                    RuVkTubeApp.instance.tmdbRepository.getMovieDetails(tmdbId)
                }
                val entry = CachedDetails(
                    ageRating = item.ageRating,
                    runtimeMinutes = item.runtimeMinutes,
                    overview = item.overview,
                    country = item.country,
                    expiresAtMs = System.currentTimeMillis() + DETAILS_CACHE_TTL_MS
                )
                detailsCache[cacheKey] = entry
                entry
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isTvChannelsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    suspend fun syncAllChannels(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        try {
            syncTrendingChannel(context)
            syncFavoritesChannel(context)
            syncHistoryChannel(context, alsoSyncWatchNext = false)
            syncWatchNext(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing all TV channels", e)
        }
    }

    suspend fun syncTrendingChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        trendingMutex.withLock {
            try {
                val helper = PreviewChannelHelper(context)
                val channelId = getOrCreateChannel(
                    context = context,
                    helper = helper,
                    internalId = CHANNEL_KEY_TRENDING,
                    displayName = "В тренде",
                    description = "Популярные фильмы и сериалы",
                    category = NumCategory.TRENDING,
                    isDefault = true
                ) ?: return@withLock

                val rawTrending = try {
                    RuVkTubeApp.instance.tmdbRepository.getTrending(page = 1).take(20)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch trending from TMDB", e)
                    emptyList()
                }

                val trendingItems = if (rawTrending.isNotEmpty()) {
                    coroutineScope {
                        rawTrending.map { item ->
                            async {
                                val details = fetchCachedDetails(item.id, item.mediaType == MediaType.TV)
                                if (details != null) {
                                    item.copy(
                                        ageRating = details.ageRating,
                                        runtimeMinutes = details.runtimeMinutes,
                                        country = details.country,
                                        overview = details.overview.ifBlank { item.overview }
                                    )
                                } else {
                                    item
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    emptyList()
                }

                if (trendingItems.isNotEmpty()) {
                    syncPrograms(context, helper, channelId, trendingItems)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing Trending channel", e)
            }
        }
    }

    suspend fun syncFavoritesChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        favoritesMutex.withLock {
            try {
                val helper = PreviewChannelHelper(context)
                val channelId = getOrCreateChannel(
                    context = context,
                    helper = helper,
                    internalId = CHANNEL_KEY_FAVORITES,
                    displayName = "Избранное",
                    description = "Ваши избранные фильмы и сериалы",
                    category = NumCategory.FAVORITES,
                    isDefault = false
                ) ?: return@withLock

                val favEntities = RuVkTubeApp.instance.database.favoritesDao().getAllFavoritesList()
                val favItems = coroutineScope {
                    favEntities.map { f ->
                        async {
                            val isTv = f.mediaType == MediaType.TV.name
                            val details = fetchCachedDetails(f.tmdbId, isTv)
                            val overview = (f.overview ?: "").ifBlank { details?.overview ?: "" }
                            val countryStr = f.country?.takeIf { it.isNotBlank() } ?: details?.country

                            MediaItem(
                                id = f.tmdbId,
                                title = f.title,
                                originalTitle = "",
                                overview = overview,
                                posterUrl = f.posterUrl,
                                backdropUrl = f.backdropUrl,
                                releaseDate = if (f.year > 0) "${f.year}" else "",
                                year = f.year,
                                voteAverage = f.rating,
                                runtimeMinutes = details?.runtimeMinutes ?: 0,
                                ageRating = details?.ageRating,
                                mediaType = if (isTv) MediaType.TV else MediaType.MOVIE,
                                country = countryStr
                            )
                        }
                    }.awaitAll()
                }
                syncPrograms(context, helper, channelId, favItems)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing Favorites channel", e)
            }
        }
    }

    suspend fun syncHistoryChannel(context: Context, alsoSyncWatchNext: Boolean = true) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        var syncedOk = false
        historyMutex.withLock {
            try {
                val helper = PreviewChannelHelper(context)
                val channelId = getOrCreateChannel(
                    context = context,
                    helper = helper,
                    internalId = CHANNEL_KEY_HISTORY,
                    displayName = "История просмотров",
                    description = "Недавно просмотренные видео",
                    category = NumCategory.HISTORY,
                    isDefault = false
                ) ?: return@withLock

                val histEntities = RuVkTubeApp.instance.database.historyDao().getRecentHistoryList()
                val historyItems = coroutineScope {
                    histEntities.map { h ->
                        async {
                            val isTv = h.mediaType == MediaType.TV.name
                            var posMs = h.positionMs
                            var durMs = h.durationMs
                            var lastStream = h.lastStreamUrl
                            var sNum = h.season
                            var eNum = h.episode

                            if (isTv) {
                                val latestEp = RuVkTubeApp.instance.database.episodeProgressDao()
                                    .getAllForShow(h.tmdbId).firstOrNull()
                                if (latestEp != null && latestEp.positionMs > 5_000L) {
                                    posMs = latestEp.positionMs
                                    durMs = latestEp.durationMs
                                    lastStream = latestEp.lastStreamUrl
                                    sNum = latestEp.season
                                    eNum = latestEp.episode
                                }
                            }

                            val details = fetchCachedDetails(h.tmdbId, isTv)
                            val runtimeMinutes = if (durMs > 0L) (durMs / 60_000L).toInt() else (details?.runtimeMinutes ?: 0)
                            val overview = (h.overview ?: "").ifBlank { details?.overview ?: "" }
                            val countryStr = h.country?.takeIf { it.isNotBlank() } ?: details?.country

                            MediaItem(
                                id = h.tmdbId,
                                title = h.title,
                                originalTitle = "",
                                overview = overview,
                                posterUrl = h.posterUrl,
                                backdropUrl = h.backdropUrl,
                                releaseDate = if (h.year > 0) "${h.year}" else "",
                                year = h.year,
                                voteAverage = h.rating,
                                runtimeMinutes = runtimeMinutes,
                                ageRating = details?.ageRating,
                                mediaType = if (isTv) MediaType.TV else MediaType.MOVIE,
                                watchPositionMs = posMs,
                                watchDurationMs = durMs,
                                lastStreamUrl = lastStream,
                                lastSeason = sNum,
                                lastEpisode = eNum,
                                country = countryStr
                            )
                        }
                    }.awaitAll()
                }

                syncPrograms(context, helper, channelId, historyItems)
                syncedOk = true
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing History channel", e)
            }
        }
        if (syncedOk && alsoSyncWatchNext) {
            syncWatchNext(context)
        }
    }

    suspend fun syncWatchNext(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        watchNextMutex.withLock {
            try {
                val helper = PreviewChannelHelper(context)
                val histEntities = RuVkTubeApp.instance.database.historyDao().getRecentHistoryList()

                data class WatchNextTarget(
                    val history: com.ruvk.tube.data.db.HistoryEntity,
                    val isTv: Boolean,
                    val watchNextType: Int,
                    val posMs: Long,
                    val durMs: Long,
                    val season: Int,
                    val episode: Int,
                    val lastStream: String?,
                    val episodeOverview: String? = null,
                    val episodeRuntimeMin: Int = 0
                )

                val resolvedEntries = mutableListOf<WatchNextTarget>()
                for (h in histEntities.take(15)) {
                    if (resolvedEntries.size >= 10) break
                    val isTv = h.mediaType == MediaType.TV.name
                    var posMs = h.positionMs
                    var durMs = h.durationMs
                    var lastStream: String? = h.lastStreamUrl
                    var sNum = h.season
                    var eNum = h.episode

                    if (isTv) {
                        val latestEp = RuVkTubeApp.instance.database.episodeProgressDao()
                            .getAllForShow(h.tmdbId).firstOrNull()
                        if (latestEp != null && (latestEp.positionMs > 5_000L || latestEp.durationMs > 0L)) {
                            posMs = latestEp.positionMs
                            durMs = latestEp.durationMs
                            lastStream = latestEp.lastStreamUrl
                            sNum = latestEp.season
                            eNum = latestEp.episode
                        }
                    }

                    val isCompleted = durMs > 0L && posMs >= (durMs * 0.95).toLong()
                    if (!isCompleted && posMs > 5_000L) {
                        resolvedEntries.add(
                            WatchNextTarget(
                                history = h,
                                isTv = isTv,
                                watchNextType = TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                                posMs = posMs,
                                durMs = durMs,
                                season = sNum,
                                episode = eNum,
                                lastStream = lastStream
                            )
                        )
                    } else if (isCompleted && isTv && sNum > 0 && eNum > 0) {
                        val nextInfo = fetchCachedNextEpisode(h.tmdbId, sNum, eNum) ?: continue
                        val nextEpProgress = RuVkTubeApp.instance.database.episodeProgressDao()
                            .getProgress(h.tmdbId, nextInfo.seasonNumber, nextInfo.episode.episodeNumber)
                        val nextDone = nextEpProgress != null &&
                            nextEpProgress.durationMs > 0L &&
                            nextEpProgress.positionMs >= (nextEpProgress.durationMs * 0.95).toLong()
                        if (nextDone) continue

                        val nextInProgress = nextEpProgress != null && nextEpProgress.positionMs > 5_000L
                        resolvedEntries.add(
                            WatchNextTarget(
                                history = h,
                                isTv = true,
                                watchNextType = if (nextInProgress) {
                                    TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                                } else {
                                    TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                                },
                                posMs = if (nextInProgress) nextEpProgress!!.positionMs else 0L,
                                durMs = if (nextInProgress) nextEpProgress!!.durationMs else 0L,
                                season = nextInfo.seasonNumber,
                                episode = nextInfo.episode.episodeNumber,
                                lastStream = if (nextInProgress) nextEpProgress!!.lastStreamUrl else null,
                                episodeOverview = nextInfo.episode.overview,
                                episodeRuntimeMin = nextInfo.episode.durationMinutes.takeIf { it > 0 }
                                    ?: nextInfo.runtimeFallbackMinutes
                            )
                        )
                    }
                }

                // Запрашиваем уже опубликованные нами программы из системного Watch Next
                val existingPrograms = mutableListOf<WatchNextProgram>()
                try {
                    context.contentResolver.query(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        WatchNextProgram.PROJECTION,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            existingPrograms.add(WatchNextProgram.fromCursor(cursor))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query WatchNext programs", e)
                }

                // Сбрасываем остаточный review_rating в системной БД, чтобы лаунчер не рисовал символьные звёзды
                try {
                    val clearRatingValues = ContentValues().apply {
                        putNull(TvContractCompat.WatchNextPrograms.COLUMN_REVIEW_RATING)
                        putNull(TvContractCompat.WatchNextPrograms.COLUMN_REVIEW_RATING_STYLE)
                    }
                    context.contentResolver.update(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        clearRatingValues,
                        "${TvContractCompat.WatchNextPrograms.COLUMN_REVIEW_RATING} IS NOT NULL",
                        null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear legacy review ratings for WatchNext", e)
                }

                val existingMap = existingPrograms.associateBy { it.internalProviderId }.toMutableMap()

                for (target in resolvedEntries) {
                    val h = target.history
                    val isTv = target.isTv
                    val posMs = target.posMs
                    val durMs = target.durMs
                    val sNum = target.season
                    val eNum = target.episode

                    val internalId = "watch_next_${if (isTv) "TV" else "MOVIE"}_${h.tmdbId}"
                    val existing = existingMap.remove(internalId)

                    val details = fetchCachedDetails(h.tmdbId, isTv)
                    val runtimeMinutes = when {
                        durMs > 0L -> (durMs / 60_000L).toInt()
                        target.episodeRuntimeMin > 0 -> target.episodeRuntimeMin
                        else -> details?.runtimeMinutes ?: 0
                    }
                    val overview = (target.episodeOverview?.takeIf { it.isNotBlank() } ?: h.overview ?: "")
                        .ifBlank { details?.overview ?: "" }
                    val countryStr = h.country?.takeIf { it.isNotBlank() } ?: details?.country

                    val mediaItem = MediaItem(
                        id = h.tmdbId,
                        title = h.title,
                        originalTitle = "",
                        overview = overview,
                        posterUrl = h.posterUrl,
                        backdropUrl = h.backdropUrl,
                        releaseDate = if (h.year > 0) "${h.year}" else "",
                        year = h.year,
                        voteAverage = h.rating,
                        runtimeMinutes = runtimeMinutes,
                        ageRating = details?.ageRating,
                        mediaType = if (isTv) MediaType.TV else MediaType.MOVIE,
                        watchPositionMs = posMs,
                        watchDurationMs = durMs,
                        lastStreamUrl = target.lastStream,
                        lastSeason = sNum,
                        lastEpisode = eNum,
                        country = countryStr
                    )

                    val programBuilder = WatchNextProgram.Builder()
                    programBuilder.setReviewRating(null)
                    programBuilder.setReviewRatingStyle(-1)

                    val programUri = buildProgramIntentUri(context, mediaItem)
                    val imageUri = mediaItem.posterUrl?.takeIf { it.isNotBlank() }
                        ?: mediaItem.backdropUrl?.takeIf { it.isNotBlank() }
                    val descriptionText = buildProgramDescription(mediaItem)
                    val longDescText = buildProgramLongDescription(mediaItem)

                    val isEpisode = isTv && sNum > 0 && eNum > 0
                    val programType = when {
                        isEpisode -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                        isTv -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                        else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                    }

                    programBuilder
                        .setWatchNextType(target.watchNextType)
                        .setLastEngagementTimeUtcMillis(h.updatedAt)
                        .setTitle(mediaItem.title)
                        .setDescription(descriptionText)
                        .setLongDescription(longDescText)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                        .setType(programType)
                        .setIntentUri(programUri)
                        .setInternalProviderId(internalId)

                    if (isEpisode) {
                        programBuilder.setSeasonNumber(sNum)
                        programBuilder.setEpisodeNumber(eNum)
                    }

                    val duration = when {
                        durMs > 0L -> durMs.toInt()
                        mediaItem.runtimeMinutes > 0 -> mediaItem.runtimeMinutes * 60_000
                        else -> 0
                    }
                    if (duration > 0) {
                        programBuilder.setDurationMillis(duration)
                    }
                    if (target.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && posMs > 0L) {
                        programBuilder.setLastPlaybackPositionMillis(posMs.toInt())
                    }

                    val releaseDateStr = when {
                        mediaItem.year > 0 -> mediaItem.year.toString()
                        mediaItem.releaseDate.isNotBlank() -> mediaItem.releaseDate.take(4).takeIf { y -> y.all { it.isDigit() } }
                        else -> null
                    }
                    if (releaseDateStr != null) {
                        programBuilder.setReleaseDate(releaseDateStr)
                    }

                    if (imageUri != null) {
                        programBuilder.setPosterArtUri(Uri.parse(imageUri))
                    }

                    try {
                        if (existing != null) {
                            helper.updateWatchNextProgram(programBuilder.build(), existing.id)
                        } else {
                            helper.publishWatchNextProgram(programBuilder.build())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to publish/update WatchNext program: ${mediaItem.title}", e)
                    }
                }

                // Удаляем из WatchNext те программы, которые пользователь досмотрел или удалил
                for ((_, removed) in existingMap) {
                    try {
                        val uri = TvContractCompat.buildWatchNextProgramUri(removed.id)
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete completed/removed WatchNext program ${removed.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing WatchNext programs", e)
            }
        }
    }

    fun clearWatchNext(context: Context) {
        if (!isTvChannelsSupported()) return
        try {
            context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear WatchNext programs", e)
        }
    }

    fun buildMetadataSubtitle(
        item: MediaItem,
        includeDuration: Boolean = false,
        includeEpisode: Boolean = false,
        includeYear: Boolean = false
    ): String {
        val meta = mutableListOf<String>()

        if (includeEpisode) {
            val episodeInfo = if (item.mediaType == MediaType.TV && item.lastSeason > 0 && item.lastEpisode > 0) {
                "S${item.lastSeason}E${item.lastEpisode}"
            } else null
            if (episodeInfo != null) {
                meta.add(episodeInfo)
            }
        }

        // 1. Возрастной рейтинг: 18+, 16+, 12+, 6+, 0+
        item.ageRating?.takeIf { it.isNotBlank() }?.let {
            meta.add(it)
        }

        // 2. Рейтинг: ★ 8.2
        if (item.voteAverage > 0.0) {
            meta.add(String.format(Locale.US, "★ %.1f", item.voteAverage))
        }

        // 3. Длительность: опционально (в карточках каналов передается системно через durationMillis)
        if (includeDuration) {
            val runtimeMinutes = when {
                item.runtimeMinutes > 0 -> item.runtimeMinutes
                item.watchDurationMs > 0L -> (item.watchDurationMs / 60_000L).toInt()
                else -> 0
            }
            if (runtimeMinutes > 0) {
                meta.add("$runtimeMinutes мин")
            }
        }

        // 4. Год: опционально (в карточках каналов передается системно через releaseDate)
        if (includeYear) {
            val yearStr = when {
                item.year > 0 -> item.year.toString()
                item.releaseDate.isNotBlank() -> item.releaseDate.take(4).takeIf { y -> y.all { it.isDigit() } }
                else -> null
            }
            if (yearStr != null) {
                meta.add(yearStr)
            }
        }

        // 5. Страна
        item.country?.takeIf { it.isNotBlank() }?.let {
            meta.add(it)
        }

        return meta.joinToString(" • ")
    }

    fun buildProgramTitle(item: MediaItem): String = item.title

    fun buildProgramDescription(item: MediaItem): String {
        val subtitle = buildMetadataSubtitle(item)
        val overview = item.overview.trim()
        return when {
            subtitle.isNotBlank() && overview.isNotBlank() -> "$subtitle\n$overview"
            subtitle.isNotBlank() -> subtitle
            else -> overview
        }
    }

    fun buildProgramLongDescription(item: MediaItem): String {
        val subtitle = buildMetadataSubtitle(item)
        val overview = item.overview.trim()
        return when {
            subtitle.isNotBlank() && overview.isNotBlank() -> "$subtitle\n\n$overview"
            overview.isNotBlank() -> overview
            else -> subtitle
        }
    }

    private fun getOrCreateChannel(
        context: Context,
        helper: PreviewChannelHelper,
        internalId: String,
        displayName: String,
        description: String,
        category: NumCategory,
        isDefault: Boolean
    ): Long? {
        return try {
            val existing = helper.allChannels.firstOrNull { it.internalProviderId == internalId }
            if (existing != null) {
                if (!existing.isBrowsable) {
                    makeChannelBrowsable(context, existing.id)
                }
                return existing.id
            }

            val appLinkUri = buildAppLinkUri(context, category)
            val channelBuilder = PreviewChannel.Builder()
                .setDisplayName(displayName)
                .setDescription(description)
                .setInternalProviderId(internalId)
                .setAppLinkIntentUri(appLinkUri)

            // PreviewChannelHelper.publishChannel требует наличия логотипа непосредственно в Builder
            val logoBitmap = getChannelLogoBitmap(context)
            channelBuilder.setLogo(logoBitmap)

            val channelId = if (isDefault) {
                helper.publishDefaultChannel(channelBuilder.build())
            } else {
                helper.publishChannel(channelBuilder.build())
            }

            makeChannelBrowsable(context, channelId)
            channelId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get or create channel $internalId", e)
            null
        }
    }

    private fun makeChannelBrowsable(context: Context, channelId: Long) {
        try {
            val values = android.content.ContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_BROWSABLE, 1)
            }
            context.contentResolver.update(
                TvContractCompat.buildChannelUri(channelId),
                values,
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update browsable flag for channel $channelId", e)
        }
    }

    private fun getChannelLogoBitmap(context: Context): Bitmap {
        val decoded = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.app_icon)
        } catch (_: Exception) {
            null
        }
        if (decoded != null) return decoded

        val fallback = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fallback)
        val paint = Paint().apply { color = Color.parseColor("#0A82FF") }
        canvas.drawCircle(40f, 40f, 36f, paint)
        return fallback
    }

    private fun syncPrograms(
        context: Context,
        helper: PreviewChannelHelper,
        channelId: Long,
        items: List<MediaItem>
    ) {
        val existingPrograms = mutableListOf<PreviewProgram>()
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    existingPrograms.add(PreviewProgram.fromCursor(cursor))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query existing programs for channel $channelId", e)
        }

        // Сбрасываем остаточный review_rating в системной БД, чтобы лаунчер не рисовал символьные звёзды
        try {
            val clearRatingValues = ContentValues().apply {
                putNull(TvContractCompat.PreviewPrograms.COLUMN_REVIEW_RATING)
                putNull(TvContractCompat.PreviewPrograms.COLUMN_REVIEW_RATING_STYLE)
            }
            context.contentResolver.update(
                uri,
                clearRatingValues,
                "${TvContractCompat.PreviewPrograms.COLUMN_REVIEW_RATING} IS NOT NULL",
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear legacy review ratings for channel $channelId", e)
        }

        val existingMap = existingPrograms.associateBy { it.internalProviderId }.toMutableMap()
        val total = items.size

        items.forEachIndexed { index, item ->
            val internalId = "${item.mediaType.name}:${item.id}"
            val weight = (total - index) * 10
            val existing = existingMap.remove(internalId)

            val programBuilder = PreviewProgram.Builder()
            programBuilder.setReviewRating(null)
            programBuilder.setReviewRatingStyle(-1)

            val programUri = buildProgramIntentUri(context, item)
            val imageUri = item.posterUrl?.takeIf { it.isNotBlank() }
                ?: item.backdropUrl?.takeIf { it.isNotBlank() }
            val descriptionText = buildProgramDescription(item)
            val longDescText = buildProgramLongDescription(item)

            val isEpisode = item.mediaType == MediaType.TV && item.lastSeason > 0 && item.lastEpisode > 0
            val programType = when {
                isEpisode -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                item.mediaType == MediaType.TV -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
            }

            programBuilder
                .setChannelId(channelId)
                .setTitle(item.title)
                .setDescription(descriptionText)
                .setLongDescription(longDescText)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setType(programType)
                .setIntentUri(programUri)
                .setInternalProviderId(internalId)
                .setWeight(weight)

            if (isEpisode) {
                programBuilder.setSeasonNumber(item.lastSeason)
                programBuilder.setEpisodeNumber(item.lastEpisode)
            }

            if (imageUri != null) {
                programBuilder.setPosterArtUri(Uri.parse(imageUri))
            }

            val duration = when {
                item.watchDurationMs > 0L -> item.watchDurationMs.toInt()
                item.runtimeMinutes > 0 -> item.runtimeMinutes * 60_000
                else -> 0
            }
            if (duration > 0) {
                programBuilder.setDurationMillis(duration)
            }

            if (item.watchPositionMs > 5_000L && duration > 0) {
                programBuilder.setLastPlaybackPositionMillis(item.watchPositionMs.toInt())
                programBuilder.setInteractionType(TvContractCompat.PreviewPrograms.INTERACTION_TYPE_VIEWS)
            }

            val releaseDateStr = when {
                item.year > 0 -> item.year.toString()
                item.releaseDate.isNotBlank() -> item.releaseDate.take(4).takeIf { y -> y.all { it.isDigit() } }
                else -> null
            }
            if (releaseDateStr != null) {
                programBuilder.setReleaseDate(releaseDateStr)
            }

            try {
                if (existing != null) {
                    helper.updatePreviewProgram(existing.id, programBuilder.build())
                } else {
                    helper.publishPreviewProgram(programBuilder.build())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish/update program: ${item.title}", e)
            }
        }

        for ((_, removed) in existingMap) {
            try {
                helper.deletePreviewProgram(removed.id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete old program ${removed.id}", e)
            }
        }
    }

    fun buildAppLinkUri(context: Context, category: NumCategory): Uri {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(context, MainActivity::class.java)
            putExtra(MainActivity.EXTRA_TARGET_CATEGORY, category.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    fun buildProgramIntentUri(context: Context, item: MediaItem): Uri {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(context, DetailsActivity::class.java)
            putExtra(DetailsActivity.EXTRA_MEDIA_ID, item.id)
            putExtra(DetailsActivity.EXTRA_MEDIA_TYPE, item.mediaType.name)
            putExtra(DetailsActivity.EXTRA_TITLE, item.title)
            putExtra(DetailsActivity.EXTRA_ORIGINAL_TITLE, item.originalTitle)
            putExtra(DetailsActivity.EXTRA_OVERVIEW, item.overview)
            putExtra(DetailsActivity.EXTRA_POSTER_URL, item.posterUrl)
            putExtra(DetailsActivity.EXTRA_BACKDROP_URL, item.backdropUrl)
            putExtra(DetailsActivity.EXTRA_YEAR, item.year)
            putExtra(DetailsActivity.EXTRA_RATING, item.voteAverage)
            putExtra(DetailsActivity.EXTRA_RUNTIME, item.runtimeMinutes)
            putExtra(DetailsActivity.EXTRA_AGE_RATING, item.ageRating)
            putExtra(DetailsActivity.EXTRA_WATCH_POSITION, item.watchPositionMs)
            putExtra(DetailsActivity.EXTRA_WATCH_DURATION, item.watchDurationMs)
            putExtra(DetailsActivity.EXTRA_LAST_STREAM, item.lastStreamUrl)
            putExtra(DetailsActivity.EXTRA_LAST_SEASON, item.lastSeason)
            putExtra(DetailsActivity.EXTRA_LAST_EPISODE, item.lastEpisode)
            putExtra(DetailsActivity.EXTRA_COUNTRY, item.country)
            putExtra(DetailsActivity.EXTRA_MEDIA_ITEM, item)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    fun requestChannelsBrowsable(activity: Activity) {
        if (!isTvChannelsSupported()) return
        try {
            val helper = PreviewChannelHelper(activity)
            val channels = helper.allChannels
            for (channel in channels) {
                if (!channel.isBrowsable && channel.internalProviderId in ALL_CHANNEL_KEYS) {
                    TvContractCompat.requestChannelBrowsable(activity, channel.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting channels browsable", e)
        }
    }

    fun schedulePeriodicSync(context: Context) {
        if (!isTvChannelsSupported()) return
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<TvChannelsWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ruvk_channels_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule periodic TV channels sync", e)
        }
    }
}