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
import android.widget.ImageButton
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    private data class NavigationHold(
        var leftForeground: Boolean = false
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
        val titlePaddingEnd: Int,
        val textTemplate: CharSequence?,
        val effectivePaint: TextPaint,
        val letterSpacing: Float,
        val textScaleX: Float,
        val includeFontPadding: Boolean,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val maxLines: Int,
        val ellipsize: android.text.TextUtils.TruncateAt?
    )

    private data class NativeMenuResources(
        val buttonId: Int,
        val iconId: Int,
        val descriptionId: Int
    )

    private var nativeMenuResources: NativeMenuResources? = null

    private fun nativeMenuResources(host: View): NativeMenuResources {
        nativeMenuResources?.let { return it }
        val resources = host.resources
        val pkg = host.context.packageName
        return NativeMenuResources(
            buttonId = resources.getIdentifier("rvContextMenu", "id", pkg),
            iconId = resources.getIdentifier("ic_gm_more_vert", "drawable", pkg),
            descriptionId = resources.getIdentifier("menu", "string", pkg)
        ).also { nativeMenuResources = it }
    }

    private data class NativeBreadcrumbStyle(
        val effectivePaint: TextPaint,
        val letterSpacing: Float,
        val includeFontPadding: Boolean,
        val rowHeightPx: Int,
        val paddingStartPx: Int,
        val paddingEndPx: Int,
        val nativeTouchBackground: Drawable.ConstantState?,
        val signature: String
    )

    private data class Browser(
        val list: ViewGroup,
        val parent: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val breadcrumbScroller: HorizontalScrollView,
        val breadcrumbRows: LinearLayout,
        val rootPath: String,
        var index: PlaylistFolderIndex.Result,
        var modelsByPath: Map<String, Any>,
        var nativeOrder: List<String>,
        val originalAlpha: Float,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val themeListener: android.view.ViewTreeObserver.OnPreDrawListener,
        val detachListener: View.OnAttachStateChangeListener,
        var currentFolderId: String? = null,
        var actionPending: Boolean = false,
        var nativeNavigationInProgress: Boolean = false,
        var nativeRefreshPending: Boolean = false,
        var renderedBreadcrumbSignature: String? = null,
        var breadcrumbRefreshPending: Boolean = false,
        var lastBreadcrumbFolderId: String? = null,
        var breadcrumbRenderGeneration: Long = 0L
    )

    private var settings = Settings()
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val styles = WeakHashMap<ViewGroup, NativeRowStyle>()
    private val pendingRetries = WeakHashMap<ViewGroup, Int>()
    private val suspendedNativeLists = WeakHashMap<ViewGroup, NavigationHold>()
    private val folderMemory = PlaylistFolderNavigationMemory()
    private val nativeOriginalAlphas = WeakHashMap<ViewGroup, Float>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLayoutObservers = WeakHashMap<
        ViewGroup,
        android.view.ViewTreeObserver.OnGlobalLayoutListener
    >()
    private var activeBrowser = WeakReference<ViewGroup>(null)
    private var observedNativeBreadcrumbStyle: NativeBreadcrumbStyle? = null
    private val observedNativeNavLists = WeakHashMap<ViewGroup, Boolean>()
    private val observedMenus = linkedSetOf<String>()
    private var playlistTabMenu: WeakReference<android.view.Menu>? = null

    init {
        multiSelect.setFolderSelectionChangedListener { list ->
            // Picker exit notifications can occur inside Android's window
            // detach traversal. Never rerender overlay children reentrantly.
            val weakList = WeakReference(list)
            mainHandler.post {
                weakList.get()?.let { current ->
                    browsers[current]?.let { browser ->
                        if (current.isAttachedToWindow &&
                            !browser.nativeNavigationInProgress
                        ) {
                            positionOverlay(browser)
                            safeRender(browser)
                        }
                    }
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
            knownLists.keys.toList().forEach { list ->
                nativeOriginalAlphas.remove(list)?.let { list.alpha = it }
            }
            pendingRetries.clear()
            return
        }

        if (groupingChanged) {
            // Folder IDs are stable for physical directories, but a virtual
            // node can disappear when its grouping option changes. Validate
            // the remembered location against the freshly rebuilt index.
            browsers.keys.toList().forEach { list ->
                removeBrowser(list, preserveNativeAlpha = true)
            }
        }
        knownLists.keys.toList().forEach { scheduleAttach(it, 0) }
    }

    /**
     * Reuse GMMP's *bound* Files-tab quickNav typography. Its native
     * MetadataTextView can have font/size spans not present in XML. The
     * actual sample is optional because the Files tab might never be opened
     * in a session; in that case the live playlist headline is the fallback.
     */
    private fun observeNativeBreadcrumb(list: ViewGroup) {
        if (observedNativeNavLists.containsKey(list)) {
            captureNativeBreadcrumbStyle(list)
            return
        }
        observedNativeNavLists[list] = true
        val weak = WeakReference(list)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                weak.get()?.let(::captureNativeBreadcrumbStyle)
            }
        }
        list.addOnLayoutChangeListener(listener)
        list.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    weak.get()?.let {
                        it.removeOnLayoutChangeListener(listener)
                        observedNativeNavLists.remove(it)
                    }
                }
            }
        )
        var attempts = 0
        val retry = object : Runnable {
            override fun run() {
                val active = weak.get() ?: return
                if (!active.isAttachedToWindow) return
                if (captureNativeBreadcrumbStyle(active)) return
                if (++attempts < 8) active.postDelayed(this, ATTACH_RETRY_MS)
            }
        }
        list.post(retry)
    }

    private fun captureNativeBreadcrumbStyle(list: ViewGroup): Boolean {
        val candidates = arrayListOf<TextView>()
        fun scan(view: View, depth: Int) {
            if (depth > 5 || candidates.size >= 20) return
            if (view is TextView &&
                view.visibility == View.VISIBLE &&
                view.text?.any(Char::isLetterOrDigit) == true
            ) {
                candidates.add(view)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    scan(view.getChildAt(index), depth + 1)
                }
            }
        }
        for (i in 0 until minOf(list.childCount, 4)) {
            scan(list.getChildAt(i), 0)
        }
        val original = candidates.maxByOrNull { it.textSize }
            ?: return false
        val paint = effectiveNativeTitlePaint(original)
        val height = list.height.takeIf { it > 0 }
            ?: original.height.coerceAtLeast(dp(list, 44))
        val nativeTouch = original.background?.constantState
            ?: (original.parent as? View)?.background?.constantState
        val signature = listOf(
            paint.textSize, paint.color, paint.typeface?.style ?: 0,
            original.letterSpacing, original.includeFontPadding, height,
            original.paddingStart, original.paddingEnd,
            nativeTouch?.javaClass?.name
        ).joinToString(":")
        if (observedNativeBreadcrumbStyle?.signature == signature) {
            return true
        }
        observedNativeBreadcrumbStyle = NativeBreadcrumbStyle(
            effectivePaint = paint,
            letterSpacing = original.letterSpacing,
            includeFontPadding = original.includeFontPadding,
            rowHeightPx = height,
            paddingStartPx = original.paddingStart,
            paddingEndPx = original.paddingEnd,
            nativeTouchBackground = nativeTouch,
            signature = signature
        )
        Log.i(
            TAG,
            "FOLDER NATIVE BREADCRUMB | quickNav style captured" +
                " | sizePx=" + paint.textSize +
                " | typeface=" + (paint.typeface?.style ?: 0) +
                " | height=" + height
        )
        // Never rebuild overlays inside GMMP's RecyclerView layout pass.
        mainHandler.post {
            browsers.values.toList().forEach { browser ->
                if (browser.list.isAttachedToWindow &&
                    browser.currentFolderId != null &&
                    browser.overlay.visibility == View.VISIBLE &&
                    isFrontFragmentView(browser.list)
                ) safeRender(browser)
            }
        }
        return true
    }

    fun onNativeRecyclerObserved(view: View?) {
        val list = view as? ViewGroup ?: return
        if (resourceName(list) == "quickNavRecyclerView") {
            observeNativeBreadcrumb(list)
            return
        }
        if (resourceName(list) != "playlistListRecyclerView") return
        val adapter = nativeAdapter(list)
        if (adapter != null && adapter.javaClass.name != "zn3") return
        knownLists[list] = true
        if (suspendedNativeLists.containsKey(list)) return
        if (!pendingLayoutObservers.containsKey(list)) {
            val weakList = WeakReference(list)
            val observer = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                weakList.get()?.let { current ->
                    // After a native playlist click, wait until we have
                    // actually seen the old list go behind another fragment
                    // before allowing it to regain folders on Back. This
                    // prevents our browser from covering the new screen.
                    suspendedNativeLists[current]?.let { hold ->
                        val front = current.isAttachedToWindow &&
                            current.isShown &&
                            isFrontFragmentView(current)
                        if (!front) {
                            hold.leftForeground = true
                        } else if (hold.leftForeground) {
                            suspendedNativeLists.remove(current)
                            Log.i(
                                TAG,
                                "FOLDER INLINE RETURN | old list visible again"
                            )
                        }
                    }
                    if (settings.enabled && browsers[current] == null &&
                        !suspendedNativeLists.containsKey(current) &&
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
                        // Retain the original alpha while the SAME
                        // RecyclerView can be reused after navigation.
                        suspendedNativeLists.remove(original)
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
        if (browser.overlay.visibility != View.VISIBLE ||
            browser.nativeNavigationInProgress
        ) return false
        if (browser.currentFolderId == null) return false
        val folder = findFolder(browser.index, browser.currentFolderId)
        browser.currentFolderId = parentFolderId(browser, folder)
        rememberFolder(browser)
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
            suspendedNativeLists.containsKey(list) ||
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
            suspendedNativeLists.containsKey(list) ||
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
        // Match GMMP's Files tab: a fixed horizontal navigation strip
        // above the scrollable contents, not a fake Back playlist row.
        // The strip is GONE in root, preserving its original height.
        val breadcrumbScroller = HorizontalScrollView(list.context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val breadcrumbRows = LinearLayout(list.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbScroller.addView(
            breadcrumbRows,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val contentColumn = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentColumn.addView(
            breadcrumbScroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        contentColumn.addView(
            scroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        overlay.addView(
            contentColumn,
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
                            scheduleNativePlaylistRefresh(browser)
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
                weakList.get()?.let { list ->
                    removeBrowser(
                        list,
                        // Keep the native ungrouped picker list hidden as well
                        // while GMMP replaces its fragment after creation.
                        // The next attached browser reuses originalAlpha;
                        // bounded attach failure restores it explicitly.
                        preserveNativeAlpha = settings.enabled
                    )
                }
            }
        }

        val rememberedFolder = folderMemory.restore(surface(list)) {
            findFolder(index, it) != null
        }

        val browser = Browser(
            list = list,
            parent = parent,
            overlay = overlay,
            rows = rows,
            breadcrumbScroller = breadcrumbScroller,
            breadcrumbRows = breadcrumbRows,
            rootPath = root,
            index = index,
            modelsByPath = native.nativeObjects,
            nativeOrder = native.paths,
            originalAlpha = nativeOriginalAlphas.getOrPut(list) { list.alpha },
            layoutListener = layoutListener,
            themeListener = themeListener,
            detachListener = detachListener,
            currentFolderId = rememberedFolder
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
            nativeOriginalAlphas[list]?.let { list.alpha = it }
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
        // Offscreen ViewPager pages may remain attached: they must not
        // intercept input on Now Playing or other library tabs.
        val visibleBounds = Rect()
        val frontFragment = isFrontFragmentView(list)
        val nativeVisible = frontFragment && list.isShown &&
            list.getGlobalVisibleRect(visibleBounds) &&
            visibleBounds.width() > dp(list, 30) &&
            visibleBounds.height() > dp(list, 30)
        val nextVisibility = if (nativeVisible) View.VISIBLE else View.GONE
        if (overlay.visibility != nextVisibility) {
            overlay.visibility = nextVisibility
            Log.i(
                TAG,
                "FOLDER INLINE VISIBILITY | surface=" + surface(list) +
                    " | visible=" + nativeVisible +
                    " | frontFragment=" + frontFragment +
                    " | nativeShown=" + list.isShown
            )
        }
        if (!nativeVisible) return
        updatePickerFab(browser)
        updatePlaylistMenu()
        val breadcrumbSignature =
            observedNativeBreadcrumbStyle?.signature ?: "native-playlist-fallback"
        if (browser.currentFolderId != null &&
            browser.renderedBreadcrumbSignature != breadcrumbSignature &&
            !browser.breadcrumbRefreshPending
        ) {
            browser.breadcrumbRefreshPending = true
            list.post {
                browser.breadcrumbRefreshPending = false
                if (browsers[list] === browser &&
                    browser.renderedBreadcrumbSignature != breadcrumbSignature &&
                    list.isAttachedToWindow
                ) safeRender(browser)
            }
        }
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

    private fun removeBrowser(
        list: ViewGroup,
        preserveNativeAlpha: Boolean = false
    ) {
        val browser = browsers.remove(list) ?: return
        styles.remove(list)

        // GMMP's FragmentManager may be iterating the same ancestor's
        // children while dispatching detach. Never remove our sibling
        // synchronously from inside that detach callback.
        browser.overlay.visibility = View.GONE
        browser.overlay.isClickable = false
        browser.overlay.isFocusable = false
        list.alpha = if (preserveNativeAlpha) 0f else browser.originalAlpha
        list.removeOnAttachStateChangeListener(browser.detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.removeOnGlobalLayoutListener(
                browser.layoutListener
            )
            list.viewTreeObserver.removeOnPreDrawListener(
                browser.themeListener
            )
        }
        if (activeBrowser.get() === list) activeBrowser.clear()

        val overlay = browser.overlay
        val parent = browser.parent
        mainHandler.post {
            runCatching {
                if (overlay.parent === parent) parent.removeView(overlay)
            }.onFailure {
                Log.e(TAG, "FOLDER INLINE CLEANUP | deferred remove", it)
            }
        }
        // Native picker owns the FAB; only touch it while still attached.
        if (list.isAttachedToWindow) {
            multiSelect.folderNativeFab(list)?.let { fab ->
                if (fab.isAttachedToWindow) fab.visibility = View.VISIBLE
            }
        }
        mainHandler.post { updatePlaylistMenu() }
        Log.i(
            TAG,
            "FOLDER INLINE CLEANUP | surface=" + surface(list) +
                " | deferred=true"
        )
    }

    /**
     * GMMP can update its adapter without replacing the RecyclerView.
     * Refresh the already-visible folder browser when its full native
     * playlist model changes; never wait for a tab switch or scan M3Us.
     * Work is posted outside the pre-draw/layout traversal.
     */
    private fun scheduleNativePlaylistRefresh(browser: Browser) {
        if (browser.nativeRefreshPending || browser.nativeNavigationInProgress ||
            !browser.list.isAttachedToWindow ||
            browsers[browser.list] !== browser
        ) return
        val adapter = nativeAdapter(browser.list) ?: return
        val count = runCatching {
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrNull() ?: return
        if (count == browser.nativeOrder.size) return
        browser.nativeRefreshPending = true
        mainHandler.post {
            browser.nativeRefreshPending = false
            if (browsers[browser.list] !== browser ||
                !browser.list.isAttachedToWindow ||
                browser.nativeNavigationInProgress
            ) return@post
            runCatching {
                val currentAdapter = nativeAdapter(browser.list) ?: return@runCatching
                val actualCount = currentAdapter.javaClass
                    .getMethod("getItemCount").invoke(currentAdapter) as Int
                if (actualCount == browser.nativeOrder.size || actualCount <= 0) {
                    return@runCatching
                }
                val native = NativePlaylistSourceInspector.inspect(
                    currentAdapter, actualCount
                )
                if (native.truncated || native.paths.size != actualCount ||
                    native.nativeObjects.size != actualCount
                ) return@runCatching
                val visibleTitles = currentlyVisibleTitles(browser.list)
                val titles = NativePlaylistTitleResolver.resolve(
                    native.models, visibleTitles
                )
                if (titles.nativeTitles != actualCount) return@runCatching
                val refreshed = PlaylistFolderIndex.build(
                    nativePlaylistPaths = native.paths,
                    mainPlaylistDirectory = browser.rootPath,
                    groupExternalLocations = settings.groupExternal,
                    groupRootPlaylists = settings.groupRoot,
                    displayNamesByPath = titles.names + visibleTitles
                )
                browser.index = refreshed
                browser.modelsByPath = native.nativeObjects
                browser.nativeOrder = native.paths
                if (browser.currentFolderId != null &&
                    findFolder(refreshed, browser.currentFolderId) == null
                ) {
                    browser.currentFolderId = null
                    rememberFolder(browser)
                }
                safeRender(browser)
                Log.i(
                    TAG,
                    "FOLDER INLINE REFRESH | surface=" + surface(browser.list) +
                        " | models=" + actualCount
                )
            }.onFailure {
                Log.w(TAG, "FOLDER INLINE REFRESH | retry on next frame", it)
            }
        }
    }

    /**
     * GMMP's Files page uses a persistent row of individually clickable
     * ancestor segments. Reuse its localized "storage" string and its
     * observed quickNav typography when available; the already-sampled
     * native playlist headline is the documented defensive fallback.
     */
    private fun renderBreadcrumb(browser: Browser) {
        val list = browser.list
        val strip = browser.breadcrumbScroller
        val previousScrollX = strip.scrollX
        val folderChanged =
            browser.lastBreadcrumbFolderId != browser.currentFolderId
        browser.lastBreadcrumbFolderId = browser.currentFolderId
        val generation = ++browser.breadcrumbRenderGeneration
        val segments = PlaylistBreadcrumbPath.forFolder(
            browser.index, browser.currentFolderId
        )
        browser.renderedBreadcrumbSignature =
            observedNativeBreadcrumbStyle?.signature ?: "native-playlist-fallback"
        if (segments.isEmpty()) {
            strip.visibility = View.GONE
            browser.breadcrumbRows.removeAllViews()
            return
        }

        val native = styles[list]
        val nav = observedNativeBreadcrumbStyle
        val height = nav?.rowHeightPx ?: native?.rowHeight ?: dp(list, 48)
        strip.layoutParams = strip.layoutParams.apply { this.height = height }
        strip.visibility = View.VISIBLE
        val foreground = native?.textColor
            ?: nav?.effectivePaint?.color ?: resolveTextColor(list)
        val background = native?.let {
            nativeContentBackground(list, browser.parent, it)
        }
        if (background != null) strip.background = background

        val storageResource = list.resources.getIdentifier(
            "storage", "string", list.context.packageName
        )
        val storageLabel = if (storageResource != 0) {
            list.context.getString(storageResource)
        } else {
            // Neutral icon if a future GMMP version removes its native
            // "storage" string. Never insert an untranslated English label.
            "⌂"
        }
        browser.breadcrumbRows.removeAllViews()
        segments.forEachIndexed { position, segment ->
            if (position != 0) {
                val separator = ImageView(list.context).apply {
                    val arrow = resources.getIdentifier(
                        "ic_gm_keyboard_arrow_right", "drawable",
                        context.packageName
                    )
                    if (arrow != 0) {
                        setImageResource(arrow)
                        imageTintList = android.content.res.ColorStateList
                            .valueOf(foreground)
                    } else {
                        setImageDrawable(null)
                    }
                    importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                browser.breadcrumbRows.addView(
                    separator,
                    LinearLayout.LayoutParams(dp(list, 24), dp(list, 24)).apply {
                        marginStart = dp(list, 8)
                        marginEnd = dp(list, 8)
                    }
                )
            }
            val title = if (position == 0) storageLabel else segment.name
            val label = TextView(list.context).apply {
                text = title
                val paintSource = nav?.effectivePaint ?: native?.effectivePaint
                if (paintSource != null) {
                    paint.set(paintSource)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX, paintSource.textSize
                    )
                }
                typeface = nav?.effectivePaint?.typeface
                    ?: Typeface.create(native?.typeface, Typeface.BOLD)
                setTextColor(foreground)
                letterSpacing = nav?.letterSpacing ?: native?.letterSpacing ?: 0f
                includeFontPadding = nav?.includeFontPadding
                    ?: native?.includeFontPadding ?: true
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(
                    nav?.paddingStartPx ?: dp(list, 6), 0,
                    nav?.paddingEndPx ?: dp(list, 6), 0
                )
                this.background = nav?.nativeTouchBackground
                    ?.newDrawable(list.resources)?.mutate()
                    ?: typedSelectableBackground(list, borderless = true)
                isClickable = segment.folderId != browser.currentFolderId
                isFocusable = isClickable
                if (isClickable) {
                    setOnClickListener {
                        if (browsers[list] !== browser) {
                            return@setOnClickListener
                        }
                        browser.currentFolderId = segment.folderId
                        rememberFolder(browser)
                        activeBrowser = WeakReference(list)
                        safeRender(browser)
                        updatePlaylistMenu()
                        updatePickerFab(browser)
                    }
                }
            }
            browser.breadcrumbRows.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        // Do not snap back to the right on an ordinary theme/layout
        // refresh; this previously made manual breadcrumb swipes unreliable.
        // Only navigation changes should reveal the newest path component.
        val reposition = object : Runnable {
            var retries = 0
            override fun run() {
                if (browsers[list] !== browser ||
                    browser.breadcrumbRenderGeneration != generation ||
                    strip.visibility != View.VISIBLE ||
                    !strip.isAttachedToWindow
                ) return
                if ((strip.width == 0 || browser.breadcrumbRows.width == 0) &&
                    ++retries <= 3
                ) {
                    strip.postOnAnimation(this)
                    return
                }
                val x = PlaylistBreadcrumbScrollPolicy.targetX(
                    folderChanged,
                    previousScrollX,
                    browser.breadcrumbRows.width,
                    strip.width
                )
                strip.scrollTo(x, 0)
            }
        }
        strip.postOnAnimation(reposition)
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
        // The native row's rvContextMenu is an actual GMMP-bound click
        // target. Preserve that control on every synthetic playlist row;
        // folder navigation and the Add picker do not get a fake menu.
        val nativeMenu = if (isPicker(list)) null else {
            firstBoundNativeContextMenu(list)
        }
        if (!isPicker(list) && nativeMenu == null &&
            observedMenus.add("native-playlist-row-menu-unavailable")
        ) {
            Log.w(
                TAG,
                "FOLDER CONTEXT MENU | native rvContextMenu not bound" +
                    " | rows=" + list.childCount
            )
        }
        browser.rows.removeAllViews()
        renderBreadcrumb(browser)
        updatePickerFab(browser)

        for (child in folders) {
            browser.rows.addView(
                row(
                    list,
                    child.name,
                    folder = true,
                    selected = false
                ).apply {
                    setOnClickListener {
                        browser.currentFolderId = child.id
                        rememberFolder(browser)
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
                    selected = selected,
                    nativeMenuButton = nativeMenu,
                    onNativeContextMenu = if (model != null && nativeMenu != null) {
                        {
                            activeBrowser = WeakReference(list)
                            dispatchNativeAction(
                                browser, model,
                                longClick = false, contextMenu = true
                            )
                            Unit
                        }
                    } else null
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
        selected: Boolean,
        nativeMenuButton: ImageView? = null,
        onNativeContextMenu: (() -> Unit)? = null
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
            if (native != null) {
                // Reuse GMMP's exact currently-rendered title style. The
                // source CharSequence may contain TextAppearance/size spans
                // that are NOT represented by TextView.textSize alone.
                // native.textSizePx is now the live EFFECTIVE native title
                // paint size after GMMP's MetricAffectingSpan processing.
                // Applying both that effective size and the original
                // RelativeSizeSpan would double-scale it.
                target.text = text
                target.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX, native.textSizePx
                )
                target.setTextColor(native.textColor)
                if (native.typeface != null) target.typeface = native.typeface
                target.gravity = native.titleGravity
                target.letterSpacing = native.letterSpacing
                target.textScaleX = native.textScaleX
                target.includeFontPadding = native.includeFontPadding
                target.setLineSpacing(
                    native.lineSpacingExtra,
                    native.lineSpacingMultiplier
                )
                target.maxLines = native.maxLines
                target.ellipsize = native.ellipsize
                target.setPaddingRelative(
                    native.titlePaddingStart,
                    target.paddingTop,
                    native.titlePaddingEnd,
                    target.paddingBottom
                )
                // Copy all current font features (fake bold, skew, font
                // variation, hinting and decoration) from the actual
                // native glyph paint rather than approximating them.
                target.paint.set(native.effectivePaint)
                target.requestLayout()
            } else {
                target.text = text
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
            configureNativeContextMenu(
                root, view, nativeMenuButton, onNativeContextMenu
            )
            if (folder) {
                addNativeFolderIcon(root, native, view)
            }
            styleRowSelection(root, view, native, selected)
            root.isClickable = true
            root.isFocusable = true
            return root
        }
        // Defensive fallback on custom native view modes without XML IDs.
        val fallback = TextView(view.context).apply {
            this.text = text
            setTextColor(native?.textColor ?: resolveTextColor(view))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                native?.textSizePx
                    ?: (view.resources.displayMetrics.scaledDensity * 16f)
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
        if (onNativeContextMenu == null || nativeMenuButton == null) {
            return fallback
        }
        val height = native?.rowHeight ?: dp(view, 54)
        val wrapper = FrameLayout(view.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height
            )
            minimumHeight = height
        }
        wrapper.addView(
            fallback,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height
            )
        )
        val nativeWidth = nativeMenuButton.width
            .takeIf { it > 0 } ?: dp(view, 48)
        val button = ImageButton(view.context).apply {
            id = nativeMenuResources(view).buttonId
            background = typedSelectableBackground(view)
            imageTintList = nativeMenuButton.imageTintList
                ?: android.content.res.ColorStateList.valueOf(
                    native?.textColor ?: resolveTextColor(view)
                )
        }
        configureNativeContextMenu(
            button, view, nativeMenuButton, onNativeContextMenu
        )
        wrapper.addView(
            button,
            FrameLayout.LayoutParams(
                nativeWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        return wrapper
    }

    /** Never synthesize a guessed menu: reuse the native row's own button. */
    private fun firstBoundNativeContextMenu(list: ViewGroup): ImageView? {
        val id = nativeMenuResources(list).buttonId
        if (id == 0) return null
        for (i in 0 until list.childCount) {
            val native = list.getChildAt(i) ?: continue
            val button = native.findViewById<ImageView>(id) ?: continue
            if (button.visibility == View.VISIBLE &&
                button.hasOnClickListeners()
            ) return button
        }
        return null
    }

    /**
     * The installed GMMP 4.2.0 APK's rv_listitem_metadata_compact XML
     * contains rvContextMenu, an AestheticTintedImageButton. An unbound
     * inflation lacks its icon and original click callback. Clone the
     * icon from the live bound native view and forward clicks only after
     * verifying the matching wp3.A -> xn3.q model.
     */
    private fun configureNativeContextMenu(
        root: View,
        host: View,
        source: ImageView?,
        onClick: (() -> Unit)?
    ) {
        val ids = nativeMenuResources(host)
        val button = if (ids.buttonId != 0) {
            root.findViewById<ImageView>(ids.buttonId)
        } else null
        if (button == null) return
        if (onClick == null || source == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        val original = source.drawable?.constantState
            ?.newDrawable(host.resources)?.mutate()
        if (original != null) {
            button.setImageDrawable(original)
        } else {
            if (ids.iconId != 0) button.setImageResource(ids.iconId)
        }
        button.visibility = View.VISIBLE
        button.isEnabled = true
        button.isClickable = true
        button.isFocusable = true
        source.imageTintList?.let { button.imageTintList = it }
        val description = source.contentDescription ?: run {
            if (ids.descriptionId != 0) {
                host.context.getString(ids.descriptionId)
            } else null
        }
        button.contentDescription = description
        button.setOnClickListener { onClick() }
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
        // The native ic_folder drawable is filled and too heavy in this
        // skin. Use a fine outlined vector, tinted with live GMMP text.
        val drawable = FolderOutlineDrawable(color, dp(host, 24))
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
            // This path is drawn in a 24x24 vector viewport and scaled
            // by Canvas. Width must be in viewport units, NOT pixels:
            // multiplying by sizePx and scaling again caused fat outlines.
            strokeWidth = 1.25f
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
        longClick: Boolean,
        contextMenu: Boolean = false
    ): Boolean {
        val path = modelPath(model) ?: return false
        val list = browser.list
        if (browser.actionPending || !list.isAttachedToWindow) return false

        if (performMatchingNativeAction(
                list, path, longClick, contextMenu
            )
        ) {
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
                if (!performMatchingNativeAction(
                        list, path, longClick, contextMenu
                    )
                ) {
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
        longClick: Boolean,
        contextMenu: Boolean
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
                // Keep the folder browser visible until GMMP's NEW
                // fragment actually reaches the foreground. Hiding it before
                // performClick() caused a one-frame flash of the original
                // native playlist list.
                val current = browsers[list]
                val opensPlaylist = !longClick && !contextMenu
                if (opensPlaylist) {
                    current?.nativeNavigationInProgress = true
                    current?.overlay?.isClickable = false
                    current?.overlay?.isFocusable = false
                    current?.let(::rememberFolder)
                }
                val handled = when {
                    contextMenu -> {
                        val menuId = nativeMenuResources(list).buttonId
                        val button = if (menuId != 0) {
                            nativeRow.findViewById<View>(menuId)
                        } else null
                        if (button?.visibility == View.VISIBLE &&
                            button.hasOnClickListeners()
                        ) {
                            button.performClick()
                        } else {
                            Log.w(
                                TAG,
                                "FOLDER CONTEXT MENU | native target missing" +
                                    " | holder=" + holder.javaClass.name
                            )
                            false
                        }
                    }
                    longClick -> nativeRow.performLongClick()
                    else -> nativeRow.performClick()
                }
                if (!handled && opensPlaylist) {
                    current?.nativeNavigationInProgress = false
                    if (current != null && browsers[list] === current) {
                        current.overlay.isClickable = true
                        current.overlay.isFocusable = true
                        positionOverlay(current)
                    }
                } else if (handled && opensPlaylist && current != null) {
                    waitForNativeNavigationThenRetire(list, current, 0)
                }
                Log.i(
                    TAG,
                    "FOLDER INLINE ACTION | surface=" + surface(list) +
                        " | type=" +
                        (if (contextMenu) "context" else if (longClick) {
                            "long"
                        } else "click") +
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

    private fun rememberFolder(browser: Browser) {
        folderMemory.remember(
            surface(browser.list),
            browser.currentFolderId
        )
    }

    private fun waitForNativeNavigationThenRetire(
        list: ViewGroup,
        browser: Browser,
        frame: Int
    ) {
        if (browsers[list] !== browser) return
        val stillFront = list.isAttachedToWindow &&
            list.isShown &&
            isFrontFragmentView(list)

        if (!stillFront) {
            // We have now observed the native detail page taking over, so
            // retiring the old browser cannot reveal the native root list.
            suspendedNativeLists[list] = NavigationHold(leftForeground = true)
            browser.overlay.visibility = View.GONE
            removeBrowser(list, preserveNativeAlpha = true)
            return
        }

        if (frame >= 32) {
            // Native click reported handled but no navigation appeared.
            // Restore the browser rather than leaving an inert overlay.
            browser.nativeNavigationInProgress = false
            browser.overlay.isClickable = true
            browser.overlay.isFocusable = true
            positionOverlay(browser)
            Log.w(
                TAG,
                "FOLDER INLINE ACTION | native navigation not observed; " +
                    "browser restored"
            )
            return
        }

        list.postOnAnimation {
            waitForNativeNavigationThenRetire(list, browser, frame + 1)
        }
    }

    /**
     * Preserve the actual styling spans from GMMP's bound title while
     * substituting only its text. This follows Android font scale, GMMP
     * TextAppearance and alternate native view modes without a GoneSmart
     * pixel/sp multiplier.
     */
    private fun nativeStyledText(
        source: CharSequence?,
        replacement: String
    ): CharSequence {
        val spanned = source as? Spanned ?: return replacement
        if (replacement.isEmpty() || spanned.isEmpty()) return replacement

        val out = SpannableString(replacement)
        val oldLength = spanned.length
        val newLength = out.length
        val spans = spanned.getSpans(
            0,
            oldLength,
            CharacterStyle::class.java
        )
        spans.forEach { span ->
            if (span !is MetricAffectingSpan &&
                span !is android.text.style.ForegroundColorSpan
            ) return@forEach
            val oldStart = spanned.getSpanStart(span).coerceAtLeast(0)
            val oldEnd = spanned.getSpanEnd(span).coerceAtMost(oldLength)
            if (oldEnd <= oldStart) return@forEach
            val newStart = if (oldStart == 0) 0 else {
                ((oldStart.toDouble() / oldLength) * newLength)
                    .toInt().coerceIn(0, newLength)
            }
            val newEnd = if (oldEnd == oldLength) newLength else {
                kotlin.math.ceil(
                    (oldEnd.toDouble() / oldLength) * newLength
                ).toInt().coerceIn(newStart, newLength)
            }
            if (newEnd <= newStart) return@forEach
            val copy = CharacterStyle.wrap(span)
            runCatching {
                out.setSpan(
                    copy,
                    newStart,
                    newEnd,
                    spanned.getSpanFlags(span)
                )
            }
        }
        return out
    }

    private fun effectiveNativeTitlePaint(title: TextView): TextPaint {
        val paint = TextPaint(title.paint)
        val source = title.text as? Spanned ?: return paint
        if (source.isEmpty()) return paint
        // Android applies metric spans before draw-only spans. Include
        // spans only when they cover the native title's first glyph.
        val spans = source.getSpans(
            0, 1, CharacterStyle::class.java
        ).filter {
            source.getSpanStart(it) <= 0 && source.getSpanEnd(it) > 0
        }
        spans.filterIsInstance<MetricAffectingSpan>()
            .forEach { it.updateMeasureState(paint) }
        spans.filterNot { it is MetricAffectingSpan }
            .forEach { it.updateDrawState(paint) }
        return paint
    }

    private fun nativeSpanDetails(source: CharSequence?): String {
        val text = source as? Spanned ?: return "none"
        return text.getSpans(
            0, text.length, CharacterStyle::class.java
        ).joinToString(";") { span ->
            val factor = if (span is RelativeSizeSpan) {
                ":factor=" + span.sizeChange
            } else ""
            val color = if (span is ForegroundColorSpan) {
                ":color=" + span.foregroundColor
            } else ""
            span.javaClass.simpleName + "[" +
                text.getSpanStart(span) + "," +
                text.getSpanEnd(span) + "]" + factor + color
        }.ifBlank { "none" }
    }

    private fun nativeSpanSignature(text: CharSequence?): String {
        val spanned = text as? Spanned ?: return "none"
        return spanned.getSpans(
            0,
            spanned.length,
            CharacterStyle::class.java
        ).map { it.javaClass.simpleName }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifBlank { "none" }
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
            // Read the *effective* native headline TextPaint after the
            // actual MetricAffectingSpan/ForegroundColorSpan for its first
            // title glyph. TextView.textSize alone omits RelativeSizeSpan
            // (30px base was visibly undersized on this GMMP skin).
            val textTemplate = title.text
            val nativePaint = effectiveNativeTitlePaint(title)
            val color = nativePaint.color
            val size = nativePaint.textSize
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
                nativePaint.typeface?.style ?: 0,
                rowBackground?.javaClass?.name,
                nativeRow.sourceLayoutResId, title.id, matchedNativeTitle,
                textTemplate?.javaClass?.name,
                nativeSpanSignature(textTemplate),
                title.letterSpacing,
                title.textScaleX,
                title.includeFontPadding
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
                        " | effectiveNativePx=" + size +
                        " | spanDetails=" +
                            nativeSpanDetails(textTemplate) +
                        " | textClass=" +
                            (textTemplate?.javaClass?.name ?: "null") +
                        " | spans=" + nativeSpanSignature(textTemplate) +
                        " | bg=" +
                            (nativeRow.background?.javaClass?.name ?: "none") +
                        " | style=" + signature
                )
            }
            return NativeRowStyle(
                textColor = color,
                textSizePx = size,
                typeface = nativePaint.typeface,
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
                titlePaddingEnd = title.paddingEnd,
                textTemplate = textTemplate,
                effectivePaint = nativePaint,
                letterSpacing = title.letterSpacing,
                textScaleX = title.textScaleX,
                includeFontPadding = title.includeFontPadding,
                lineSpacingExtra = title.lineSpacingExtra,
                lineSpacingMultiplier = title.lineSpacingMultiplier,
                maxLines = title.maxLines,
                ellipsize = title.ellipsize
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
        // AestheticCoordinatorLayout in the Add dialog exposes #303030
        // even when the native GMMP list is drawn on the black window
        // background. Sample the genuine, live window surface first.
        val window = list.rootView.background as? ColorDrawable
        if (window != null && Color.alpha(window.color) == 255) {
            return window.color
        }
        var current: View? = list
        repeat(7) {
            val view = current ?: return@repeat
            val background = view.background as? ColorDrawable
            if (background != null &&
                Color.alpha(background.color) == 255
            ) return background.color
            current = view.parent as? View
        }
        return resolveBackground(list)
    }

    /**
     * GMMP keeps the former Playlists fragment alive behind Now Playing
     * and playlist-details screens. getGlobalVisibleRect() does not detect
     * a later full-screen sibling occluding that old fragment, so look at
     * the actual topmost page in GMMP's mainFragmentSlot as well.
     */
    private fun isFrontFragmentView(list: ViewGroup): Boolean {
        if (isPicker(list)) return true
        var cursor: View? = list
        var slot: ViewGroup? = null
        while (cursor != null) {
            if (cursor is ViewGroup &&
                resourceName(cursor) == "mainFragmentSlot"
            ) {
                slot = cursor
                break
            }
            cursor = cursor.parent as? View
        }
        val host = slot ?: return true
        val ownPage = directChildInHost(list, host) ?: return false
        for (index in host.childCount - 1 downTo 0) {
            val candidate = host.getChildAt(index) ?: continue
            if (candidate.visibility != View.VISIBLE ||
                candidate.alpha <= 0.01f ||
                !candidate.isAttachedToWindow ||
                candidate.width <= 0 || candidate.height <= 0
            ) continue
            return candidate === ownPage
        }
        return false
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
        // Mirror the current GMMP window surface, not the intermediate
        // gray picker CoordinatorLayout; the original row is transparent.
        val originalWindow = list.rootView.background
        val windowDrawable = runCatching {
            originalWindow?.constantState?.newDrawable(list.resources)?.mutate()
        }.getOrNull()
        if (windowDrawable != null) return windowDrawable
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
        val folderId = normal?.currentFolderId
        val state = if (normal == null) {
            // Before the folder browser attaches we cannot safely infer a
            // writable root. Only apply the one rule that is independent of
            // that path: root creation is hidden while root grouping is on.
            PlaylistCreationUiPolicy.State(
                normalMenuVisible = !settings.enabled || !settings.groupRoot,
                pickerFabVisible = true,
                destination = null,
                physicalDestinationUnsupported = false
            )
        } else {
            PlaylistCreationUiPolicy.state(
                foldersEnabled = settings.enabled,
                currentFolderId = folderId,
                mainPlaylistDirectory = normal.rootPath,
                groupRootPlaylists = settings.groupRoot,
                hasPickerSelection = false
            )
        }
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val id = runCatching {
                val context = normal?.list?.context
                    ?: knownLists.keys.firstOrNull()?.context
                context?.resources?.getResourceEntryName(item.itemId)
            }.getOrNull()
            if (id == "menuAdd") {
                if (item.isVisible == state.normalMenuVisible) break
                item.isVisible = state.normalMenuVisible
                Log.i(
                    TAG,
                    "FOLDER CREATE MENU | visible=" +
                        state.normalMenuVisible +
                        " | folder=" + (folderId ?: "root") +
                        " | destination=" + state.destination +
                        " | rootGrouping=" + settings.groupRoot +
                        " | externalGrouping=" + settings.groupExternal +
                        " | physicalUnsupported=" +
                            state.physicalDestinationUnsupported
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
        val state = PlaylistCreationUiPolicy.state(
            foldersEnabled = settings.enabled,
            currentFolderId = browser.currentFolderId,
            mainPlaylistDirectory = browser.rootPath,
            groupRootPlaylists = settings.groupRoot,
            hasPickerSelection = selected
        )
        val show = state.pickerFabVisible
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
        val state = PlaylistCreationUiPolicy.state(
            foldersEnabled = settings.enabled,
            currentFolderId = browser.currentFolderId,
            mainPlaylistDirectory = browser.rootPath,
            groupRootPlaylists = settings.groupRoot,
            hasPickerSelection = false
        )
        val destination = state.destination
        if (destination == null) {
            Log.i(TAG, "FOLDER CREATE BLOCK | location forbids creation")
            return true
        }
        if (state.physicalDestinationUnsupported) {
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
        view: View,
        borderless: Boolean = false
    ): android.graphics.drawable.Drawable? {
        val out = TypedValue()
        val attribute = if (borderless) {
            android.R.attr.selectableItemBackgroundBorderless
        } else android.R.attr.selectableItemBackground
        return if (view.context.theme.resolveAttribute(
                attribute,
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
