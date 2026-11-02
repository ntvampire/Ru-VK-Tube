package com.ruvk.tube.ui.details

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import com.ruvk.tube.domain.models.MediaItem

class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return

        viewHolder.title.text = mediaItem.title

        val subtitleParts = mutableListOf<String>()

        // Возрастной рейтинг: для цифровых (18+, 16+, 12+, 6+, 0+) выводится кратко без расшифровки
        if (!mediaItem.ageRating.isNullOrBlank()) {
            val rating = mediaItem.ageRating.trim()
            val isDigitalRating = rating.matches(Regex("""^\d+\+?$"""))
            val ageText = if (isDigitalRating || mediaItem.ageRatingDescription.isNullOrBlank()) {
                if (rating.all { it.isDigit() }) "$rating+" else rating
            } else {
                "$rating (${mediaItem.ageRatingDescription})"
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
