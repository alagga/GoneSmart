package io.github.alagga.gonesmart

import android.content.Context
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Phase 1: opt-in, NON-DESTRUCTIVE integration diagnostics for GMMP 4.2.0.
 *
 * We verified the exact native menu XMLs and item IDs in GMMP's APK.
 * Do not reorder thousands of native queue entries, replace the playing
 * song, or trigger playlist playback until the native queue write path and
 * asynchronous context-menu dispatch have been checked on-device.
 *
 * Follow-up phase: use the captured native queue and native Play action
 * to implement the actual inversion. No direct writes to GMMP's SQLite DB.
 */
internal class QueueFlipController {
    companion object {
        private const val TAG = "GoneSmartFlip"

        // Our own non-resource ID; GMMP resource IDs begin with 0x7f.
        private const val FLIP_ACTION_ID = 0x47534601

        private const val QUEUE_MENU = "menu_gm_queue"
        private const val PLAYLIST_MENU = "menu_gm_context_playlist_list"
        private const val PLAYLIST_DETAIL_MENU = "menu_gm_context_playlist"
        private const val SMART_MENU = "menu_gm_context_smart"
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var nativeQueue: WeakReference<Any>? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        Log.i(TAG, "FLIP SETTINGS | enabled=$value | phase=menu-diagnostics")
    }

    fun captureNativeQueue(candidate: Any?) {
        if (candidate?.javaClass?.name != "ex3") return
        nativeQueue = WeakReference(candidate)
    }

    fun onMenuInflated(
        menuResId: Int,
        menu: Menu?,
        inflater: Any?
    ) {
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: return
        if (context.packageName != "gonemad.gmmp") return

        val menuName = runCatching {
            context.resources.getResourceEntryName(menuResId)
        }.getOrNull() ?: return

        val kind = when (menuName) {
            QUEUE_MENU -> Kind.QUEUE
            PLAYLIST_MENU, PLAYLIST_DETAIL_MENU -> Kind.PLAYLIST
            SMART_MENU -> Kind.SMART
            else -> return
        }

        val existing = menu.findItem(FLIP_ACTION_ID)
        if (!enabled) {
            existing?.isVisible = false
            return
        }
        if (existing != null) {
            existing.isVisible = true
            return
        }

        val anchorName = when (kind) {
            Kind.QUEUE -> "menuRemoveDuplicates"
            Kind.PLAYLIST, Kind.SMART -> "menuContextShuffle"
        }
        val anchorId = context.resources.getIdentifier(
            anchorName,
            "id",
            context.packageName
        )
        val anchor = if (anchorId != 0) menu.findItem(anchorId) else null
        val nativePlayId = context.resources.getIdentifier(
            "menuContextPlay",
            "id",
            context.packageName
        )
        val nativePlay = if (nativePlayId != 0) {
            menu.findItem(nativePlayId)
        } else {
            null
        }

        // There is no localized "reverse/flip queue" string in GMMP
        // 4.2.0. Compose its existing localized Play/Queue text with
        // a direction symbol and our tiny white sparkle. These are
        // presentation labels for the first menu-placement test.
        val baseLabel = when (kind) {
            Kind.QUEUE -> nativeString(context, "queue") ?: "Queue"
            Kind.PLAYLIST, Kind.SMART ->
                nativePlay?.title?.toString()
                    ?: nativeString(context, "play")
                    ?: "Play"
        }
        val title = "$baseLabel ⇵ ✦"

        val item = menu.add(
            Menu.NONE,
            FLIP_ACTION_ID,
            anchor?.order ?: Menu.NONE,
            title
        )
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        item.setOnMenuItemClickListener {
            onFlipPressed(kind, context, menu, menuName, nativePlayId)
            true
        }

        val placed = if (anchor != null) {
            moveRelativeToAnchor(
                menu = menu,
                inserted = item,
                anchor = anchor,
                before = kind == Kind.QUEUE
            )
        } else {
            false
        }

        Log.i(
            TAG,
            "FLIP MENU | name=$menuName | " +
                "kind=$kind | anchorFound=${anchor != null} | " +
                "positioned=$placed | playFound=${nativePlay != null} | " +
                "menuClass=${menu.javaClass.name}"
        )
        Log.i(
            TAG,
            "FLIP ITEMS | " +
                (0 until menu.size()).joinToString(" | ") { index ->
                    val child = menu.getItem(index)
                    "$index:${child.title}#${child.itemId}"
                }
        )
    }

    private enum class Kind {
        QUEUE,
        PLAYLIST,
        SMART
    }

