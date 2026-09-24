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
import java.security.MessageDigest
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
    private val observedTitles = linkedMapOf<String, String>()
    private val snapshotFingerprints = linkedMapOf<String, String>()

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
        observedTitles.putAll(currentlyVisibleTitles(list))
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
        val native = NativePlaylistSourceInspector.inspect(adapter, itemCount)
        val nativePaths = (native.paths + nativePlaylistPaths(adapter)).distinct()
        observedPaths.addAll(currentlyVisiblePaths(list))
        observedTitles.putAll(currentlyVisibleTitles(list))

        // Names are always keyed by the native xn3.q path, never filenames.
        // Use bound native text views to validate which xn3 metadata field
        // contains the title; the winning field then covers unseen rows.
        val resolvedTitles = NativePlaylistTitleResolver.resolve(
            native.models, observedTitles
        )
        val sample = native.models
            .filter { observedTitles.containsKey(it.path) }
            .take(3)
        for (model in sample) {
            val fields = model.textFields.entries
                .filter { it.key != "q" }
                .take(12)
                .joinToString(";") { entry ->
                    entry.key + "=" + entry.value.replace("\n", " ").take(64)
                }
            Log.i(
                TAG,
                "FOLDER TITLE MODEL | path=" + model.path +
                    " | gmmpRow=" + observedTitles[model.path] +
                    " | modelFields=" + fields
            )
        }
        Log.i(
            TAG,
            "FOLDER TITLE SOURCE | chosenField=" +
                (resolvedTitles.chosenField ?: "none") +
                " | matchedVisible=" + resolvedTitles.observedLabels +
                " | nativeTitles=" + resolvedTitles.nativeTitles +
                " | fallbackFilenames=" + resolvedTitles.filenameFallbacks +
                " | modelCount=" + native.models.size +
                " | candidateFields=" + resolvedTitles.candidates
                    .take(5).joinToString(";") {
                        it.field + ":match=" + it.matches +
                            ",mismatch=" + it.mismatches +
                            ",distinctive=" + it.distinctiveMatches +
                            ",coverage=" + it.coverage
                    }
        )

        val root = PlaylistRootLocator.infer(
            nativePaths + observedPaths,
            Environment.getExternalStorageDirectory().absolutePath
        )
        val rootCount = if (root == null) -1 else {
            val prefix = root.trimEnd('/') + "/"
            nativePaths.count { it.startsWith(prefix) }
        }
        val externalCount = if (rootCount < 0) -1
            else nativePaths.size - rootCount
        val nativeComplete =
            itemCount > 0 && nativePaths.size == itemCount && !native.truncated
        val surface = if (isPicker(list)) "add-picker" else "playlists-tab"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(nativePaths.sorted().joinToString("\n").toByteArray())
            .take(6).joinToString("") { byte ->
                (byte.toInt() and 255).toString(16).padStart(2, '0')
            }
        val opposite = if (surface == "add-picker") "playlists-tab"
            else "add-picker"
        val oppositeDigest = snapshotFingerprints[opposite]
        snapshotFingerprints[surface] = digest
        Log.i(
            TAG,
            "FOLDER NATIVE SOURCE | surface=" + surface +
                " | adapterRows=" + itemCount +
                " | nativeModels=" + nativePaths.size +
                " | internalNative=" + rootCount +
                " | externalNative=" + externalCount +
                " | visibleCached=" + observedPaths.size +
                " | visited=" + native.visitedObjects +
                " | truncated=" + native.truncated +
                " | complete=" + nativeComplete +
                " | fingerprint=" + digest +
                " | sameAsOtherSurface=" +
                    (oppositeDigest?.let { it == digest } ?: "unknown") +
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
        // Cached rows are only a fallback for an incomplete native dataset.
        // Otherwise an old visible row could reintroduce a deleted playlist.
        val paths = if (nativeComplete) nativePaths
            else (nativePaths + observedPaths).distinct()
        val index = PlaylistFolderIndex.build(
            nativePlaylistPaths = paths,
            mainPlaylistDirectory = root,
            groupExternalLocations = settings.groupExternal,
            groupRootPlaylists = settings.groupRoot,
            displayNamesByPath = resolvedTitles.names + observedTitles
        )
        // Exercise all four grouping combinations using one device test.
        // This does not modify settings or GMMP's native playlist adapter.
        for (external in listOf(false, true)) {
            for (groupRoot in listOf(false, true)) {
                val candidate = PlaylistFolderIndex.build(
                    nativePlaylistPaths = paths,
                    mainPlaylistDirectory = root,
                    groupExternalLocations = external,
                    groupRootPlaylists = groupRoot,
                    displayNamesByPath = resolvedTitles.names + observedTitles
                )
                fun countFolder(folder: PlaylistFolderIndex.Folder): Int =
                    folder.playlists.size +
                        folder.children.sumOf(::countFolder)
                val nested = candidate.folders.sumOf(::countFolder)
                val grouped = candidate.otherLocations?.playlists?.size ?: 0
                val ungrouped = candidate.ungroupedPlaylists.size
                Log.i(
                    TAG,
                    "FOLDER GROUP MATRIX | external=" + external +
                        " | root=" + groupRoot +
                        " | nested=" + nested +
                        " | other=" + grouped +
                        " | loose=" + ungrouped +
                        " | total=" + (nested + grouped + ungrouped) +
                        " | native=" + paths.size
                )
            }
        }
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

    /** Read GMMP's actual rendered playlist title from visible native rows. */
    private fun currentlyVisibleTitles(list: ViewGroup): Map<String, String> {
        val holderMethod = runCatching {
            list.javaClass.getMethod("getChildViewHolder", View::class.java)
        }.getOrNull() ?: return emptyMap()
        val found = linkedMapOf<String, String>()
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) ?: continue
            val holder = runCatching {
                holderMethod.invoke(list, row)
            }.getOrNull() ?: continue
            val model = runCatching {
                holder.javaClass.getDeclaredField("A").apply {
                    isAccessible = true
                }.get(holder)
            }.getOrNull() ?: continue
            val path = modelPath(model) ?: continue
            val title = visiblePlaylistTitle(row) ?: continue
            found[path] = title
        }
        return found
    }

    private fun visiblePlaylistTitle(row: View): String? {
        val candidates = arrayListOf<Pair<String, Float>>()
        fun collect(view: View, depth: Int) {
            if (depth > 6 || candidates.size >= 32 ||
                view.visibility != View.VISIBLE
            ) return
            if (view is TextView) {
                val title = view.text?.toString()?.trim().orEmpty()
                if (title.length in 1..250 && title.any(Char::isLetterOrDigit) &&
                    !title.startsWith("/") && !title.contains("://")
                ) {
                    candidates.add(title to view.textSize)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    collect(view.getChildAt(index), depth + 1)
                }
            }
        }
        collect(row, 0)
        return candidates.maxByOrNull { it.second }?.first
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
