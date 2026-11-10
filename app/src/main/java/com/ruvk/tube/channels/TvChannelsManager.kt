package com.ruvk.tube.channels

import android.app.Activity
import android.content.ComponentName
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
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.ui.details.DetailsActivity
import com.ruvk.tube.ui.main.MainActivity
import com.ruvk.tube.ui.main.NumCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
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

    private fun isTvChannelsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    suspend fun syncAllChannels(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        try {
            syncTrendingChannel(context)
            syncFavoritesChannel(context)
            syncHistoryChannel(context)
            syncWatchNext(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing all TV channels", e)
        }
    }

    suspend fun syncTrendingChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
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
            ) ?: return@withContext

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
                            try {
                                if (item.mediaType == MediaType.TV) {
                                    val details = RuVkTubeApp.instance.tmdbRepository.getTvDetails(item.id)
                                    item.copy(
                                        ageRating = details.ageRating,
                                        runtimeMinutes = details.runtimeMinutes,
                                        country = details.country,
                                        overview = details.overview.ifBlank { item.overview }
                                    )
                                } else {
                                    val details = RuVkTubeApp.instance.tmdbRepository.getMovieDetails(item.id)
                                    item.copy(
                                        ageRating = details.ageRating,
                                        runtimeMinutes = details.runtimeMinutes,
                                        country = details.country,
                                        overview = details.overview.ifBlank { item.overview }
                                    )
                                }
                            } catch (_: Exception) {
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

    suspend fun syncFavoritesChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
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
            ) ?: return@withContext

            val favEntities = RuVkTubeApp.instance.database.favoritesDao().getAllFavoritesList()
            val favItems = coroutineScope {
                favEntities.map { f ->
                    async {
                        val isTv = f.mediaType == MediaType.TV.name
                        var ageRating: String? = null
                        var runtimeMinutes = 0
                        var overview = f.overview ?: ""
                        var countryStr = f.country
                        try {
                            if (isTv) {
                                val tvDetails = RuVkTubeApp.instance.tmdbRepository.getTvDetails(f.tmdbId)
                                ageRating = tvDetails.ageRating
                                runtimeMinutes = tvDetails.runtimeMinutes
                                if (overview.isBlank()) overview = tvDetails.overview
                                if (countryStr.isNullOrBlank()) countryStr = tvDetails.country
                            } else {
                                val mDetails = RuVkTubeApp.instance.tmdbRepository.getMovieDetails(f.tmdbId)
                                ageRating = mDetails.ageRating
                                runtimeMinutes = mDetails.runtimeMinutes
                                if (overview.isBlank()) overview = mDetails.overview
                                if (countryStr.isNullOrBlank()) countryStr = mDetails.country
                            }
                        } catch (_: Exception) {}

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
                            runtimeMinutes = runtimeMinutes,
                            ageRating = ageRating,
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

    suspend fun syncHistoryChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
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
            ) ?: return@withContext

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

                        var ageRating: String? = null
                        var runtimeMinutes = if (durMs > 0L) (durMs / 60_000L).toInt() else 0
                        var overview = h.overview ?: ""
                        var countryStr = h.country

                        try {
                            if (isTv) {
                                val tvDetails = RuVkTubeApp.instance.tmdbRepository.getTvDetails(h.tmdbId)
                                ageRating = tvDetails.ageRating
                                if (runtimeMinutes == 0) runtimeMinutes = tvDetails.runtimeMinutes
                                if (overview.isBlank()) overview = tvDetails.overview
                                if (countryStr.isNullOrBlank()) countryStr = tvDetails.country
                            } else {
                                val mDetails = RuVkTubeApp.instance.tmdbRepository.getMovieDetails(h.tmdbId)
                                ageRating = mDetails.ageRating
                                if (runtimeMinutes == 0) runtimeMinutes = mDetails.runtimeMinutes
                                if (overview.isBlank()) overview = mDetails.overview
                                if (countryStr.isNullOrBlank()) countryStr = mDetails.country
                            }
                        } catch (_: Exception) {}

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
                            ageRating = ageRating,
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
            // При обновлении истории просмотров сразу синхронизируем системный Play Next
            syncWatchNext(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing History channel", e)
        }
    }

    suspend fun syncWatchNext(context: Context) = withContext(Dispatchers.IO) {
        if (!isTvChannelsSupported()) return@withContext
        try {
            val helper = PreviewChannelHelper(context)
            val histEntities = RuVkTubeApp.instance.database.historyDao().getRecentHistoryList()

            // В Play Next выводим только реально начатые и недосмотренные до конца видео (> 5 сек и < 95% длительности)
            val inProgressEntities = histEntities.filter { h ->
                val pos = h.positionMs
                val dur = h.durationMs
                pos > 5_000L && (dur <= 0L || pos < (dur * 0.95).toLong())
            }.take(10)

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

            val existingMap = existingPrograms.associateBy { it.internalProviderId }.toMutableMap()

            for (h in inProgressEntities) {
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

                // Пропускаем, если серия/фильм завершены
                if (durMs > 0L && posMs >= (durMs * 0.95).toLong()) {
                    continue
                }

                val internalId = "watch_next_${if (isTv) "TV" else "MOVIE"}_${h.tmdbId}"
                val existing = existingMap.remove(internalId)

                var ageRating: String? = null
                var runtimeMinutes = if (durMs > 0L) (durMs / 60_000L).toInt() else 0
                var overview = h.overview ?: ""
                var countryStr = h.country

                try {
                    if (isTv) {
                        val tvDetails = RuVkTubeApp.instance.tmdbRepository.getTvDetails(h.tmdbId)
                        ageRating = tvDetails.ageRating
                        if (runtimeMinutes == 0) runtimeMinutes = tvDetails.runtimeMinutes
                        if (overview.isBlank()) overview = tvDetails.overview
                        if (countryStr.isNullOrBlank()) countryStr = tvDetails.country
                    } else {
                        val mDetails = RuVkTubeApp.instance.tmdbRepository.getMovieDetails(h.tmdbId)
                        ageRating = mDetails.ageRating
                        if (runtimeMinutes == 0) runtimeMinutes = mDetails.runtimeMinutes
                        if (overview.isBlank()) overview = mDetails.overview
                        if (countryStr.isNullOrBlank()) countryStr = mDetails.country
                    }
                } catch (_: Exception) {}

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
                    ageRating = ageRating,
                    mediaType = if (isTv) MediaType.TV else MediaType.MOVIE,
                    watchPositionMs = posMs,
                    watchDurationMs = durMs,
                    lastStreamUrl = lastStream,
                    lastSeason = sNum,
                    lastEpisode = eNum,
                    country = countryStr
                )

                val programBuilder = if (existing != null) {
                    WatchNextProgram.Builder(existing)
                } else {
                    WatchNextProgram.Builder()
                }

                val programUri = buildProgramIntentUri(context, mediaItem)
                val imageUri = mediaItem.posterUrl?.takeIf { it.isNotBlank() }
                    ?: mediaItem.backdropUrl?.takeIf { it.isNotBlank() }
                val subtitle = buildMetadataSubtitle(mediaItem)
                val overviewText = mediaItem.overview.takeIf { it.isNotBlank() } ?: subtitle

                programBuilder
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(h.updatedAt)
                    .setTitle(mediaItem.title)
                    .setDescription(subtitle.ifBlank { overviewText })
                    .setLongDescription(overviewText)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                    .setType(if (isTv) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setIntentUri(programUri)
                    .setInternalProviderId(internalId)
                    .setLastPlaybackPositionMillis(posMs.toInt())
                    .setDurationMillis(if (durMs > 0L) durMs.toInt() else 0)

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

    fun clearWatchNext(context: Context) {
        if (!isTvChannelsSupported()) return
        try {
            context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear WatchNext programs", e)
        }
    }

    fun buildMetadataSubtitle(item: MediaItem): String {
        val meta = mutableListOf<String>()

        val episodeInfo = if (item.mediaType == MediaType.TV && item.lastSeason > 0 && item.lastEpisode > 0) {
            "S${item.lastSeason}E${item.lastEpisode}"
        } else null
        if (episodeInfo != null) {
            meta.add(episodeInfo)
        }

        // 1. Возрастной рейтинг: 18+, 16+, 12+, 6+, 0+
        item.ageRating?.takeIf { it.isNotBlank() }?.let {
            meta.add(it)
        }

        // 2. Рейтинг: ★ 8.2
        if (item.voteAverage > 0.0) {
            meta.add(String.format(Locale.US, "★ %.1f", item.voteAverage))
        }

        // 3. Длительность: 124 мин
        val runtimeMinutes = when {
            item.runtimeMinutes > 0 -> item.runtimeMinutes
            item.watchDurationMs > 0L -> (item.watchDurationMs / 60_000L).toInt()
            else -> 0
        }
        if (runtimeMinutes > 0) {
            meta.add("$runtimeMinutes мин")
        }

        // 4. Год: 2024
        val yearStr = when {
            item.year > 0 -> item.year.toString()
            item.releaseDate.isNotBlank() -> item.releaseDate.take(4).takeIf { y -> y.all { it.isDigit() } }
            else -> null
        }
        if (yearStr != null) {
            meta.add(yearStr)
        }

        // 5. Страна
        item.country?.takeIf { it.isNotBlank() }?.let {
            meta.add(it)
        }

        return meta.joinToString(" • ")
    }

    fun buildProgramTitle(item: MediaItem): String = item.title

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

        val existingMap = existingPrograms.associateBy { it.internalProviderId }.toMutableMap()
        val total = items.size

        items.forEachIndexed { index, item ->
            val internalId = "${item.mediaType.name}:${item.id}"
            val weight = (total - index) * 10
            val existing = existingMap.remove(internalId)

            val programBuilder = if (existing != null) {
                PreviewProgram.Builder(existing)
            } else {
                PreviewProgram.Builder()
            }

            val programUri = buildProgramIntentUri(context, item)
            val imageUri = item.posterUrl?.takeIf { it.isNotBlank() }
                ?: item.backdropUrl?.takeIf { it.isNotBlank() }
            val subtitle = buildMetadataSubtitle(item)
            val overviewText = item.overview.takeIf { it.isNotBlank() } ?: subtitle

            programBuilder
                .setChannelId(channelId)
                .setTitle(item.title)
                .setDescription(subtitle.ifBlank { overviewText })
                .setLongDescription(overviewText)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setType(if (item.mediaType == MediaType.TV) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setIntentUri(programUri)
                .setInternalProviderId(internalId)
                .setWeight(weight)

            if (imageUri != null) {
                programBuilder.setPosterArtUri(Uri.parse(imageUri))
            }

            if (item.watchPositionMs > 5_000L && item.watchDurationMs > 0L) {
                programBuilder.setLastPlaybackPositionMillis(item.watchPositionMs.toInt())
                programBuilder.setDurationMillis(item.watchDurationMs.toInt())
                programBuilder.setInteractionType(TvContractCompat.PreviewPrograms.INTERACTION_TYPE_VIEWS)
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