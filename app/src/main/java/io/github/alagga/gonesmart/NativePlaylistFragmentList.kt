package io.github.alagga.gonesmart

import android.view.View
import android.view.ViewGroup

/**
 * Called exactly once per original GMMP Fragment.performCreateView(), before
 * FragmentManager attaches/draws the page. The caller also verifies which
 * original GMMP tab fragment owns this root (op3 / ls4 on 4.2.1).
 *
 * Resource identity + ORIGINAL host RecyclerView superclass are required.
 * An ordinary module RecyclerView or a similarly named arbitrary View
 * must not become an early mask target.
 */
internal object NativePlaylistFragmentList {
    fun find(root: View, resourceName: String): ViewGroup? {
        val resourceId = root.resources.getIdentifier(
            resourceName, "id", root.context.packageName
        )
        if (resourceId == 0) return null
        val list = root.findViewById<View>(resourceId) as? ViewGroup
            ?: return null
        if (list.id != resourceId) return null
        val nativeRecycler = generateSequence<Class<*>>(list.javaClass) {
            it.superclass
        }.any { it.name == "androidx.recyclerview.widget.RecyclerView" }
        return list.takeIf { nativeRecycler }
    }
}
