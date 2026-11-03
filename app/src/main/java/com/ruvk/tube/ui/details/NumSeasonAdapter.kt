package com.ruvk.tube.ui.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.TvSeason

class NumSeasonAdapter(
    private val onSeasonSelected: (TvSeason) -> Unit
) : RecyclerView.Adapter<NumSeasonAdapter.SeasonViewHolder>() {

    private val items = mutableListOf<TvSeason>()
    var selectedSeasonNumber: Int = 1
        private set

    fun submitList(newItems: List<TvSeason>, initialSelectedSeason: Int = 1) {
        items.clear()
        items.addAll(newItems)
        selectedSeasonNumber = if (newItems.any { it.seasonNumber == initialSelectedSeason }) {
            initialSelectedSeason
        } else {
            newItems.firstOrNull()?.seasonNumber ?: 1
        }
        notifyDataSetChanged()
    }

    fun setSelectedSeason(seasonNumber: Int) {
        if (selectedSeasonNumber != seasonNumber) {
            selectedSeasonNumber = seasonNumber
            notifyDataSetChanged()
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
                        selectedSeasonNumber = season.seasonNumber
                        notifyDataSetChanged()
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
