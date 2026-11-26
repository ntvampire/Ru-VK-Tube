package com.ruvk.tube.player

import android.app.Activity
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.api.tmdb.TmdbRepository
import com.ruvk.tube.data.db.EpisodeProgressEntity
import com.ruvk.tube.data.db.HistoryEntity
import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.domain.models.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InternalPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var bufferingBar: ProgressBar
    private lateinit var dimOverlay: View
    private lateinit var seekIndicatorText: TextView
    private lateinit var nextEpisodeOverlay: LinearLayout
    private lateinit var nextEpisodeTitleView: TextView
    private lateinit var nextEpisodeCountdownView: TextView
    private lateinit var btnNextEpisodePlay: TextView
    private lateinit var btnNextEpisodeCancel: TextView
    private var titleView: TextView? = null
    private var qualityView: TextView? = null

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var hasAppliedSafeConstraints = false
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var isDimmed = false
    private var pauseDimJob: Job? = null
    private var hasReachedReady = false
    private var hasRenderedFirstFrame = false
    private var hasApplied720pFallback = false
    private var firstFrameWatchdogJob: Job? = null
    private var playbackFailed = false
    private var isSavedStream = false
    private var fallbackUrls: ArrayList<String> = arrayListOf()
    private var fallbackIndex: Int = 0

    private var streamUrl: String = ""
    private var title: String = ""
    private var tmdbId: Long = 0
    private var quality: String = ""
    private var startPositionMs: Long = 0
    private var isTrailer: Boolean = false
    private var posterUrl: String? = null
    private var backdropUrl: String? = null
    private var movieTitle: String = ""
    private var originalTitle: String = ""
    private var year: Int = 0
    private var rating: Double = 0.0
    private var overview: String? = null
    private var mediaType: String = "MOVIE"
    private var season: Int = 0
    private var episode: Int = 0
    private var country: String? = null
    private var ageRating: String? = null
    private var runtimeMinutes: Int = 0
    private var preferredPlatform: String? = null
    private var preferredVoiceover: String? = null

    private var nextEpisodeJob: Job? = null
    private var nextEpisodeSearchDeferred: Deferred<List<VideoSource>>? = null
    private var currentNextEpisodeInfo: TmdbRepository.NextEpisodeInfo? = null
    private var isNextEpisodeOverlayVisible: Boolean = false
    private var isSwitchingNextEpisode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        tmdbId = intent.getLongExtra(EXTRA_TMDB_ID, 0)
        quality = intent.getStringExtra(EXTRA_QUALITY) ?: ""
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0)
        isTrailer = intent.getBooleanExtra(EXTRA_IS_TRAILER, false)
        isSavedStream = intent.getBooleanExtra(EXTRA_IS_SAVED_STREAM, false)
        posterUrl = intent.getStringExtra(EXTRA_POSTER_URL)
        backdropUrl = intent.getStringExtra(EXTRA_BACKDROP_URL)
        movieTitle = intent.getStringExtra(EXTRA_MOVIE_TITLE) ?: title
        originalTitle = intent.getStringExtra(EXTRA_ORIGINAL_TITLE) ?: ""
        year = intent.getIntExtra(EXTRA_YEAR, 0)
        rating = intent.getDoubleExtra(EXTRA_RATING, 0.0)
        overview = intent.getStringExtra(EXTRA_OVERVIEW)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "MOVIE"
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        country = intent.getStringExtra(EXTRA_COUNTRY)
        ageRating = intent.getStringExtra(EXTRA_AGE_RATING)
        runtimeMinutes = intent.getIntExtra(EXTRA_RUNTIME_MINUTES, 0)
        preferredPlatform = intent.getStringExtra(EXTRA_PREFERRED_PLATFORM)
        preferredVoiceover = intent.getStringExtra(EXTRA_PREFERRED_VOICEOVER)
        fallbackUrls = intent.getStringArrayListExtra(EXTRA_FALLBACK_URLS) ?: arrayListOf()
        fallbackIndex = 0

        playerView = findViewById(R.id.player_view)
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        bufferingBar = findViewById(R.id.player_buffering)
        dimOverlay = findViewById(R.id.player_dim_overlay)
        seekIndicatorText = findViewById(R.id.player_seek_indicator)
        nextEpisodeOverlay = findViewById(R.id.next_episode_overlay)
        nextEpisodeTitleView = findViewById(R.id.next_episode_title)
        nextEpisodeCountdownView = findViewById(R.id.next_episode_countdown)
        btnNextEpisodePlay = findViewById(R.id.btn_next_episode_play)
        btnNextEpisodeCancel = findViewById(R.id.btn_next_episode_cancel)

        titleView = playerView.findViewById(R.id.player_title)
        qualityView = playerView.findViewById(R.id.player_quality_badge)
        updateHeaderUi()

        btnNextEpisodePlay.setOnClickListener {
            triggerImmediateNextEpisode()
        }
        btnNextEpisodeCancel.setOnClickListener {
            cancelNextEpisodeFlow()
        }

        initPlayer()
    }

    private fun updateHeaderUi() {
        titleView?.text = if (season > 0 && episode > 0) {
            "$movieTitle (Сезон $season, Серия $episode)"
        } else {
            title
        }
        if (quality.isNotBlank()) {
            qualityView?.visibility = View.VISIBLE
            qualityView?.text = quality
        } else {
            qualityView?.visibility = View.GONE
        }
    }

    private fun resolveRefererForStream(url: String): String {
        val lower = url.lowercase()
        return if (lower.contains("rutube") || lower.contains("rtbcdn")) {
            "https://rutube.ru/"
        } else {
            "https://vk.com/"
        }
    }

    private fun buildMediaSource(url: String): MediaSource {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(com.ruvk.tube.data.network.NetworkClient.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to resolveRefererForStream(url)))
        return DefaultMediaSourceFactory(httpDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun isVkStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("vk.com") || lower.contains("vkuser") || lower.contains("mycdn") || lower.contains("vkvideo")
    }

    private fun configureInitialTrackConstraints(selector: DefaultTrackSelector, url: String) {
        val displayHeight = DeviceCapabilityHelper.getMaxDisplayHeight(this)
        val builder = selector.buildUponParameters()
            .setViewportSizeToPhysicalDisplaySize(this, true)
            .clearVideoSizeConstraints()

        when {
            displayHeight <= 720 -> {
                builder.setMaxVideoSize(1280, 720)
            }
            isTrailer || isVkStream(url) || displayHeight <= 1080 -> {
                // Для трейлеров, потоков VK (AVC) и FullHD-приставок сразу ограничиваем профиль до 1080p ДО prepare(),
                // чтобы не открывать 4K-декодер с последующей поломкой SurfaceView при переключении трека
                builder.setMaxVideoSize(
                    DeviceCapabilityHelper.MAX_SAFE_AVC_WIDTH,
                    DeviceCapabilityHelper.MAX_SAFE_AVC_HEIGHT
                )
            }
        }
        selector.parameters = builder.build()
    }

    private fun initPlayer() {
        if (streamUrl.isBlank()) {
            finish()
            return
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        trackSelector = DefaultTrackSelector(this).also { selector ->
            configureInitialTrackConstraints(selector, streamUrl)
        }
        hasAppliedSafeConstraints = false
        hasReachedReady = false
        hasRenderedFirstFrame = false
        hasApplied720pFallback = false
        playbackFailed = false

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector!!)
            .build().apply {
            val source = buildMediaSource(streamUrl)
            if (startPositionMs > 5_000L && !isTrailer) {
                setMediaSource(source, startPositionMs)
                Toast.makeText(
                    this@InternalPlayerActivity,
                    "Продолжение просмотра с ${formatTime(startPositionMs)}",
                    Toast.LENGTH_SHORT
                ).show()
                if (tmdbId > 0) {
                    savePlaybackPositionInternal(isInitial = true, syncChannels = false)
                }
            } else {
                setMediaSource(source)
                // Сначала читаем сохранённую позицию из БД, чтобы исключить гонку записи 0L поверх прогресса
                if (!isTrailer && tmdbId > 0) {
                    lifecycleScope.launch {
                        val savedPos = withContext(Dispatchers.IO) {
                            if (mediaType == "TV" && season > 0 && episode > 0) {
                                RuVkTubeApp.instance.database.episodeProgressDao()
                                    .getProgress(tmdbId, season, episode)?.positionMs ?: 0L
                            } else {
                                RuVkTubeApp.instance.database.historyDao()
                                    .getByTmdbId(tmdbId)?.positionMs ?: 0L
                            }
                        }
                        if (savedPos > 5_000L && !playbackFailed) {
                            startPositionMs = savedPos
                            exoPlayer?.seekTo(savedPos)
                            Toast.makeText(
                                this@InternalPlayerActivity,
                                "Продолжение просмотра с ${formatTime(savedPos)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (!playbackFailed) {
                            savePlaybackPositionInternal(isInitial = true, syncChannels = false)
                        }
                    }
                }
            }
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    setupAudioEffects(audioSessionId)
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    checkAndApplySafeVideoConstraints(tracks)
                }

                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    cancelFirstFrameWatchdog()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> bufferingBar.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            hasReachedReady = true
                            bufferingBar.visibility = View.GONE
                            if (playWhenReady && !hasRenderedFirstFrame) {
                                startFirstFrameWatchdog()
                            }
                        }
                        Player.STATE_ENDED -> {
                            bufferingBar.visibility = View.GONE
                            cancelFirstFrameWatchdog()
                            cancelPauseDimTimer()
                            stopProgressTracking()
                            commitPendingSeek()
                            if (isTrailer) {
                                finish()
                            } else {
                                savePlaybackPositionInternal(syncChannels = true, forceEndPosition = true)
                                if (mediaType == "TV" && season > 0 && episode > 0 && tmdbId > 0) {
                                    startNextEpisodeFlow()
                                }
                            }
                        }
                        Player.STATE_IDLE -> bufferingBar.visibility = View.GONE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        cancelPauseDimTimer()
                        startProgressTracking()
                        if (hasReachedReady && !hasRenderedFirstFrame) {
                            startFirstFrameWatchdog()
                        }
                    } else {
                        cancelFirstFrameWatchdog()
                        stopProgressTracking()
                        if (hasReachedReady) {
                            savePlaybackPositionInternal(syncChannels = false)
                        }
                        startPauseDimTimer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                    cancelFirstFrameWatchdog()
                    bufferingBar.visibility = View.GONE
                    cancelPauseDimTimer()
                    stopProgressTracking()
                    handlePlaybackError(error)
                }
            })
        }

        exoPlayer?.audioSessionId?.let { sessionId ->
            if (sessionId > 0) setupAudioEffects(sessionId)
        }

        playerView.player = exoPlayer
    }

    private fun startFirstFrameWatchdog() {
        if (hasRenderedFirstFrame || firstFrameWatchdogJob?.isActive == true) return
        firstFrameWatchdogJob = lifecycleScope.launch {
            delay(FIRST_FRAME_WATCHDOG_MS)
            if (isFinishing || isDestroyed || hasRenderedFirstFrame) return@launch
            val player = exoPlayer ?: return@launch
            if (!player.isPlaying) return@launch

            android.util.Log.w("InternalPlayer", "No video frames rendered within ${FIRST_FRAME_WATCHDOG_MS}ms; applying safe fallback")
            if (!hasApplied720pFallback) {
                hasApplied720pFallback = true
                trackSelector?.let { selector ->
                    selector.parameters = selector.buildUponParameters()
                        .setMaxVideoSize(1280, 720)
                        .setMaxVideoFrameRate(30)
                        .build()
                }
                val pos = player.currentPosition.coerceAtLeast(0L)
                hasReachedReady = false
                player.setMediaSource(buildMediaSource(streamUrl), pos)
                player.prepare()
                player.playWhenReady = true
                return@launch
            }

            if (tryNextFallbackUrl()) {
                return@launch
            }

            Toast.makeText(
                this@InternalPlayerActivity,
                "Видеодорожка потока не поддерживается устройством",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun cancelFirstFrameWatchdog() {
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = null
    }

    private fun tryNextFallbackUrl(): Boolean {
        while (fallbackIndex < fallbackUrls.size) {
            val nextUrl = fallbackUrls[fallbackIndex++]
            if (nextUrl.isNotBlank() && nextUrl != streamUrl) {
                streamUrl = nextUrl
                hasReachedReady = false
                hasRenderedFirstFrame = false
                hasApplied720pFallback = false
                hasAppliedSafeConstraints = false
                playbackFailed = false
                trackSelector?.let { configureInitialTrackConstraints(it, nextUrl) }
                exoPlayer?.let { player ->
                    player.setMediaSource(buildMediaSource(nextUrl), 0L)
                    player.prepare()
                    player.playWhenReady = true
                }
                return true
            }
        }
        return false
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (isFinishing || isDestroyed) return
        val httpCause = error.cause as? HttpDataSource.InvalidResponseCodeException
        android.util.Log.e(
            "InternalPlayer",
            "Playback error: ${error.errorCodeName}, httpCode=${httpCause?.responseCode}, isSaved=$isSavedStream, ready=$hasReachedReady",
            error
        )

        if (isSavedStream && !hasReachedReady && !isTrailer) {
            Toast.makeText(
                this,
                "Сохранённая ссылка устарела — открываем поиск источников",
                Toast.LENGTH_LONG
            ).show()
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_RESULT_ACTION, ACTION_REFRESH_STREAM)
                    putExtra(EXTRA_SEASON, season)
                    putExtra(EXTRA_EPISODE, episode)
                }
            )
            finish()
            return
        }

        if (isTrailer && tryNextFallbackUrl()) {
            return
        }

        val reason = when {
            httpCause != null -> "HTTP ${httpCause.responseCode}"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "нет ответа от сервера"
            else -> error.errorCodeName
        }
        Toast.makeText(
            this,
            "Ошибка воспроизведения ($reason). Попробуйте другой источник.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun checkAndApplySafeVideoConstraints(tracks: androidx.media3.common.Tracks) {
        if (hasAppliedSafeConstraints) return
        val selector = trackSelector ?: return

        for (group in tracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val mime = format.sampleMimeType ?: ""
                    val isAvc = mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true)
                    // H.264 (AVC) на ТВ-чипах сертифицирован только до 1080p60.
                    // 4K AVC приводит к переполнению DPB и мертвому зависанию VPU ядра ТВ.
                    if (isAvc && (format.height > DeviceCapabilityHelper.MAX_SAFE_AVC_HEIGHT || format.width > DeviceCapabilityHelper.MAX_SAFE_AVC_WIDTH)) {
                        android.util.Log.w("InternalPlayer", "Limiting AVC (H.264) track to 1080p to prevent TV VPU kernel deadlock (${format.width}x${format.height})")
                        hasAppliedSafeConstraints = true
                        selector.parameters = selector.parameters.buildUpon()
                            .setMaxVideoSize(DeviceCapabilityHelper.MAX_SAFE_AVC_WIDTH, DeviceCapabilityHelper.MAX_SAFE_AVC_HEIGHT)
                            .build()
                        return
                    }
                }
            }
        }
    }

    private var progressTrackingJob: Job? = null

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                if (exoPlayer?.isPlaying == true) {
                    savePlaybackPositionInternal(syncChannels = false)
                }
            }
        }
    }

    private fun stopProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = null
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private var seekHideJob: Job? = null
    private var seekCommitJob: Job? = null
    private var pendingSeekTargetMs: Long = -1L
    private var seekStartPosMs: Long = -1L

    private fun cancelSeekHide() {
        seekHideJob?.cancel()
        seekHideJob = null
    }

    private fun commitPendingSeek() {
        seekCommitJob?.cancel()
        seekCommitJob = null
        val target = pendingSeekTargetMs
        pendingSeekTargetMs = -1L
        seekStartPosMs = -1L
        seekIndicatorText.visibility = View.GONE
        if (target >= 0L) {
            exoPlayer?.seekTo(target)
        }
    }

    private fun handleSeekStep(deltaMs: Long) {
        val player = exoPlayer ?: return
        cancelSeekHide()
        if (isDimmed) {
            hideDimOverlay()
        }
        if (!player.isPlaying) {
            startPauseDimTimer()
        }

        if (pendingSeekTargetMs < 0L) {
            seekStartPosMs = player.currentPosition.coerceAtLeast(0L)
            pendingSeekTargetMs = seekStartPosMs
        }
        val dur = player.duration
        val next = pendingSeekTargetMs + deltaMs
        pendingSeekTargetMs = if (dur > 0L) next.coerceIn(0L, dur) else next.coerceAtLeast(0L)

        val diffSec = (pendingSeekTargetMs - seekStartPosMs) / 1000L
        seekIndicatorText.text = if (diffSec >= 0L) "+$diffSec сек" else "$diffSec сек"
        seekIndicatorText.visibility = View.VISIBLE

        playerView.showController()

        seekCommitJob?.cancel()
        seekCommitJob = lifecycleScope.launch {
            delay(SEEK_COALESCE_TIMEOUT_MS)
            if (!isFinishing && !isDestroyed) {
                commitPendingSeek()
            }
        }

        // Резервный таймер автоскрытия для пультов без ACTION_UP (HDMI-CEC)
        scheduleSeekHide(SEEK_FALLBACK_TIMEOUT_MS)
    }

    private fun scheduleSeekHide(delayMs: Long) {
        cancelSeekHide()
        seekHideJob = lifecycleScope.launch {
            delay(delayMs)
            if (!isFinishing && !isDestroyed) {
                commitPendingSeek()
                playerView.hideController()
            }
        }
    }

    private fun startNextEpisodeFlow() {
        if (isNextEpisodeOverlayVisible || isSwitchingNextEpisode || isFinishing || isDestroyed) return
        nextEpisodeJob?.cancel()
        nextEpisodeJob = lifecycleScope.launch {
            val nextInfo = withContext(Dispatchers.IO) {
                RuVkTubeApp.instance.tmdbRepository.getNextEpisode(tmdbId, season, episode)
            } ?: return@launch

            if (isFinishing || isDestroyed) return@launch
            currentNextEpisodeInfo = nextInfo
            isNextEpisodeOverlayVisible = true
            playerView.hideController()

            nextEpisodeTitleView.text = "S${nextInfo.seasonNumber}E${nextInfo.episode.episodeNumber} — ${nextInfo.episode.name}"
            nextEpisodeCountdownView.text = "Автозапуск через 10 сек..."
            nextEpisodeOverlay.visibility = View.VISIBLE
            btnNextEpisodePlay.isEnabled = true
            btnNextEpisodePlay.requestFocus()

            val runtimeMin = if (nextInfo.episode.durationMinutes > 0) {
                nextInfo.episode.durationMinutes
            } else {
                nextInfo.runtimeFallbackMinutes
            }
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@InternalPlayerActivity)
            nextEpisodeSearchDeferred = async(Dispatchers.IO) {
                RuVkTubeApp.instance.searchCoordinator.findEpisodeSources(
                    title = movieTitle,
                    seasonNumber = nextInfo.seasonNumber,
                    episodeNumber = nextInfo.episode.episodeNumber,
                    runtimeMinutes = runtimeMin,
                    originalTitle = originalTitle.ifBlank { null },
                    maxSupportedHeight = maxRes
                )
            }

            for (sec in 10 downTo 1) {
                if (!isNextEpisodeOverlayVisible || isFinishing || isDestroyed) return@launch
                nextEpisodeCountdownView.text = "Автозапуск через $sec сек..."
                delay(1000L)
            }

            if (isNextEpisodeOverlayVisible && !isFinishing && !isDestroyed) {
                launchResolvedNextEpisode(nextInfo)
            }
        }
    }

    private fun triggerImmediateNextEpisode() {
        val nextInfo = currentNextEpisodeInfo ?: return
        if (isSwitchingNextEpisode) return
        nextEpisodeJob?.cancel()
        nextEpisodeJob = lifecycleScope.launch {
            launchResolvedNextEpisode(nextInfo)
        }
    }

    private suspend fun launchResolvedNextEpisode(nextInfo: TmdbRepository.NextEpisodeInfo) {
        if (isSwitchingNextEpisode || isFinishing || isDestroyed) return
        isSwitchingNextEpisode = true
        btnNextEpisodePlay.isEnabled = false
        nextEpisodeCountdownView.text = "Подбор потока..."

        val sources = try {
            nextEpisodeSearchDeferred?.await() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (isFinishing || isDestroyed) {
            isSwitchingNextEpisode = false
            return
        }

        val best = pickBestSourceForNextEpisode(sources)
        if (best != null) {
            switchToNextEpisode(nextInfo, best)
        } else {
            hideNextEpisodeOverlay()
            Toast.makeText(
                this,
                "Источники для S${nextInfo.seasonNumber}E${nextInfo.episode.episodeNumber} не найдены",
                Toast.LENGTH_LONG
            ).show()
        }
        isSwitchingNextEpisode = false
    }

    private fun pickBestSourceForNextEpisode(sources: List<VideoSource>): VideoSource? {
        if (sources.isEmpty()) return null
        val voice = preferredVoiceover
        val plat = preferredPlatform
        return sources.firstOrNull { !voice.isNullOrBlank() && it.voiceover.equals(voice, ignoreCase = true) }
            ?: sources.firstOrNull { !plat.isNullOrBlank() && it.platform.name.equals(plat, ignoreCase = true) }
            ?: sources.first()
    }

    private fun switchToNextEpisode(nextInfo: TmdbRepository.NextEpisodeInfo, source: VideoSource) {
        hideNextEpisodeOverlay()

        streamUrl = source.streamUrl
        season = nextInfo.seasonNumber
        episode = nextInfo.episode.episodeNumber
        quality = source.quality
        preferredPlatform = source.platform.name
        preferredVoiceover = source.voiceover
        title = "$movieTitle (S${season}E${episode} - $quality)"
        startPositionMs = 0L
        isSavedStream = false
        hasReachedReady = false
        hasRenderedFirstFrame = false
        hasApplied720pFallback = false
        playbackFailed = false
        hasAppliedSafeConstraints = false
        trackSelector?.let { configureInitialTrackConstraints(it, streamUrl) }

        updateHeaderUi()

        exoPlayer?.let { player ->
            player.setMediaSource(buildMediaSource(streamUrl), 0L)
            player.prepare()
            player.playWhenReady = true
        }

        if (tmdbId > 0) {
            savePlaybackPositionInternal(isInitial = true, syncChannels = true)
        }
    }

    private fun cancelNextEpisodeFlow() {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = null
        nextEpisodeSearchDeferred?.cancel()
        nextEpisodeSearchDeferred = null
        hideNextEpisodeOverlay()
    }

    private fun hideNextEpisodeOverlay() {
        isNextEpisodeOverlayVisible = false
        currentNextEpisodeInfo = null
        nextEpisodeOverlay.visibility = View.GONE
        btnNextEpisodePlay.isEnabled = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = exoPlayer ?: return super.dispatchKeyEvent(event)

        if (isNextEpisodeOverlayVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                cancelNextEpisodeFlow()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    cancelSeekHide()
                    commitPendingSeek()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    if (player.isPlaying) {
                        player.pause()
                        playerView.showController()
                        startPauseDimTimer()
                    } else {
                        cancelPauseDimTimer()
                        player.play()
                        playerView.hideController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    cancelSeekHide()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    if (!player.isPlaying) {
                        startPauseDimTimer()
                    }
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    handleSeekStep(-10_000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    handleSeekStep(10_000L)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    cancelSeekHide()
                    commitPendingSeek()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    if (player.isPlaying) {
                        player.pause()
                        playerView.showController()
                        startPauseDimTimer()
                    } else {
                        cancelPauseDimTimer()
                        player.play()
                        playerView.hideController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    cancelSeekHide()
                    commitPendingSeek()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    cancelPauseDimTimer()
                    player.play()
                    playerView.hideController()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    cancelSeekHide()
                    commitPendingSeek()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    player.pause()
                    playerView.showController()
                    startPauseDimTimer()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    cancelSeekHide()
                    commitPendingSeek()
                    if (isDimmed) {
                        hideDimOverlay()
                        if (!player.isPlaying) {
                            startPauseDimTimer()
                        }
                        return true
                    }
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    seekCommitJob?.cancel()
                    seekCommitJob = lifecycleScope.launch {
                        delay(150L)
                        if (!isFinishing && !isDestroyed) {
                            commitPendingSeek()
                        }
                    }
                    // Моментальное скрытие интерфейса сразу по отпусканию кнопки перемотки
                    scheduleSeekHide(SEEK_RELEASE_TIMEOUT_MS)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startPauseDimTimer() {
        pauseDimJob?.cancel()
        pauseDimJob = lifecycleScope.launch {
            delay(30_000L)
            showDimOverlay()
        }
    }

    private fun cancelPauseDimTimer() {
        pauseDimJob?.cancel()
        pauseDimJob = null
        if (isDimmed) {
            hideDimOverlay()
        }
    }

    private fun showDimOverlay() {
        if (isFinishing || isDestroyed) return
        isDimmed = true
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.animate().alpha(1f).setDuration(800).start()
    }

    private fun hideDimOverlay() {
        isDimmed = false
        dimOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            if (!isDimmed) {
                dimOverlay.visibility = View.GONE
            }
        }.start()
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        releaseAudioEffects()
        if (!AppPreferences.audioCompressionEnabled) return
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(300) // +3.0 dB
                enabled = true
            }
        } catch (e: Exception) {
            android.util.Log.w("InternalPlayer", "LoudnessEnhancer initialization skipped", e)
        }
    }

    private fun releaseAudioEffects() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            // ignore
        }
        loudnessEnhancer = null
    }

    override fun onPause() {
        cancelFirstFrameWatchdog()
        cancelSeekHide()
        commitPendingSeek()
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(syncChannels = false)
        exoPlayer?.pause()
        super.onPause()
    }

    override fun onStop() {
        cancelFirstFrameWatchdog()
        cancelSeekHide()
        commitPendingSeek()
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(syncChannels = true)
        exoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        cancelFirstFrameWatchdog()
        cancelSeekHide()
        commitPendingSeek()
        cancelNextEpisodeFlow()
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(isBlocking = true, syncChannels = false)
        releaseAudioEffects()
        // Отвязываем SurfaceView от ExoPlayer ДО вызова stop/release и super.onDestroy,
        // чтобы нативный дисплейный слой корректно освободил буферы и не вызвал дедлок ядра VPU ТВ
        playerView.player = null
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        super.onDestroy()
    }

    private fun savePlaybackPositionInternal(
        isInitial: Boolean = false,
        isBlocking: Boolean = false,
        syncChannels: Boolean = false,
        forceEndPosition: Boolean = false
    ) {
        val player = exoPlayer
        if (isTrailer || tmdbId == 0L) return
        // Если поток упал до начала воспроизведения, не затираем сохранённый прогресс в БД
        if (playbackFailed && !hasReachedReady) return

        val duration = player?.duration ?: 0L
        var validDur = if (duration > 0) duration else 0L
        val rawPosition = when {
            forceEndPosition && validDur > 0L -> validDur
            isInitial -> startPositionMs
            else -> player?.currentPosition ?: startPositionMs
        }
        var validPos = if (rawPosition > 0) rawPosition else 0L

        val now = System.currentTimeMillis()
        val action: suspend () -> Unit = {
            try {
                if (mediaType == "TV" && season > 0 && episode > 0) {
                    if (isInitial && validPos == 0L) {
                        val existingEp = RuVkTubeApp.instance.database.episodeProgressDao()
                            .getProgress(tmdbId, season, episode)
                        if (existingEp != null && existingEp.positionMs > 0L) {
                            validPos = existingEp.positionMs
                            if (validDur == 0L) validDur = existingEp.durationMs
                        }
                    }
                    RuVkTubeApp.instance.database.episodeProgressDao().saveProgress(
                        EpisodeProgressEntity(
                            tmdbId = tmdbId,
                            season = season,
                            episode = episode,
                            positionMs = validPos,
                            durationMs = validDur,
                            lastStreamUrl = streamUrl,
                            updatedAt = now
                        )
                    )
                }

                if (isInitial && validPos == 0L) {
                    val existingHist = RuVkTubeApp.instance.database.historyDao().getByTmdbId(tmdbId)
                    if (existingHist != null && existingHist.positionMs > 0L &&
                        (mediaType != "TV" || (existingHist.season == season && existingHist.episode == episode))
                    ) {
                        validPos = existingHist.positionMs
                        if (validDur == 0L) validDur = existingHist.durationMs
                    }
                }

                RuVkTubeApp.instance.database.historyDao().insertOrUpdate(
                    HistoryEntity(
                        tmdbId = tmdbId,
                        title = if (movieTitle.isNotBlank()) movieTitle else title,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        mediaType = mediaType,
                        positionMs = validPos,
                        durationMs = validDur,
                        year = year,
                        rating = rating,
                        overview = overview,
                        season = season,
                        episode = episode,
                        lastStreamUrl = streamUrl,
                        updatedAt = now,
                        country = country,
                        ageRating = ageRating,
                        runtimeMinutes = if (runtimeMinutes > 0) runtimeMinutes else (validDur / 60_000L).toInt()
                    )
                )
                // Храним только последние 30 записей истории
                RuVkTubeApp.instance.database.historyDao().trimToLast30()
                if (syncChannels) {
                    com.ruvk.tube.channels.TvChannelsManager.syncHistoryChannel(applicationContext)
                }
            } catch (e: Exception) {
                android.util.Log.e("InternalPlayer", "Failed to save playback position", e)
            }
        }

        if (isBlocking) {
            try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(600L) {
                        action()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("InternalPlayer", "Blocking save failed", e)
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                action()
            }
        }
    }

    companion object {
        private const val CONTROLLER_TIMEOUT_MS = 2000
        private const val SEEK_COALESCE_TIMEOUT_MS = 280L
        private const val SEEK_RELEASE_TIMEOUT_MS = 250L
        private const val SEEK_FALLBACK_TIMEOUT_MS = 600L
        private const val FIRST_FRAME_WATCHDOG_MS = 3000L

        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_START_POSITION_MS = "extra_start_pos"
        const val EXTRA_IS_TRAILER = "extra_is_trailer"
        const val EXTRA_FALLBACK_URLS = "extra_fallback_urls"
        const val EXTRA_IS_SAVED_STREAM = "extra_is_saved_stream"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_BACKDROP_URL = "extra_backdrop_url"
        const val EXTRA_MOVIE_TITLE = "extra_movie_title"
        const val EXTRA_ORIGINAL_TITLE = "extra_original_title"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_RATING = "extra_rating"
        const val EXTRA_OVERVIEW = "extra_overview"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_SEASON = "extra_season"
        const val EXTRA_EPISODE = "extra_episode"
        const val EXTRA_COUNTRY = "extra_country"
        const val EXTRA_AGE_RATING = "extra_age_rating"
        const val EXTRA_RUNTIME_MINUTES = "extra_runtime_minutes"
        const val EXTRA_PREFERRED_PLATFORM = "extra_preferred_platform"
        const val EXTRA_PREFERRED_VOICEOVER = "extra_preferred_voiceover"

        const val EXTRA_RESULT_ACTION = "extra_result_action"
        const val ACTION_REFRESH_STREAM = "action_refresh_stream"
    }
}
