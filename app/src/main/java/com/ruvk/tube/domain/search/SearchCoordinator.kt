package com.ruvk.tube.domain.search

import com.ruvk.tube.data.api.rutube.RutubeClient
import com.ruvk.tube.data.api.vk.VkClient
import com.ruvk.tube.domain.models.VideoPlatform
import com.ruvk.tube.domain.models.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class SearchCoordinator(
    private val rutubeClient: RutubeClient = RutubeClient(),
    private val vkClient: VkClient = VkClient()
) {

    private val movieStopWords = listOf(
        "трейлер", "тизер", "trailer", "teaser", "обзор", "реакция", "разбор",
        "саундтрек", "ost", "клип", "отрывок", "сцена", "съемки", "со съемок"
    )

    private val trailerStopWords = listOf(
        "обзор", "разбор", "реакция", "пародия", "саундтрек", "фильм целиком"
    )

    private val trailerRequiredWords = listOf(
        "трейлер", "тизер", "trailer", "teaser"
    )

    private val voiceovers = listOf(
        "дубляж" to "Дубляж",
        "дублированный" to "Дубляж",
        "red head sound" to "Red Head Sound",
        "rhs" to "Red Head Sound",
        "lostfilm" to "LostFilm",
        "кубик в кубе" to "Кубик в кубе",
        "hdrezka" to "HDRezka",
        "пифагор" to "Пифагор",
        "кураж бамбей" to "Кураж Бамбей",
        "newstudio" to "NewStudio",
        "tvshows" to "TVShows",
        "многоголосый" to "Многоголосый"
    )

    suspend fun findMovieSources(title: String, year: Int, runtimeMinutes: Int): List<VideoSource> = coroutineScope {
        val queryWithYear = if (year > 0) "$title $year" else title

        val rutubeDeferred = async { rutubeClient.searchVideos(queryWithYear) }
        val vkDeferred = async { vkClient.searchVideos(queryWithYear) }

        val rutubeRaw = rutubeDeferred.await()
        val vkRaw = vkDeferred.await()

        val results = mutableListOf<VideoSource>()
        val targetSeconds = runtimeMinutes * 60

        // Фильтрация Rutube
        for (item in rutubeRaw) {
            val lowerTitle = item.title.lowercase(Locale.ROOT)
            if (movieStopWords.any { lowerTitle.contains(it) }) continue

            if (targetSeconds > 0) {
                val minSec = (targetSeconds * 0.80).toInt()
                val maxSec = (targetSeconds * 1.20).toInt()
                if (item.duration !in minSec..maxSec) continue
            } else if (item.duration < 45 * 60) {
                continue
            }

            // Получаем ссылку на поток
            val streamUrl = rutubeClient.getStreamUrl(item.id) ?: continue
            val quality = detectQuality(lowerTitle)
            val voiceover = detectVoiceover(lowerTitle)
            val score = calculateScore(lowerTitle, year, quality, voiceover)

            results.add(
                VideoSource(
                    platform = VideoPlatform.RUTUBE,
                    title = item.title,
                    streamUrl = streamUrl,
                    quality = quality,
                    durationSeconds = item.duration,
                    voiceover = voiceover,
                    isTrailer = false,
                    score = score
                )
            )
        }

        // Фильтрация VK
        for (item in vkRaw) {
            val lowerTitle = item.title.lowercase(Locale.ROOT)
            if (movieStopWords.any { lowerTitle.contains(it) }) continue

            if (targetSeconds > 0 && item.durationSeconds > 0) {
                val minSec = (targetSeconds * 0.80).toInt()
                val maxSec = (targetSeconds * 1.20).toInt()
                if (item.durationSeconds !in minSec..maxSec) continue
            } else if (item.durationSeconds in 1..(45 * 60)) {
                continue
            }

            val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: continue
            val streamUrl = streamInfo.hlsUrl
                ?: streamInfo.directUrls["1080"]
                ?: streamInfo.directUrls["720"]
                ?: streamInfo.directUrls["480"]
                ?: continue

            val quality = streamInfo.bestQuality
            val voiceover = detectVoiceover(lowerTitle)
            val score = calculateScore(lowerTitle, year, quality, voiceover)

            results.add(
                VideoSource(
                    platform = VideoPlatform.VK,
                    title = item.title,
                    streamUrl = streamUrl,
                    quality = quality,
                    durationSeconds = item.durationSeconds,
                    voiceover = voiceover,
                    isTrailer = false,
                    score = score
                )
            )
        }

        results.sortedByDescending { it.score }
    }

    suspend fun findTrailers(title: String, year: Int): List<VideoSource> = coroutineScope {
        val query = "$title русский трейлер"

        val rutubeDeferred = async { rutubeClient.searchVideos(query) }
        val vkDeferred = async { vkClient.searchVideos(query) }

        val rutubeRaw = rutubeDeferred.await()
        val vkRaw = vkDeferred.await()

        val results = mutableListOf<VideoSource>()

        // Rutube Trailers
        for (item in rutubeRaw) {
            val lowerTitle = item.title.lowercase(Locale.ROOT)
            if (trailerStopWords.any { lowerTitle.contains(it) }) continue
            if (!trailerRequiredWords.any { lowerTitle.contains(it) }) continue
            if (item.duration !in 25..270) continue // 25 сек - 4.5 мин

            val streamUrl = rutubeClient.getStreamUrl(item.id) ?: continue
            results.add(
                VideoSource(
                    platform = VideoPlatform.RUTUBE,
                    title = item.title,
                    streamUrl = streamUrl,
                    quality = detectQuality(lowerTitle),
                    durationSeconds = item.duration,
                    isTrailer = true,
                    score = 100
                )
            )
        }

        // VK Trailers
        for (item in vkRaw) {
            val lowerTitle = item.title.lowercase(Locale.ROOT)
            if (trailerStopWords.any { lowerTitle.contains(it) }) continue
            if (!trailerRequiredWords.any { lowerTitle.contains(it) }) continue
            if (item.durationSeconds !in 25..270) continue

            val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: continue
            val streamUrl = streamInfo.hlsUrl
                ?: streamInfo.directUrls["1080"]
                ?: streamInfo.directUrls["720"]
                ?: continue

            results.add(
                VideoSource(
                    platform = VideoPlatform.VK,
                    title = item.title,
                    streamUrl = streamUrl,
                    quality = streamInfo.bestQuality,
                    durationSeconds = item.durationSeconds,
                    isTrailer = true,
                    score = 100
                )
            )
        }

        results
    }

    private fun detectQuality(lowerTitle: String): String {
        return when {
            lowerTitle.contains("2160") || lowerTitle.contains("4k") -> "4K"
            lowerTitle.contains("1080") || lowerTitle.contains("fhd") -> "1080p"
            lowerTitle.contains("720") || lowerTitle.contains("hd") -> "720p"
            else -> "720p"
        }
    }

    private fun detectVoiceover(lowerTitle: String): String? {
        for ((keyword, label) in voiceovers) {
            if (lowerTitle.contains(keyword)) {
                return label
            }
        }
        return null
    }

    private fun calculateScore(lowerTitle: String, year: Int, quality: String, voiceover: String?): Int {
        var score = 0
        if (year > 0 && lowerTitle.contains(year.toString())) score += 25
        if (quality == "4K") score += 50
        if (quality == "1080p") score += 35
        if (quality == "720p") score += 15
        if (voiceover != null) score += 20
        return score
    }
}
