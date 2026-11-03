package com.ruvk.tube.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.ui.details.DetailsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var heroBackdrop: ImageView
    private lateinit var posterGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyText: TextView

    // Боковое меню (NUM-style)
    private lateinit var sideMenuContainer: LinearLayout
    private lateinit var menuAppTitle: TextView
    private lateinit var sideMenuRecycler: RecyclerView
    private lateinit var sideMenuAdapter: NumSideMenuAdapter
    private var isMenuExpanded = false

    // Элементы информационного блока (Spotlight)
    private lateinit var heroTitle: TextView
    private lateinit var badgeAge: TextView
    private lateinit var badgeRating: TextView
    private lateinit var badgeYear: TextView
    private lateinit var badgeRuntime: TextView
    private lateinit var badgeOriginalTitle: TextView
    private lateinit var heroOverview: TextView

    private lateinit var posterAdapter: NumPosterAdapter

    private var currentMediaItem: MediaItem? = null
    private var detailsFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSideMenu()
        setupPosterGrid()
        loadCategory(NumCategory.TRENDING)
    }

    private fun initViews() {
        heroBackdrop = findViewById(R.id.hero_backdrop)
        posterGridView = findViewById(R.id.poster_grid_view)
        loadingProgress = findViewById(R.id.loading_progress)
        emptyText = findViewById(R.id.empty_text)

        sideMenuContainer = findViewById(R.id.side_menu_container)
        menuAppTitle = findViewById(R.id.menu_app_title)
        sideMenuRecycler = findViewById(R.id.side_menu_recycler)

        heroTitle = findViewById(R.id.hero_title)
        badgeAge = findViewById(R.id.badge_age)
        badgeRating = findViewById(R.id.badge_rating)
        badgeYear = findViewById(R.id.badge_year)
        badgeRuntime = findViewById(R.id.badge_runtime)
        badgeOriginalTitle = findViewById(R.id.badge_original_title)
        heroOverview = findViewById(R.id.hero_overview)

        // Настройка HorizontalGridView
        posterGridView.setNumRows(1)

        // Перехват DPAD_LEFT на первом постере для перехода в боковое меню
        posterGridView.setOnKeyInterceptListener { event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (posterGridView.selectedPosition <= 0) {
                    val targetPos = sideMenuAdapter.selectedCategory.ordinal
                    val targetHolder = sideMenuRecycler.findViewHolderForAdapterPosition(targetPos)
                    val targetView = targetHolder?.itemView
                        ?: sideMenuRecycler.layoutManager?.findViewByPosition(targetPos)
                        ?: sideMenuRecycler.layoutManager?.findViewByPosition(0)

                    if (targetView != null && targetView.requestFocus()) {
                        return@setOnKeyInterceptListener true
                    }
                }
            }
            false
        }
    }

    private fun setupSideMenu() {
        sideMenuRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        sideMenuAdapter = NumSideMenuAdapter(
            onItemSelected = { category ->
                loadCategory(category)
                posterGridView.post {
                    posterGridView.requestFocus()
                }
            },
            onMenuFocusChanged = { _ ->
                // Фокус отслеживается через глобальный слушатель
            },
            onRightNavigate = {
                posterGridView.requestFocus()
            }
        )
        sideMenuRecycler.adapter = sideMenuAdapter

        // Глобальный слушатель фокуса для плавной анимации раскрытия/сворачивания меню
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val hasFocusInMenu = isViewDescendant(newFocus, sideMenuContainer)
            setMenuExpanded(hasFocusInMenu)
        }
    }

    private fun isViewDescendant(view: View?, parent: View): Boolean {
        var current: ViewParent? = view?.parent
        while (current != null) {
            if (current == parent) return true
            current = current.parent
        }
        return false
    }

    private fun setMenuExpanded(expanded: Boolean) {
        if (isMenuExpanded == expanded) return
        isMenuExpanded = expanded

        val startWidth = sideMenuContainer.width
        val targetWidth = if (expanded) 220.dpToPx() else 68.dpToPx()

        ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                sideMenuContainer.layoutParams.width = w
                sideMenuContainer.requestLayout()
            }
            start()
        }

        menuAppTitle.animate().alpha(if (expanded) 1.0f else 0.0f).setDuration(150).start()
        sideMenuAdapter.isExpanded = expanded
    }

    private fun setupPosterGrid() {
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
                overview = f.overview ?: "",
                posterUrl = f.posterUrl,
                backdropUrl = f.backdropUrl,
                releaseDate = if (f.year > 0) "${f.year}-01-01" else "",
                year = f.year,
                voteAverage = f.rating,
                mediaType = if (f.mediaType == "TV") MediaType.TV else MediaType.MOVIE
            )
        }
    }

    private suspend fun loadHistory(): List<MediaItem> = withContext(Dispatchers.IO) {
        val hist = RuVkTubeApp.instance.database.historyDao().getAllHistory().firstOrNull() ?: emptyList()
        val items = mutableListOf<MediaItem>()
        for (h in hist) {
            var itemPoster = h.posterUrl
            var itemBackdrop = h.backdropUrl
            var itemYear = h.year
            var itemRating = h.rating
            var itemOverview = h.overview ?: ""
            var originalTitle = ""
            var runtime = 0
            var ageRating: String? = null

            // Если постер или метаданные отсутствуют (старые записи), догружаем из TMDB
            if (itemPoster.isNullOrBlank() && h.tmdbId > 0) {
                try {
                    val isTv = h.mediaType.equals("tv", ignoreCase = true)
                    val fullItem = if (isTv) {
                        RuVkTubeApp.instance.tmdbRepository.getTvDetails(h.tmdbId)
                    } else {
                        RuVkTubeApp.instance.tmdbRepository.getMovieDetails(h.tmdbId)
                    }
                    itemPoster = fullItem.posterUrl
                    itemBackdrop = fullItem.backdropUrl
                    itemYear = fullItem.year
                    itemRating = fullItem.voteAverage
                    itemOverview = fullItem.overview
                    originalTitle = fullItem.originalTitle
                    runtime = fullItem.runtimeMinutes
                    ageRating = fullItem.ageRating

                    // Сохраняем обновленные данные в локальную БД
                    RuVkTubeApp.instance.database.historyDao().insertOrUpdate(
                        h.copy(
                            posterUrl = itemPoster,
                            backdropUrl = itemBackdrop,
                            year = itemYear,
                            rating = itemRating,
                            overview = itemOverview
                        )
                    )
                } catch (e: Exception) {
                    // Игнорируем сетевые ошибки фонового обогащения
                }
            }

            items.add(
                MediaItem(
                    id = h.tmdbId,
                    title = h.title,
                    originalTitle = originalTitle,
                    overview = itemOverview,
                    posterUrl = itemPoster,
                    backdropUrl = itemBackdrop,
                    releaseDate = if (itemYear > 0) "$itemYear" else "",
                    year = itemYear,
                    voteAverage = itemRating,
                    runtimeMinutes = runtime,
                    ageRating = ageRating,
                    mediaType = if (h.mediaType.equals("tv", ignoreCase = true)) MediaType.TV else MediaType.MOVIE
                )
            )
        }
        items
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
                // Игнорируем ошибки фоновой подгрузки
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

    override fun onBackPressed() {
        // Если фокус в боковом меню, кнопка Назад возвращает фокус на ряд постеров
        if (isViewDescendant(currentFocus, sideMenuContainer)) {
            posterGridView.requestFocus()
            return
        }
        super.onBackPressed()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
