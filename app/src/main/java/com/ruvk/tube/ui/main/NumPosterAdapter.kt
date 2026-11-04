package com.ruvk.tube.ui.main

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.MediaItem
import com.ruvk.tube.domain.models.MediaType

class NumPosterAdapter(
    private val onItemFocused: (MediaItem) -> Unit,
    private val onItemClicked: (MediaItem) -> Unit
) : RecyclerView.Adapter<NumPosterAdapter.PosterViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendList(newItems: List<MediaItem>) {
        if (newItems.isEmpty()) return
        val existingIds = items.map { it.id }.toSet()
        val uniqueNew = newItems.filter { it.id !in existingIds }
        if (uniqueNew.isEmpty()) return
        val startPos = items.size
        items.addAll(uniqueNew)
        notifyItemRangeInserted(startPos, uniqueNew.size)
    }

    fun getItemAt(position: Int): MediaItem? {
        return items.getOrNull(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_num_poster, parent, false)
        return PosterViewHolder(view)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PosterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardContainer: FrameLayout = itemView.findViewById(R.id.card_container)
        private val posterImage: ImageView = itemView.findViewById(R.id.poster_image)
        private val posterTitleFallback: TextView = itemView.findViewById(R.id.poster_title_fallback)
        private val ratingBadge: TextView = itemView.findViewById(R.id.poster_rating_badge)
        private val episodeBadge: TextView = itemView.findViewById(R.id.poster_episode_badge)
        private val watchProgressBar: ProgressBar = itemView.findViewById(R.id.poster_watch_progress)

        init {
            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            val roundOutline = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            cardContainer.outlineProvider = roundOutline
            cardContainer.clipToOutline = true
            posterImage.outlineProvider = roundOutline
            posterImage.clipToOutline = true
        }

        fun bind(item: MediaItem) {
            posterTitleFallback.text = item.title
            val hasPoster = !item.posterUrl.isNullOrBlank()
            posterTitleFallback.visibility = if (hasPoster) View.GONE else View.VISIBLE

            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            posterImage.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.color.surface_card)
                error(R.color.surface_card)
                transformations(RoundedCornersTransformation(radiusPx))
                listener(
                    onError = { _, _ ->
                        posterTitleFallback.visibility = View.VISIBLE
                    },
                    onSuccess = { _, _ ->
                        posterTitleFallback.visibility = View.GONE
                    }
                )
            }

            if (item.voteAverage > 0) {
                ratingBadge.visibility = View.VISIBLE
                ratingBadge.text = String.format("%.1f", item.voteAverage)
            } else {
                ratingBadge.visibility = View.GONE
            }

            // Номер серии для сериалов
            if (item.mediaType == MediaType.TV && item.lastSeason > 0 && item.lastEpisode > 0) {
                episodeBadge.visibility = View.VISIBLE
                episodeBadge.text = "S${item.lastSeason}E${item.lastEpisode}"
            } else {
                episodeBadge.visibility = View.GONE
            }

            // Полоса прогресса просмотра фильма или серии
            if (item.watchPositionMs > 5_000L) {
                val duration = if (item.watchDurationMs > 30_000L) {
                    item.watchDurationMs
                } else if (item.runtimeMinutes > 0) {
                    item.runtimeMinutes * 60_000L
                } else {
                    0L
                }
                val progress = if (duration > 0) {
                    ((item.watchPositionMs.toDouble() / duration) * 1000).toInt().coerceIn(20, 1000)
                } else {
                    300
                }
                watchProgressBar.progress = progress
                watchProgressBar.visibility = View.VISIBLE
            } else {
                watchProgressBar.visibility = View.GONE
            }

            cardContainer.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    cardContainer.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationZ(8f)
                        .setDuration(150)
                        .start()
                    onItemFocused(item)
                } else {
                    cardContainer.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(150)
                        .start()
                }
            }

            cardContainer.setOnClickListener {
                onItemClicked(item)
            }
        }
    }
}
