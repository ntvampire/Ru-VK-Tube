package com.ruvk.tube.player

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
        quality = intent.getStringExtra(EXTRA_QUALITY) ?: "Auto"
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0)
        isTrailer = intent.getBooleanExtra(EXTRA_IS_TRAILER, false)
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
        bufferingBar = findViewById(R.id.player_buffering)
        dimOverlay = findViewById(R.id.player_dim_overlay)

        val titleView = playerView.findViewById<TextView>(R.id.player_title)
        val qualityView = playerView.findViewById<TextView>(R.id.player_quality_badge)
        titleView?.text = if (season > 0 && episode > 0) {
            "$movieTitle (Сезон $season, Серия $episode)"
        } else {
            title
        }
        qualityView?.text = quality

        initPlayer()
    }

    private fun initPlayer() {
        if (streamUrl.isBlank()) {
            finish()
            return
        }

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(com.ruvk.tube.data.network.NetworkClient.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to "https://vk.com/"))

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        hasAppliedSafeConstraints = false

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
            } else {
                setMediaItem(mediaItem)
            }
            prepare()

            // Гарантированно фиксируем фильм в истории сразу при открытии плеера
            if (!isTrailer && tmdbId > 0) {
                savePlaybackPositionInternal(isInitial = true)
            }

            if (startPositionMs == 0L && !isTrailer && tmdbId > 0) {
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
                    if (savedPos > 5_000L) {
                        exoPlayer?.seekTo(savedPos)
                        Toast.makeText(
                            this@InternalPlayerActivity,
                            "Продолжение просмотра с ${formatTime(savedPos)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
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
                        Player.STATE_READY -> bufferingBar.visibility = View.GONE
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
                        savePlaybackPositionInternal()
                        startPauseDimTimer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    bufferingBar.visibility = View.GONE
                    cancelPauseDimTimer()
                    stopProgressTracking()
                }
            })
        }

        exoPlayer?.audioSessionId?.let { sessionId ->
            if (sessionId > 0) setupAudioEffects(sessionId)
        }

        playerView.player = exoPlayer
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
                    savePlaybackPositionInternal()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val player = exoPlayer
            if (player != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
                        if (isDimmed) {
                            hideDimOverlay()
                        }
                        if (!player.isPlaying) {
                            startPauseDimTimer()
                        }
                        playerView.showController()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isDimmed) {
                            hideDimOverlay()
                        }
                        if (!player.isPlaying) {
                            startPauseDimTimer()
                        }
                        playerView.showController()
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isDimmed) {
                            hideDimOverlay()
                        }
                        if (!player.isPlaying) {
                            startPauseDimTimer()
                        }
                        playerView.showController()
                        player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration))
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
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
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal()
        exoPlayer?.pause()
        super.onPause()
    }

    override fun onStop() {
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal()
        exoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        cancelPauseDimTimer()
        stopProgressTracking()
        savePlaybackPositionInternal(isBlocking = true)
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

    private fun savePlaybackPositionInternal(isInitial: Boolean = false, isBlocking: Boolean = false) {
        val player = exoPlayer
        if (isTrailer || tmdbId == 0L) return

        val position = if (isInitial) startPositionMs else (player?.currentPosition ?: startPositionMs)
        val duration = player?.duration ?: 0L
        val validPos = if (position > 0) position else 0L
        val validDur = if (duration > 0) duration else 0L

        val now = System.currentTimeMillis()
        val action: suspend () -> Unit = {
            try {
                if (mediaType == "TV" && season > 0 && episode > 0) {
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
                com.ruvk.tube.channels.TvChannelsManager.syncHistoryChannel(applicationContext)
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
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_START_POSITION_MS = "extra_start_pos"
        const val EXTRA_IS_TRAILER = "extra_is_trailer"
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
    }
}