    private fun onFlipPressed(
        kind: Kind,
        context: Context,
        menu: Menu,
        menuName: String,
        nativePlayId: Int
    ) {
        Log.i(
            TAG,
            "FLIP CLICK | menu=$menuName | kind=$kind | " +
                "nativePlay=${if (nativePlayId != 0)
                    menu.findItem(nativePlayId)?.title else null}"
        )
        logNativeQueueSnapshot()

        // Phase 1 intentionally does not invoke a native Play item.
        // Doing so before a matching queue-change callback is identified
        // could reverse the previous queue instead of the new playlist.
        Toast.makeText(
            context,
            "GoneSmart Flip preview: diagnostics logged; queue unchanged.",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * GMMP's native queue is ex3 and its DAO is ex3.r (tx3/xx3).
     * tx3.H1() returns ey3 entries; ey3.a is the visible position,
     * ey3.b is song ID and ey3.d is the unique queue entry ID.
     * We only READ and log a few entries, even for very long queues.
     */
    private fun logNativeQueueSnapshot() {
        val queue = nativeQueue?.get()
        if (queue == null) {
            Log.w(TAG, "FLIP QUEUE | native ex3 not captured yet")
            return
        }

        runCatching {
            val current = queue.javaClass
                .getDeclaredMethod("D").apply { isAccessible = true }
                .invoke(queue) as? Int
            val dao = field(queue, "r")
            val raw = dao?.javaClass
                ?.getMethod("H1")
                ?.invoke(dao) as? List<*>

            if (raw == null) {
                Log.w(TAG, "FLIP QUEUE | native DAO snapshot unavailable")
                return@runCatching
            }

            val sorted = raw.filterNotNull().sortedBy {
                (field(it, "a") as? Number)?.toInt()
                    ?: Int.MAX_VALUE
            }
            fun describe(entry: Any): String {
                return "position=${field(entry, "a")} " +
                    "trackId=${field(entry, "b")} " +
                    "entryId=${field(entry, "d")} " +
                    "shuffle=${field(entry, "c")}"
            }

            Log.i(
                TAG,
                "FLIP QUEUE | size=${sorted.size} | " +
                    "currentPosition=$current | " +
                    "queueClass=${queue.javaClass.name}"
            )
            sorted.take(3).forEachIndexed { i, entry ->
                Log.i(TAG, "FLIP FIRST[$i] | ${describe(entry)}")
            }
            sorted.takeLast(3).forEachIndexed { i, entry ->
                Log.i(TAG, "FLIP LAST[$i] | ${describe(entry)}")
            }
        }.onFailure {
            Log.e(TAG, "FLIP QUEUE | snapshot failed", it)
        }
    }

    private fun nativeString(context: Context, name: String): String? {
        val res = context.resources.getIdentifier(
            name,
            "string",
            context.packageName
        )
        return if (res != 0) {
            runCatching { context.getString(res) }.getOrNull()
        } else {
            null
        }
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
        val fromMenu = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? Context
        }.getOrNull()
        if (fromMenu != null) return fromMenu

        if (inflater is MenuInflater) {
            return field(inflater, "mContext") as? Context
        }
        return null
    }

    private fun field(instance: Any, name: String): Any? {
        var cls: Class<*>? = instance.javaClass
        while (cls != null && cls != Any::class.java) {
            val current = cls
            val resolved = runCatching {
                current.getDeclaredField(name).apply {
                    isAccessible = true
                }.get(instance)
            }
            if (resolved.isSuccess) return resolved.getOrNull()
            cls = cls.superclass
        }
        return null
    }

    /**
     * GMMP's AppCompat MenuBuilder retains item order in mItems.
     * When available, place the new entry exactly after Shuffle or
     * immediately before Remove duplicates. Native items are otherwise
     * unchanged and Menu.add's normal order is left as a fallback.
     */
    private fun moveRelativeToAnchor(
        menu: Menu,
        inserted: MenuItem,
        anchor: MenuItem,
        before: Boolean
    ): Boolean {
        val items = field(menu, "mItems") as? MutableList<*> ?: return false
        @Suppress("UNCHECKED_CAST")
        val editable = items as MutableList<Any>
        val originalIndex = editable.indexOf(inserted)
        if (originalIndex < 0 || editable.indexOf(anchor) < 0) return false
        return runCatching {
            editable.removeAt(originalIndex)
            val anchorIndex = editable.indexOf(anchor)
            editable.add(
                if (before) anchorIndex else anchorIndex + 1,
                inserted
            )
            true
        }.getOrDefault(false)
    }
}
