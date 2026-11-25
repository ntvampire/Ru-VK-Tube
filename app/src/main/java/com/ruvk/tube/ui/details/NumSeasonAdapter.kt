package com.ruvk.tube.ui.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.TvSeason
import com.ruvk.tube.ui.common.dispatchListDiff

class NumSeasonAdapter(
    private val onSeasonSelected: (TvSeason) -> Unit
) : RecyclerView.Adapter<NumSeasonAdapter.SeasonViewHolder>() {

    private val items = mutableListOf<TvSeason>()
    var selectedSeasonNumber: Int = 1
        private set

    fun submitList(newItems: List<TvSeason>, initialSelectedSeason: Int = 1) {
        val prevSelected = selectedSeasonNumber
        val nextSelected = if (newItems.any { it.seasonNumber == initialSelectedSeason }) {
            initialSelectedSeason
        } else {
            newItems.firstOrNull()?.seasonNumber ?: 1
        }
        selectedSeasonNumber = nextSelected
        dispatchListDiff(
            target = items,
            newItems = newItems,
            areItemsSame = { old, new -> old.seasonNumber == new.seasonNumber }
        )
        if (prevSelected != nextSelected) {
            val prevIdx = items.indexOfFirst { it.seasonNumber == prevSelected }
            val nextIdx = items.indexOfFirst { it.seasonNumber == nextSelected }
            if (prevIdx != -1) notifyItemChanged(prevIdx)
            if (nextIdx != -1) notifyItemChanged(nextIdx)
        }
    }

    fun setSelectedSeason(seasonNumber: Int) {
        if (selectedSeasonNumber != seasonNumber) {
            val prevIdx = items.indexOfFirst { it.seasonNumber == selectedSeasonNumber }
            selectedSeasonNumber = seasonNumber
            val nextIdx = items.indexOfFirst { it.seasonNumber == seasonNumber }
            if (prevIdx != -1) notifyItemChanged(prevIdx)
            if (nextIdx != -1) notifyItemChanged(nextIdx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_pill, parent, false)
        return SeasonViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeasonViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SeasonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pillText: TextView = itemView.findViewById(R.id.season_pill_text)

        init {
            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    val season = items[pos]
                    if (selectedSeasonNumber != season.seasonNumber) {
                        setSelectedSeason(season.seasonNumber)
                        onSeasonSelected(season)
                    }
                }
            }
        }

        fun bind(season: TvSeason) {
            pillText.text = season.name
            itemView.isSelected = (season.seasonNumber == selectedSeasonNumber)
        }
    }
}
