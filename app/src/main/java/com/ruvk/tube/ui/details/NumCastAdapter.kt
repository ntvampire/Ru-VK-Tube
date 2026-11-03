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
import com.ruvk.tube.domain.models.CastMember

class NumCastAdapter : RecyclerView.Adapter<NumCastAdapter.CastViewHolder>() {

    private val items = mutableListOf<CastMember>()

    fun submitList(newItems: List<CastMember>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast_member, parent, false)
        return CastViewHolder(view)
    }

    override fun onBindViewHolder(holder: CastViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CastViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.cast_container)
        private val photoCard: FrameLayout = itemView.findViewById(R.id.cast_photo_card)
        private val photo: ImageView = itemView.findViewById(R.id.cast_photo)
        private val name: TextView = itemView.findViewById(R.id.cast_name)
        private val character: TextView = itemView.findViewById(R.id.cast_character)

        init {
            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            val roundOutline = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            photoCard.outlineProvider = roundOutline
            photoCard.clipToOutline = true
            photo.outlineProvider = roundOutline
            photo.clipToOutline = true

            container.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    container.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
                } else {
                    container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }

        fun bind(item: CastMember) {
            name.text = item.name
            character.text = if (item.character.isNotBlank()) item.character else "В роли себя"

            val radiusPx = itemView.context.resources.displayMetrics.density * 10f
            if (!item.profileUrl.isNullOrBlank()) {
                photo.load(item.profileUrl) {
                    crossfade(true)
                    placeholder(R.color.surface_card)
                    error(R.drawable.ic_actor_placeholder)
                    transformations(RoundedCornersTransformation(radiusPx))
                }
            } else {
                photo.setImageResource(R.drawable.ic_actor_placeholder)
            }
        }
    }
}
