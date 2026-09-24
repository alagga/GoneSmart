package io.github.alagga.gonesmart

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.LayoutInflater
import android.widget.ImageView
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
        val signature: String,
        val nativeRowLayoutId: Int,
        val titleViewId: Int,
        val titleGravity: Int,
        val titlePaddingStart: Int,
        val titlePaddingEnd: Int
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
        val themeListener: android.view.ViewTreeObserver.OnPreDrawListener,
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
    private var playlistTabMenu: WeakReference<android.view.Menu>? = null

    init {
        multiSelect.setFolderSelectionChangedListener { list ->
            browsers[list]?.let { browser ->
                if (list.isAttachedToWindow) {
                    positionOverlay(browser)
                    safeRender(browser)
                }
            }
        }
    }

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
        updatePlaylistMenu()

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
        if (name == "menu_gm_playlist_list") {
            playlistTabMenu = WeakReference(menu)
            updatePlaylistMenu()
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
        // Keep the browser INSIDE GMMP's page/dialog content. Decorating
        // the whole window previously covered the drawer, FAB and mini player.
        val parent = safeOverlayHost(list) ?: run {
            Log.w(TAG, "FOLDER INLINE STOP | no scoped page overlay host")
            return
        }
        val overlay = FrameLayout(list.context).apply {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = nativeContentBackground(list, parent, nativeStyle)
            elevation = 0f
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
        var lastThemeProbe = 0L
        // Aesthetic can change color values from album artwork without
        // triggering a new layout. Probe the actual native row on redraw,
        // throttled so scrolling and cover animations remain lightweight.
        val themeListener = android.view.ViewTreeObserver.OnPreDrawListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastThemeProbe >= 400L) {
                lastThemeProbe = now
                weakList.get()?.let { current ->
                    browsers[current]?.let { browser ->
                        runCatching {
                            positionOverlay(browser)
                        }.onFailure { error ->
                            Log.e(TAG, "FOLDER INLINE ERROR | theme probe", error)
                            removeBrowser(current)
                        }
                    }
                }
            }
            true
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
            themeListener = themeListener,
            detachListener = detachListener
        )
        browsers[list] = browser
        styles[list] = nativeStyle
        list.addOnAttachStateChangeListener(detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            list.viewTreeObserver.addOnPreDrawListener(themeListener)
        }

        list.alpha = 0f
        // Insert after the fragment content, but BELOW later siblings
        // such as the native creation/confirm FAB.
        val contentChild = directChildInHost(list, parent)
        val insertAt = if (contentChild == null) parent.childCount else {
            (parent.indexOfChild(contentChild) + 1).coerceAtMost(parent.childCount)
        }
        parent.addView(
            overlay,
            insertAt,
            ViewGroup.LayoutParams(list.width, list.height)
        )
        positionOverlay(browser)
        safeRender(browser)
        activeBrowser = WeakReference(list)
        updatePlaylistMenu()
        updatePickerFab(browser)

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
        updatePickerFab(browser)
        updatePlaylistMenu()
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
            overlay.background = nativeContentBackground(
                list, browser.parent, updatedStyle
            )
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
            list.viewTreeObserver.removeOnPreDrawListener(
                browser.themeListener
            )
        }
        if (browser.overlay.parent === browser.parent) {
            browser.parent.removeView(browser.overlay)
        }
        if (activeBrowser.get() === list) {
            activeBrowser.clear()
        }
        updatePlaylistMenu()
        multiSelect.folderNativeFab(list)?.let { fab ->
            // Native controls regain their original responsibility as soon
            // as inline folders are disabled or the picker is dismissed.
            fab.visibility = View.VISIBLE
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
        updatePickerFab(browser)

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
                        updatePlaylistMenu()
                        updatePickerFab(browser)
                    }
                }
            )
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
                        updatePlaylistMenu()
                        updatePickerFab(browser)
                        Log.i(
                            TAG,
                            "FOLDER INLINE NAV | surface=" + surface(list) +
                                " | folder=" + child.name
                        )
                    }
                }
            )
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
    ): View {
        val native = styles[view]
        val parent = browsers[view as? ViewGroup]?.rows
        // Clone GMMP's own current row XML first. Its AestheticTextViews
        // retain native textAppearance, layout and live theme subscriptions.
        val template = if (native != null && native.nativeRowLayoutId != 0) {
            runCatching {
                LayoutInflater.from(view.context).inflate(
                    native.nativeRowLayoutId, parent, false
                )
            }.getOrNull()
        } else null
        val target = if (template != null) {
            if (native?.titleViewId != 0) {
                template.findViewById<TextView>(native!!.titleViewId)
            } else null
        } else null
        if (target != null && template != null) {
            target.text = text
            if (native != null) {
                target.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX, native.textSizePx
                )
                target.setTextColor(native.textColor)
                target.typeface = native.typeface
                target.gravity = native.titleGravity
                target.setPaddingRelative(
                    native.titlePaddingStart,
                    target.paddingTop,
                    native.titlePaddingEnd,
                    target.paddingBottom
                )
            }
            val root = template
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                native?.rowHeight ?: dp(view, 54)
            )
            root.minimumHeight = native?.rowHeight ?: dp(view, 54)
            // A bare inflation is not bound by zn3. Hide unused template
            // metadata until we have real native metadata for this model.
            hideOtherTemplateLabels(root, target)
            if (folder) {
                addNativeFolderIcon(root, native, view)
            }
            styleRowSelection(root, view, native, selected)
            root.isClickable = true
            root.isFocusable = true
            return root
        }
        // Defensive fallback on custom native view modes without XML IDs.
        return TextView(view.context).apply {
            this.text = text
            setTextColor(native?.textColor ?: resolveTextColor(view))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                native?.textSizePx ?: (view.resources.displayMetrics.scaledDensity * 16f)
            )
            typeface = native?.typeface
            gravity = Gravity.CENTER_VERTICAL
            val inset = (native?.titleInset ?: dp(view, 18)) +
                if (folder) dp(view, 28) else 0
            setPadding(inset, 0, dp(view, 12), 0)
            minHeight = native?.rowHeight ?: dp(view, 54)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                native?.rowHeight ?: dp(view, 54)
            )
            background = native?.rowBackground?.newDrawable(resources)?.mutate()
                ?: typedSelectableBackground(view)
            if (folder) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    FolderOutlineDrawable(
                        native?.textColor ?: resolveTextColor(view),
                        dp(view, 24)
                    ), null, null, null
                )
                compoundDrawablePadding = dp(view, 12)
                setPadding(native?.titleInset ?: dp(view, 12), 0, dp(view, 12), 0)
            }
            if (selected) {
                foreground = ColorDrawable(
                    withAlpha(
                        multiSelect.folderSelectionAccent(view as ViewGroup)
                            ?: native?.accentColor ?: resolveAccent(view),
                        0x80
                    )
                )
            }
        }
    }

    private fun hideOtherTemplateLabels(root: View, title: TextView) {
        fun walk(view: View) {
            if (view is TextView && view !== title) {
                view.visibility = View.GONE
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
    }

    private fun styleRowSelection(
        root: View,
        view: View,
        native: NativeRowStyle?,
        selected: Boolean
    ) {
        val backdrop = native?.rowBackground?.newDrawable(view.resources)
            ?.mutate() ?: typedSelectableBackground(view)
        if (backdrop != null) root.background = backdrop
        if (selected) {
            root.foreground = ColorDrawable(
                withAlpha(
                    multiSelect.folderSelectionAccent(view as ViewGroup)
                        ?: native?.accentColor ?: resolveAccent(view),
                    0x80
                )
            )
        } // Otherwise preserve the native XML foreground/ripple.
    }

    private fun addNativeFolderIcon(
        root: View,
        native: NativeRowStyle?,
        host: View
    ) {
        val content = root as? ViewGroup ?: return
        val color = native?.textColor ?: resolveTextColor(host)
        // Use GMMP's own drawable if its APK exposes one; otherwise render
        // an outline vector, tinted from the native playlist text.
        val names = arrayOf(
            "ic_folder", "ic_folder_outline", "ic_folder_24dp",
            "ic_folder_black_24dp", "ic_folder_closed"
        )
        val drawable = names.firstNotNullOfOrNull { name ->
            val id = host.resources.getIdentifier(
                name, "drawable", host.context.packageName
            )
            if (id != 0) runCatching {
                host.context.getDrawable(id)?.mutate()?.apply {
                    setTint(color)
                }
            }.getOrNull() else null
        } ?: FolderOutlineDrawable(color, dp(host, 24))
        val image = ImageView(host.context).apply {
            setImageDrawable(drawable)
            contentDescription = "Folder"
            isClickable = false
            isFocusable = false
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = dp(host, 24)
        // The original GMMP row has no GoneSmart glyph. The folder
        // indicator is introduced only on synthetic folder rows.
        if (content is FrameLayout) {
            content.addView(
                image,
                FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { marginStart = dp(host, 12) }
            )
        } else {
            content.addView(
                image,
                ViewGroup.LayoutParams(size, size)
            )
        }
        val title = if (native?.titleViewId != 0) {
            root.findViewById<TextView>(native!!.titleViewId)
        } else null
        title?.let {
            val padding = it.paddingStart
            it.setPaddingRelative(
                padding + dp(host, 28),
                it.paddingTop,
                it.paddingEnd,
                it.paddingBottom
            )
        }
    }

    private class FolderOutlineDrawable(
        color: Int,
        private val sizePx: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.075f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val scale = minOf(
                b.width().toFloat() / 24f,
                b.height().toFloat() / 24f
            )
            if (scale <= 0f) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scale, scale)
            val p = Path().apply {
                moveTo(3f, 6f)
                lineTo(9f, 6f)
                lineTo(11f, 8.5f)
                lineTo(21f, 8.5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
            canvas.drawPath(p, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) {
            paint.colorFilter = filter
        }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int =
            android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
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
                // Native GMMP can replace this page synchronously.
                // Hide our old overlay before the native click to avoid a
                // brief flash of both old and new playlist screens.
                val current = browsers[list]
                if (!longClick) current?.overlay?.visibility = View.INVISIBLE
                val handled = if (longClick) {
                    nativeRow.performLongClick()
                } else {
                    nativeRow.performClick()
                }
                if (!handled || longClick) {
                    if (current != null && browsers[list] === current) {
                        current.overlay.visibility = View.VISIBLE
                    }
                } else {
                    list.post {
                        if (current != null && browsers[list] === current) {
                            positionOverlay(current)
                        }
                    }
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
            val boundModel = runCatching {
                holder.javaClass.getDeclaredField("A").apply {
                    isAccessible = true
                }.get(holder)
            }.getOrNull() ?: continue
            val name = runCatching {
                boundModel.javaClass.getDeclaredField("p").apply {
                    isAccessible = true
                }.get(boundModel) as? String
            }.getOrNull()
            val title = findNativeTitleTextView(nativeRow, name) ?: continue
            val matchedNativeTitle = !name.isNullOrBlank() &&
                title.text?.toString()?.trim().equals(name.trim(), true)
            val normalTextSize = if (matchedNativeTitle) title.textSize
                else title.textSize.coerceAtLeast(
                    16f * list.resources.displayMetrics.scaledDensity
                )
            val color = title.currentTextColor
            val size = normalTextSize
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
                title.typeface?.style ?: 0, rowBackground?.javaClass?.name,
                nativeRow.sourceLayoutResId, title.id, matchedNativeTitle
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
                        " | matchedTitle=" + matchedNativeTitle +
                        " | modelName=" + name?.take(60) +
                        " | nativePx=" + title.textSize +
                        " | chosenPx=" + size +
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
                signature = signature,
                nativeRowLayoutId = nativeRow.sourceLayoutResId,
                titleViewId = title.id,
                titleGravity = title.gravity,
                titlePaddingStart = title.paddingStart,
                titlePaddingEnd = title.paddingEnd
            )
        }
        return null
    }

    private fun findNativeTitleTextView(
        root: View,
        nativeName: String? = null
    ): TextView? {
        val options = arrayListOf<TextView>()
        fun descend(node: View, depth: Int) {
            if (depth > 8 || options.size >= 40) return
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
        if (!nativeName.isNullOrBlank()) {
            options.filter {
                it.text?.toString()?.trim().equals(nativeName.trim(), true)
            }.maxWithOrNull(
                compareBy<TextView> {
                    resourceName(it) != "metadataTextEntry"
                }.thenBy { it.textSize }
            )?.let { return it }
        }
        return options.filterNot {
            resourceName(it) == "metadataTextEntry"
        }.maxByOrNull { it.textSize }
            ?: options.maxByOrNull { it.textSize }
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

    /** Find the nearest GMMP page/dialog host, NEVER the DecorView. */
    private fun safeOverlayHost(list: ViewGroup): ViewGroup? {
        var parent = list.parent as? ViewGroup
        while (parent != null && parent !== list.rootView) {
            if (parent.javaClass.simpleName.contains(
                    "CoordinatorLayout", ignoreCase = true
                )
            ) {
                Log.i(
                    TAG,
                    "FOLDER INLINE HOST | surface=" + surface(list) +
                        " | class=" + parent.javaClass.name +
                        " | id=" + resourceName(parent)
                )
                return parent
            }
            parent = parent.parent as? ViewGroup
        }
        return null
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

    private fun nativeContentBackground(
        list: View,
        host: ViewGroup,
        style: NativeRowStyle
    ): Drawable {
        var node: View? = list
        while (node != null) {
            val original = node.background
            if (original != null) {
                val clone = runCatching {
                    original.constantState?.newDrawable(list.resources)?.mutate()
                }.getOrNull()
                if (clone != null) return clone
            }
            if (node === host) break
            node = node.parent as? View
        }
        return ColorDrawable(style.backgroundColor)
    }

    private fun currentBrowser(
        picker: Boolean
    ): Browser? = browsers.values.firstOrNull {
        isPicker(it.list) == picker &&
            it.list.isAttachedToWindow &&
            it.overlay.visibility == View.VISIBLE
    }

    private fun creationDestination(browser: Browser): String? =
        PlaylistCreationPolicy.destination(
            browser.currentFolderId,
            browser.rootPath,
            settings.groupRoot
        )

    private fun updatePlaylistMenu() {
        val menu = playlistTabMenu?.get() ?: return
        val normal = currentBrowser(picker = false)
        val destination = normal?.let(::creationDestination)
        // A root-target creation is fully native. Physical subfolders need
        // their native GMMP creation destination hook before they can write.
        val allowRootNativeCreate = if (normal == null) {
            !settings.enabled || !settings.groupRoot
        } else {
            !settings.enabled || destination == normal.rootPath
        }
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val id = runCatching {
                val context = normal?.list?.context
                    ?: knownLists.keys.firstOrNull()?.context
                context?.resources?.getResourceEntryName(item.itemId)
            }.getOrNull()
            if (id == "menuAdd") {
                if (item.isVisible == allowRootNativeCreate) break
                item.isVisible = allowRootNativeCreate
                Log.i(
                    TAG,
                    "FOLDER CREATE MENU | visible=" + allowRootNativeCreate +
                        " | folder=" + (normal?.currentFolderId ?: "root") +
                        " | rootGrouping=" + settings.groupRoot
                )
                break
            }
        }
    }

    private fun updatePickerFab(browser: Browser) {
        val list = browser.list
        if (!isPicker(list)) return
        val fab = multiSelect.folderNativeFab(list) ?: return
        val selected = multiSelect.hasFolderSelection(list)
        val destination = creationDestination(browser)
        val show = selected || destination != null
        if (show) {
            if (fab.visibility != View.VISIBLE ||
                fab.alpha < 1f || fab.translationY != 0f
            ) {
                fab.animate().cancel()
                fab.clearAnimation()
                fab.visibility = View.VISIBLE
                fab.alpha = 1f
                fab.translationY = 0f
            }
            // Bringing the native FAB to the front on every global
            // layout would create a layout loop. Reorder only if needed.
            val fabHost = fab.parent as? ViewGroup
            if (fabHost != null &&
                fabHost.indexOfChild(fab) < fabHost.childCount - 1
            ) {
                fab.bringToFront()
            }
            fab.invalidate()
        } else if (fab.visibility != View.GONE) {
            fab.animate().cancel()
            fab.clearAnimation()
            fab.visibility = View.GONE
        }
    }

    /**
     * Guard the native creation callback when the physical destination
     * cannot yet be passed to GMMP. Confirmation remains fully native.
     */
    fun interceptNativePickerFabClick(fab: View?): Boolean {
        if (!settings.enabled || fab == null ||
            resourceName(fab) != "playlistFab"
        ) return false
        val browser = currentBrowser(picker = true) ?: return false
        if (multiSelect.hasFolderSelection(browser.list)) return false
        val destination = creationDestination(browser)
        if (destination == null) {
            Log.i(TAG, "FOLDER CREATE BLOCK | location forbids creation")
            return true
        }
        if (destination != browser.rootPath) {
            Toast.makeText(
                browser.list.context,
                "Creating playlists in subfolders is not supported in " +
                    "this build yet. No playlist was created.",
                Toast.LENGTH_LONG
            ).show()
            Log.i(
                TAG,
                "FOLDER CREATE GUARD | native root-only callback; " +
                    "physicalFolder=" + destination
            )
            return true
        }
        return false
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
