package com.ruvk.tube.ui.details

import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.db.FavoriteEntity
import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.domain.models.CastMember
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.domain.models.TvEpisode
import com.ruvk.tube.domain.models.TvSeason
import com.ruvk.tube.domain.models.VideoSource
import com.ruvk.tube.player.DeviceCapabilityHelper
import com.ruvk.tube.player.ExternalPlayerLauncher
import com.ruvk.tube.player.InternalPlayerActivity
import com.ruvk.tube.ui.main.NumPosterAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailsActivity : FragmentActivity() {

    private lateinit var heroBackdrop: ImageView
    private lateinit var posterImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var originalTitleText: TextView
    private lateinit var badgeAge: TextView
    private lateinit var badgeRating: TextView
    private lateinit var badgeYear: TextView
    private lateinit var badgeRuntime: TextView
    private lateinit var overviewText: TextView

    private lateinit var btnWatch: TextView
    private lateinit var btnSources: TextView
    private lateinit var btnTrailer: TextView
    private lateinit var btnFavorite: TextView

    private lateinit var sourcesSection: LinearLayout
    private lateinit var sourcesHeader: TextView
    private lateinit var sourcesFrame: View
    private lateinit var sourcesGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var sourcesProgressLayout: LinearLayout
    private lateinit var sourcesEmptyText: TextView

    private lateinit var actorsSection: LinearLayout
    private lateinit var actorsGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var castAdapter: NumCastAdapter

    private lateinit var filmographySection: LinearLayout
    private lateinit var filmographyHeader: TextView
    private lateinit var filmographyProgress: ProgressBar
    private lateinit var filmographyGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var filmographyAdapter: NumPosterAdapter
    private var filmographyJob: Job? = null

    private lateinit var seriesSection: LinearLayout
    private lateinit var seasonsGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var episodesGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var episodesProgress: ProgressBar
    private lateinit var seasonAdapter: NumSeasonAdapter
    private lateinit var episodeAdapter: NumEpisodeAdapter
    private var loadEpisodesJob: Job? = null
    private var currentSeasonNumber: Int = 1
    private var currentEpisodeNumber: Int = 1
    private var currentEpisodesList = listOf<TvEpisode>()
    private var savedSeason: Int = 0
    private var savedEpisode: Int = 0

    private lateinit var sourceAdapter: NumSourceAdapter

    private var mediaItem: MediaItem? = null
    private var isFavorite = false
    private var savedPositionMs: Long = 0L
    private var savedStreamUrl: String? = null
    private var isSearchingSources = false
    private var sourcesList = listOf<VideoSource>()
    private var searchSourcesJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        @Suppress("DEPRECATION")
        val item = intent.getSerializableExtra(EXTRA_MEDIA_ITEM) as? MediaItem
        if (item == null) {
            finish()
            return
        }
        mediaItem = item

        if (!item.lastStreamUrl.isNullOrBlank() && item.watchPositionMs > 5_000L) {
            savedStreamUrl = item.lastStreamUrl
            savedPositionMs = item.watchPositionMs
            savedSeason = item.lastSeason
            savedEpisode = item.lastEpisode
        }
        if (item.lastSeason > 0) {
            currentSeasonNumber = item.lastSeason
        }
        if (item.lastEpisode > 0) {
            currentEpisodeNumber = item.lastEpisode
        }

        initViews()
        setupListeners()
        bindMediaItem(item)
        checkFavoriteStatus(item.id)
        checkHistoryPosition(item.id)
    }

    override fun onResume() {
        super.onResume()
        mediaItem?.let { checkHistoryPosition(it.id) }
    }

    private fun initViews() {
        heroBackdrop = findViewById(R.id.details_hero_backdrop)
        posterImage = findViewById(R.id.details_poster)

        val posterCard = findViewById<View>(R.id.poster_card)
        val radiusPx = resources.displayMetrics.density * 10f
        val roundOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        posterCard?.outlineProvider = roundOutline
        posterCard?.clipToOutline = true
        posterImage.outlineProvider = roundOutline
        posterImage.clipToOutline = true

        titleText = findViewById(R.id.details_title)
        originalTitleText = findViewById(R.id.details_original_title)
        badgeAge = findViewById(R.id.badge_age)
        badgeRating = findViewById(R.id.badge_rating)
        badgeYear = findViewById(R.id.badge_year)
        badgeRuntime = findViewById(R.id.badge_runtime)
        overviewText = findViewById(R.id.details_overview)

        btnWatch = findViewById(R.id.btn_watch)
        btnSources = findViewById(R.id.btn_sources)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnFavorite = findViewById(R.id.btn_favorite)

        sourcesSection = findViewById(R.id.sources_section)
        sourcesHeader = findViewById(R.id.sources_header)
        sourcesFrame = findViewById(R.id.sources_frame)
        sourcesGridView = findViewById(R.id.sources_grid_view)
        sourcesProgressLayout = findViewById(R.id.sources_progress_layout)
        sourcesEmptyText = findViewById(R.id.sources_empty_text)

        actorsSection = findViewById(R.id.actors_section)
        actorsGridView = findViewById(R.id.actors_grid_view)

        filmographySection = findViewById(R.id.filmography_section)
        filmographyHeader = findViewById(R.id.filmography_header)
        filmographyProgress = findViewById(R.id.filmography_progress)
        filmographyGridView = findViewById(R.id.filmography_grid_view)

        seriesSection = findViewById(R.id.series_section)
        seasonsGridView = findViewById(R.id.seasons_grid_view)
        episodesGridView = findViewById(R.id.episodes_grid_view)
        episodesProgress = findViewById(R.id.episodes_progress)

        // По умолчанию полки скрыты
        sourcesSection.visibility = View.GONE
        actorsSection.visibility = View.GONE
        filmographySection.visibility = View.GONE
        seriesSection.visibility = View.GONE

        // Настройка полки сезонов (1 ряд)
        seasonsGridView.setNumRows(1)
        seasonAdapter = NumSeasonAdapter { season ->
            loadEpisodes(season.seasonNumber)
        }
        seasonsGridView.adapter = seasonAdapter

        // Настройка полки серий (1 ряд)
        episodesGridView.setNumRows(1)
        episodeAdapter = NumEpisodeAdapter(defaultBackdropUrl = mediaItem?.backdropUrl) { episode ->
            searchEpisodeSourcesAndFocus(episode)
        }
        episodesGridView.adapter = episodeAdapter

        // Настройка полки источников (1 ряд)
        sourcesGridView.setNumRows(1)
        sourceAdapter = NumSourceAdapter { source ->
            startPlayback(source)
        }
        sourcesGridView.adapter = sourceAdapter

        // Настройка полки актеров (1 ряд)
        actorsGridView.setNumRows(1)
        castAdapter = NumCastAdapter { actor ->
            loadFilmography(actor)
        }
        actorsGridView.adapter = castAdapter

        // Настройка полки фильмографии (1 ряд)
        filmographyGridView.setNumRows(1)
        filmographyAdapter = NumPosterAdapter(
            onItemFocused = {},
            onItemClicked = { movie ->
                val intent = Intent(this, DetailsActivity::class.java).apply {
                    putExtra(EXTRA_MEDIA_ITEM, movie)
                }
                startActivity(intent)
            }
        )
        filmographyGridView.adapter = filmographyAdapter

        if (!savedStreamUrl.isNullOrBlank() && savedPositionMs > 5_000L) {
            btnWatch.text = if (mediaItem?.mediaType == MediaType.TV && savedSeason > 0 && savedEpisode > 0) {
                "(S${savedSeason}E${savedEpisode} ${formatTime(savedPositionMs)})"
            } else {
                "(${formatTime(savedPositionMs)})"
            }
            btnSources.visibility = View.VISIBLE
        } else {
            btnWatch.text = ""
            btnSources.visibility = View.GONE
        }
        btnSources.text = ""
        btnTrailer.text = ""
        btnFavorite.text = ""

        updateFocusNavigation()

        // Фокус по умолчанию на кнопке "Смотреть" / "Продолжить"
        btnWatch.post {
            btnWatch.requestFocus()
        }
    }

    private fun updateFocusNavigation() {
        val hasSourcesBtn = btnSources.visibility == View.VISIBLE
        val isSeriesShown = seriesSection.visibility == View.VISIBLE
        val isSourcesShown = sourcesSection.visibility == View.VISIBLE
        val isActorsShown = actorsSection.visibility == View.VISIBLE
        val isFilmographyShown = filmographySection.visibility == View.VISIBLE

        val firstBelowId = when {
            isSeriesShown -> R.id.seasons_grid_view
            isSourcesShown -> R.id.sources_grid_view
            isActorsShown -> R.id.actors_grid_view
            isFilmographyShown -> R.id.filmography_grid_view
            else -> View.NO_ID
        }

        btnWatch.nextFocusRightId = if (hasSourcesBtn) R.id.btn_sources else R.id.btn_trailer
        btnWatch.nextFocusDownId = firstBelowId

        btnSources.nextFocusLeftId = R.id.btn_watch
        btnSources.nextFocusRightId = R.id.btn_trailer
        btnSources.nextFocusDownId = firstBelowId

        btnTrailer.nextFocusLeftId = if (hasSourcesBtn) R.id.btn_sources else R.id.btn_watch
        btnTrailer.nextFocusRightId = R.id.btn_favorite
        btnTrailer.nextFocusDownId = firstBelowId

        btnFavorite.nextFocusLeftId = R.id.btn_trailer
        btnFavorite.nextFocusDownId = firstBelowId

        // Навигация внутри блока сезонов и серий
        seasonsGridView.nextFocusUpId = R.id.btn_watch
        seasonsGridView.nextFocusDownId = R.id.episodes_grid_view

        val belowEpisodesId = when {
            isSourcesShown -> R.id.sources_grid_view
            isActorsShown -> R.id.actors_grid_view
            isFilmographyShown -> R.id.filmography_grid_view
            else -> View.NO_ID
        }
        episodesGridView.nextFocusUpId = R.id.seasons_grid_view
        episodesGridView.nextFocusDownId = belowEpisodesId

        sourcesGridView.nextFocusUpId = if (isSeriesShown) R.id.episodes_grid_view else R.id.btn_watch
        sourcesGridView.nextFocusDownId = when {
            isActorsShown -> R.id.actors_grid_view
            isFilmographyShown -> R.id.filmography_grid_view
            else -> View.NO_ID
        }

        actorsGridView.nextFocusUpId = when {
            isSourcesShown -> R.id.sources_grid_view
            isSeriesShown -> R.id.episodes_grid_view
            else -> R.id.btn_watch
        }
        actorsGridView.nextFocusDownId = if (isFilmographyShown) R.id.filmography_grid_view else View.NO_ID

        filmographyGridView.nextFocusUpId = when {
            isActorsShown -> R.id.actors_grid_view
            isSourcesShown -> R.id.sources_grid_view
            isSeriesShown -> R.id.episodes_grid_view
            else -> R.id.btn_watch
        }
    }

    private fun setupListeners() {
        btnWatch.setOnClickListener {
            val item = mediaItem ?: return@setOnClickListener
            val stream = savedStreamUrl
            if (!stream.isNullOrBlank() && savedPositionMs > 5_000L) {
                // Автоматический запуск сохраненного потока на том же месте просмотра
                playSavedStream(stream, item, savedPositionMs)
                return@setOnClickListener
            }

            // Для сериалов: если серии загружены, переводим фокус на выбор серий
            if (item.mediaType == MediaType.TV && seriesSection.visibility == View.VISIBLE) {
                if (currentEpisodesList.isNotEmpty()) {
                    episodesGridView.post {
                        episodesGridView.setSelectedPosition(0)
                        episodesGridView.requestFocus()
                    }
                } else {
                    seasonsGridView.post {
                        seasonsGridView.requestFocus()
                    }
                }
                return@setOnClickListener
            }

            // Стандартное поведение для фильмов:
            if (sourcesList.isNotEmpty()) {
                sourcesSection.visibility = View.VISIBLE
                sourcesGridView.visibility = View.VISIBLE
                sourcesHeader.visibility = View.VISIBLE
                sourcesFrame.visibility = View.VISIBLE
                updateFocusNavigation()
                sourcesGridView.post {
                    sourcesGridView.setSelectedPosition(0)
                    sourcesGridView.requestFocus()
                }
            } else if (isSearchingSources) {
                Toast.makeText(this, "Идет поиск видео...", Toast.LENGTH_SHORT).show()
            } else {
                searchSourcesAndFocus(item)
            }
        }

        btnSources.setOnClickListener {
            val item = mediaItem ?: return@setOnClickListener
            if (sourcesList.isNotEmpty()) {
                sourcesSection.visibility = View.VISIBLE
                sourcesGridView.visibility = View.VISIBLE
                sourcesHeader.visibility = View.VISIBLE
                sourcesFrame.visibility = View.VISIBLE
                updateFocusNavigation()
                sourcesGridView.post {
                    sourcesGridView.setSelectedPosition(0)
                    sourcesGridView.requestFocus()
                }
            } else if (isSearchingSources) {
                Toast.makeText(this, "Идет поиск видео...", Toast.LENGTH_SHORT).show()
            } else if (item.mediaType == MediaType.TV && currentEpisodesList.isNotEmpty()) {
                val ep = currentEpisodesList.firstOrNull { it.episodeNumber == currentEpisodeNumber } ?: currentEpisodesList.first()
                searchEpisodeSourcesAndFocus(ep)
            } else {
                searchSourcesAndFocus(item)
            }
        }

        btnTrailer.setOnClickListener {
            val item = mediaItem ?: return@setOnClickListener
            playTrailer(item)
        }

        btnFavorite.setOnClickListener {
            val item = mediaItem ?: return@setOnClickListener
            toggleFavorite(item)
        }
    }

    private fun bindMediaItem(item: MediaItem) {
        val backdropUrl = item.backdropUrl ?: item.posterUrl
        heroBackdrop.load(backdropUrl) {
            crossfade(300)
            placeholder(R.color.background_dark)
            error(R.color.background_dark)
        }

        val radiusPx = resources.displayMetrics.density * 10f
        posterImage.load(item.posterUrl) {
            crossfade(300)
            placeholder(R.color.surface_card)
            error(R.color.surface_card)
            transformations(RoundedCornersTransformation(radiusPx))
        }

        titleText.text = item.title

        if (item.originalTitle.isNotBlank() && item.originalTitle != item.title) {
            originalTitleText.visibility = View.VISIBLE
            originalTitleText.text = item.originalTitle
        } else {
            originalTitleText.visibility = View.GONE
        }

        if (!item.ageRating.isNullOrBlank()) {
            badgeAge.visibility = View.VISIBLE
            badgeAge.text = item.ageRating
        } else {
            badgeAge.visibility = View.GONE
        }

        if (item.voteAverage > 0) {
            badgeRating.visibility = View.VISIBLE
            badgeRating.text = "★ ${String.format("%.1f", item.voteAverage)}"
        } else {
            badgeRating.visibility = View.GONE
        }

        if (item.year > 0) {
            badgeYear.visibility = View.VISIBLE
            badgeYear.text = item.year.toString()
        } else {
            badgeYear.visibility = View.GONE
        }

        if (item.runtimeMinutes > 0) {
            badgeRuntime.visibility = View.VISIBLE
            badgeRuntime.text = "${item.runtimeMinutes} мин"
        } else {
            badgeRuntime.visibility = View.GONE
        }

        overviewText.text = if (item.overview.isNotBlank()) item.overview else "Описание сюжета отсутствует"

        // Сериалы: показ блока сезонов и серий
        if (item.mediaType == MediaType.TV) {
            seriesSection.visibility = View.VISIBLE
            episodeAdapter.defaultBackdropUrl = item.backdropUrl
            if (item.seasons.isNotEmpty()) {
                bindSeasons(item.seasons)
            }
        } else {
            seriesSection.visibility = View.GONE
        }

        // Привязываем актеров если они уже есть в объекте
        if (item.cast.isNotEmpty()) {
            bindCast(item.cast)
        }

        // Фоновая дозагрузка деталей TMDB (актеры, сезоны, возрастной рейтинг, хронометраж)
        if (item.cast.isEmpty() || item.ageRating.isNullOrBlank() || item.runtimeMinutes == 0 || (item.mediaType == MediaType.TV && item.seasons.isEmpty())) {
            fetchExtraDetails(item)
        }
    }

    private fun bindSeasons(seasons: List<TvSeason>) {
        if (seasons.isEmpty()) return
        val targetSeason = if (savedSeason > 0 && seasons.any { it.seasonNumber == savedSeason }) {
            savedSeason
        } else {
            seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber ?: seasons.first().seasonNumber
        }
        seasonAdapter.submitList(seasons, initialSelectedSeason = targetSeason)
        loadEpisodes(targetSeason)
        updateFocusNavigation()
    }

    private fun loadEpisodes(seasonNumber: Int) {
        val item = mediaItem ?: return
        currentSeasonNumber = seasonNumber
        episodesProgress.visibility = View.VISIBLE

        loadEpisodesJob?.cancel()
        loadEpisodesJob = lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    RuVkTubeApp.instance.tmdbRepository.getSeasonEpisodes(item.id, seasonNumber)
                }
                episodesProgress.visibility = View.GONE
                if (episodes.isNotEmpty()) {
                    currentEpisodesList = episodes
                    episodeAdapter.submitList(episodes)
                    episodesGridView.visibility = View.VISIBLE
                } else {
                    episodesGridView.visibility = View.GONE
                }
                updateFocusNavigation()
            } catch (e: Exception) {
                episodesProgress.visibility = View.GONE
                episodesGridView.visibility = View.GONE
            }
        }
    }

    private fun searchEpisodeSourcesAndFocus(episode: TvEpisode) {
        val item = mediaItem ?: return
        currentSeasonNumber = episode.seasonNumber
        currentEpisodeNumber = episode.episodeNumber

        isSearchingSources = true
        sourcesSection.visibility = View.VISIBLE
        sourcesHeader.visibility = View.VISIBLE
        sourcesHeader.text = "Сезон ${episode.seasonNumber}, Серия ${episode.episodeNumber}: Источники"
        sourcesFrame.visibility = View.VISIBLE
        sourcesProgressLayout.visibility = View.VISIBLE
        sourcesEmptyText.visibility = View.GONE
        sourcesGridView.visibility = View.GONE
        updateFocusNavigation()

        searchSourcesJob?.cancel()
        searchSourcesJob = lifecycleScope.launch {
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@DetailsActivity)
            val sources = RuVkTubeApp.instance.searchCoordinator.findEpisodeSources(
                title = item.title,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                runtimeMinutes = if (episode.durationMinutes > 0) episode.durationMinutes else item.runtimeMinutes,
                originalTitle = item.originalTitle,
                maxSupportedHeight = maxRes
            )

            isSearchingSources = false
            sourcesProgressLayout.visibility = View.GONE
            sourcesList = sources

            if (sources.isNotEmpty()) {
                sourcesEmptyText.visibility = View.GONE
                sourcesGridView.visibility = View.VISIBLE
                sourcesHeader.text = "Сезон ${episode.seasonNumber}, Серия ${episode.episodeNumber}: Источники (${sources.size})"
                sourceAdapter.submitList(sources)
                updateFocusNavigation()

                sourcesGridView.post {
                    sourcesGridView.setSelectedPosition(0)
                    sourcesGridView.requestFocus()
                }
            } else {
                sourcesGridView.visibility = View.GONE
                sourcesEmptyText.visibility = View.VISIBLE
                sourcesHeader.text = "Сезон ${episode.seasonNumber}, Серия ${episode.episodeNumber}: Источники"
                updateFocusNavigation()
                Toast.makeText(this@DetailsActivity, getString(R.string.no_sources_found), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindCast(cast: List<CastMember>) {
        if (cast.isNotEmpty()) {
            actorsSection.visibility = View.VISIBLE
            castAdapter.submitList(cast)
        } else {
            actorsSection.visibility = View.GONE
        }
        updateFocusNavigation()
    }

    private fun fetchExtraDetails(item: MediaItem) {
        lifecycleScope.launch {
            try {
                val detailed = withContext(Dispatchers.IO) {
                    if (item.mediaType == MediaType.MOVIE) {
                        RuVkTubeApp.instance.tmdbRepository.getMovieDetails(item.id)
                    } else {
                        RuVkTubeApp.instance.tmdbRepository.getTvDetails(item.id)
                    }
                }
                mediaItem = detailed

                if (!detailed.ageRating.isNullOrBlank()) {
                    badgeAge.visibility = View.VISIBLE
                    badgeAge.text = detailed.ageRating
                }
                if (detailed.runtimeMinutes > 0) {
                    badgeRuntime.visibility = View.VISIBLE
                    badgeRuntime.text = "${detailed.runtimeMinutes} мин"
                }
                if (detailed.cast.isNotEmpty()) {
                    bindCast(detailed.cast)
                }
                if (detailed.mediaType == MediaType.TV && detailed.seasons.isNotEmpty()) {
                    bindSeasons(detailed.seasons)
                }
            } catch (e: Exception) {
                // Игнорируем ошибки подгрузки
            }
        }
    }

    private fun searchSourcesAndFocus(item: MediaItem) {
        isSearchingSources = true
        sourcesSection.visibility = View.VISIBLE
        sourcesHeader.visibility = View.VISIBLE
        sourcesHeader.text = "Источники"
        sourcesFrame.visibility = View.VISIBLE
        sourcesProgressLayout.visibility = View.VISIBLE
        sourcesEmptyText.visibility = View.GONE
        sourcesGridView.visibility = View.GONE
        updateFocusNavigation()

        searchSourcesJob?.cancel()
        searchSourcesJob = lifecycleScope.launch {
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@DetailsActivity)
            val sources = RuVkTubeApp.instance.searchCoordinator.findMovieSources(
                title = item.title,
                year = item.year,
                runtimeMinutes = item.runtimeMinutes,
                originalTitle = item.originalTitle,
                maxSupportedHeight = maxRes
            )

            isSearchingSources = false
            sourcesProgressLayout.visibility = View.GONE
            sourcesList = sources

            if (sources.isNotEmpty()) {
                sourcesEmptyText.visibility = View.GONE
                sourcesGridView.visibility = View.VISIBLE
                sourcesHeader.text = "Источники (${sources.size})"
                sourceAdapter.submitList(sources)
                updateFocusNavigation()

                // Фокусируем курсор на первом найденном видео в панели источников
                sourcesGridView.post {
                    sourcesGridView.setSelectedPosition(0)
                    sourcesGridView.requestFocus()
                }
            } else {
                sourcesGridView.visibility = View.GONE
                sourcesEmptyText.visibility = View.VISIBLE
                sourcesHeader.text = "Источники"
                updateFocusNavigation()
                Toast.makeText(this@DetailsActivity, getString(R.string.no_sources_found), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playTrailer(item: MediaItem) {
        Toast.makeText(this, "Поиск трейлера...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@DetailsActivity)
            val trailers = RuVkTubeApp.instance.searchCoordinator.findTrailers(
                title = item.title,
                year = item.year,
                originalTitle = item.originalTitle,
                maxSupportedHeight = maxRes
            )

            if (trailers.isNotEmpty()) {
                val best = trailers.first()
                val intent = Intent(this@DetailsActivity, InternalPlayerActivity::class.java).apply {
                    putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, best.streamUrl)
                    putExtra(InternalPlayerActivity.EXTRA_TITLE, "${item.title} (Трейлер)")
                    putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
                    putExtra(InternalPlayerActivity.EXTRA_QUALITY, best.quality)
                    putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, true)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@DetailsActivity, "Русский трейлер не найден", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPlayback(source: VideoSource) {
        val item = mediaItem ?: return
        val isTv = item.mediaType == MediaType.TV
        val playTitle = if (isTv && currentSeasonNumber > 0 && currentEpisodeNumber > 0) {
            "${item.title} (S${currentSeasonNumber}E${currentEpisodeNumber} - ${source.quality})"
        } else {
            "${item.title} (${source.quality})"
        }

        if (AppPreferences.isExternalPlayer && !source.isTrailer) {
            ExternalPlayerLauncher.launch(
                context = this,
                streamUrl = source.streamUrl,
                title = playTitle,
                positionMs = savedPositionMs
            )
            return
        }
        val intent = Intent(this, InternalPlayerActivity::class.java).apply {
            putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, source.streamUrl)
            putExtra(InternalPlayerActivity.EXTRA_TITLE, playTitle)
            putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
            putExtra(InternalPlayerActivity.EXTRA_QUALITY, source.quality)
            putExtra(InternalPlayerActivity.EXTRA_START_POSITION_MS, savedPositionMs)
            putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, source.isTrailer)
            putExtra(InternalPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
            putExtra(InternalPlayerActivity.EXTRA_BACKDROP_URL, item.backdropUrl)
            putExtra(InternalPlayerActivity.EXTRA_MOVIE_TITLE, item.title)
            putExtra(InternalPlayerActivity.EXTRA_YEAR, item.year)
            putExtra(InternalPlayerActivity.EXTRA_RATING, item.voteAverage)
            putExtra(InternalPlayerActivity.EXTRA_OVERVIEW, item.overview)
            putExtra(InternalPlayerActivity.EXTRA_MEDIA_TYPE, item.mediaType.name)
            if (isTv) {
                putExtra(InternalPlayerActivity.EXTRA_SEASON, currentSeasonNumber)
                putExtra(InternalPlayerActivity.EXTRA_EPISODE, currentEpisodeNumber)
            }
        }
        startActivity(intent)
    }

    private fun playSavedStream(streamUrl: String, item: MediaItem, startPosMs: Long) {
        val isTv = item.mediaType == MediaType.TV
        val playTitle = if (isTv && savedSeason > 0 && savedEpisode > 0) {
            "${item.title} (S${savedSeason}E${savedEpisode})"
        } else {
            item.title
        }

        if (AppPreferences.isExternalPlayer) {
            ExternalPlayerLauncher.launch(
                context = this,
                streamUrl = streamUrl,
                title = playTitle,
                positionMs = startPosMs
            )
            return
        }
        val intent = Intent(this, InternalPlayerActivity::class.java).apply {
            putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(InternalPlayerActivity.EXTRA_TITLE, playTitle)
            putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
            putExtra(InternalPlayerActivity.EXTRA_START_POSITION_MS, startPosMs)
            putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, false)
            putExtra(InternalPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
            putExtra(InternalPlayerActivity.EXTRA_BACKDROP_URL, item.backdropUrl)
            putExtra(InternalPlayerActivity.EXTRA_MOVIE_TITLE, item.title)
            putExtra(InternalPlayerActivity.EXTRA_YEAR, item.year)
            putExtra(InternalPlayerActivity.EXTRA_RATING, item.voteAverage)
            putExtra(InternalPlayerActivity.EXTRA_OVERVIEW, item.overview)
            putExtra(InternalPlayerActivity.EXTRA_MEDIA_TYPE, item.mediaType.name)
            if (isTv) {
                putExtra(InternalPlayerActivity.EXTRA_SEASON, savedSeason)
                putExtra(InternalPlayerActivity.EXTRA_EPISODE, savedEpisode)
            }
        }
        startActivity(intent)
    }

    private fun checkHistoryPosition(tmdbId: Long) {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                RuVkTubeApp.instance.database.historyDao().getByTmdbId(tmdbId)
            }
            if (history != null && history.lastStreamUrl.isNotBlank() && history.positionMs > 5_000L) {
                savedPositionMs = history.positionMs
                savedStreamUrl = history.lastStreamUrl
                savedSeason = history.season
                savedEpisode = history.episode
                btnWatch.text = if (mediaItem?.mediaType == MediaType.TV && savedSeason > 0 && savedEpisode > 0) {
                    "(S${savedSeason}E${savedEpisode} ${formatTime(savedPositionMs)})"
                } else {
                    "(${formatTime(savedPositionMs)})"
                }
                btnSources.visibility = View.VISIBLE
            } else {
                savedPositionMs = 0L
                savedStreamUrl = null
                savedSeason = 0
                savedEpisode = 0
                btnWatch.text = ""
                btnSources.visibility = View.GONE
            }
            updateFocusNavigation()
        }
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

    private fun checkFavoriteStatus(tmdbId: Long) {
        lifecycleScope.launch {
            val isFav = withContext(Dispatchers.IO) {
                RuVkTubeApp.instance.database.favoritesDao().isFavorite(tmdbId)
            }
            isFavorite = isFav
            updateFavoriteButton()
        }
    }

    private fun toggleFavorite(item: MediaItem) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = RuVkTubeApp.instance.database.favoritesDao()
                if (isFavorite) {
                    dao.delete(item.id)
                } else {
                    dao.insert(
                        FavoriteEntity(
                            tmdbId = item.id,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            backdropUrl = item.backdropUrl,
                            overview = item.overview,
                            mediaType = item.mediaType.name,
                            year = item.year,
                            rating = item.voteAverage
                        )
                    )
                }
            }
            isFavorite = !isFavorite
            updateFavoriteButton()
            Toast.makeText(
                this@DetailsActivity,
                if (isFavorite) "Добавлено в избранное" else "Удалено из избранного",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateFavoriteButton() {
        if (isFavorite) {
            btnFavorite.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorites, 0, 0, 0)
        } else {
            btnFavorite.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorite_border, 0, 0, 0)
        }
        btnFavorite.text = ""
    }

    private fun loadFilmography(actor: CastMember) {
        filmographyHeader.text = "Фильмография: ${actor.name}"
        filmographySection.visibility = View.VISIBLE
        filmographyProgress.visibility = View.VISIBLE
        updateFocusNavigation()

        filmographyJob?.cancel()
        filmographyJob = lifecycleScope.launch {
            try {
                val movies = withContext(Dispatchers.IO) {
                    RuVkTubeApp.instance.tmdbRepository.getPersonFilmography(actor.id)
                }
                filmographyProgress.visibility = View.GONE
                if (movies.isNotEmpty()) {
                    filmographyAdapter.submitList(movies)
                    updateFocusNavigation()
                    filmographyGridView.post {
                        filmographyGridView.setSelectedPosition(0)
                        filmographyGridView.requestFocus()
                    }
                } else {
                    filmographyHeader.text = "Фильмография: ${actor.name} (нет данных)"
                }
            } catch (e: Exception) {
                filmographyProgress.visibility = View.GONE
                filmographyHeader.text = "Фильмография: ${actor.name} (ошибка загрузки)"
            }
        }
    }

    override fun onBackPressed() {
        val current = currentFocus
        if (current != null && isViewDescendant(current, filmographySection)) {
            actorsGridView.requestFocus()
            return
        }
        if (current != null && isViewDescendant(current, actorsSection)) {
            if (sourcesSection.visibility == View.VISIBLE) {
                sourcesGridView.requestFocus()
            } else if (seriesSection.visibility == View.VISIBLE) {
                episodesGridView.requestFocus()
            } else {
                btnWatch.requestFocus()
            }
            return
        }
        if (current != null && isViewDescendant(current, sourcesFrame)) {
            if (seriesSection.visibility == View.VISIBLE) {
                episodesGridView.requestFocus()
            } else {
                btnWatch.requestFocus()
            }
            return
        }
        if (current != null && isViewDescendant(current, episodesGridView)) {
            seasonsGridView.requestFocus()
            return
        }
        if (current != null && isViewDescendant(current, seasonsGridView)) {
            btnWatch.requestFocus()
            return
        }
        super.onBackPressed()
    }

    private fun isViewDescendant(view: View?, parent: View?): Boolean {
        if (view == null || parent == null) return false
        var p = view.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    companion object {
        const val EXTRA_MEDIA_ITEM = "extra_media_item"
    }
}
