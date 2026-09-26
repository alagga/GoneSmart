package io.github.alagga.gonesmart

/**
 * Exact public final update methods on the original R8-obfuscated
 * GMMP 4.2.0 androidx.recyclerview.widget.RecyclerView$h Adapter.
 * Verified against the maintainer-supplied APK; version-specific.
 *
 * Only observe real native notifications, never synthesize any of them.
 */
internal object NativePlaylistAdapterEventPolicy {
    val nativeMethods = listOf(
        "notifyDataSetChanged" to 0,
        "notifyItemInserted" to 1,
        "notifyItemRangeInserted" to 2,
        "notifyItemRemoved" to 1,
        "notifyItemRangeRemoved" to 2
    )

    fun needsRefresh(renderedCount: Int, nativeCount: Int): Boolean =
        nativeCount > 0 && nativeCount != renderedCount
}
