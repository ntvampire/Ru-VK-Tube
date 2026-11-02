package com.ruvk.tube.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruvk.tube.R

enum class NumCategory(val title: String) {
    TRENDING("🔥 В тренде"),
    MOVIES("🎬 Фильмы"),
    SERIES("📺 Сериалы"),
    FAVORITES("❤️ Избранное"),
    HISTORY("🕒 История")
}

class NumCategoryAdapter(
    private val onCategorySelected: (NumCategory) -> Unit
) : RecyclerView.Adapter<NumCategoryAdapter.CategoryViewHolder>() {

    private val categories = NumCategory.values().toList()
    var selectedPosition = 0
        private set

    fun setSelected(category: NumCategory) {
        val index = categories.indexOf(category)
        if (index != -1 && index != selectedPosition) {
            val old = selectedPosition
            selectedPosition = index
            notifyItemChanged(old)
            notifyItemChanged(selectedPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_num_tab, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tabText: TextView = itemView.findViewById(R.id.tab_text)

        fun bind(category: NumCategory, isSelected: Boolean) {
            tabText.text = category.title
            tabText.isSelected = isSelected

            tabText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    tabText.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
                } else {
                    tabText.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }

            tabText.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && selectedPosition != pos) {
                    val old = selectedPosition
                    selectedPosition = pos
                    notifyItemChanged(old)
                    notifyItemChanged(selectedPosition)
                    onCategorySelected(category)
                }
            }
        }
    }
}
