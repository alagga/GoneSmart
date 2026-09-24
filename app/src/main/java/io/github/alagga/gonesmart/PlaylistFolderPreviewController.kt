package io.github.alagga.gonesmart

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Debug-only, opt-in, read-only folder browser for native GMMP playlist views.
 *
 * Deliberately leaves zn3's native adapter, playlist click listeners, native
 * menus, playlist creation and io3 writes untouched. It overlays a small
 * Folders entrypoint and navigates the same virtual folder index on the
 * normal Playlists page and in the Add picker. This is a preview of the
 * hierarchy, not yet the final in-list folder renderer.
 */
internal class PlaylistFolderPreviewController {
    companion object {
        private const val TAG = "GoneSmartPlaylist"
    }

    private data class Settings(
        val enabled: Boolean = false,
        val groupExternal: Boolean = true,
        val groupRoot: Boolean = true
    )

    private data class Chip(
        val view: TextView,
        val root: FrameLayout,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val detachListener: View.OnAttachStateChangeListener
    )

    private var settings = Settings()
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val chips = WeakHashMap<ViewGroup, Chip>()
    private val observedPaths = linkedSetOf<String>()

    fun setOptions(
        enabled: Boolean,
        groupExternal: Boolean,
        groupRoot: Boolean
    ) {
        val next = Settings(enabled, groupExternal, groupRoot)
        if (settings == next) return
        settings = next
        val lists = knownLists.keys.toList()
        if (!enabled) {
            lists.forEach(::removeChip)
        } else {
            lists.forEach(::ensureChip)
        }
    }

    fun onNativeRecyclerObserved(view: View?) {
        val list = view as? ViewGroup ?: return
        if (resourceName(list) != "playlistListRecyclerView") return
        knownLists[list] = true
        if (settings.enabled) ensureChip(list)
    }

