package com.ruvk.tube.ui.main

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.MediaItem

class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.surface_dark))
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = mediaItem.title
        cardView.contentText = if (mediaItem.year > 0) "${mediaItem.year} • ★ ${String.format("%.1f", mediaItem.voteAverage)}" else "★ ${String.format("%.1f", mediaItem.voteAverage)}"

        if (!mediaItem.posterUrl.isNullOrBlank()) {
            cardView.mainImageView.load(mediaItem.posterUrl) {
                crossfade(true)
                placeholder(R.color.surface_dark)
                error(R.color.surface_dark)
            }
        } else {
            cardView.mainImage = null
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 190
        private const val CARD_HEIGHT = 285
    }
}
