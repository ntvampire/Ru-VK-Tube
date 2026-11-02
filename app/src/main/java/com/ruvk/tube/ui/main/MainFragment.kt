package com.ruvk.tube.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.ui.details.DetailsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()

    private val trendingAdapter = ArrayObjectAdapter(cardPresenter)
    private val popularMoviesAdapter = ArrayObjectAdapter(cardPresenter)
    private val popularTvAdapter = ArrayObjectAdapter(cardPresenter)
    private val historyAdapter = ArrayObjectAdapter(cardPresenter)
    private val favoritesAdapter = ArrayObjectAdapter(cardPresenter)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupEventListeners()
        loadData()
    }

    private fun setupUI() {
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        // Цвет панели навигации - темный, акцент поиска - фирменный красный
        brandColor = ContextCompat.getColor(requireContext(), R.color.surface_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        // Заранее добавляем ряды, чтобы интерфейс имел структуру сразу
        rowsAdapter.add(ListRow(HeaderItem(0, getString(R.string.category_trending)), trendingAdapter))
        rowsAdapter.add(ListRow(HeaderItem(1, getString(R.string.category_popular)), popularMoviesAdapter))
        rowsAdapter.add(ListRow(HeaderItem(2, getString(R.string.category_series)), popularTvAdapter))
        rowsAdapter.add(ListRow(HeaderItem(3, getString(R.string.category_history)), historyAdapter))
        rowsAdapter.add(ListRow(HeaderItem(4, getString(R.string.category_favorites)), favoritesAdapter))

        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                val intent = Intent(requireContext(), DetailsActivity::class.java).apply {
                    putExtra(DetailsActivity.EXTRA_MEDIA_ITEM, item)
                }
                startActivity(intent)
            }
        }
    }

    private fun loadData() {
        // Загрузка каталогов TMDB
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val trending = RuVkTubeApp.instance.tmdbRepository.getTrending()
                trending.forEach { trendingAdapter.add(it) }
            } catch (e: Exception) {
                Log.e("RuVkTube", "Failed to load trending", e)
            }

            try {
                val movies = RuVkTubeApp.instance.tmdbRepository.getPopularMovies()
                movies.forEach { popularMoviesAdapter.add(it) }
            } catch (e: Exception) {
                Log.e("RuVkTube", "Failed to load popular movies", e)
            }

            try {
                val series = RuVkTubeApp.instance.tmdbRepository.getPopularTv()
                series.forEach { popularTvAdapter.add(it) }
            } catch (e: Exception) {
                Log.e("RuVkTube", "Failed to load popular series", e)
            }
        }

        // Подписка на локальную историю просмотров (Room Flow)
        viewLifecycleOwner.lifecycleScope.launch {
            RuVkTubeApp.instance.database.historyDao().getAllHistory().collectLatest { list ->
                historyAdapter.clear()
                list.forEach { h ->
                    historyAdapter.add(
                        MediaItem(
                            id = h.tmdbId,
                            title = h.title,
                            originalTitle = h.title,
                            overview = "",
                            posterUrl = h.posterUrl,
                            backdropUrl = h.backdropUrl,
                            releaseDate = "",
                            year = 0,
                            voteAverage = 0.0,
                            mediaType = if (h.mediaType == "tv") MediaType.TV else MediaType.MOVIE
                        )
                    )
                }
            }
        }

        // Подписка на локальное избранное (Room Flow)
        viewLifecycleOwner.lifecycleScope.launch {
            RuVkTubeApp.instance.database.favoritesDao().getAllFavorites().collectLatest { list ->
                favoritesAdapter.clear()
                list.forEach { f ->
                    favoritesAdapter.add(
                        MediaItem(
                            id = f.tmdbId,
                            title = f.title,
                            originalTitle = f.title,
                            overview = "",
                            posterUrl = f.posterUrl,
                            backdropUrl = null,
                            releaseDate = "",
                            year = f.year,
                            voteAverage = f.rating,
                            mediaType = if (f.mediaType == "tv") MediaType.TV else MediaType.MOVIE
                        )
                    )
                }
            }
        }
    }
}
