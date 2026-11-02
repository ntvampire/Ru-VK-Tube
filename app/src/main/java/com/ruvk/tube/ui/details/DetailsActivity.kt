package com.ruvk.tube.ui.details

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.db.FavoriteEntity
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.domain.models.VideoSource
import com.ruvk.tube.player.DeviceCapabilityHelper
import com.ruvk.tube.player.InternalPlayerActivity
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
    private lateinit var btnTrailer: TextView
    private lateinit var btnFavorite: TextView

    private lateinit var sourcesHeader: TextView
    private lateinit var sourcesFrame: View
    private lateinit var sourcesGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var sourcesProgressLayout: LinearLayout
    private lateinit var sourcesEmptyText: TextView

    private lateinit var sourceAdapter: NumSourceAdapter

    private var mediaItem: MediaItem? = null
    private var isFavorite = false
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

        initViews()
        setupListeners()
        bindMediaItem(item)
        checkFavoriteStatus(item.id)
    }

    private fun initViews() {
        heroBackdrop = findViewById(R.id.details_hero_backdrop)
        posterImage = findViewById(R.id.details_poster)
        titleText = findViewById(R.id.details_title)
        originalTitleText = findViewById(R.id.details_original_title)
        badgeAge = findViewById(R.id.badge_age)
        badgeRating = findViewById(R.id.badge_rating)
        badgeYear = findViewById(R.id.badge_year)
        badgeRuntime = findViewById(R.id.badge_runtime)
        overviewText = findViewById(R.id.details_overview)

        btnWatch = findViewById(R.id.btn_watch)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnFavorite = findViewById(R.id.btn_favorite)

        sourcesHeader = findViewById(R.id.sources_header)
        sourcesFrame = findViewById(R.id.sources_frame)
        sourcesGridView = findViewById(R.id.sources_grid_view)
        sourcesProgressLayout = findViewById(R.id.sources_progress_layout)
        sourcesEmptyText = findViewById(R.id.sources_empty_text)

        // По умолчанию полка источников скрыта до нажатия кнопки "Смотреть"
        sourcesHeader.visibility = View.GONE
        sourcesFrame.visibility = View.GONE

        // Настройка полки источников (1 ряд)
        sourcesGridView.setNumRows(1)
        sourceAdapter = NumSourceAdapter { source ->
            startPlayback(source)
        }
        sourcesGridView.adapter = sourceAdapter

        // Связка навигации пульта (D-Pad)
        btnWatch.nextFocusRightId = R.id.btn_trailer
        btnWatch.nextFocusDownId = R.id.sources_grid_view

        btnTrailer.nextFocusLeftId = R.id.btn_watch
        btnTrailer.nextFocusRightId = R.id.btn_favorite
        btnTrailer.nextFocusDownId = R.id.sources_grid_view

        btnFavorite.nextFocusLeftId = R.id.btn_trailer
        btnFavorite.nextFocusDownId = R.id.sources_grid_view

        sourcesGridView.nextFocusUpId = R.id.btn_watch

        // Фокус по умолчанию на кнопке "Смотреть"
        btnWatch.post {
            btnWatch.requestFocus()
        }
    }

    private fun setupListeners() {
        btnWatch.setOnClickListener {
            val item = mediaItem ?: return@setOnClickListener
            if (sourcesList.isNotEmpty()) {
                // Если источники уже найдены - переводим курсор на первое видео
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

        posterImage.load(item.posterUrl) {
            crossfade(300)
            placeholder(R.color.surface_card)
            error(R.color.surface_card)
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

        // Фоновая дозагрузка деталей TMDB, если возраст или хронометраж отсутствуют
        if (item.ageRating.isNullOrBlank() || item.runtimeMinutes == 0) {
            fetchExtraDetails(item)
        }
    }

    private fun fetchExtraDetails(item: MediaItem) {
        lifecycleScope.launch {
            try {
                val detailed = if (item.mediaType == MediaType.MOVIE) {
                    RuVkTubeApp.instance.tmdbRepository.getMovieDetails(item.id)
                } else {
                    RuVkTubeApp.instance.tmdbRepository.getTvDetails(item.id)
                }

                if (!detailed.ageRating.isNullOrBlank()) {
                    badgeAge.visibility = View.VISIBLE
                    badgeAge.text = detailed.ageRating
                }
                if (detailed.runtimeMinutes > 0) {
                    badgeRuntime.visibility = View.VISIBLE
                    badgeRuntime.text = "${detailed.runtimeMinutes} мин"
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }

    private fun searchSourcesAndFocus(item: MediaItem) {
        isSearchingSources = true
        sourcesHeader.visibility = View.VISIBLE
        sourcesHeader.text = "Источники"
        sourcesFrame.visibility = View.VISIBLE
        sourcesProgressLayout.visibility = View.VISIBLE
        sourcesEmptyText.visibility = View.GONE
        sourcesGridView.visibility = View.GONE

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

                // Фокусируем курсор на первом найденном видео в панели источников
                sourcesGridView.post {
                    sourcesGridView.setSelectedPosition(0)
                    sourcesGridView.requestFocus()
                }
            } else {
                sourcesGridView.visibility = View.GONE
                sourcesEmptyText.visibility = View.VISIBLE
                sourcesHeader.text = "Источники"
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
        val intent = Intent(this, InternalPlayerActivity::class.java).apply {
            putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, source.streamUrl)
            putExtra(InternalPlayerActivity.EXTRA_TITLE, "${item.title} (${source.quality})")
            putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
            putExtra(InternalPlayerActivity.EXTRA_QUALITY, source.quality)
            putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, source.isTrailer)
        }
        startActivity(intent)
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
            btnFavorite.text = "В избранном"
        } else {
            btnFavorite.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorite_border, 0, 0, 0)
            btnFavorite.text = "В избранное"
        }
    }

    companion object {
        const val EXTRA_MEDIA_ITEM = "extra_media_item"
    }
}
