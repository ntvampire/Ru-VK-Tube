package com.ruvk.tube.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruvk.tube.R

enum class NumCategory(val title: String) {
    TRENDING("В тренде"),
    MOVIES("Фильмы"),
    SERIES("Сериалы"),
    FAVORITES("Избранное"),
    HISTORY("История")
}

data class MenuItemData(
    val category: NumCategory,
    val icon: String,
    val title: String
)

class NumSideMenuAdapter(
    private val onItemSelected: (NumCategory) -> Unit,
    private val onMenuFocusChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<NumSideMenuAdapter.MenuViewHolder>() {

    val items = listOf(
        MenuItemData(NumCategory.TRENDING, "🔥", "В тренде"),
        MenuItemData(NumCategory.MOVIES, "🎬", "Фильмы"),
        MenuItemData(NumCategory.SERIES, "📺", "Сериалы"),
        MenuItemData(NumCategory.FAVORITES, "❤️", "Избранное"),
        MenuItemData(NumCategory.HISTORY, "🕒", "История")
    )

    var selectedCategory: NumCategory = NumCategory.TRENDING
        private set

    var isExpanded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, items.size)
            }
        }

    fun setSelected(category: NumCategory) {
        if (selectedCategory != category) {
            val oldIndex = items.indexOfFirst { it.category == selectedCategory }
            selectedCategory = category
            val newIndex = items.indexOfFirst { it.category == selectedCategory }
            if (oldIndex != -1) notifyItemChanged(oldIndex)
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_num_side_menu, parent, false)
        return MenuViewHolder(view)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(items[position], items[position].category == selectedCategory, isExpanded)
    }

    override fun getItemCount(): Int = items.size

    inner class MenuViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView.findViewById(R.id.menu_item_container)
        private val iconView: TextView = itemView.findViewById(R.id.menu_icon)
        private val titleView: TextView = itemView.findViewById(R.id.menu_title)

        fun bind(item: MenuItemData, isSelected: Boolean, expanded: Boolean) {
            iconView.text = item.icon
            titleView.text = item.title
            container.isSelected = isSelected
            titleView.alpha = if (expanded) 1.0f else 0.0f

            container.setOnFocusChangeListener { _, hasFocus ->
                onMenuFocusChanged(hasFocus)
            }

            container.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    setSelected(item.category)
                    onItemSelected(item.category)
                }
            }
        }
    }
}
