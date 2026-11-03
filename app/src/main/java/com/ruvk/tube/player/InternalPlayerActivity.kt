package com.ruvk.tube.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.db.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InternalPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var bufferingBar: ProgressBar
    private var exoPlayer: ExoPlayer? = null

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

        playerView = findViewById(R.id.player_view)
        bufferingBar = findViewById(R.id.player_buffering)

        val titleView = playerView.findViewById<TextView>(R.id.player_title)
        val qualityView = playerView.findViewById<TextView>(R.id.player_quality_badge)
        titleView?.text = title
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

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            setMediaItem(mediaItem)
            prepare()
            if (startPositionMs > 0 && !isTrailer) {
                seekTo(startPositionMs)
            } else if (!isTrailer && tmdbId > 0) {
                lifecycleScope.launch {
                    val savedPos = withContext(Dispatchers.IO) {
                        RuVkTubeApp.instance.database.historyDao().getByTmdbId(tmdbId)?.positionMs ?: 0L
                    }
                    if (savedPos > 5_000L) {
                        exoPlayer?.seekTo(savedPos)
                    }
                }
            }
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> bufferingBar.visibility = View.VISIBLE
                        Player.STATE_READY -> bufferingBar.visibility = View.GONE
                        Player.STATE_ENDED -> {
                            bufferingBar.visibility = View.GONE
                            if (isTrailer) finish()
                        }
                        Player.STATE_IDLE -> bufferingBar.visibility = View.GONE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) {
                        savePlaybackPosition()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    bufferingBar.visibility = View.GONE
                }
            })
        }

        playerView.player = exoPlayer
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val player = exoPlayer ?: return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                playerView.showController()
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                playerView.showController()
                player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration))
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (player.isPlaying) {
                    player.pause()
                    playerView.showController()
                } else {
                    player.play()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackPosition()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun savePlaybackPosition() {
        val player = exoPlayer ?: return
        if (isTrailer || tmdbId == 0L) return

        val position = player.currentPosition
        val duration = player.duration
        val validPos = if (position > 0) position else 0L
        val validDur = if (duration > 0) duration else 0L

        // Сохраняем в историю если воспроизведение длилось от 3 секунд
        if (validPos > 3_000L || validDur > 60_000L) {
            lifecycleScope.launch(Dispatchers.IO) {
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
                        lastStreamUrl = streamUrl
                    )
                )
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
    }
}
