package com.ruvk.tube.ui.main

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import com.ruvk.tube.data.update.UpdateInfo
import com.ruvk.tube.data.update.UpdateManager
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.ui.details.DetailsActivity
import com.ruvk.tube.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var heroBackdrop: ImageView
    private lateinit var posterGridView: androidx.leanback.widget.HorizontalGridView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyText: TextView

    // Поиск по каталогу
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchClearBtn: ImageView
    private var searchJob: Job? = null
    private var currentFeedJob: Job? = null

    // Бесконечная пагинация
    private var currentPage: Int = 1
    private var isLoadingNextPage: Boolean = false
    private var canLoadMore: Boolean = true

    // Боковое меню (NUM-style)
    private lateinit var sideMenuContainer: LinearLayout
    private lateinit var menuAppTitle: TextView
    private lateinit var sideMenuRecycler: RecyclerView
    private lateinit var sideMenuAdapter: NumSideMenuAdapter
    private var isMenuExpanded = false

    // Элементы информационного блока (Spotlight)
    private lateinit var heroTitle: TextView
    private lateinit var badgeWatchProgress: TextView
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
        checkBackgroundUpdate()
    }

    private fun initViews() {
        heroBackdrop = findViewById(R.id.hero_backdrop)
        posterGridView = findViewById(R.id.poster_grid_view)
        loadingProgress = findViewById(R.id.loading_progress)
        emptyText = findViewById(R.id.empty_text)

        searchContainer = findViewById(R.id.search_container)
        searchEditText = findViewById(R.id.search_edit_text)
        searchClearBtn = findViewById(R.id.search_clear_btn)

        sideMenuContainer = findViewById(R.id.side_menu_container)
        menuAppTitle = findViewById(R.id.menu_app_title)
        menuAppTitle.text = androidx.core.text.HtmlCompat.fromHtml(
            "<font color=\"#0A82FF\">RuVK</font> <font color=\"#FF3C4B\">Hub</font>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        sideMenuRecycler = findViewById(R.id.side_menu_recycler)

        heroTitle = findViewById(R.id.hero_title)
        badgeWatchProgress = findViewById(R.id.badge_watch_progress)
        badgeAge = findViewById(R.id.badge_age)
        badgeRating = findViewById(R.id.badge_rating)
        badgeYear = findViewById(R.id.badge_year)
        badgeRuntime = findViewById(R.id.badge_runtime)
        badgeOriginalTitle = findViewById(R.id.badge_original_title)
        heroOverview = findViewById(R.id.hero_overview)

        // Настройка навигации строки поиска
        searchEditText.nextFocusLeftId = R.id.side_menu_recycler
        searchEditText.nextFocusDownId = R.id.poster_grid_view

        searchClearBtn.setOnClickListener {
            searchEditText.setText("")
            searchEditText.requestFocus()
        }

        searchEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = searchEditText.text.toString().trim()
                if (query.isNotBlank()) {
                    searchJob?.cancel()
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                searchClearBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                if (sideMenuAdapter.selectedCategory == NumCategory.SEARCH) {
                    searchJob?.cancel()
                    if (query.length >= 2) {
                        searchJob = lifecycleScope.launch {
                            delay(500)
                            performSearch(query)
                        }
                    } else if (query.isEmpty()) {
                        posterAdapter.submitList(emptyList())
                        clearHeroSpotlight()
                        emptyText.text = "Введите название фильма или сериала для поиска"
                        emptyText.visibility = View.VISIBLE
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Настройка HorizontalGridView
        posterGridView.setNumRows(1)

        // Перехват кнопок пульта в HorizontalGridView
        posterGridView.setOnKeyInterceptListener { event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && posterGridView.selectedPosition <= 0) {
                    focusSideMenu()
                    return@setOnKeyInterceptListener true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && sideMenuAdapter.selectedCategory == NumCategory.SEARCH) {
                    if (searchEditText.requestFocus()) {
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
                if (category == NumCategory.SETTINGS) {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    loadCategory(category)
                    if (category == NumCategory.SEARCH) {
                        searchEditText.requestFocus()
                    }
                }
            },
            onMenuFocusChanged = { _ ->
                // Фокус отслеживается через глобальный слушатель
            },
            onRightNavigate = {
                if (sideMenuAdapter.selectedCategory == NumCategory.SEARCH) {
                    searchEditText.requestFocus()
                } else if (sideMenuAdapter.selectedCategory != NumCategory.SETTINGS) {
                    posterGridView.requestFocus()
                }
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
        if (view == null) return false
        if (view == parent) return true
        var current: ViewParent? = view.parent
        while (current != null) {
            if (current == parent) return true
            current = current.parent
        }
        return false
    }

    private fun focusSideMenu() {
        val targetPos = sideMenuAdapter.selectedCategory.ordinal
        val targetHolder = sideMenuRecycler.findViewHolderForAdapterPosition(targetPos)
        val targetView = targetHolder?.itemView
            ?: sideMenuRecycler.layoutManager?.findViewByPosition(targetPos)
            ?: sideMenuRecycler.layoutManager?.findViewByPosition(0)

        if (targetView != null && targetView.requestFocus()) {
            return
        }
        sideMenuRecycler.requestFocus()
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

        // Пагинация: отслеживаем прокрутку ленты
        posterGridView.setOnChildViewHolderSelectedListener(object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                viewHolder: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                if (position >= 0 && position < posterAdapter.itemCount) {
                    posterAdapter.getItemAt(position)?.let { updateHeroSpotlight(it) }

                    // Бесконечная подгрузка: за 5 карточек до конца списка запрашиваем следующую страницу
                    if (canLoadMore && !isLoadingNextPage && posterAdapter.itemCount >= 10 && position >= posterAdapter.itemCount - 5) {
                        loadNextPage()
                    }
                }
            }
        })
    }

    private fun loadCategory(category: NumCategory) {
        currentFeedJob?.cancel()
        currentPage = 1
        canLoadMore = true
        isLoadingNextPage = false
        clearHeroSpotlight()

        if (category == NumCategory.SEARCH) {
            searchContainer.visibility = View.VISIBLE
            loadingProgress.visibility = View.GONE
            val currentQuery = searchEditText.text.toString().trim()
            if (currentQuery.length >= 2) {
                performSearch(currentQuery)
            } else {
                emptyText.text = "Введите название фильма или сериала для поиска"
                emptyText.visibility = View.VISIBLE
                posterAdapter.submitList(emptyList())
                clearHeroSpotlight()
            }
            searchEditText.post { searchEditText.requestFocus() }
            return
        }

        searchContainer.visibility = View.GONE
        loadingProgress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        posterAdapter.submitList(emptyList())

        currentFeedJob = lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    when (category) {
                        NumCategory.SEARCH -> emptyList()
                        NumCategory.TRENDING -> RuVkTubeApp.instance.tmdbRepository.getTrending(page = 1)
                        NumCategory.NEW_RELEASES -> RuVkTubeApp.instance.tmdbRepository.getNewDigitalReleases(page = 1)
                        NumCategory.ON_THE_AIR -> RuVkTubeApp.instance.tmdbRepository.getOnTheAirTv(page = 1)
                        NumCategory.MOVIES -> RuVkTubeApp.instance.tmdbRepository.getPopularMovies(page = 1)
                        NumCategory.SERIES -> RuVkTubeApp.instance.tmdbRepository.getPopularTv(page = 1)
                        NumCategory.FAVORITES -> loadFavorites()
                        NumCategory.HISTORY -> loadHistory()
                        NumCategory.SETTINGS -> emptyList()
                    }
                }

                loadingProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    emptyText.visibility = View.GONE
                    posterAdapter.submitList(items)
                    posterGridView.scrollToPosition(0)
                    posterGridView.post { posterGridView.requestFocus() }
                    updateHeroSpotlight(items.first())
                } else {
                    emptyText.text = when (category) {
                        NumCategory.FAVORITES -> "В избранном пока ничего нет"
                        NumCategory.HISTORY -> "История просмотров пуста"
                        else -> getString(R.string.no_sources_found)
                    }
                    emptyText.visibility = View.VISIBLE
                    posterAdapter.submitList(emptyList())
                    clearHeroSpotlight()
                    focusSideMenu()
                }
            } catch (e: Exception) {
                loadingProgress.visibility = View.GONE
                emptyText.text = "Не удалось загрузить каталог.\nПроверьте подключение к сети или ключ TMDB в Настройках."
                emptyText.visibility = View.VISIBLE
                posterAdapter.submitList(emptyList())
                clearHeroSpotlight()
                focusSideMenu()
            }
        }
    }

    private fun loadNextPage() {
        val cat = sideMenuAdapter.selectedCategory
        if (cat == NumCategory.FAVORITES || cat == NumCategory.HISTORY || cat == NumCategory.SETTINGS) {
            return
        }

        isLoadingNextPage = true
        val nextPage = currentPage + 1

        lifecycleScope.launch {
            try {
                val newItems = withContext(Dispatchers.IO) {
                    when (cat) {
                        NumCategory.SEARCH -> {
                            val q = searchEditText.text.toString().trim()
                            if (q.length >= 2) RuVkTubeApp.instance.tmdbRepository.search(q, page = nextPage)
                            else emptyList()
                        }
                        NumCategory.TRENDING -> RuVkTubeApp.instance.tmdbRepository.getTrending(page = nextPage)
                        NumCategory.NEW_RELEASES -> RuVkTubeApp.instance.tmdbRepository.getNewDigitalReleases(page = nextPage)
                        NumCategory.ON_THE_AIR -> RuVkTubeApp.instance.tmdbRepository.getOnTheAirTv(page = nextPage)
                        NumCategory.MOVIES -> RuVkTubeApp.instance.tmdbRepository.getPopularMovies(page = nextPage)
                        NumCategory.SERIES -> RuVkTubeApp.instance.tmdbRepository.getPopularTv(page = nextPage)
                        else -> emptyList()
                    }
                }

                if (newItems.isNotEmpty()) {
                    currentPage = nextPage
                    posterAdapter.appendList(newItems)
                } else {
                    canLoadMore = false
                }
            } catch (e: Exception) {
                canLoadMore = false
            } finally {
                isLoadingNextPage = false
            }
        }
    }

    private fun performSearch(query: String) {
        currentFeedJob?.cancel()
        currentPage = 1
        canLoadMore = true
        isLoadingNextPage = false

        currentFeedJob = lifecycleScope.launch {
            loadingProgress.visibility = View.VISIBLE
            emptyText.visibility = View.GONE

            try {
                val results = withContext(Dispatchers.IO) {
                    RuVkTubeApp.instance.tmdbRepository.search(query, page = 1)
                }
                loadingProgress.visibility = View.GONE
                if (results.isNotEmpty()) {
                    emptyText.visibility = View.GONE
                    posterAdapter.submitList(results)
                    posterGridView.scrollToPosition(0)
                    updateHeroSpotlight(results.first())
                } else {
                    posterAdapter.submitList(emptyList())
                    clearHeroSpotlight()
                    emptyText.text = "Ничего не найдено по запросу \"$query\""
                    emptyText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                loadingProgress.visibility = View.GONE
                emptyText.text = "Ошибка поиска: проверьте подключение к сети"
                emptyText.visibility = View.VISIBLE
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
                    mediaType = if (h.mediaType.equals("tv", ignoreCase = true)) MediaType.TV else MediaType.MOVIE,
                    watchPositionMs = h.positionMs,
                    watchDurationMs = h.durationMs,
                    lastStreamUrl = h.lastStreamUrl
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

        // Бейдж прогресса просмотра
        if (item.watchPositionMs > 5_000L) {
            badgeWatchProgress.visibility = View.VISIBLE
            badgeWatchProgress.text = "▶ ${formatTime(item.watchPositionMs)}"
        } else {
            badgeWatchProgress.visibility = View.GONE
        }

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

    override fun onResume() {
        super.onResume()
        if (::sideMenuAdapter.isInitialized && sideMenuAdapter.selectedCategory == NumCategory.HISTORY) {
            loadCategory(NumCategory.HISTORY)
        }
    }

    private fun checkBackgroundUpdate() {
        lifecycleScope.launch {
            val update = UpdateManager.checkUpdate(force = false)
            if (update != null && !isFinishing) {
                showUpdateDialog(update)
            }
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        if (isFinishing || isDestroyed) return
        try {
            val changelog = update.changelog ?: "Улучшения стабильности и новые возможности."
            AlertDialog.Builder(this)
                .setTitle("Доступна новая версия v${update.versionName}")
                .setMessage("Что нового:\n$changelog\n\nПерейти в Настройки для обновления?")
                .setPositiveButton("Обновить") { _, _ ->
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton("Позже", null)
                .show()
        } catch (e: Exception) {
            // Защита от WindowManager$BadTokenException при сворачивании активности
        }
    }

    private fun clearHeroSpotlight() {
        detailsFetchJob?.cancel()
        currentMediaItem = null
        heroBackdrop.setImageDrawable(null)
        heroTitle.text = ""
        badgeWatchProgress.visibility = View.GONE
        badgeAge.visibility = View.GONE
        badgeRating.visibility = View.GONE
        badgeYear.visibility = View.GONE
        badgeRuntime.visibility = View.GONE
        badgeOriginalTitle.visibility = View.GONE
        heroOverview.text = ""
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

    override fun onBackPressed() {
        // 1. Если фокус уже в боковом меню -> выход из приложения
        if (isViewDescendant(currentFocus, sideMenuContainer)) {
            finish()
            return
        }

        // 2. Если фокус в строке поиска или контейнере поиска -> фокус в боковое меню
        val searchContainer = findViewById<View>(R.id.search_container)
        if (currentFocus == searchEditText || (searchContainer != null && isViewDescendant(currentFocus, searchContainer))) {
            focusSideMenu()
            return
        }

        // 3. Если фокус в ленте постеров и позиция > 0 -> плавная перемотка ленты на 1-ю карточку (позиция 0)
        if (posterGridView.selectedPosition > 0) {
            posterGridView.setSelectedPositionSmooth(0)
            return
        }

        // 4. Если уже на 1-й карточке ленты (или в любом другом месте экрана) -> переход в боковое меню
        focusSideMenu()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
