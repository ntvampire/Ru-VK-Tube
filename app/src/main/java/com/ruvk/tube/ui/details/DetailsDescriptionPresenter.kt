package com.ruvk.tube.ui.details

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import com.ruvk.tube.domain.models.MediaItem

class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return

        viewHolder.title.text = mediaItem.title

        val subtitleParts = mutableListOf<String>()

        // Возрастной рейтинг с понятной расшифровкой для пользователя
        if (!mediaItem.ageRating.isNullOrBlank()) {
            val ageText = if (!mediaItem.ageRatingDescription.isNullOrBlank()) {
                "${mediaItem.ageRating} (${mediaItem.ageRatingDescription})"
            } else {
                mediaItem.ageRating
            }
            subtitleParts.add(ageText)
        }

        if (mediaItem.year > 0) subtitleParts.add(mediaItem.year.toString())
        if (mediaItem.voteAverage > 0) subtitleParts.add("★ ${String.format("%.1f", mediaItem.voteAverage)}")
        if (mediaItem.runtimeMinutes > 0) subtitleParts.add("${mediaItem.runtimeMinutes} мин")
        if (mediaItem.originalTitle.isNotBlank() && mediaItem.originalTitle != mediaItem.title) {
            subtitleParts.add(mediaItem.originalTitle)
        }

        viewHolder.subtitle.text = subtitleParts.joinToString("   •   ")
        viewHolder.body.text = mediaItem.overview
    }
}