    private fun ensureChip(list: ViewGroup) {
        if (!BuildConfig.DEBUG || !settings.enabled ||
            chips.containsKey(list) || !list.isAttachedToWindow
        ) return

        val adapter = nativeAdapter(list)
        if (adapter?.javaClass?.name != "zn3") return

        val root = list.rootView as? FrameLayout ?: return
        val weakList = WeakReference(list)
        val button = TextView(list.context).apply {
            text = "▣ Folders · preview"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(list, 10), 0, dp(list, 10), 0)
            background = GradientDrawable().apply {
                setColor(0xFF34313C.toInt())
                cornerRadius = dp(list, 10).toFloat()
                setStroke(dp(list, 1), 0xFFA39AFF.toInt())
            }
            elevation = dp(list, 5).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                weakList.get()?.let(::openRoot)
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(list, 36),
            Gravity.TOP or Gravity.END
        )
        root.addView(button, params)
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            weakList.get()?.let(::positionChip)
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                weakList.get()?.let(::removeChip)
            }
        }
        chips[list] = Chip(button, root, layout, detach)
        list.addOnAttachStateChangeListener(detach)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layout)
        }
        positionChip(list)
        Log.i(TAG, "FOLDER PREVIEW | attached to " +
            if (isPicker(list)) "add-picker" else "playlists-tab")
    }

    private fun positionChip(list: ViewGroup) {
        val chip = chips[list] ?: return
        // GMMP can keep the normal page attached behind its add dialog.
        // In that case only the dialog's Folders chip should be visible.
        val coveredByPicker = !isPicker(list) && knownLists.keys.any {
            it !== list && isPicker(it) &&
                it.isAttachedToWindow && it.isShown
        }
        if (!settings.enabled || !list.isAttachedToWindow ||
            !list.isShown || coveredByPicker
        ) {
            chip.view.visibility = View.GONE
            return
        }
        observedPaths.addAll(currentlyVisiblePaths(list))
        val root = chip.root
        if (root.width == 0 || list.width == 0) return
        val listPosition = IntArray(2)
        val rootPosition = IntArray(2)
        list.getLocationOnScreen(listPosition)
        root.getLocationOnScreen(rootPosition)
        val top = (listPosition[1] - rootPosition[1] + dp(list, 7))
            .coerceAtLeast(0)
        val right = (
            root.width -
                (listPosition[0] - rootPosition[0] + list.width) +
                dp(list, 8)
            ).coerceAtLeast(0)
        val params = chip.view.layoutParams as? FrameLayout.LayoutParams
            ?: return
        if (params.topMargin != top || params.rightMargin != right) {
            params.topMargin = top
            params.rightMargin = right
            chip.view.layoutParams = params
        }
        chip.view.visibility = View.VISIBLE
    }

    private fun removeChip(list: ViewGroup) {
        val chip = chips.remove(list) ?: return
        list.removeOnAttachStateChangeListener(chip.detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.removeOnGlobalLayoutListener(
                chip.layoutListener
            )
        }
        chip.root.removeView(chip.view)
    }

    private fun openRoot(list: ViewGroup) {
        if (!settings.enabled || !list.isAttachedToWindow) return

        val adapter = nativeAdapter(list)
        if (adapter?.javaClass?.name != "zn3") return
        val itemCount = runCatching {
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrDefault(-1)
        // GMMP's zn3.i0()/t23.r() exposes section headers, not
        // the full playlist model list. Inspect only native adapter
        // backing fields and standard read-only getItem(int).
        val native = NativePlaylistSourceInspector.inspect(adapter, itemCount)
        for (trace in native.traces) {
            Log.i(TAG, "FOLDER NATIVE TRACE | " + trace)
        }
        val nativePaths = (native.paths + nativePlaylistPaths(adapter)).distinct()
        observedPaths.addAll(currentlyVisiblePaths(list))
        val paths = (nativePaths + observedPaths).distinct()
        val root = PlaylistRootLocator.infer(
            paths,
            Environment.getExternalStorageDirectory().absolutePath
        )
        val externalNative = if (root == null) -1 else {
            val prefix = root.trimEnd('/') + "/"
            nativePaths.count { !it.startsWith(prefix) }
        }
        Log.i(
            TAG,
            "FOLDER NATIVE SOURCE | adapterRows=" + itemCount +
                " | nativeModels=" + nativePaths.size +
                " | externalNative=" + externalNative +
                " | visibleCached=" + observedPaths.size +
                " | visited=" + native.visitedObjects +
                " | truncated=" + native.truncated +
                " | filesystemScan=false"
        )
        if (root == null) {
            Toast.makeText(
                list.context,
                "Playlist root not yet verified from GMMP's native entries.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // Native lists may contain a section header. Mark partial unless
        // nearly all native adapter rows have corresponding playlist models.
        val nativeComplete = itemCount > 0 &&
            nativePaths.size >= itemCount - 1 && !native.truncated
        val index = PlaylistFolderIndex.build(
            nativePlaylistPaths = paths,
            mainPlaylistDirectory = root,
            groupExternalLocations = settings.groupExternal,
            groupRootPlaylists = settings.groupRoot
        )
        showFolder(
            list = list,
            index = index,
            mainRoot = root,
            folder = null,
            parents = emptyList(),
            complete = nativeComplete
        )
    }

    private fun showFolder(
        list: ViewGroup,
        index: PlaylistFolderIndex.Result,
        mainRoot: String,
        folder: PlaylistFolderIndex.Folder?,
        parents: List<PlaylistFolderIndex.Folder?>,
        complete: Boolean
    ) {
        if (!list.isAttachedToWindow) return
        val folders = folder?.children ?: index.topLevelFolders
        val playlists = folder?.playlists ?: index.ungroupedPlaylists
        val choices = folders.map { child ->
            (if (child.virtual) "◇ " else "▣ ") + child.name +
                " ›"
        } + playlists.map { playlist ->
            "♫ " + playlist.name
        }
        val title = when {
            folder == null -> "Playlist folders · preview"
            folder.virtual -> folder.name
            else -> folder.name
        }
        val displayTitle = if (complete) title else "$title · partial"
        val dialog = AlertDialog.Builder(list.context)
            .setTitle(displayTitle)
            .setItems(
                if (choices.isEmpty()) arrayOf("This folder is empty")
                else choices.toTypedArray()
            ) { _, selected ->
                when {
                    choices.isEmpty() -> Unit
                    selected < folders.size -> {
                        showFolder(
                            list, index, mainRoot, folders[selected],
                            parents + listOf(folder), complete
                        )
                    }
                    else -> {
                        val playlist = playlists[selected - folders.size]
                        showPlaylistLocation(
                            list, playlist, mainRoot, complete
                        )
                    }
                }
            }
            // Android's AlertDialog can hide setItems when setMessage is
            // also supplied. Keep the folder/playlist choices visible.
            .setNegativeButton(
                if (parents.isEmpty()) "Close" else "Back"
            ) { _, _ ->
                if (parents.isNotEmpty()) {
                    showFolder(
                        list, index, mainRoot, parents.last(),
                        parents.dropLast(1), complete
                    )
                }
            }
            .create()
        dialog.show()
    }

    private fun showPlaylistLocation(
        list: ViewGroup,
        playlist: PlaylistFolderIndex.Playlist,
        mainRoot: String,
        complete: Boolean
    ) {
        val location = when (playlist.location) {
            PlaylistFolderIndex.Location.MAIN_ROOT -> "Main playlist root"
            PlaylistFolderIndex.Location.MAIN_SUBFOLDER ->
                playlist.path.removePrefix(mainRoot).substringBeforeLast('/')
                    .trimStart('/')
            PlaylistFolderIndex.Location.EXTERNAL -> "External location"
        }
        AlertDialog.Builder(list.context)
            .setTitle(playlist.name)
            .setMessage(
                "Location: $location\n\n" +
                    "This preview does not alter playlists or replace " +
                    "GMMP's native selection and creation controls." +
                    if (complete) "" else
                        "\n\nThe native playlist snapshot is not yet complete."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun nativeAdapter(list: ViewGroup): Any? = runCatching {
        list.javaClass.getMethod("getAdapter").invoke(list)
    }.getOrNull()

    private fun nativePlaylistPaths(adapter: Any): List<String> {
        val groups = runCatching {
            adapter.javaClass.getMethod("i0").invoke(adapter) as? List<*>
        }.getOrNull() ?: return emptyList()
        return groups.flatMap { group ->
            if (group?.javaClass?.name != "t23") {
                emptyList()
            } else {
                val members = runCatching {
                    group.javaClass.getMethod("r").invoke(group) as? List<*>
                }.getOrNull().orEmpty()
                members.mapNotNull(::modelPath)
            }
        }.distinct()
    }

    private fun currentlyVisiblePaths(list: ViewGroup): List<String> {
        val holderMethod = runCatching {
            list.javaClass.getMethod("getChildViewHolder", View::class.java)
        }.getOrNull() ?: return emptyList()
        return (0 until list.childCount).mapNotNull { index ->
            val holder = runCatching {
                holderMethod.invoke(list, list.getChildAt(index))
            }.getOrNull()
            val model = holder?.let {
                runCatching {
                    it.javaClass.getDeclaredField("A").apply {
                        isAccessible = true
                    }.get(it)
                }.getOrNull()
            }
            modelPath(model)
        }
    }

    private fun modelPath(value: Any?): String? {
        if (value?.javaClass?.name != "xn3") return null
        return runCatching {
            value.javaClass.getDeclaredField("q").apply {
                isAccessible = true
            }.get(value) as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun isPicker(list: ViewGroup): Boolean =
        (list.parent as? View)?.javaClass?.simpleName
            ?.contains("Coordinator") == true

    private fun dp(view: View, value: Int): Int =
        (view.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun resourceName(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")
}
