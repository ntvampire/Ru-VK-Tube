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
import kotlinx.coroutines.withContext
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

            val trendingItems = try {
                RuVkTubeApp.instance.tmdbRepository.getTrending(page = 1).take(20)
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch trending from TMDB", e)
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
            val favItems = favEntities.map { f ->
                MediaItem(
                    id = f.tmdbId,
                    title = f.title,
                    originalTitle = "",
                    overview = f.overview ?: "",
                    posterUrl = f.posterUrl,
                    backdropUrl = f.backdropUrl,
                    releaseDate = if (f.year > 0) "${f.year}" else "",
                    year = f.year,
                    voteAverage = f.rating,
                    mediaType = if (f.mediaType == MediaType.TV.name) MediaType.TV else MediaType.MOVIE
                )
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
            val historyItems = mutableListOf<MediaItem>()

            for (h in histEntities) {
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

                historyItems.add(
                    MediaItem(
                        id = h.tmdbId,
                        title = h.title,
                        originalTitle = "",
                        overview = h.overview ?: "",
                        posterUrl = h.posterUrl,
                        backdropUrl = h.backdropUrl,
                        releaseDate = if (h.year > 0) "${h.year}" else "",
                        year = h.year,
                        voteAverage = h.rating,
                        mediaType = if (isTv) MediaType.TV else MediaType.MOVIE,
                        watchPositionMs = posMs,
                        watchDurationMs = durMs,
                        lastStreamUrl = lastStream,
                        lastSeason = sNum,
                        lastEpisode = eNum
                    )
                )
            }

            syncPrograms(context, helper, channelId, historyItems)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing History channel", e)
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

            programBuilder
                .setChannelId(channelId)
                .setTitle(item.title)
                .setDescription(item.overview.takeIf { it.isNotBlank() } ?: item.title)
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
