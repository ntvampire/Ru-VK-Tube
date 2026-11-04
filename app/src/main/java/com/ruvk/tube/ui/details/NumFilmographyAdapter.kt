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
import com.ruvk.tube.domain.models.MediaItem

class NumFilmographyAdapter(
    private val onMovieClicked: (MediaItem) -> Unit
) : RecyclerView.Adapter<NumFilmographyAdapter.FilmographyViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilmographyViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filmography_card, parent, false)
        return FilmographyViewHolder(view)
    }

    override fun onBindViewHolder(holder: FilmographyViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FilmographyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.filmography_card_root)
        private val posterCard: FrameLayout = itemView.findViewById(R.id.filmography_poster_card)
        private val posterImage: ImageView = itemView.findViewById(R.id.filmography_poster)
        private val ratingBadge: TextView = itemView.findViewById(R.id.filmography_rating)
        private val titleText: TextView = itemView.findViewById(R.id.filmography_title)
        private val yearText: TextView = itemView.findViewById(R.id.filmography_year)

        init {
            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            val roundOutline = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            posterCard.outlineProvider = roundOutline
            posterCard.clipToOutline = true
            posterImage.outlineProvider = roundOutline
            posterImage.clipToOutline = true

            cardRoot.setOnFocusChangeListener { view, hasFocus ->
                titleText.isSelected = hasFocus
                if (hasFocus) {
                    view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }

            cardRoot.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    onMovieClicked(items[pos])
                }
            }
        }

        fun bind(item: MediaItem) {
            titleText.text = item.title

            if (item.year > 0) {
                yearText.visibility = View.VISIBLE
                yearText.text = item.year.toString()
            } else {
                yearText.visibility = View.GONE
            }

            if (item.voteAverage > 0) {
                ratingBadge.visibility = View.VISIBLE
                ratingBadge.text = String.format("%.1f", item.voteAverage)
            } else {
                ratingBadge.visibility = View.GONE
            }

            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            posterImage.load(item.posterUrl) {
                crossfade(200)
                placeholder(R.color.surface_card)
                error(R.color.surface_card)
                transformations(RoundedCornersTransformation(radiusPx))
            }
        }
    }
}
