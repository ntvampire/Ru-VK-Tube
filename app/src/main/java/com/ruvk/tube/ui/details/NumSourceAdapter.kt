package com.ruvk.tube.ui.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.VideoPlatform
import com.ruvk.tube.domain.models.VideoSource

class NumSourceAdapter(
    private val onItemClicked: (VideoSource) -> Unit
) : RecyclerView.Adapter<NumSourceAdapter.SourceViewHolder>() {

    private val items = mutableListOf<VideoSource>()

    fun submitList(newItems: List<VideoSource>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<VideoSource> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_source_card, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener {
            onItemClicked(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val platformView: TextView = itemView.findViewById(R.id.source_platform)
        private val qualityView: TextView = itemView.findViewById(R.id.source_quality)
        private val durationView: TextView? = itemView.findViewById(R.id.source_duration)
        private val titleView: TextView = itemView.findViewById(R.id.source_title)

        fun bind(source: VideoSource) {
            val isRutube = source.platform == VideoPlatform.RUTUBE
            platformView.text = if (isRutube) "Rutube" else "VK Видео"

            val bg = ContextCompat.getDrawable(itemView.context, R.drawable.bg_source_platform)?.mutate()
            bg?.setTint(if (isRutube) 0xFFE52D27.toInt() else 0xFF2787F5.toInt())
            platformView.background = bg

            val qualityText = if (!source.voiceover.isNullOrBlank()) {
                "${source.quality} • ${source.voiceover}"
            } else {
                source.quality
            }
            qualityView.text = qualityText

            if (durationView != null) {
                if (source.durationSeconds > 0) {
                    val hours = source.durationSeconds / 3600
                    val minutes = (source.durationSeconds % 3600) / 60
                    val seconds = source.durationSeconds % 60
                    durationView.visibility = View.VISIBLE
                    durationView.text = if (hours > 0) {
                        String.format("%d:%02d:%02d", hours, minutes, seconds)
                    } else {
                        String.format("%d:%02d", minutes, seconds)
                    }
                } else {
                    durationView.visibility = View.GONE
                }
            }

            titleView.text = source.title
        }
    }
}
