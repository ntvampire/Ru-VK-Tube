package com.ruvk.tube.ui.details

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.VideoPlatform
import com.ruvk.tube.domain.models.VideoSource

class SourceCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_source_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val source = item as? VideoSource ?: return
        val platformView = viewHolder.view.findViewById<TextView>(R.id.source_platform)
        val qualityView = viewHolder.view.findViewById<TextView>(R.id.source_quality)
        val titleView = viewHolder.view.findViewById<TextView>(R.id.source_title)

        platformView.text = if (source.platform == VideoPlatform.RUTUBE) "Rutube" else "VK"
        platformView.setBackgroundColor(if (source.platform == VideoPlatform.RUTUBE) 0xFF0077FF.toInt() else 0xFF2787F5.toInt())

        qualityView.text = if (source.voiceover != null) "${source.quality} • ${source.voiceover}" else source.quality
        titleView.text = source.title
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
