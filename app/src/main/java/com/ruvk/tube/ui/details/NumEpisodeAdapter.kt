package com.ruvk.tube.ui.details

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
import com.ruvk.tube.data.db.EpisodeProgressEntity
import com.ruvk.tube.domain.models.TvEpisode

class NumEpisodeAdapter(
    var defaultBackdropUrl: String? = null,
    private val onEpisodeClicked: (TvEpisode) -> Unit
) : RecyclerView.Adapter<NumEpisodeAdapter.EpisodeViewHolder>() {

    private val items = mutableListOf<TvEpisode>()
    private var progressMap = mapOf<Int, EpisodeProgressEntity>()

    fun submitList(newItems: List<TvEpisode>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setProgressList(list: List<EpisodeProgressEntity>) {
        progressMap = list.associateBy { it.episode }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: FrameLayout = itemView.findViewById(R.id.episode_card_root)
        private val stillImage: ImageView = itemView.findViewById(R.id.episode_still)
        private val badgeText: TextView = itemView.findViewById(R.id.episode_badge)
        private val durationText: TextView = itemView.findViewById(R.id.episode_duration)
        private val titleText: TextView = itemView.findViewById(R.id.episode_title)
        private val watchProgressBar: ProgressBar = itemView.findViewById(R.id.episode_watch_progress)

        init {
            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            val roundOutline = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            cardRoot.outlineProvider = roundOutline
            cardRoot.clipToOutline = true

            cardRoot.setOnFocusChangeListener { view, hasFocus ->
                titleText.isSelected = hasFocus
                if (hasFocus) {
                    view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                    view.elevation = 8f
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    view.elevation = 0f
                }
            }

            cardRoot.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    onEpisodeClicked(items[pos])
                }
            }
        }

        fun bind(episode: TvEpisode) {
            val progress = progressMap[episode.episodeNumber]
            if (progress != null && progress.positionMs > 5_000L) {
                badgeText.text = "${episode.episodeNumber} сер • ${formatTime(progress.positionMs)}"
                val duration = if (progress.durationMs > 30_000L) {
                    progress.durationMs
                } else if (episode.durationMinutes > 0) {
                    episode.durationMinutes * 60_000L
                } else {
                    0L
                }
                val progressVal = if (duration > 0) {
                    ((progress.positionMs.toDouble() / duration) * 1000).toInt().coerceIn(20, 1000)
                } else {
                    300
                }
                watchProgressBar.progress = progressVal
                watchProgressBar.visibility = View.VISIBLE
            } else {
                badgeText.text = "${episode.episodeNumber} серия"
                watchProgressBar.visibility = View.GONE
            }

            titleText.text = episode.name

            if (episode.durationMinutes > 0) {
                durationText.visibility = View.VISIBLE
                durationText.text = "${episode.durationMinutes} мин"
            } else {
                durationText.visibility = View.GONE
            }

            val imageUrl = episode.stillUrl ?: defaultBackdropUrl
            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            stillImage.load(imageUrl) {
                crossfade(200)
                placeholder(R.color.surface_card)
                error(R.color.surface_card)
                transformations(RoundedCornersTransformation(radiusPx))
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
    }
}
