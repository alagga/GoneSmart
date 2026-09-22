package io.github.alagga.gonesmart

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.WeakHashMap

/**
 * Temporary, read-only diagnostics for GMMP 4.2.0's Add-to-Playlist picker.
 *
 * Deliberately does not replace click listeners or write to playlists.
 * Compile-time gated behind BuildConfig.DEBUG in GoneSmartModule.
 */
internal object PlaylistDiagnosticReporter {

    private const val TAG = "GoneSmartPlaylist"

    private val observedFabs =
        Collections.newSetFromMap(
            WeakHashMap<View, Boolean>()
        )

    private val observedLists =
        Collections.newSetFromMap(
            WeakHashMap<View, Boolean>()
        )

    private val lastFabState =
        WeakHashMap<View, String>()

    private val lastListState =
        WeakHashMap<View, String>()

    @Synchronized
    fun observeFab(view: View) {
        if (!observedFabs.add(view)) {
            reportFabState(view)
            return
        }

        Log.i(
            TAG,
            "FAB FOUND | class=${view.javaClass.name}" +
                " | id=${resourceName(view)}" +
                " | clickListener=${listenerName(view, "mOnClickListener")}" +
                " | longClickListener=${listenerName(view, "mOnLongClickListener")}"
        )

        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnGlobalLayoutListener {
                reportFabState(view)
            }
            observer.addOnScrollChangedListener {
                reportFabState(view)
            }
        }

        reportFabState(view)
    }

    @Synchronized
    private fun reportFabState(view: View) {
        val rect = Rect()
        val onScreen = view.getGlobalVisibleRect(rect)
        val clickListener = listenerName(view, "mOnClickListener")
        val state = (
            "shown=${view.isShown}" +
                " | onScreen=$onScreen" +
                " | visibility=${view.visibility}" +
                " | alpha=${"%.2f".format(java.util.Locale.US, view.alpha)}" +
                " | translationY=${view.translationY.toInt()}" +
                " | clickListener=$clickListener"
            )

        if (lastFabState[view] == state) {
            return
        }

        lastFabState[view] = state
        Log.i(TAG, "FAB STATE | $state")
    }

    @Synchronized
    fun observeList(view: View) {
        if (!observedLists.add(view)) {
            reportListState(view)
            return
        }

        Log.i(
            TAG,
            "LIST FOUND | class=${view.javaClass.name}" +
                " | id=${resourceName(view)}"
        )

        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnGlobalLayoutListener {
                reportListState(view)
            }
        }

        reportListState(view)
    }

    @Synchronized
    private fun reportListState(view: View) {
        val adapter = runCatching {
            view.javaClass
                .getMethod("getAdapter")
                .invoke(view)
        }.getOrNull()

        val childCount = (view as? ViewGroup)?.childCount ?: -1

        val state = (
            "adapter=${adapter?.javaClass?.name ?: "none"}" +
                " | visibleRows=$childCount"
            )

        if (lastListState[view] == state) {
            return
        }

        lastListState[view] = state

        Log.i(
            TAG,
            "LIST STATE | $state"
        )

        if (adapter != null) {
            val methods = adapter.javaClass.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name }
                .distinct()
                .take(25)

            Log.i(
                TAG,
                "ADAPTER METHODS | ${adapter.javaClass.name} | $methods"
            )
        }

        val firstRow =
            (view as? ViewGroup)?.getChildAt(0)
                ?: return

        Log.i(
            TAG,
            "FIRST ROW | class=${firstRow.javaClass.name}" +
                " | id=${resourceName(firstRow)}" +
                " | clickListener=${listenerName(firstRow, "mOnClickListener")}" +
                " | longClickListener=${listenerName(firstRow, "mOnLongClickListener")}"
        )

        val children = firstRow as? ViewGroup ?: return
        for (index in 0 until minOf(3, children.childCount)) {
            val child = children.getChildAt(index)

            Log.i(
                TAG,
                "ROW CHILD $index | class=${child.javaClass.name}" +
                    " | id=${resourceName(child)}" +
                    " | clickListener=${listenerName(child, "mOnClickListener")}"
            )
        }
    }

    /**
     * Only inspect listener *types*. No listener is replaced or invoked.
     * Reflection on Android framework internals can be restricted, so errors
     * are reduced to a short marker instead of crashing GMMP.
     */
    private fun listenerName(
        view: View,
        fieldName: String
    ): String {
        return try {
            val listenerInfoField =
                View::class.java.getDeclaredField("mListenerInfo")

            listenerInfoField.isAccessible = true
            val info = listenerInfoField.get(view)
                ?: return "none"

            val field = info.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true

            field.get(info)?.javaClass?.name ?: "none"
        } catch (_: Throwable) {
            "unavailable"
        }
    }

    private fun resourceName(view: View): String {
        if (view.id == View.NO_ID) {
            return "no-id"
        }

        return runCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrDefault(view.id.toString())
    }

    fun event(message: String) {
        Log.i(TAG, message)
    }
}
