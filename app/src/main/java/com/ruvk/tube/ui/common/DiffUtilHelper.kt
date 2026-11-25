package com.ruvk.tube.ui.common

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Синхронный расчёт и применение DiffUtil-обновлений списка без мерцания карточек
 * и без потери согласованности позиций сразу после возврата из submitList.
 */
fun <T> RecyclerView.Adapter<*>.dispatchListDiff(
    target: MutableList<T>,
    newItems: List<T>,
    areItemsSame: (old: T, new: T) -> Boolean,
    areContentsSame: (old: T, new: T) -> Boolean = { old, new -> old == new }
) {
    val oldItems = target.toList()
    val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size
        override fun getNewListSize(): Int = newItems.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return areItemsSame(oldItems[oldItemPosition], newItems[newItemPosition])
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return areContentsSame(oldItems[oldItemPosition], newItems[newItemPosition])
        }
    })
    target.clear()
    target.addAll(newItems)
    diff.dispatchUpdatesTo(this)
}
