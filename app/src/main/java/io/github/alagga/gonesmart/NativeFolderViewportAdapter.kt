package io.github.alagga.gonesmart

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Only create a native XML row when AndroidX binds a visible/prefetched
 * position. Holder containers are recycled; row actions still capture the
 * exact current native model. No original GMMP adapter is mutated.
 */
internal class NativeFolderViewportAdapter(
    private val onDiscard: (String, View) -> Unit,
    private val onAttached: (String, View) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : RecyclerView.Adapter<NativeFolderViewportAdapter.Holder>() {
    data class Entry(val key: String, val create: () -> View)

    class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var key: String? = null
        var row: View? = null
    }

    private var entries: List<Entry> = emptyList()
    private var failed = false

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

    private fun discard(holder: Holder) {
        val key = holder.key
        val row = holder.row
        if (key != null && row != null) onDiscard(key, row)
        holder.frame.removeAllViews()
        holder.key = null
        holder.row = null
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        discard(holder)
        if (failed) return
        val entry = entries.getOrNull(position) ?: return
        runCatching {
            val row = entry.create()
            val height = row.layoutParams?.height
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
            holder.frame.addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height
            ))
            holder.key = entry.key
            holder.row = row
        }.onFailure {
            failed = true
            onFailure(it)
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val key = holder.key ?: return
        val row = holder.row ?: return
        onAttached(key, row)
    }

    override fun onViewRecycled(holder: Holder) {
        discard(holder)
        super.onViewRecycled(holder)
    }

    fun submit(next: List<Entry>, animateInsertions: Boolean) {
        val old = entries
        entries = next.toList()
        if (!animateInsertions) {
            // A folder switch is a new dataset, not an insertion of every
            // row. Do not retain stale holders as removal animations.
            notifyDataSetChanged()
            return
        }
        // Preserve the accepted small same-folder insertion animation,
        // using RecyclerView's own layout/animator rather than fabricated
        // holders and manually assigned translations.
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = entries.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                old[oldItemPosition].key == entries[newItemPosition].key
            // Rebind existing closures to the current native model snapshot.
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = false
        }, false).dispatchUpdatesTo(this)
    }
}
