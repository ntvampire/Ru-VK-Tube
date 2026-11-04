package com.ruvk.tube.ui.details

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.TvEpisode

class NumEpisodeAdapter(
    var defaultBackdropUrl: String? = null,
    private val onEpisodeClicked: (TvEpisode) -> Unit
) : RecyclerView.Adapter<NumEpisodeAdapter.EpisodeViewHolder>() {

    private val items = mutableListOf<TvEpisode>()

    fun submitList(newItems: List<TvEpisode>) {
        items.clear()
        items.addAll(newItems)
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
            badgeText.text = "${episode.episodeNumber} серия"
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
    }
}
