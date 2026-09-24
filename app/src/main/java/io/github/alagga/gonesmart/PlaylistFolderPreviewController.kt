package io.github.alagga.gonesmart

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

    private data class NativeRowStyle(
        val textColor: Int,
        val textSizePx: Float,
        val typeface: Typeface?,
        val rowHeight: Int,
        val titleInset: Int,
        val backgroundColor: Int,
        val accentColor: Int,
        val rowBackground: Drawable.ConstantState?,
        val signature: String
    )

    private data class Browser(
        val list: ViewGroup,
        val parent: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val rootPath: String,
        val index: PlaylistFolderIndex.Result,
        val modelsByPath: Map<String, Any>,
        val nativeOrder: List<String>,
        val originalAlpha: Float,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val detachListener: View.OnAttachStateChangeListener,
        var currentFolderId: String? = null,
        var actionPending: Boolean = false
    )

    private var settings = Settings()
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val styles = WeakHashMap<ViewGroup, NativeRowStyle>()
    private val pendingRetries = WeakHashMap<ViewGroup, Int>()
    private val pendingLayoutObservers = WeakHashMap<
        ViewGroup,
        android.view.ViewTreeObserver.OnGlobalLayoutListener
    >()
    private var activeBrowser = WeakReference<ViewGroup>(null)
    private val observedMenus = linkedSetOf<String>()

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
        if (!pendingLayoutObservers.containsKey(list)) {
            val weakList = WeakReference(list)
            val observer = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                weakList.get()?.let { current ->
                    if (settings.enabled && browsers[current] == null &&
                        pendingRetries[current] == null &&
                        current.isAttachedToWindow
                    ) {
                        scheduleAttach(current, 0)
                    }
                }
            }
            pendingLayoutObservers[list] = observer
            if (list.viewTreeObserver.isAlive) {
                list.viewTreeObserver.addOnGlobalLayoutListener(observer)
            }
            list.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        val original = weakList.get() ?: return
                        pendingLayoutObservers.remove(original)?.let {
                            if (original.viewTreeObserver.isAlive) {
                                original.viewTreeObserver
                                    .removeOnGlobalLayoutListener(it)
                            }
                        }
                        pendingRetries.remove(original)
                        knownLists.remove(original)
                    }
                }
            )
        }
        if (settings.enabled) scheduleAttach(list, 0)
    }

    /**
     * Bounded read-only discovery of the exact native Add Playlist menu ID.
     * Do not remove the overflow menu or guess a title/ID before the actual
     * GMMP menu structure and create callback have been verified.
     */
    fun onMenuInflated(resourceId: Int, menu: android.view.Menu?) {
        if (!BuildConfig.DEBUG || !settings.enabled || menu == null) return
        val context = knownLists.keys.firstOrNull()?.context ?: return
        val name = runCatching {
            context.resources.getResourceEntryName(resourceId)
        }.getOrNull() ?: return
        val items = (0 until menu.size()).map { position ->
            val item = menu.getItem(position)
            val idName = runCatching {
                context.resources.getResourceEntryName(item.itemId)
            }.getOrElse { item.itemId.toString() }
            idName + "=" + item.title?.toString().orEmpty() +
                ":visible=" + item.isVisible
        }
        val playlistRelated =
            name.contains("playlist", ignoreCase = true) ||
                items.any {
                    it.contains("playlist", ignoreCase = true) ||
                        it.contains("wiedergabeliste", ignoreCase = true)
                }
        if (!playlistRelated || !observedMenus.add(name) ||
            observedMenus.size > 18
        ) return
        Log.i(
            TAG,
            "FOLDER NATIVE MENU | menu=" + name +
                " | items=" + items.joinToString(";")
        )
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
        safeRender(browser)
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
            runCatching {
                attachIfReady(list, attempt)
            }.onFailure { error ->
                Log.e(TAG, "FOLDER INLINE ERROR | native list restored", error)
                removeBrowser(list)
            }
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

        // A GMMP Playlists tab RecyclerView is owned by
        // FragmentContainerView, which explicitly rejects arbitrary
        // addView() children. Use the existing DecorView FrameLayout
        // instead, positioning over the native RecyclerView in screen
        // coordinates. This is safe for both the tab and picker.
        val nativeStyle = sampleNativeStyle(list) ?: run {
            Log.i(TAG, "FOLDER INLINE WAIT | native row style not ready")
            retry(list, attempt)
            return
        }
        val parent = list.rootView as? FrameLayout ?: run {
            Log.w(TAG, "FOLDER INLINE STOP | no safe FrameLayout overlay host")
            return
        }
        val overlay = FrameLayout(list.context).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = ColorDrawable(nativeStyle.backgroundColor)
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
                    val found = browsers[current] ?: return@let
                    runCatching {
                        positionOverlay(found)
                    }.onFailure { error ->
                        Log.e(TAG, "FOLDER INLINE ERROR | layout", error)
                        removeBrowser(current)
                    }
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
            nativeOrder = native.paths,
            originalAlpha = list.alpha,
            layoutListener = layoutListener,
            detachListener = detachListener
        )
        browsers[list] = browser
        styles[list] = nativeStyle
        list.addOnAttachStateChangeListener(detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        list.alpha = 0f
        parent.addView(
            overlay,
            FrameLayout.LayoutParams(list.width, list.height)
        )
        positionOverlay(browser)
        safeRender(browser)
        activeBrowser = WeakReference(list)

        Log.i(
            TAG,
            "FOLDER INLINE READY | surface=" + surface(list) +
                " | models=" + itemCount +
                " | loose=" + index.ungroupedPlaylists.size +
                " | folders=" + index.topLevelFolders.size +
                " | nativeNames=" + titleResult.nativeTitles +
                " | nativeAdapterPreserved=true" +
                " | safeHost=" + parent.javaClass.simpleName +
                " | nativeStyle=" + nativeStyle.signature
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
        val listLocation = IntArray(2)
        val hostLocation = IntArray(2)
        list.getLocationOnScreen(listLocation)
        browser.parent.getLocationOnScreen(hostLocation)
        overlay.x = (listLocation[0] - hostLocation[0]).toFloat()
        overlay.y = (listLocation[1] - hostLocation[1]).toFloat()
        overlay.visibility =
            if (list.isShown) View.VISIBLE else View.GONE
        // GMMP's Aesthetic theme can change live with album art or user
        // settings. Mirror the real native row typography/background each
        // time its rendered style changes; never freeze an Android theme
        // color at startup.
        val updatedStyle = sampleNativeStyle(list)
        val oldStyle = styles[list]
        if (updatedStyle != null &&
            oldStyle?.signature != updatedStyle.signature
        ) {
            styles[list] = updatedStyle
            overlay.background = ColorDrawable(updatedStyle.backgroundColor)
            safeRender(browser)
            Log.i(
                TAG,
                "FOLDER INLINE THEME | native style refreshed | " +
                    updatedStyle.signature
            )
        }
    }

    private fun removeBrowser(list: ViewGroup) {
        val browser = browsers.remove(list) ?: return
        styles.remove(list)
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

    private fun safeRender(browser: Browser) {
        runCatching {
            render(browser)
        }.onFailure { error ->
            Log.e(
                TAG,
                "FOLDER INLINE ERROR | rendering failed; restoring GMMP list",
                error
            )
            removeBrowser(browser.list)
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
                        safeRender(browser)
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
                        safeRender(browser)
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
                            safeRender(browser)
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
                        if (handled && isPicker(list)) safeRender(browser)
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
        val native = styles[view]
        this.text = (if (folder) "▣  " else "") + text
        setTextColor(native?.textColor ?: resolveTextColor(view))
        if (native != null) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, native.textSizePx)
            typeface = native.typeface
        } else {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        gravity = Gravity.CENTER_VERTICAL
        val inset = native?.titleInset ?: dp(view, 18)
        setPadding(inset, 0, dp(view, 12), 0)
        minHeight = native?.rowHeight ?: dp(view, 54)
        isClickable = true
        isFocusable = true
        background = if (selected) {
            ColorDrawable(
                withAlpha(
                    native?.accentColor ?: resolveAccent(view),
                    0x35
                )
            )
        } else {
            runCatching {
                native?.rowBackground?.newDrawable(view.resources)?.mutate()
            }.getOrNull() ?: typedSelectableBackground(view)
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

    /**
     * Never swap wp3/jo3.A and run its click listener against a different
     * playlist: native lambdas can capture a model independently of A.
     * Instead, bring the real native row to the foreground of RecyclerView,
     * verify its currently bound xn3.q and click THAT row only.
     */
    private fun dispatchNativeAction(
        browser: Browser,
        model: Any,
        longClick: Boolean
    ): Boolean {
        val path = modelPath(model) ?: return false
        val list = browser.list
        if (browser.actionPending || !list.isAttachedToWindow) return false

        if (performMatchingNativeAction(list, path, longClick)) {
            return true
        }

        val expectedPosition = browser.nativeOrder.indexOf(path)
        if (expectedPosition < 0) {
            Log.w(TAG, "FOLDER INLINE ACTION | path absent from native snapshot")
            warn(list, "Playlist no longer available")
            return false
        }

        browser.actionPending = true
        val scroll = runCatching {
            list.javaClass.getMethod(
                "scrollToPosition",
                Int::class.javaPrimitiveType
            ).invoke(list, expectedPosition)
        }.isSuccess
        if (!scroll) {
            browser.actionPending = false
            Log.w(TAG, "FOLDER INLINE ACTION | native scroll unavailable")
            warn(list, "Native playlist action unavailable")
            return false
        }

        // RecyclerView binds its target holder on the next layout frame.
        // Never dispatch a row click if it still holds a different model.
        list.postOnAnimation {
            list.postOnAnimation {
                browser.actionPending = false
                if (browsers[list] !== browser || !list.isAttachedToWindow) {
                    return@postOnAnimation
                }
                if (!performMatchingNativeAction(list, path, longClick)) {
                    Log.w(
                        TAG,
                        "FOLDER INLINE ACTION | target not bound" +
                            " | expectedPosition=" + expectedPosition +
                            " | path=" + path
                    )
                    warn(list, "Playlist row not ready; please try again")
                }
            }
        }
        return true
    }

    private fun performMatchingNativeAction(
        list: ViewGroup,
        targetPath: String,
        longClick: Boolean
    ): Boolean {
        val getHolder = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return false
        val expectedHolder =
            if (isPicker(list)) "jo3" else "wp3"
        for (i in 0 until list.childCount) {
            val nativeRow = list.getChildAt(i) ?: continue
            val holder = runCatching {
                getHolder.invoke(list, nativeRow)
            }.getOrNull() ?: continue
            if (holder.javaClass.name != expectedHolder) continue
            val actual = runCatching {
                holder.javaClass.getDeclaredField("A").apply {
                    isAccessible = true
                }.get(holder)
            }.getOrNull() ?: continue
            if (modelPath(actual) != targetPath) continue
            return runCatching {
                val handled = if (longClick) {
                    nativeRow.performLongClick()
                } else {
                    nativeRow.performClick()
                }
                Log.i(
                    TAG,
                    "FOLDER INLINE ACTION | surface=" + surface(list) +
                        " | type=" +
                        (if (longClick) "long" else "click") +
                        " | verifiedNativePath=" + targetPath +
                        " | handled=" + handled
                )
                handled
            }.onFailure {
                Log.e(
                    TAG,
                    "FOLDER INLINE ACTION | native row click failed",
                    it
                )
            }.getOrDefault(false)
        }
        return false
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

    /**
     * Derive the layout from the actual currently-bound GMMP row rather than
     * using fixed GoneSmart colors, font size or row height. Read-only.
     */
    private fun sampleNativeStyle(list: ViewGroup): NativeRowStyle? {
        val holderGetter = runCatching {
            list.javaClass.getMethod("getChildViewHolder", View::class.java)
        }.getOrNull() ?: return null
        val expected = if (isPicker(list)) "jo3" else "wp3"
        for (index in 0 until list.childCount) {
            val nativeRow = list.getChildAt(index) ?: continue
            val holder = runCatching {
                holderGetter.invoke(list, nativeRow)
            }.getOrNull() ?: continue
            if (holder.javaClass.name != expected) continue
            val title = findNativeTitleTextView(nativeRow) ?: continue
            val color = title.currentTextColor
            val size = title.textSize
            val height = nativeRow.height.coerceAtLeast(dp(list, 44))
            val nativeRowPos = IntArray(2)
            val titlePos = IntArray(2)
            nativeRow.getLocationOnScreen(nativeRowPos)
            title.getLocationOnScreen(titlePos)
            val inset = (
                titlePos[0] - nativeRowPos[0] + title.paddingLeft
            ).coerceAtLeast(dp(list, 12))
            val backgroundColor = nativeSurfaceBackground(list)
            val accent = resolveAccent(list)
            val rowBackground = nativeRow.background?.constantState
            val signature = listOf(
                color, size.toInt(), height, inset, backgroundColor, accent,
                title.typeface?.style ?: 0, rowBackground?.javaClass?.name
            ).joinToString(":")
            if (observedMenus.add("native-row-style-" + surface(list))) {
                val layoutName = runCatching {
                    list.resources.getResourceEntryName(
                        nativeRow.sourceLayoutResId
                    )
                }.getOrDefault("programmatic-or-unavailable")
                Log.i(
                    TAG,
                    "FOLDER NATIVE STYLE | surface=" + surface(list) +
                        " | rowLayout=" + layoutName +
                        " | row=" + nativeRow.javaClass.name +
                        " | title=" + title.javaClass.name +
                        " | titleId=" + resourceName(title) +
                        " | bg=" +
                            (nativeRow.background?.javaClass?.name ?: "none") +
                        " | style=" + signature
                )
            }
            return NativeRowStyle(
                textColor = color,
                textSizePx = size,
                typeface = title.typeface,
                rowHeight = height,
                titleInset = inset,
                backgroundColor = backgroundColor,
                accentColor = accent,
                rowBackground = rowBackground,
                signature = signature
            )
        }
        return null
    }

    private fun findNativeTitleTextView(root: View): TextView? {
        val options = arrayListOf<TextView>()
        fun descend(node: View, depth: Int) {
            if (depth > 7 || options.size >= 32) return
            if (node is TextView) {
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank() && text.any(Char::isLetterOrDigit)) {
                    options.add(node)
                }
            }
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) {
                    descend(node.getChildAt(i), depth + 1)
                }
            }
        }
        descend(root, 0)
        return options.maxByOrNull { it.textSize }
    }

    private fun nativeSurfaceBackground(list: View): Int {
        var current: View? = list
        repeat(5) {
            val view = current ?: return@repeat
            val background = view.background as? ColorDrawable
            if (background != null &&
                Color.alpha(background.color) == 255
            ) return background.color
            current = view.parent as? View
        }
        return resolveBackground(list)
    }

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
