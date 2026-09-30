package io.github.alagga.gonesmart

import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout

/**
 * Navigation-layout bridge shared by both folder surfaces.
 *
 * GMMP's classic navigation embeds list fragments below a CoordinatorLayout.
 * Its top-tabs mode instead puts the fragment roots directly in a legacy
 * ViewPager. In that mode an ordinary extra child would be mistaken for a
 * pager page, so GoneSmart installs the folder overlay as a reflected
 * ViewPager decor child while keeping all interaction inside the pager's
 * content bounds.
 */
internal object PlaylistNavigationSurfaceHost {
    private const val TAG = "GoneSmartPlaylist"

    internal fun isPagerClassName(name: String): Boolean =
        name.contains("ViewPager", ignoreCase = true)

    internal fun isOverlayContainerClassName(name: String): Boolean =
        name.contains("CoordinatorLayout", ignoreCase = true) ||
            name.contains("ConstraintLayout", ignoreCase = true) ||
            name.contains("RelativeLayout", ignoreCase = true) ||
            (
                name.contains("FrameLayout", ignoreCase = true) &&
                    !name.contains("FragmentContainerView", ignoreCase = true)
                )

    /**
     * Prefer the existing page-local overlay container. If tabs expose the
     * RecyclerView directly to ViewPager, return that pager and use a decor
     * LayoutParams in addOverlay().
     */
    fun resolve(list: ViewGroup): ViewGroup? {
        var parent = list.parent as? ViewGroup
        var fallback: ViewGroup? = null
        while (parent != null && parent !== list.rootView) {
            val name = parent.javaClass.name
            if (name.contains("CoordinatorLayout", ignoreCase = true)) {
                return parent
            }
            if (fallback == null &&
                (
                    parent is FrameLayout ||
                        parent is RelativeLayout ||
                        isOverlayContainerClassName(name)
                    ) &&
                !name.contains("FragmentContainerView", ignoreCase = true)
            ) {
                fallback = parent
            }
            if (isPagerClassName(name)) {
                return fallback ?: parent
            }
            parent = parent.parent as? ViewGroup
        }
        return fallback
    }

    fun isPagerHost(host: ViewGroup): Boolean =
        isPagerClassName(host.javaClass.name)

    /**
     * Adds the overlay without changing ViewPager's native page accounting.
     */
    fun addOverlay(
        host: ViewGroup,
        list: View,
        overlay: View,
        width: Int,
        height: Int
    ): Boolean {
        if (!isPagerHost(host)) {
            val contentChild = directChildInHost(list, host)
            val insertAt = if (contentChild == null) {
                host.childCount
            } else {
                (host.indexOfChild(contentChild) + 1)
                    .coerceAtMost(host.childCount)
            }
            host.addView(
                overlay,
                insertAt,
                ViewGroup.LayoutParams(width, height)
            )
            return true
        }

        return runCatching {
            val pagerClass = generateSequence<Class<*>>(host.javaClass) {
                it.superclass
            }
                .firstOrNull { isPagerClassName(it.name) }
                ?: error("ViewPager class unavailable")
            val layoutParamsClass = pagerClass.declaredClasses
                .firstOrNull {
                    ViewGroup.LayoutParams::class.java.isAssignableFrom(it) &&
                        it.simpleName == "LayoutParams"
                }
                ?: error("ViewPager.LayoutParams unavailable")
            val params = layoutParamsClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as ViewGroup.LayoutParams
            params.width = width
            params.height = height

            val isDecor = generateSequence<Class<*>>(layoutParamsClass) {
                it.superclass
            }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull {
                    it.name == "isDecor" &&
                        it.type == Boolean::class.javaPrimitiveType
                } ?: error("ViewPager isDecor field unavailable")
            isDecor.isAccessible = true
            isDecor.setBoolean(params, true)

            generateSequence<Class<*>>(layoutParamsClass) {
                it.superclass
            }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull {
                    it.name == "gravity" &&
                        it.type == Int::class.javaPrimitiveType
                }
                ?.let {
                    it.isAccessible = true
                    it.setInt(params, Gravity.TOP or Gravity.START)
                }

            host.addView(overlay, params)
            true
        }.onFailure {
            Log.w(
                TAG,
                "FOLDER NAV HOST | ViewPager decor overlay unavailable",
                it
            )
        }.getOrDefault(false)
    }

    /**
     * Returns null outside ViewPager. Inside tabs, require the fragment page
     * containing this list to occupy most of the pager viewport.
     */
    fun isPagerPageFront(list: View): Boolean? {
        var child: View = list
        var parent = child.parent as? ViewGroup
        while (parent != null && parent !== list.rootView) {
            if (isPagerHost(parent)) {
                val pageRect = Rect()
                val pagerRect = Rect()
                val pageVisible = child.getGlobalVisibleRect(pageRect)
                val pagerVisible = parent.getGlobalVisibleRect(pagerRect)
                if (!pageVisible || !pagerVisible ||
                    pagerRect.width() <= 0 || pagerRect.height() <= 0
                ) return false
                return pageRect.width() >= pagerRect.width() / 2 &&
                    pageRect.height() >= pagerRect.height() / 2
            }
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    fun parentChain(list: View): String {
        val entries = arrayListOf<String>()
        var current: View? = list
        repeat(10) {
            val view = current ?: return@repeat
            val id = if (NativeResourceIdPolicy.canResolveEntryName(view.id)) {
                runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrDefault("")
            } else ""
            entries += view.javaClass.simpleName +
                if (id.isBlank()) "" else "#$id"
            current = view.parent as? View
        }
        return entries.joinToString(" > ")
    }

    private fun directChildInHost(
        list: View,
        host: ViewGroup
    ): View? {
        var node: View = list
        while (node.parent != null && node.parent !== host) {
            node = node.parent as? View ?: return null
        }
        return node.takeIf { it.parent === host }
    }
}
