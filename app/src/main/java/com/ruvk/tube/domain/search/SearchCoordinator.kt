package com.ruvk.tube.domain.search

import android.util.Log
import com.ruvk.tube.data.api.rutube.RutubeClient
import com.ruvk.tube.data.api.vk.VkClient
import com.ruvk.tube.domain.models.VideoPlatform
import com.ruvk.tube.domain.models.VideoSource
import com.ruvk.tube.player.DeviceCapabilityHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class SearchCoordinator(
    private val rutubeClient: RutubeClient = RutubeClient(),
    private val vkClient: VkClient = VkClient()
) {

    private val movieStopWords = listOf(
        "трейлер", "тизер", "trailer", "teaser", "обзор", "реакция", "разбор",
        "саундтрек", "ost", "клип", "отрывок", "сцена", "съемки", "со съемок", "пародия",
        "shorts", "шортс", "тикток", "tiktok", "reels", "рилс", "смешные моменты",
        "лучшие моменты", "нарезка", "фрагмент", "эдиты", "edit", "эдит", "спойлеры"
    )

    private val trailerStopWords = listOf(
        "обзор", "разбор", "реакция", "пародия", "саундтрек", "фильм целиком", "полный фильм",
        "смотреть онлайн", "фильм полностью", "серия"
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

    suspend fun findMovieSources(
        title: String,
        year: Int,
        runtimeMinutes: Int,
        originalTitle: String? = null,
        maxSupportedHeight: Int = 1080
    ): List<VideoSource> = coroutineScope {
        val rutubeResults = mutableListOf<VideoSource>()
        val vkResults = mutableListOf<VideoSource>()
        val targetSeconds = runtimeMinutes * 60

        val queries = mutableListOf<String>()
        if (year > 0) {
            queries.add("$title $year")
            queries.add("$title фильм")
        }
        queries.add(title)
        if (!originalTitle.isNullOrBlank() && originalTitle != title) {
            if (year > 0) queries.add("$originalTitle $year")
            queries.add(originalTitle)
        }

        for (q in queries) {
            val rutubeDeferred = if (rutubeResults.size < 10) async { rutubeClient.searchVideos(q) } else null
            val vkDeferred = if (vkResults.size < 10) async { vkClient.searchVideos(q) } else null

            val rutubeRaw = rutubeDeferred?.await() ?: emptyList()
            val vkRaw = vkDeferred?.await() ?: emptyList()

            // 1. Фильтрация и извлечение потоков Rutube (до 10 видео)
            for (item in rutubeRaw) {
                if (rutubeResults.size >= 10) break
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                if (movieStopWords.any { lowerTitle.contains(it) }) continue
                if (!isTitleRelevant(item.title, title, originalTitle)) continue

                if (targetSeconds > 0) {
                    val minSec = (targetSeconds * 0.50).toInt()
                    val maxSec = (targetSeconds * 1.50).toInt()
                    if (item.duration !in minSec..maxSec) continue
                } else if (item.duration < 35 * 60) {
                    continue
                }

                val streamUrl = rutubeClient.getStreamUrl(item.id) ?: continue
                val quality = detectQuality(lowerTitle)
                val voiceover = detectVoiceover(lowerTitle)
                val score = calculateScore(lowerTitle, year, quality, voiceover, maxSupportedHeight)

                if (rutubeResults.none { it.streamUrl == streamUrl }) {
                    rutubeResults.add(
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
            }

            // 2. Фильтрация и извлечение потоков VK (до 10 видео)
            for (item in vkRaw) {
                if (vkResults.size >= 10) break
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                if (movieStopWords.any { lowerTitle.contains(it) }) continue
                if (!isTitleRelevant(item.title, title, originalTitle)) continue

                // Проверка хронометража: отсекаем ролики, не подходящие под фильм
                if (targetSeconds > 0) {
                    val minSec = (targetSeconds * 0.50).toInt()
                    val maxSec = (targetSeconds * 1.50).toInt()
                    if (item.durationSeconds > 0 && item.durationSeconds !in minSec..maxSec) continue
                } else if (item.durationSeconds in 1..(35 * 60)) {
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
                val score = calculateScore(lowerTitle, year, quality, voiceover, maxSupportedHeight)

                if (vkResults.none { it.streamUrl == streamUrl }) {
                    vkResults.add(
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
            }

            // Если оба сервиса нашли источники, можно завершить цикл запросов досрочно
            if (rutubeResults.isNotEmpty() && vkResults.isNotEmpty()) break
        }

        val allResults = (rutubeResults + vkResults).sortedByDescending { it.score }
        Log.d("RuVkTube", "findMovieSources for '$title': Rutube=${rutubeResults.size}, VK=${vkResults.size}")
        allResults
    }

    suspend fun findTrailers(
        title: String,
        year: Int,
        originalTitle: String? = null,
        maxSupportedHeight: Int = 1080
    ): List<VideoSource> = coroutineScope {
        val rutubeTrailers = mutableListOf<VideoSource>()
        val vkTrailers = mutableListOf<VideoSource>()

        val queries = listOf(
            "$title русский трейлер",
            "$title трейлер"
        )

        for (q in queries) {
            val rutubeDeferred = if (rutubeTrailers.size < 10) async { rutubeClient.searchVideos(q) } else null
            val vkDeferred = if (vkTrailers.size < 10) async { vkClient.searchVideos(q) } else null

            val rutubeRaw = rutubeDeferred?.await() ?: emptyList()
            val vkRaw = vkDeferred?.await() ?: emptyList()

            // Rutube Trailers (до 10 штук)
            for (item in rutubeRaw) {
                if (rutubeTrailers.size >= 10) break
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                if (trailerStopWords.any { lowerTitle.contains(it) }) continue
                if (!trailerRequiredWords.any { lowerTitle.contains(it) }) continue
                if (!isTitleRelevant(item.title, title, originalTitle)) continue
                if (item.duration !in 20..360) continue

                val streamUrl = rutubeClient.getStreamUrl(item.id) ?: continue
                val quality = detectQuality(lowerTitle)
                val score = calculateScore(lowerTitle, year, quality, null, maxSupportedHeight)

                rutubeTrailers.add(
                    VideoSource(
                        platform = VideoPlatform.RUTUBE,
                        title = item.title,
                        streamUrl = streamUrl,
                        quality = quality,
                        durationSeconds = item.duration,
                        isTrailer = true,
                        score = score
                    )
                )
            }

            // VK Trailers (до 10 штук)
            for (item in vkRaw) {
                if (vkTrailers.size >= 10) break
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                if (trailerStopWords.any { lowerTitle.contains(it) }) continue
                if (!trailerRequiredWords.any { lowerTitle.contains(it) }) continue
                if (!isTitleRelevant(item.title, title, originalTitle)) continue
                if (item.durationSeconds in 1..19 || item.durationSeconds > 400) continue

                val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: continue
                val streamUrl = streamInfo.hlsUrl
                    ?: streamInfo.directUrls["1080"]
                    ?: streamInfo.directUrls["720"]
                    ?: continue

                val quality = streamInfo.bestQuality
                val score = calculateScore(lowerTitle, year, quality, null, maxSupportedHeight)

                vkTrailers.add(
                    VideoSource(
                        platform = VideoPlatform.VK,
                        title = item.title,
                        streamUrl = streamUrl,
                        quality = quality,
                        durationSeconds = item.durationSeconds,
                        isTrailer = true,
                        score = score
                    )
                )
            }

            if (rutubeTrailers.isNotEmpty() && vkTrailers.isNotEmpty()) break
        }

        val allTrailers = (rutubeTrailers + vkTrailers).sortedByDescending { it.score }
        Log.d("RuVkTube", "findTrailers for '$title': Rutube=${rutubeTrailers.size}, VK=${vkTrailers.size}")
        allTrailers
    }

    /**
     * Проверяет, что видео действительно относится к запрашиваемому фильму/сериалу,
     * а не является случайным роликом или клипом со схожими словами.
     */
    private fun isTitleRelevant(videoTitle: String, movieTitle: String, originalTitle: String? = null): Boolean {
        val cleanVideo = videoTitle.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .trim()

        val checkTitles = mutableListOf(movieTitle)
        if (!originalTitle.isNullOrBlank() && originalTitle != movieTitle) {
            checkTitles.add(originalTitle)
        }

        val stopPrepositions = setOf(
            "в", "на", "и", "с", "по", "из", "о", "об", "к", "за", "от", "для", "до", "без",
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "with", "by", "and"
        )

        for (targetTitle in checkTitles) {
            val cleanTarget = targetTitle.lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("[^a-zа-я0-9\\s]"), " ")
                .trim()

            if (cleanTarget.isBlank()) continue

            // 1. Точное вхождение целевого названия целиком
            if (cleanVideo.contains(cleanTarget)) {
                return true
            }

            // 2. Выделение значимых ключевых слов (длиной от 2 символов, исключая предлоги)
            val keywords = cleanTarget.split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.length >= 2 && it !in stopPrepositions }

            if (keywords.isEmpty()) continue

            val firstKeyword = keywords.first()
            // Если хотя бы первое (главное) ключевое слово есть в видео:
            if (cleanVideo.contains(firstKeyword)) {
                if (keywords.size == 1) {
                    return true
                }
                val matchedCount = keywords.count { cleanVideo.contains(it) }
                if (matchedCount >= (keywords.size / 2).coerceAtLeast(1)) {
                    return true
                }
            }
        }

        return false
    }

    private fun detectQuality(lowerTitle: String): String {
        return when {
            lowerTitle.contains("2160") || lowerTitle.contains("4k") -> "4K"
            lowerTitle.contains("1440") || lowerTitle.contains("2k") -> "2K"
            lowerTitle.contains("1080") || lowerTitle.contains("fhd") -> "1080p"
            lowerTitle.contains("720") || lowerTitle.contains("hd") -> "720p"
            lowerTitle.contains("480") || lowerTitle.contains("sd") -> "480p"
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

    private fun calculateScore(
        lowerTitle: String,
        year: Int,
        quality: String,
        voiceover: String?,
        maxSupportedHeight: Int
    ): Int {
        var score = 0

        // 1. Точность по году
        if (year > 0 && lowerTitle.contains(year.toString())) score += 25

        // 2. Наличие известной студии дубляжа
        if (voiceover != null) score += 30

        // 3. Автовыбор по разрешению устройства
        val qualityHeight = DeviceCapabilityHelper.qualityToHeight(quality)
        if (qualityHeight <= maxSupportedHeight) {
            score += (qualityHeight / 10)
        } else {
            score += ((maxSupportedHeight / 10) - 20)
        }

        return score
    }
}
