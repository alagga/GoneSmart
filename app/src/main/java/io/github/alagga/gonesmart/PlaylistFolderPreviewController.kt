package io.github.alagga.gonesmart

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class PlaylistFolderPreviewController(
    private val multiSelect: PlaylistMultiSelectController
) {
    companion object {
        private const val TAG = "GoneSmartPlaylist"
        private const val MAX_ATTACH_RETRIES = 20
        private const val ATTACH_RETRY_MS = 150L
    }

    private data class Settings(
        val enabled: Boolean = false,
        val groupExternal: Boolean = true,
        val groupRoot: Boolean = true
    )

    private data class Browser(
        val list: ViewGroup,
        val parent: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val rootPath: String,
        val index: PlaylistFolderIndex.Result,
        val modelsByPath: Map<String, Any>,
        val originalAlpha: Float,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val detachListener: View.OnAttachStateChangeListener,
        var currentFolderId: String? = null
    )

    private var settings = Settings()
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val pendingRetries = WeakHashMap<ViewGroup, Int>()
    private var activeBrowser = WeakReference<ViewGroup>(null)

    fun setOptions(
        enabled: Boolean,
        groupExternal: Boolean,
        groupRoot: Boolean
    ) {
        val next = Settings(enabled, groupExternal, groupRoot)
        if (settings == next) return
        val groupingChanged =
            settings.groupExternal != next.groupExternal ||
                settings.groupRoot != next.groupRoot
        settings = next

        if (!enabled) {
            browsers.keys.toList().forEach(::removeBrowser)
            pendingRetries.clear()
            return
        }

        if (groupingChanged) {
            browsers.keys.toList().forEach(::removeBrowser)
        }
        knownLists.keys.toList().forEach { scheduleAttach(it, 0) }
    }

    fun onNativeRecyclerObserved(view: View?) {
        val list = view as? ViewGroup ?: return
        if (resourceName(list) != "playlistListRecyclerView") return
        val adapter = nativeAdapter(list)
        if (adapter != null && adapter.javaClass.name != "zn3") return
        knownLists[list] = true
        if (settings.enabled) scheduleAttach(list, 0)
    }

    fun consumeBack(): Boolean {
        if (!settings.enabled) return false
        val list = activeBrowser.get()
        val browser = list?.let { browsers[it] }
            ?: browsers.values.firstOrNull {
                it.list.isAttachedToWindow && it.overlay.isShown
            }
            ?: return false
        if (browser.currentFolderId == null) return false
        val folder = findFolder(browser.index, browser.currentFolderId)
        browser.currentFolderId = parentFolderId(browser, folder)
        render(browser)
        activeBrowser = WeakReference(browser.list)
        Log.i(
            TAG,
            "FOLDER INLINE BACK | folder=" +
                (browser.currentFolderId ?: "root")
        )
        return true
    }

    private fun scheduleAttach(list: ViewGroup, attempt: Int) {
        if (!settings.enabled || browsers.containsKey(list) ||
            !list.isAttachedToWindow
        ) return
        val previous = pendingRetries[list]
        if (previous != null && previous >= attempt) return
        pendingRetries[list] = attempt
        list.postDelayed({
            pendingRetries.remove(list)
            attachIfReady(list, attempt)
        }, if (attempt == 0) 0L else ATTACH_RETRY_MS)
    }

    private fun attachIfReady(list: ViewGroup, attempt: Int) {
        if (!settings.enabled || browsers.containsKey(list) ||
            !list.isAttachedToWindow
        ) return
        val adapter = nativeAdapter(list)
        if (adapter?.javaClass?.name != "zn3") {
            retry(list, attempt)
            return
        }
        val itemCount = runCatching {
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrDefault(0)
        if (itemCount <= 0 || list.width <= 0 || list.height <= 0) {
            retry(list, attempt)
            return
        }

        val native = NativePlaylistSourceInspector.inspect(adapter, itemCount)
        if (native.paths.size != itemCount ||
            native.nativeObjects.size != itemCount ||
            native.truncated
        ) {
            Log.w(
                TAG,
                "FOLDER INLINE WAIT | rows=" + itemCount +
                    " models=" + native.paths.size +
                    " objects=" + native.nativeObjects.size +
                    " truncated=" + native.truncated
            )
            retry(list, attempt)
            return
        }

        val root = PlaylistRootLocator.infer(
            native.paths,
            Environment.getExternalStorageDirectory().absolutePath
        )
        if (root == null) {
            Log.w(TAG, "FOLDER INLINE STOP | main root unavailable")
            return
        }

        val renderedTitles = currentlyVisibleTitles(list)
        val titleResult = NativePlaylistTitleResolver.resolve(
            native.models,
            renderedTitles
        )
        if (titleResult.nativeTitles != itemCount) {
            Log.w(
                TAG,
                "FOLDER INLINE STOP | native titles incomplete " +
                    titleResult.nativeTitles + "/" + itemCount
            )
            retry(list, attempt)
            return
        }

        val index = PlaylistFolderIndex.build(
            nativePlaylistPaths = native.paths,
            mainPlaylistDirectory = root,
            groupExternalLocations = settings.groupExternal,
            groupRootPlaylists = settings.groupRoot,
            displayNamesByPath = titleResult.names + renderedTitles
        )

        val parent = list.parent as? ViewGroup ?: return
        val overlay = FrameLayout(list.context).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = ColorDrawable(resolveBackground(list))
            elevation = list.elevation + dp(list, 1).toFloat()
        }
        val scroller = ScrollView(list.context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val rows = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroller.addView(
            rows,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        overlay.addView(
            scroller,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val weakList = WeakReference(list)
        val layoutListener =
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
                weakList.get()?.let { current ->
                    browsers[current]?.let(::positionOverlay)
                }
            }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                weakList.get()?.let(::removeBrowser)
            }
        }

        val browser = Browser(
            list = list,
            parent = parent,
            overlay = overlay,
            rows = rows,
            rootPath = root,
            index = index,
            modelsByPath = native.nativeObjects,
            originalAlpha = list.alpha,
            layoutListener = layoutListener,
            detachListener = detachListener
        )
        browsers[list] = browser
        list.addOnAttachStateChangeListener(detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        list.alpha = 0f
        val insertAt =
            (parent.indexOfChild(list) + 1).coerceAtMost(parent.childCount)
        parent.addView(
            overlay,
            insertAt,
            ViewGroup.LayoutParams(list.width, list.height)
        )
        positionOverlay(browser)
        render(browser)
        activeBrowser = WeakReference(list)

        Log.i(
            TAG,
            "FOLDER INLINE READY | surface=" + surface(list) +
                " | models=" + itemCount +
                " | loose=" + index.ungroupedPlaylists.size +
                " | folders=" + index.topLevelFolders.size +
                " | nativeNames=" + titleResult.nativeTitles +
                " | nativeAdapterPreserved=true"
        )
    }

    private fun retry(list: ViewGroup, attempt: Int) {
        if (attempt >= MAX_ATTACH_RETRIES) {
            Log.w(TAG, "FOLDER INLINE STOP | attach retries exhausted")
            return
        }
        scheduleAttach(list, attempt + 1)
    }

    private fun positionOverlay(browser: Browser) {
        val list = browser.list
        val overlay = browser.overlay
        if (!list.isAttachedToWindow || list.width <= 0 || list.height <= 0) {
            return
        }
        if (overlay.layoutParams.width != list.width ||
            overlay.layoutParams.height != list.height
        ) {
            overlay.layoutParams = overlay.layoutParams.apply {
                width = list.width
                height = list.height
            }
        }
        overlay.x = list.x
        overlay.y = list.y
        overlay.visibility = if (list.isShown) View.VISIBLE else View.GONE
    }

    private fun removeBrowser(list: ViewGroup) {
        val browser = browsers.remove(list) ?: return
        list.alpha = browser.originalAlpha
        list.removeOnAttachStateChangeListener(browser.detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.removeOnGlobalLayoutListener(
                browser.layoutListener
            )
        }
        if (browser.overlay.parent === browser.parent) {
            browser.parent.removeView(browser.overlay)
        }
        if (activeBrowser.get() === list) {
            activeBrowser.clear()
        }
    }

    private fun render(browser: Browser) {
        val list = browser.list
        if (!list.isAttachedToWindow) return
        val folder = findFolder(browser.index, browser.currentFolderId)
        val folders = folder?.children ?: browser.index.topLevelFolders
        val playlists = folder?.playlists ?: browser.index.ungroupedPlaylists
        browser.rows.removeAllViews()

        if (folder != null) {
            browser.rows.addView(
                row(
                    list,
                    "‹  " + folder.name,
                    folder = true,
                    selected = false
                ).apply {
                    setOnClickListener {
                        browser.currentFolderId =
                            parentFolderId(browser, folder)
                        activeBrowser = WeakReference(list)
                        render(browser)
                    }
                }
            )
            browser.rows.addView(divider(list))
        }

        for (child in folders) {
            browser.rows.addView(
                row(
                    list,
                    child.name + "  ›",
                    folder = true,
                    selected = false
                ).apply {
                    setOnClickListener {
                        browser.currentFolderId = child.id
                        activeBrowser = WeakReference(list)
                        render(browser)
                        Log.i(
                            TAG,
                            "FOLDER INLINE NAV | surface=" + surface(list) +
                                " | folder=" + child.name
                        )
                    }
                }
            )
            browser.rows.addView(divider(list))
        }

        for (playlist in playlists) {
            val model = browser.modelsByPath[playlist.path]
            val selected =
                multiSelect.isFolderPlaylistSelected(playlist.path)
            browser.rows.addView(
                row(
                    list,
                    playlist.name,
                    folder = false,
                    selected = selected
                ).apply {
                    setOnClickListener {
                        activeBrowser = WeakReference(list)
                        if (model == null) {
                            warn(list, "Native playlist model unavailable")
                            return@setOnClickListener
                        }
                        if (isPicker(list) &&
                            multiSelect.onFolderPlaylistClick(list, model)
                        ) {
                            render(browser)
                            return@setOnClickListener
                        }
                        dispatchNativeAction(
                            browser,
                            model,
                            longClick = false
                        )
                    }
                    setOnLongClickListener {
                        activeBrowser = WeakReference(list)
                        if (model == null) {
                            return@setOnLongClickListener false
                        }
                        val handled = if (isPicker(list)) {
                            multiSelect.onFolderPlaylistLongClick(list, model)
                        } else {
                            dispatchNativeAction(
                                browser,
                                model,
                                longClick = true
                            )
                        }
                        if (handled && isPicker(list)) render(browser)
                        handled
                    }
                }
            )
            browser.rows.addView(divider(list))
        }

        if (folders.isEmpty() && playlists.isEmpty()) {
            browser.rows.addView(
                row(
                    list,
                    "This folder is empty",
                    folder = false,
                    selected = false
                ).apply {
                    isEnabled = false
                    alpha = 0.55f
                }
            )
        }
    }

    private fun row(
        view: View,
        text: String,
        folder: Boolean,
        selected: Boolean
    ): TextView = TextView(view.context).apply {
        this.text = (if (folder) "▣  " else "") + text
        setTextColor(resolveTextColor(view))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(view, 18), 0, dp(view, 18), 0)
        minHeight = dp(view, 54)
        isClickable = true
        isFocusable = true
        background = if (selected) {
            ColorDrawable(withAlpha(resolveAccent(view), 0x35))
        } else {
            typedSelectableBackground(view)
        }
    }

    private fun divider(view: View): View = View(view.context).apply {
        setBackgroundColor(withAlpha(resolveTextColor(view), 0x18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(view, 1)
        ).apply {
            marginStart = dp(view, 18)
        }
    }

    private fun dispatchNativeAction(
        browser: Browser,
        model: Any,
        longClick: Boolean
    ): Boolean {
        val list = browser.list
        val expectedHolder = if (isPicker(list)) "jo3" else "wp3"
        val holder = firstHolder(list, expectedHolder)
        if (holder == null) {
            warn(list, "Native GMMP playlist row unavailable")
            Log.w(
                TAG,
                "FOLDER INLINE ACTION | holder missing=" + expectedHolder
            )
            return false
        }
        val field = runCatching {
            holder.javaClass.getDeclaredField("A").apply {
                isAccessible = true
            }
        }.getOrNull() ?: return false
        val row = runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull() ?: return false
        val original = runCatching { field.get(holder) }.getOrNull()
        val path = modelPath(model)
        return try {
            field.set(holder, model)
            val handled = if (longClick) {
                row.performLongClick()
            } else {
                row.performClick()
            }
            Log.i(
                TAG,
                "FOLDER INLINE ACTION | surface=" + surface(list) +
                    " | type=" + (if (longClick) "long" else "click") +
                    " | path=" + path +
                    " | handled=" + handled
            )
            handled
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "FOLDER INLINE ACTION | native dispatch failed",
                error
            )
            false
        } finally {
            runCatching { field.set(holder, original) }
        }
    }

    private fun firstHolder(
        list: ViewGroup,
        expected: String
    ): Any? {
        val method = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return null
        for (index in 0 until list.childCount) {
            val holder = runCatching {
                method.invoke(list, list.getChildAt(index))
            }.getOrNull()
            if (holder?.javaClass?.name == expected) return holder
        }
        return null
    }

    private fun findFolder(
        index: PlaylistFolderIndex.Result,
        id: String?
    ): PlaylistFolderIndex.Folder? {
        if (id == null) return null
        fun search(
            folders: List<PlaylistFolderIndex.Folder>
        ): PlaylistFolderIndex.Folder? {
            for (folder in folders) {
                if (folder.id == id) return folder
                search(folder.children)?.let { return it }
            }
            return null
        }
        return search(index.topLevelFolders)
    }

    private fun parentFolderId(
        browser: Browser,
        folder: PlaylistFolderIndex.Folder?
    ): String? {
        val target = folder ?: return null
        fun search(
            candidates: List<PlaylistFolderIndex.Folder>,
            parent: String?
        ): String? {
            for (candidate in candidates) {
                if (candidate.id == target.id) return parent
                val nested = search(candidate.children, candidate.id)
                if (nested != null) return nested
            }
            return null
        }
        return search(browser.index.topLevelFolders, null)
    }

    private fun currentlyVisibleTitles(
        list: ViewGroup
    ): Map<String, String> {
        val holderMethod = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return emptyMap()
        val found = linkedMapOf<String, String>()
        for (index in 0 until list.childCount) {
            val nativeRow = list.getChildAt(index) ?: continue
            val holder = runCatching {
                holderMethod.invoke(list, nativeRow)
            }.getOrNull() ?: continue
            val model = runCatching {
                holder.javaClass.getDeclaredField("A").apply {
                    isAccessible = true
                }.get(holder)
            }.getOrNull() ?: continue
            val path = modelPath(model) ?: continue
            visiblePlaylistTitle(nativeRow)?.let {
                found[path] = it
            }
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
                val value =
                    view.text?.toString()?.trim().orEmpty()
                if (value.length in 1..250 &&
                    value.any(Char::isLetterOrDigit) &&
                    !value.startsWith("/") &&
                    !value.contains("://")
                ) {
                    candidates.add(value to view.textSize)
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

    private fun nativeAdapter(list: ViewGroup): Any? = runCatching {
        list.javaClass.getMethod("getAdapter").invoke(list)
    }.getOrNull()

    private fun modelPath(model: Any): String? = runCatching {
        model.javaClass.getDeclaredField("q").apply {
            isAccessible = true
        }.get(model) as? String
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun isPicker(list: ViewGroup): Boolean =
        (list.parent as? View)?.javaClass?.simpleName
            ?.contains("Coordinator") == true

    private fun surface(list: ViewGroup): String =
        if (isPicker(list)) "add-picker" else "playlists-tab"

    private fun typedSelectableBackground(
        view: View
    ): android.graphics.drawable.Drawable? {
        val out = TypedValue()
        return if (view.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                out,
                true
            )
        ) {
            runCatching {
                view.context.getDrawable(out.resourceId)
            }.getOrNull()
        } else null
    }

    private fun resolveTextColor(view: View): Int =
        resolveColorAttr(
            view,
            android.R.attr.textColorPrimary,
            Color.WHITE
        )

    private fun resolveBackground(view: View): Int =
        resolveColorAttr(
            view,
            android.R.attr.colorBackground,
            Color.BLACK
        )

    private fun resolveAccent(view: View): Int =
        resolveColorAttr(
            view,
            android.R.attr.colorAccent,
            0xFFA39AFF.toInt()
        )

    private fun resolveColorAttr(
        view: View,
        attr: Int,
        fallback: Int
    ): Int {
        val value = TypedValue()
        if (!view.context.theme.resolveAttribute(
                attr,
                value,
                true
            )
        ) {
            return fallback
        }
        return if (value.resourceId != 0) {
            runCatching {
                view.context.getColor(value.resourceId)
            }.getOrDefault(fallback)
        } else {
            value.data
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun warn(view: View, message: String) {
        Toast.makeText(
            view.context,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(view: View, value: Int): Int =
        (view.resources.displayMetrics.density * value + 0.5f)
            .toInt()

    private fun resourceName(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")
}
