package com.ruvk.tube.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
import com.ruvk.tube.player.DeviceCapabilityHelper
import com.ruvk.tube.player.InternalPlayerActivity
import com.ruvk.tube.ui.details.DetailsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var heroBackdrop: ImageView
    private lateinit var tabsGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var posterGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyText: TextView

    // Элементы информационного блока (Spotlight)
    private lateinit var heroTitle: TextView
    private lateinit var badgeAge: TextView
    private lateinit var badgeRating: TextView
    private lateinit var badgeYear: TextView
    private lateinit var badgeRuntime: TextView
    private lateinit var badgeOriginalTitle: TextView
    private lateinit var heroOverview: TextView

    // Кнопки быстрого действия
    private lateinit var btnWatch: TextView
    private lateinit var btnTrailer: TextView
    private lateinit var btnDetails: TextView
    private lateinit var btnFavorite: TextView

    private lateinit var categoryAdapter: NumCategoryAdapter
    private lateinit var posterAdapter: NumPosterAdapter

    private var currentMediaItem: MediaItem? = null
    private var isCurrentFavorite = false
    private var detailsFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupAdapters()
        setupButtonListeners()
        loadCategory(NumCategory.TRENDING)
    }

    private fun initViews() {
        heroBackdrop = findViewById(R.id.hero_backdrop)
        tabsGridView = findViewById(R.id.tabs_grid_view)
        posterGridView = findViewById(R.id.poster_grid_view)
        loadingProgress = findViewById(R.id.loading_progress)
        emptyText = findViewById(R.id.empty_text)

        heroTitle = findViewById(R.id.hero_title)
        badgeAge = findViewById(R.id.badge_age)
        badgeRating = findViewById(R.id.badge_rating)
        badgeYear = findViewById(R.id.badge_year)
        badgeRuntime = findViewById(R.id.badge_runtime)
        badgeOriginalTitle = findViewById(R.id.badge_original_title)
        heroOverview = findViewById(R.id.hero_overview)

        btnWatch = findViewById(R.id.btn_watch)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnDetails = findViewById(R.id.btn_details)
        btnFavorite = findViewById(R.id.btn_favorite)

        // Настройка HorizontalGridView для ТВ
        posterGridView.setNumRows(1)
        tabsGridView.setNumRows(1)

        // Фокус с ряда постеров при стрелке ВВЕРХ идет на кнопку "Смотреть"
        posterGridView.nextFocusUpId = R.id.btn_watch
        btnWatch.nextFocusDownId = R.id.poster_grid_view
        btnTrailer.nextFocusDownId = R.id.poster_grid_view
        btnDetails.nextFocusDownId = R.id.poster_grid_view
        btnFavorite.nextFocusDownId = R.id.poster_grid_view

        // С кнопок при стрелке ВВЕРХ идет на панель вкладок
        btnWatch.nextFocusUpId = R.id.tabs_grid_view
        btnTrailer.nextFocusUpId = R.id.tabs_grid_view
        btnDetails.nextFocusUpId = R.id.tabs_grid_view
        btnFavorite.nextFocusUpId = R.id.tabs_grid_view

        tabsGridView.nextFocusDownId = R.id.btn_watch
    }

    private fun setupAdapters() {
        categoryAdapter = NumCategoryAdapter { category ->
            loadCategory(category)
        }
        tabsGridView.adapter = categoryAdapter

        posterAdapter = NumPosterAdapter(
            onItemFocused = { item ->
                updateHeroSpotlight(item)
            },
            onItemClicked = { item ->
                openMovieDetails(item)
            }
        )
        posterGridView.adapter = posterAdapter
    }

    private fun setupButtonListeners() {
        btnWatch.setOnClickListener {
            val item = currentMediaItem ?: return@setOnClickListener
            searchAndPlayMovie(item)
        }

        btnTrailer.setOnClickListener {
            val item = currentMediaItem ?: return@setOnClickListener
            searchAndPlayTrailer(item)
        }

        btnDetails.setOnClickListener {
            val item = currentMediaItem ?: return@setOnClickListener
            openMovieDetails(item)
        }

        btnFavorite.setOnClickListener {
            val item = currentMediaItem ?: return@setOnClickListener
            toggleFavorite(item)
        }
    }

    private fun loadCategory(category: NumCategory) {
        loadingProgress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        lifecycleScope.launch {
            val items = when (category) {
                NumCategory.TRENDING -> RuVkTubeApp.instance.tmdbRepository.getTrending()
                NumCategory.MOVIES -> RuVkTubeApp.instance.tmdbRepository.getPopularMovies()
                NumCategory.SERIES -> RuVkTubeApp.instance.tmdbRepository.getPopularTv()
                NumCategory.FAVORITES -> loadFavorites()
                NumCategory.HISTORY -> loadHistory()
            }

            loadingProgress.visibility = View.GONE
            if (items.isNotEmpty()) {
                emptyText.visibility = View.GONE
                posterAdapter.submitList(items)
                posterGridView.scrollToPosition(0)
                posterGridView.post { posterGridView.requestFocus() }
                updateHeroSpotlight(items.first())
            } else {
                emptyText.visibility = View.VISIBLE
                posterAdapter.submitList(emptyList())
                clearHeroSpotlight()
            }
        }
    }

    private suspend fun loadFavorites(): List<MediaItem> = withContext(Dispatchers.IO) {
        val favs = RuVkTubeApp.instance.database.favoritesDao().getAllFavorites().firstOrNull() ?: emptyList()
        favs.map { f ->
            MediaItem(
                id = f.tmdbId,
                title = f.title,
                originalTitle = "",
                overview = "",
                posterUrl = f.posterUrl,
                backdropUrl = null,
                releaseDate = if (f.year > 0) "${f.year}-01-01" else "",
                year = f.year,
                voteAverage = f.rating,
                mediaType = if (f.mediaType == "TV") MediaType.TV else MediaType.MOVIE
            )
        }
    }

    private suspend fun loadHistory(): List<MediaItem> = withContext(Dispatchers.IO) {
        val hist = RuVkTubeApp.instance.database.historyDao().getAllHistory().firstOrNull() ?: emptyList()
        hist.map { h ->
            MediaItem(
                id = h.tmdbId,
                title = h.title,
                originalTitle = "",
                overview = "",
                posterUrl = h.posterUrl,
                backdropUrl = h.backdropUrl,
                releaseDate = "",
                year = 0,
                voteAverage = 0.0,
                mediaType = if (h.mediaType == "TV") MediaType.TV else MediaType.MOVIE
            )
        }
    }

    private fun updateHeroSpotlight(item: MediaItem) {
        currentMediaItem = item

        // Загрузка сочного полноэкранного Backdrop с кроссфейдом
        val backdropUrl = item.backdropUrl ?: item.posterUrl
        heroBackdrop.load(backdropUrl) {
            crossfade(300)
            placeholder(R.color.background_dark)
            error(R.color.background_dark)
        }

        heroTitle.text = item.title

        // Бейдж возраста (18+, 16+, 12+, 6+, 0+)
        if (!item.ageRating.isNullOrBlank()) {
            badgeAge.visibility = View.VISIBLE
            badgeAge.text = item.ageRating
        } else {
            badgeAge.visibility = View.GONE
        }

        // Оценка
        if (item.voteAverage > 0) {
            badgeRating.visibility = View.VISIBLE
            badgeRating.text = "★ ${String.format("%.1f", item.voteAverage)}"
        } else {
            badgeRating.visibility = View.GONE
        }

        // Год
        if (item.year > 0) {
            badgeYear.visibility = View.VISIBLE
            badgeYear.text = item.year.toString()
        } else {
            badgeYear.visibility = View.GONE
        }

        // Хронометраж
        if (item.runtimeMinutes > 0) {
            badgeRuntime.visibility = View.VISIBLE
            badgeRuntime.text = "${item.runtimeMinutes} мин"
        } else {
            badgeRuntime.visibility = View.GONE
        }

        // Оригинальное название
        if (item.originalTitle.isNotBlank() && item.originalTitle != item.title) {
            badgeOriginalTitle.visibility = View.VISIBLE
            badgeOriginalTitle.text = item.originalTitle
        } else {
            badgeOriginalTitle.visibility = View.GONE
        }

        // Описание сюжета
        heroOverview.text = if (item.overview.isNotBlank()) item.overview else "Описание отсутствует"

        // Проверяем статус избранного
        checkFavoriteStatus(item.id)

        // Фоновая дозагрузка возрастного рейтинга и хронометража, если их еще нет в объекте
        if (item.ageRating.isNullOrBlank() || item.runtimeMinutes == 0) {
            fetchExtraDetails(item)
        }
    }

    private fun fetchExtraDetails(item: MediaItem) {
        detailsFetchJob?.cancel()
        detailsFetchJob = lifecycleScope.launch {
            try {
                val detailed = if (item.mediaType == MediaType.MOVIE) {
                    RuVkTubeApp.instance.tmdbRepository.getMovieDetails(item.id)
                } else {
                    RuVkTubeApp.instance.tmdbRepository.getTvDetails(item.id)
                }

                if (currentMediaItem?.id == item.id) {
                    if (!detailed.ageRating.isNullOrBlank()) {
                        badgeAge.visibility = View.VISIBLE
                        badgeAge.text = detailed.ageRating
                    }
                    if (detailed.runtimeMinutes > 0) {
                        badgeRuntime.visibility = View.VISIBLE
                        badgeRuntime.text = "${detailed.runtimeMinutes} мин"
                    }
                }
            } catch (e: Exception) {
                // Игнорируем сетевые сбои дозагрузки деталей
            }
        }
    }

    private fun checkFavoriteStatus(tmdbId: Long) {
        lifecycleScope.launch {
            val isFav = withContext(Dispatchers.IO) {
                RuVkTubeApp.instance.database.favoritesDao().isFavorite(tmdbId)
            }
            isCurrentFavorite = isFav
            btnFavorite.text = if (isCurrentFavorite) "💔  В избранном" else "❤️  В избранное"
        }
    }

    private fun toggleFavorite(item: MediaItem) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = RuVkTubeApp.instance.database.favoritesDao()
                if (isCurrentFavorite) {
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
            isCurrentFavorite = !isCurrentFavorite
            btnFavorite.text = if (isCurrentFavorite) "💔  В избранном" else "❤️  В избранное"
            Toast.makeText(
                this@MainActivity,
                if (isCurrentFavorite) "Добавлено в избранное" else "Удалено из избранного",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun searchAndPlayMovie(item: MediaItem) {
        Toast.makeText(this, getString(R.string.searching_sources), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@MainActivity)
            val sources = RuVkTubeApp.instance.searchCoordinator.findMovieSources(
                title = item.title,
                year = item.year,
                runtimeMinutes = item.runtimeMinutes,
                maxSupportedHeight = maxRes
            )

            if (sources.isNotEmpty()) {
                val best = sources.first()
                val intent = Intent(this@MainActivity, InternalPlayerActivity::class.java).apply {
                    putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, best.streamUrl)
                    putExtra(InternalPlayerActivity.EXTRA_TITLE, "${item.title} (${best.quality})")
                    putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
                    putExtra(InternalPlayerActivity.EXTRA_QUALITY, best.quality)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.no_sources_found), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchAndPlayTrailer(item: MediaItem) {
        Toast.makeText(this, "Поиск трейлера...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val maxRes = DeviceCapabilityHelper.getMaxDisplayHeight(this@MainActivity)
            val trailers = RuVkTubeApp.instance.searchCoordinator.findTrailers(
                title = item.title,
                year = item.year,
                maxSupportedHeight = maxRes
            )

            if (trailers.isNotEmpty()) {
                val best = trailers.first()
                val intent = Intent(this@MainActivity, InternalPlayerActivity::class.java).apply {
                    putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, best.streamUrl)
                    putExtra(InternalPlayerActivity.EXTRA_TITLE, "${item.title} (Трейлер)")
                    putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, item.id)
                    putExtra(InternalPlayerActivity.EXTRA_QUALITY, best.quality)
                    putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, true)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, "Русский трейлер не найден", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMovieDetails(item: MediaItem) {
        val intent = Intent(this, DetailsActivity::class.java).apply {
            putExtra(DetailsActivity.EXTRA_MEDIA_ITEM, item)
        }
        startActivity(intent)
    }

    private fun clearHeroSpotlight() {
        heroBackdrop.setImageDrawable(null)
        heroTitle.text = ""
        badgeAge.visibility = View.GONE
        badgeRating.visibility = View.GONE
        badgeYear.visibility = View.GONE
        badgeRuntime.visibility = View.GONE
        badgeOriginalTitle.visibility = View.GONE
        heroOverview.text = ""
    }
}
