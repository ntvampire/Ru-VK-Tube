package com.ruvk.tube.ui.details

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ruvk.tube.R
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.db.FavoriteEntity
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType
import com.ruvk.tube.domain.models.VideoSource
import com.ruvk.tube.player.ExternalPlayerLauncher
import com.ruvk.tube.player.InternalPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailsFragment : DetailsSupportFragment() {

    private lateinit var mediaItem: MediaItem
    private lateinit var backgroundController: DetailsSupportFragmentBackgroundController
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var actionAdapter: ArrayObjectAdapter
    private val sourcesAdapter = ArrayObjectAdapter(SourceCardPresenter())

    private var isFavorite = false
    private val actionWatch = Action(ACTION_WATCH, "Смотреть")
    private val actionTrailer = Action(ACTION_TRAILER, "Трейлер")
    private val actionFavorite = Action(ACTION_FAVORITE, "В избранное")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        mediaItem = arguments?.getSerializable(EXTRA_MEDIA_ITEM) as? MediaItem
            ?: throw IllegalArgumentException("MediaItem required")

        backgroundController = DetailsSupportFragmentBackgroundController(this)
        backgroundController.enableParallax()

        setupPresenter()
        setupEventListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadDetails()
        checkFavoriteStatus()
    }

    private fun setupPresenter() {
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter()).apply {
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.surface_dark)
            initialState = FullWidthDetailsOverviewRowPresenter.STATE_FULL
        }

        val presenterSelector = ClassPresenterSelector().apply {
            addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
            addClassPresenter(ListRow::class.java, ListRowPresenter())
        }

        rowsAdapter = ArrayObjectAdapter(presenterSelector)
        adapter = rowsAdapter

        val detailsRow = DetailsOverviewRow(mediaItem)
        actionAdapter = ArrayObjectAdapter().apply {
            add(actionWatch)
            add(actionTrailer)
            add(actionFavorite)
        }
        detailsRow.actionsAdapter = actionAdapter
        rowsAdapter.add(detailsRow)

        // Строка найденных источников
        rowsAdapter.add(ListRow(HeaderItem(1, "Источники видео"), sourcesAdapter))
    }

    private fun setupEventListeners() {
        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Action -> handleActionClick(item)
                is VideoSource -> startPlayback(item)
            }
        }
    }

    private fun handleActionClick(action: Action) {
        when (action.id) {
            ACTION_WATCH -> {
                if (sourcesAdapter.size() > 0) {
                    val firstSource = sourcesAdapter.get(0) as VideoSource
                    startPlayback(firstSource)
                } else {
                    searchFullVideoSources()
                }
            }
            ACTION_TRAILER -> {
                searchAndPlayTrailer()
            }
            ACTION_FAVORITE -> {
                toggleFavorite()
            }
        }
    }

    private fun searchFullVideoSources() {
        Toast.makeText(requireContext(), getString(R.string.searching_sources), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val sources = RuVkTubeApp.instance.searchCoordinator.findMovieSources(
                title = mediaItem.title,
                year = mediaItem.year,
                runtimeMinutes = mediaItem.runtimeMinutes
            )

            sourcesAdapter.clear()
            if (sources.isNotEmpty()) {
                sources.forEach { sourcesAdapter.add(it) }
                startPlayback(sources.first())
            } else {
                Toast.makeText(requireContext(), getString(R.string.no_sources_found), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchAndPlayTrailer() {
        Toast.makeText(requireContext(), "Поиск трейлера...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val trailers = RuVkTubeApp.instance.searchCoordinator.findTrailers(
                title = mediaItem.title,
                year = mediaItem.year
            )

            if (trailers.isNotEmpty()) {
                val trailer = trailers.first()
                startPlayback(trailer)
            } else {
                Toast.makeText(requireContext(), "Русский трейлер не найден", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPlayback(source: VideoSource) {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val useExternal = prefs.getBoolean("use_external_player", false)

        if (useExternal) {
            ExternalPlayerLauncher.launch(
                context = requireContext(),
                streamUrl = source.streamUrl,
                title = "${mediaItem.title} (${source.quality})"
            )
        } else {
            val intent = Intent(requireContext(), InternalPlayerActivity::class.java).apply {
                putExtra(InternalPlayerActivity.EXTRA_STREAM_URL, source.streamUrl)
                putExtra(InternalPlayerActivity.EXTRA_TITLE, mediaItem.title)
                putExtra(InternalPlayerActivity.EXTRA_TMDB_ID, mediaItem.id)
                putExtra(InternalPlayerActivity.EXTRA_QUALITY, "${source.platform.name} • ${source.quality}")
                putExtra(InternalPlayerActivity.EXTRA_IS_TRAILER, source.isTrailer)
            }
            startActivity(intent)
        }
    }

    private fun toggleFavorite() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = RuVkTubeApp.instance.database.favoritesDao()
            if (isFavorite) {
                dao.delete(mediaItem.id)
                isFavorite = false
            } else {
                dao.insert(
                    FavoriteEntity(
                        tmdbId = mediaItem.id,
                        title = mediaItem.title,
                        posterUrl = mediaItem.posterUrl,
                        mediaType = if (mediaItem.mediaType == MediaType.TV) "tv" else "movie",
                        year = mediaItem.year,
                        rating = mediaItem.voteAverage
                    )
                )
                isFavorite = true
            }
            withContext(Dispatchers.Main) {
                updateFavoriteActionLabel()
            }
        }
    }

    private fun checkFavoriteStatus() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            isFavorite = RuVkTubeApp.instance.database.favoritesDao().isFavorite(mediaItem.id)
            withContext(Dispatchers.Main) {
                updateFavoriteActionLabel()
            }
        }
    }

    private fun updateFavoriteActionLabel() {
        actionFavorite.label1 = if (isFavorite) "В избранном ★" else "В избранное"
        actionAdapter.notifyItemRangeChanged(2, 1)
    }

    private fun loadDetails() {
        // Фоновая подгрузка постера и задника
        val backdrop = mediaItem.backdropUrl ?: mediaItem.posterUrl
        if (!backdrop.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val loader = ImageLoader(requireContext())
                val request = ImageRequest.Builder(requireContext())
                    .data(backdrop)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        backgroundController.coverBitmap = bitmap
                    }
                }
            }
        }

        // Подгрузка хронометража, если он 0
        if (mediaItem.runtimeMinutes == 0) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val fullItem = if (mediaItem.mediaType == MediaType.TV) {
                        RuVkTubeApp.instance.tmdbRepository.getTvDetails(mediaItem.id)
                    } else {
                        RuVkTubeApp.instance.tmdbRepository.getMovieDetails(mediaItem.id)
                    }
                    mediaItem = fullItem
                    val row = rowsAdapter.get(0) as? DetailsOverviewRow
                    row?.item = fullItem
                } catch (e: Exception) {
                    // Игнорируем сетевые ошибки для надежности
                }
            }
        }
    }

    companion object {
        const val EXTRA_MEDIA_ITEM = "extra_media_item"
        private const val ACTION_WATCH = 1L
        private const val ACTION_TRAILER = 2L
        private const val ACTION_FAVORITE = 3L

        fun newInstance(item: MediaItem) = DetailsFragment().apply {
            arguments = Bundle().apply {
                putSerializable(EXTRA_MEDIA_ITEM, item)
            }
        }
    }
}
