package io.github.alagga.gonesmart

import androidx.recyclerview.widget.DiffUtil

/** The native Files presenter interleaves equal empty-path separators. */
internal object NativeQuickNavTransaction {
    data class Item(val metadataIdentity: String?)
    fun items(segments: List<String>): List<Item> = buildList {
        segments.forEachIndexed { index, identity ->
            if (index > 0) add(Item(null))
            add(Item(identity))
        }
    }

    /** Verified wg1$f/qg1 callback: equality, no move detection. */
    fun calculate(old: List<String>, next: List<String>): DiffUtil.DiffResult {
        val oldItems = items(old)
        val newItems = items(next)
        return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition] == newItems[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                areItemsTheSame(oldItemPosition, newItemPosition)
        }, false)
    }
}
