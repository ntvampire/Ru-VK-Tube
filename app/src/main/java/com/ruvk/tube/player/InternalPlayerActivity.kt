package com.ruvk.tube.player

import android.app.Activity
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.db.EpisodeProgressEntity
import com.ruvk.tube.data.db.HistoryEntity
import com.ruvk.tube.data.local.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InternalPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var bufferingBar: ProgressBar
    private lateinit var dimOverlay: View
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: androidx.media3.exoplayer.trackselection.DefaultTrackSelector? = null
    private var hasAppliedSafeConstraints = false
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var isDimmed = false
    private var pauseDimJob: Job? = null
    private var hasReachedReady = false
    private var playbackFailed = false
    private var isSavedStream = false

    private var streamUrl: String = ""
    private var title: String = ""
    private var tmdbId: Long = 0
    private var quality: String = ""
    private var startPositionMs: Long = 0
    private var isTrailer: Boolean = false
    private var posterUrl: String? = null
    private var backdropUrl: String? = null
    private var movieTitle: String = ""
    private var year: Int = 0
    private var rating: Double = 0.0
    private var overview: String? = null
    private var mediaType: String = "MOVIE"
    private var season: Int = 0
    private var episode: Int = 0
    private var country: String? = null

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
        year = intent.getIntExtra(EXTRA_YEAR, 0)
        rating = intent.getDoubleExtra(EXTRA_RATING, 0.0)
        overview = intent.getStringExtra(EXTRA_OVERVIEW)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "MOVIE"
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        country = intent.getStringExtra(EXTRA_COUNTRY)

        playerView = findViewById(R.id.player_view)
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        bufferingBar = findViewById(R.id.player_buffering)
        dimOverlay = findViewById(R.id.player_dim_overlay)

        val titleView = playerView.findViewById<TextView>(R.id.player_title)
        val qualityView = playerView.findViewById<TextView>(R.id.player_quality_badge)
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

        initPlayer()
    }

    private fun resolveRefererForStream(url: String): String {
        val lower = url.lowercase()
        return if (lower.contains("rutube") || lower.contains("rtbcdn")) {
            "https://rutube.ru/"
        } else {
            "https://vk.com/"
        }
    }

    private fun initPlayer() {
        if (streamUrl.isBlank()) {
            finish()
            return
        }

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(com.ruvk.tube.data.network.NetworkClient.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to resolveRefererForStream(streamUrl)))

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        hasAppliedSafeConstraints = false
        hasReachedReady = false
        playbackFailed = false

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector!!)
            .build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            if (startPositionMs > 5_000L && !isTrailer) {
                setMediaItem(mediaItem, startPositionMs)
                Toast.makeText(
                    this@InternalPlayerActivity,
                    "Продолжение просмотра с ${formatTime(startPositionMs)}",
                    Toast.LENGTH_SHORT
                ).show()
                if (tmdbId > 0) {
                    savePlaybackPositionInternal(isInitial = true, syncChannels = false)
                }
            } else {
                setMediaItem(mediaItem)
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

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> bufferingBar.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            hasReachedReady = true
                            bufferingBar.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            bufferingBar.visibility = View.GONE
                            cancelPauseDimTimer()
                            stopProgressTracking()
                            if (isTrailer) finish()
                        }
                        Player.STATE_IDLE -> bufferingBar.visibility = View.GONE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        cancelPauseDimTimer()
                        startProgressTracking()
                    } else {
                        stopProgressTracking()
                        if (hasReachedReady) {
                            savePlaybackPositionInternal(syncChannels = false)
                        }
                        startPauseDimTimer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
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

    private fun cancelSeekHide() {
        seekHideJob?.cancel()
        seekHideJob = null
    }

    private fun scheduleSeekHide(delayMs: Long) {
        cancelSeekHide()
        seekHideJob = lifecycleScope.launch {
            delay(delayMs)
            if (!isFinishing && !isDestroyed) {
                playerView.hideController()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = exoPlayer ?: return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    cancelSeekHide()
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
                    cancelSeekHide()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    if (!player.isPlaying) {
                        startPauseDimTimer()
                    }
                    playerView.showController()
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    // Резервный таймер автоскрытия для пультов без ACTION_UP (HDMI-CEC)
                    scheduleSeekHide(SEEK_FALLBACK_TIMEOUT_MS)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    cancelSeekHide()
                    if (isDimmed) {
                        hideDimOverlay()
                    }
                    if (!player.isPlaying) {
                        startPauseDimTimer()
                    }
                    playerView.showController()
                    val dur = player.duration
                    val target = if (dur > 0) {
                        (player.currentPosition + 10_000).coerceAtMost(dur)
                    } else {
                        player.currentPosition + 10_000
                    }
                    player.seekTo(target)
                    // Резервный таймер автоскрытия для пультов без ACTION_UP (HDMI-CEC)
                    scheduleSeekHide(SEEK_FALLBACK_TIMEOUT_MS)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    cancelSeekHide()
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
        cancelSeekHide()
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(syncChannels = false)
        exoPlayer?.pause()
        super.onPause()
    }

    override fun onStop() {
        cancelSeekHide()
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(syncChannels = true)
        exoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        cancelSeekHide()
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
        syncChannels: Boolean = false
    ) {
        val player = exoPlayer
        if (isTrailer || tmdbId == 0L) return
        // Если поток упал до начала воспроизведения, не затираем сохранённый прогресс в БД
        if (playbackFailed && !hasReachedReady) return

        val rawPosition = if (isInitial) startPositionMs else (player?.currentPosition ?: startPositionMs)
        val duration = player?.duration ?: 0L
        var validPos = if (rawPosition > 0) rawPosition else 0L
        var validDur = if (duration > 0) duration else 0L

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
                        country = country
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
        private const val SEEK_RELEASE_TIMEOUT_MS = 250L
        private const val SEEK_FALLBACK_TIMEOUT_MS = 600L

        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_START_POSITION_MS = "extra_start_pos"
        const val EXTRA_IS_TRAILER = "extra_is_trailer"
        const val EXTRA_IS_SAVED_STREAM = "extra_is_saved_stream"
        const val EXTRA_POSTER_URL = "extra_poster_url"
        const val EXTRA_BACKDROP_URL = "extra_backdrop_url"
        const val EXTRA_MOVIE_TITLE = "extra_movie_title"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_RATING = "extra_rating"
        const val EXTRA_OVERVIEW = "extra_overview"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_SEASON = "extra_season"
        const val EXTRA_EPISODE = "extra_episode"
        const val EXTRA_COUNTRY = "extra_country"

        const val EXTRA_RESULT_ACTION = "extra_result_action"
        const val ACTION_REFRESH_STREAM = "action_refresh_stream"
    }
}
