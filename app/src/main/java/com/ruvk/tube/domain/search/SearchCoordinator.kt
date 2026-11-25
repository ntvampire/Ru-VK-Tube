package com.ruvk.tube.domain.search

import android.util.Log
import com.ruvk.tube.data.api.rutube.RutubeClient
import com.ruvk.tube.data.api.rutube.RutubeVideoItem
import com.ruvk.tube.data.api.vk.VkClient
import com.ruvk.tube.data.api.vk.VkVideoItem
import com.ruvk.tube.domain.models.VideoPlatform
import com.ruvk.tube.domain.models.VideoSource
import com.ruvk.tube.player.DeviceCapabilityHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    // Маркеры экранок, камрипов и записей из кинозалов
    private val camRegex = Regex("(?i)\\b(TS|TELESYNC|CAMRIP|CAM|HDCAM|TC|TELECINE|LINE)\\b|экранк|экран|чистый звук|звук с ts|звук ts")

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
            val rutubeDeferred = if (rutubeResults.size < MAX_SOURCES_PER_PLATFORM) async { rutubeClient.searchVideos(q) } else null
            val vkDeferred = if (vkResults.size < MAX_SOURCES_PER_PLATFORM) async { vkClient.searchVideos(q) } else null

            val rutubeRaw = rutubeDeferred?.await() ?: emptyList()
            val vkRaw = vkDeferred?.await() ?: emptyList()

            val rutubeCandidates = rutubeRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !movieStopWords.any { lowerTitle.contains(it) } &&
                    !camRegex.containsMatchIn(lowerTitle) &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    isValidDuration(item.duration, targetSeconds, isEpisode = false, allowZeroDuration = false)
            }.take((MAX_SOURCES_PER_PLATFORM - rutubeResults.size) + 4)

            val vkCandidates = vkRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !movieStopWords.any { lowerTitle.contains(it) } &&
                    !camRegex.containsMatchIn(lowerTitle) &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    isValidDuration(item.durationSeconds, targetSeconds, isEpisode = false, allowZeroDuration = true)
            }.take((MAX_SOURCES_PER_PLATFORM - vkResults.size) + 4)

            val resolvedRutubeDeferred = async {
                rutubeCandidates.map { item ->
                    async {
                        val streamUrl = rutubeClient.getStreamUrl(item.id) ?: return@async null
                        val lowerTitle = item.title.lowercase(Locale.ROOT)
                        val quality = detectRutubeQuality(streamUrl, lowerTitle)
                        val voiceover = detectVoiceover(lowerTitle)
                        val score = calculateScore(lowerTitle, year, quality, voiceover, maxSupportedHeight, VideoPlatform.RUTUBE)
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
                    }
                }.awaitAll().filterNotNull()
            }

            val resolvedVkDeferred = async {
                vkCandidates.map { item ->
                    async {
                        val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: return@async null
                        val streamUrl = streamInfo.hlsUrl
                            ?: streamInfo.directUrls["1080"]
                            ?: streamInfo.directUrls["720"]
                            ?: streamInfo.directUrls["480"]
                            ?: return@async null
                        val lowerTitle = item.title.lowercase(Locale.ROOT)
                        val quality = detectVkQuality(streamInfo.bestQuality, lowerTitle)
                        val voiceover = detectVoiceover(lowerTitle)
                        val score = calculateScore(lowerTitle, year, quality, voiceover, maxSupportedHeight, VideoPlatform.VK)
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
                    }
                }.awaitAll().filterNotNull()
            }

            appendUniqueSources(rutubeResults, resolvedRutubeDeferred.await())
            appendUniqueSources(vkResults, resolvedVkDeferred.await())

            // Завершаем досрочно только при наличии достаточного выбора с обеих платформ
            if (shouldStopEarly(rutubeResults, vkResults)) break
        }

        val allResults = (rutubeResults + vkResults).sortedByDescending { it.score }
        Log.d("RuVkTube", "findMovieSources for '$title': Rutube=${rutubeResults.size}, VK=${vkResults.size}")
        allResults
    }

    suspend fun findEpisodeSources(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        runtimeMinutes: Int = 0,
        originalTitle: String? = null,
        maxSupportedHeight: Int = 1080
    ): List<VideoSource> = coroutineScope {
        val rutubeResults = mutableListOf<VideoSource>()
        val vkResults = mutableListOf<VideoSource>()
        val seenRutubeRaw = mutableListOf<RutubeVideoItem>()
        val seenVkRaw = mutableListOf<VkVideoItem>()
        val targetSeconds = runtimeMinutes * 60

        val sPadded = seasonNumber.toString().padStart(2, '0')
        val ePadded = episodeNumber.toString().padStart(2, '0')

        val queries = mutableListOf<String>()
        queries.add("$title $seasonNumber сезон $episodeNumber серия")
        queries.add("$title s${sPadded}e${ePadded}")
        queries.add("$title $episodeNumber серия")
        queries.add("$title $seasonNumber сезон $episodeNumber")

        if (!originalTitle.isNullOrBlank() && originalTitle != title) {
            queries.add("$originalTitle s${sPadded}e${ePadded}")
            queries.add("$originalTitle season $seasonNumber episode $episodeNumber")
        }

        for (q in queries) {
            val rutubeDeferred = if (rutubeResults.size < MAX_SOURCES_PER_PLATFORM) async { rutubeClient.searchVideos(q) } else null
            val vkDeferred = if (vkResults.size < MAX_SOURCES_PER_PLATFORM) async { vkClient.searchVideos(q) } else null

            val rutubeRaw = rutubeDeferred?.await() ?: emptyList()
            val vkRaw = vkDeferred?.await() ?: emptyList()
            seenRutubeRaw.addAll(rutubeRaw)
            seenVkRaw.addAll(vkRaw)

            val rutubeCandidates = rutubeRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !movieStopWords.any { lowerTitle.contains(it) } &&
                    !camRegex.containsMatchIn(lowerTitle) &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    isEpisodeMatch(lowerTitle, seasonNumber, episodeNumber) &&
                    isValidDuration(item.duration, targetSeconds, isEpisode = true, allowZeroDuration = false)
            }.take((MAX_SOURCES_PER_PLATFORM - rutubeResults.size) + 4)

            val vkCandidates = vkRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !movieStopWords.any { lowerTitle.contains(it) } &&
                    !camRegex.containsMatchIn(lowerTitle) &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    isEpisodeMatch(lowerTitle, seasonNumber, episodeNumber) &&
                    isValidDuration(item.durationSeconds, targetSeconds, isEpisode = true, allowZeroDuration = true)
            }.take((MAX_SOURCES_PER_PLATFORM - vkResults.size) + 4)

            val resolvedRutubeDeferred = async {
                resolveRutubeEpisodeCandidates(rutubeCandidates, seasonNumber, episodeNumber, maxSupportedHeight)
            }
            val resolvedVkDeferred = async {
                resolveVkEpisodeCandidates(vkCandidates, seasonNumber, episodeNumber, maxSupportedHeight)
            }

            appendUniqueSources(rutubeResults, resolvedRutubeDeferred.await())
            appendUniqueSources(vkResults, resolvedVkDeferred.await())

            if (shouldStopEarly(rutubeResults, vkResults)) break
        }

        // Этап 5: если поштучный поиск не нашёл серию, ищем через плейлисты Rutube и альбомы/связанные видео VK
        val rutubePlaylistDeferred = if (rutubeResults.isEmpty()) {
            async {
                findRutubePlaylistEpisodes(
                    title = title,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    targetSeconds = targetSeconds,
                    originalTitle = originalTitle,
                    maxSupportedHeight = maxSupportedHeight,
                    initialRawItems = seenRutubeRaw
                )
            }
        } else null

        val vkAlbumDeferred = if (vkResults.isEmpty()) {
            async {
                findVkAlbumEpisodes(
                    title = title,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    targetSeconds = targetSeconds,
                    originalTitle = originalTitle,
                    maxSupportedHeight = maxSupportedHeight,
                    initialRawItems = seenVkRaw
                )
            }
        } else null

        rutubePlaylistDeferred?.await()?.let { appendUniqueSources(rutubeResults, it) }
        vkAlbumDeferred?.await()?.let { appendUniqueSources(vkResults, it) }

        val allResults = (rutubeResults + vkResults).sortedByDescending { it.score }
        Log.d("RuVkTube", "findEpisodeSources for '$title S${seasonNumber}E${episodeNumber}': Rutube=${rutubeResults.size}, VK=${vkResults.size}")
        allResults
    }

    private suspend fun resolveRutubeEpisodeCandidates(
        candidates: List<RutubeVideoItem>,
        seasonNumber: Int,
        episodeNumber: Int,
        maxSupportedHeight: Int
    ): List<VideoSource> = coroutineScope {
        candidates.map { item ->
            async {
                val streamUrl = rutubeClient.getStreamUrl(item.id) ?: return@async null
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                val quality = detectRutubeQuality(streamUrl, lowerTitle)
                val voiceover = detectVoiceover(lowerTitle)
                val score = calculateEpisodeScore(lowerTitle, seasonNumber, episodeNumber, quality, voiceover, maxSupportedHeight, VideoPlatform.RUTUBE)
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
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveVkEpisodeCandidates(
        candidates: List<VkVideoItem>,
        seasonNumber: Int,
        episodeNumber: Int,
        maxSupportedHeight: Int
    ): List<VideoSource> = coroutineScope {
        candidates.map { item ->
            async {
                val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: return@async null
                val streamUrl = streamInfo.hlsUrl
                    ?: streamInfo.directUrls["1080"]
                    ?: streamInfo.directUrls["720"]
                    ?: streamInfo.directUrls["480"]
                    ?: return@async null
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                val quality = detectVkQuality(streamInfo.bestQuality, lowerTitle)
                val voiceover = detectVoiceover(lowerTitle)
                val score = calculateEpisodeScore(lowerTitle, seasonNumber, episodeNumber, quality, voiceover, maxSupportedHeight, VideoPlatform.VK)
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
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun findRutubePlaylistEpisodes(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        targetSeconds: Int,
        originalTitle: String?,
        maxSupportedHeight: Int,
        initialRawItems: List<RutubeVideoItem>
    ): List<VideoSource> = coroutineScope {
        val broadItems = initialRawItems.toMutableList()
        if (broadItems.none { isTitleRelevant(it.title, title, originalTitle) && (it.author?.id ?: 0L) > 0L }) {
            broadItems.addAll(rutubeClient.searchVideos("$title $seasonNumber сезон"))
            if (broadItems.none { isTitleRelevant(it.title, title, originalTitle) && (it.author?.id ?: 0L) > 0L }) {
                broadItems.addAll(rutubeClient.searchVideos(title))
            }
        }

        val authorIds = broadItems
            .filter { isTitleRelevant(it.title, title, originalTitle) && (it.author?.id ?: 0L) > 0L }
            .mapNotNull { it.author?.id }
            .distinct()
            .take(4)

        if (authorIds.isEmpty()) return@coroutineScope emptyList()

        val playlists = authorIds.map { aid ->
            async { rutubeClient.getUserPlaylists(aid) }
        }.awaitAll().flatten()
            .distinctBy { it.id }
            .filter { pl ->
                val plLower = pl.title.lowercase(Locale.ROOT)
                (pl.videosCount == 0 || pl.videosCount >= episodeNumber) &&
                    isTitleRelevant(pl.title, title, originalTitle) &&
                    !hasConflictingSeason(plLower, seasonNumber)
            }
            .take(4)

        if (playlists.isEmpty()) return@coroutineScope emptyList()

        val matchedCandidates = playlists.map { pl ->
            async {
                val plLower = pl.title.lowercase(Locale.ROOT)
                val plMatchesSeason = hasExplicitSeason(plLower, seasonNumber)
                val videos = rutubeClient.getPlaylistVideos(pl.id)
                videos.filter { item ->
                    val lowerTitle = item.title.lowercase(Locale.ROOT)
                    !movieStopWords.any { lowerTitle.contains(it) } &&
                        !camRegex.containsMatchIn(lowerTitle) &&
                        isPlaylistEpisodeMatch(lowerTitle, seasonNumber, episodeNumber, plMatchesSeason) &&
                        isValidDuration(item.duration, targetSeconds, isEpisode = true, allowZeroDuration = false)
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }.take(8)

        if (matchedCandidates.isEmpty()) return@coroutineScope emptyList()
        resolveRutubeEpisodeCandidates(matchedCandidates, seasonNumber, episodeNumber, maxSupportedHeight)
    }

    private suspend fun findVkAlbumEpisodes(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        targetSeconds: Int,
        originalTitle: String?,
        maxSupportedHeight: Int,
        initialRawItems: List<VkVideoItem>
    ): List<VideoSource> = coroutineScope {
        val broadItems = initialRawItems.toMutableList()
        broadItems.addAll(vkClient.searchVideos("$title $seasonNumber сезон"))
        if (broadItems.none { isTitleRelevant(it.title, title, originalTitle) }) {
            broadItems.addAll(vkClient.searchVideos(title))
        }

        val relevant = broadItems
            .distinctBy { "${it.oid}_${it.id}" }
            .filter { isTitleRelevant(it.title, title, originalTitle) }

        val directCandidates = relevant.filter { item ->
            val lowerTitle = item.title.lowercase(Locale.ROOT)
            !movieStopWords.any { lowerTitle.contains(it) } &&
                !camRegex.containsMatchIn(lowerTitle) &&
                isEpisodeMatch(lowerTitle, seasonNumber, episodeNumber) &&
                isValidDuration(item.durationSeconds, targetSeconds, isEpisode = true, allowZeroDuration = true)
        }.take(6)

        if (directCandidates.isNotEmpty()) {
            return@coroutineScope resolveVkEpisodeCandidates(directCandidates, seasonNumber, episodeNumber, maxSupportedHeight)
        }

        // Фолбэк: проверяем связанные видео (next_other_videos) из альбомов/плейлистов найденных роликов
        val seeds = relevant
            .sortedByDescending { !it.playlistId.isNullOrBlank() }
            .take(3)

        val relatedPool = seeds.map { seed ->
            async { vkClient.resolveStream(seed.oid, seed.id)?.relatedVideos ?: emptyList() }
        }.awaitAll().flatten()
            .distinctBy { "${it.oid}_${it.id}" }
            .filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !movieStopWords.any { lowerTitle.contains(it) } &&
                    !camRegex.containsMatchIn(lowerTitle) &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    isEpisodeMatch(lowerTitle, seasonNumber, episodeNumber) &&
                    isValidDuration(item.durationSeconds, targetSeconds, isEpisode = true, allowZeroDuration = true)
            }
            .take(6)

        if (relatedPool.isEmpty()) return@coroutineScope emptyList()
        resolveVkEpisodeCandidates(relatedPool, seasonNumber, episodeNumber, maxSupportedHeight)
    }

    internal fun hasConflictingSeason(lowerTitle: String, seasonNumber: Int): Boolean {
        val otherSeasonRegex = Regex("(?i)\\b(\\d+)\\s*(?:сезон|сез|season)\\b")
        val matchSeason = otherSeasonRegex.find(lowerTitle) ?: return false
        val foundSeason = matchSeason.groupValues[1].toIntOrNull()
        return foundSeason != null && foundSeason != seasonNumber
    }

    private fun hasExplicitSeason(lowerTitle: String, seasonNumber: Int): Boolean {
        val seasonRegex = Regex("(?i)\\b0?${seasonNumber}\\s*(?:сезон|сез|season)\\b")
        return seasonRegex.containsMatchIn(lowerTitle)
    }

    internal fun isPlaylistEpisodeMatch(
        lowerTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        playlistHasSeason: Boolean
    ): Boolean {
        if (isEpisodeMatch(lowerTitle, seasonNumber, episodeNumber)) return true
        if (!playlistHasSeason || hasConflictingSeason(lowerTitle, seasonNumber)) return false
        if (lowerTitle.contains("все серии") || lowerTitle.contains("серии подряд")) return false
        // Внутри плейлиста конкретного сезона ролики могут быть названы просто "02. Название" или "2"
        val bareEpRegex = Regex("(?i)(?:^|\\s)0?${episodeNumber}(?:\\.|\\s|-|$)")
        return bareEpRegex.containsMatchIn(lowerTitle)
    }

    internal fun isEpisodeMatch(lowerTitle: String, seasonNumber: Int, episodeNumber: Int): Boolean {
        // Исключаем сборники всех серий
        if (lowerTitle.contains("все серии") || lowerTitle.contains("серии подряд")) {
            return false
        }

        // Проверяем явное указание другого сезона (если сезон > 1 или в названии есть "сезон")
        if (hasConflictingSeason(lowerTitle, seasonNumber)) {
            return false
        }

        // 1. Шаблон S01E02 или S1E2
        val sxxExxPattern = Regex("(?i)\\bs0?${seasonNumber}\\s*e0?${episodeNumber}\\b")
        if (sxxExxPattern.containsMatchIn(lowerTitle)) {
            return true
        }

        // 2. Шаблон "X серия" или "серия X"
        val epPattern1 = Regex("(?i)(?:сери[яиею]|сер|выпуск|эпизод|ep)[\\s#№:]*0?${episodeNumber}\\b")
        val epPattern2 = Regex("(?i)\\b0?${episodeNumber}[\\s-]*(?:сери[яиею]|сер|выпуск|эпизод|ep)\\b")

        if (epPattern1.containsMatchIn(lowerTitle) || epPattern2.containsMatchIn(lowerTitle)) {
            return true
        }

        return false
    }

    private fun calculateEpisodeScore(
        lowerTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        quality: String,
        voiceover: String?,
        maxSupportedHeight: Int,
        platform: VideoPlatform
    ): Int {
        var score = 0
        val sPadded = seasonNumber.toString().padStart(2, '0')
        val ePadded = episodeNumber.toString().padStart(2, '0')

        if (lowerTitle.contains("s${sPadded}e${ePadded}") || lowerTitle.contains("s${seasonNumber}e${episodeNumber}")) {
            score += 35
        } else if (lowerTitle.contains("$seasonNumber сезон") && lowerTitle.contains("$episodeNumber серия")) {
            score += 40
        } else if (lowerTitle.contains("$episodeNumber серия")) {
            score += 25
        }

        if (voiceover != null) score += 30

        // Для ВКонтакте 4K жмётся исключительно в 8-бит H.264 без тономаппинга (тёмная картинка), поэтому не завышаем скор выше 1080p
        val qualityHeight = if (platform == VideoPlatform.VK && quality == "4K") {
            DeviceCapabilityHelper.MAX_SAFE_AVC_HEIGHT
        } else {
            DeviceCapabilityHelper.qualityToHeight(quality)
        }
        if (qualityHeight <= maxSupportedHeight) {
            score += (qualityHeight / 10)
        } else {
            score += ((maxSupportedHeight / 10) - 20)
        }

        return score
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
            val rutubeDeferred = if (rutubeTrailers.size < MAX_SOURCES_PER_PLATFORM) async { rutubeClient.searchVideos(q) } else null
            val vkDeferred = if (vkTrailers.size < MAX_SOURCES_PER_PLATFORM) async { vkClient.searchVideos(q) } else null

            val rutubeRaw = rutubeDeferred?.await() ?: emptyList()
            val vkRaw = vkDeferred?.await() ?: emptyList()

            val rutubeCandidates = rutubeRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !trailerStopWords.any { lowerTitle.contains(it) } &&
                    trailerRequiredWords.any { lowerTitle.contains(it) } &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    item.duration in 20..360
            }.take((MAX_SOURCES_PER_PLATFORM - rutubeTrailers.size) + 4)

            val vkCandidates = vkRaw.filter { item ->
                val lowerTitle = item.title.lowercase(Locale.ROOT)
                !trailerStopWords.any { lowerTitle.contains(it) } &&
                    trailerRequiredWords.any { lowerTitle.contains(it) } &&
                    isTitleRelevant(item.title, title, originalTitle) &&
                    !(item.durationSeconds in 1..19 || item.durationSeconds > 400)
            }.take((MAX_SOURCES_PER_PLATFORM - vkTrailers.size) + 4)

            val resolvedRutubeDeferred = async {
                rutubeCandidates.map { item ->
                    async {
                        val streamUrl = rutubeClient.getStreamUrl(item.id) ?: return@async null
                        val lowerTitle = item.title.lowercase(Locale.ROOT)
                        val quality = detectRutubeQuality(streamUrl, lowerTitle)
                        val score = calculateScore(lowerTitle, year, quality, null, maxSupportedHeight, VideoPlatform.RUTUBE)
                        VideoSource(
                            platform = VideoPlatform.RUTUBE,
                            title = item.title,
                            streamUrl = streamUrl,
                            quality = quality,
                            durationSeconds = item.duration,
                            isTrailer = true,
                            score = score
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            val resolvedVkDeferred = async {
                vkCandidates.map { item ->
                    async {
                        val streamInfo = vkClient.resolveStream(item.oid, item.id) ?: return@async null
                        val streamUrl = streamInfo.hlsUrl
                            ?: streamInfo.directUrls["1080"]
                            ?: streamInfo.directUrls["720"]
                            ?: return@async null
                        val lowerTitle = item.title.lowercase(Locale.ROOT)
                        val quality = detectVkQuality(streamInfo.bestQuality, lowerTitle)
                        val score = calculateScore(lowerTitle, year, quality, null, maxSupportedHeight, VideoPlatform.VK)
                        VideoSource(
                            platform = VideoPlatform.VK,
                            title = item.title,
                            streamUrl = streamUrl,
                            quality = quality,
                            durationSeconds = item.durationSeconds,
                            isTrailer = true,
                            score = score
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            appendUniqueSources(rutubeTrailers, resolvedRutubeDeferred.await())
            appendUniqueSources(vkTrailers, resolvedVkDeferred.await())

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

    private fun detectRutubeQuality(streamUrl: String, lowerTitle: String): String {
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)
        return when {
            lowerUrl.contains("3840x2160") || lowerUrl.contains("2160") || lowerTitle.contains("4k") || lowerTitle.contains("2160") -> "4K"
            lowerUrl.contains("2560x1440") || lowerUrl.contains("1440") || lowerTitle.contains("2k") || lowerTitle.contains("1440") -> "2K"
            lowerUrl.contains("1920x1080") || lowerUrl.contains("1080") || lowerTitle.contains("1080") || lowerTitle.contains("fhd") -> "1080p"
            lowerUrl.contains("1280x720") || lowerUrl.contains("720") || lowerTitle.contains("720") || lowerTitle.contains("hd") -> "720p"
            lowerUrl.contains("848x480") || lowerUrl.contains("640x480") || lowerUrl.contains("480") || lowerTitle.contains("480") || lowerTitle.contains("sd") -> "480p"
            else -> "1080p"
        }
    }

    private fun detectVkQuality(bestQuality: String, lowerTitle: String): String {
        if (bestQuality != "Auto (HLS)" && bestQuality.isNotBlank()) {
            return bestQuality
        }
        return when {
            lowerTitle.contains("2160") || lowerTitle.contains("4k") -> "4K"
            lowerTitle.contains("1440") || lowerTitle.contains("2k") -> "2K"
            lowerTitle.contains("1080") || lowerTitle.contains("fhd") -> "1080p"
            lowerTitle.contains("720") || lowerTitle.contains("hd") -> "720p"
            lowerTitle.contains("480") || lowerTitle.contains("sd") -> "480p"
            else -> "1080p"
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
        maxSupportedHeight: Int,
        platform: VideoPlatform
    ): Int {
        var score = 0

        // 1. Точность по году
        if (year > 0 && lowerTitle.contains(year.toString())) score += 25

        // 2. Наличие известной студии дубляжа
        if (voiceover != null) score += 30

        // 3. Автовыбор по разрешению устройства
        // Для ВКонтакте 4K жмётся исключительно в 8-бит H.264 без тономаппинга (тёмная картинка), поэтому не завышаем скор выше 1080p
        val qualityHeight = if (platform == VideoPlatform.VK && quality == "4K") {
            DeviceCapabilityHelper.MAX_SAFE_AVC_HEIGHT
        } else {
            DeviceCapabilityHelper.qualityToHeight(quality)
        }
        if (qualityHeight <= maxSupportedHeight) {
            score += (qualityHeight / 10)
        } else {
            score += ((maxSupportedHeight / 10) - 20)
        }

        return score
    }

    private fun isValidDuration(
        durationSeconds: Int,
        targetSeconds: Int,
        isEpisode: Boolean,
        allowZeroDuration: Boolean
    ): Boolean {
        if (allowZeroDuration && durationSeconds == 0) return true
        if (targetSeconds > 0) {
            val factorMin = if (isEpisode) 0.40 else 0.50
            val factorMax = if (isEpisode) 1.60 else 1.50
            val minSec = (targetSeconds * factorMin).toInt()
            val maxSec = (targetSeconds * factorMax).toInt()
            return durationSeconds in minSec..maxSec
        }
        return if (isEpisode) {
            durationSeconds in (10 * 60 + 1)..(110 * 60)
        } else {
            durationSeconds > 35 * 60
        }
    }

    private fun appendUniqueSources(target: MutableList<VideoSource>, resolved: List<VideoSource>) {
        for (source in resolved) {
            if (target.size >= MAX_SOURCES_PER_PLATFORM) break
            if (target.none { it.streamUrl == source.streamUrl }) {
                target.add(source)
            }
        }
    }

    private fun shouldStopEarly(rutube: List<VideoSource>, vk: List<VideoSource>): Boolean {
        if (rutube.isEmpty() || vk.isEmpty()) return false
        val total = rutube.size + vk.size
        val hasHd = (rutube + vk).any { DeviceCapabilityHelper.qualityToHeight(it.quality) >= 720 }
        return total >= 3 && hasHd
    }

    companion object {
        private const val MAX_SOURCES_PER_PLATFORM = 10
    }
}
