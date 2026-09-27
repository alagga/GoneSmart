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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.DefaultItemAnimator
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
        // GMMP 4.2.0 native quickNav measured 58.8px on the same skin
        // whose bound playlist title measured 48px. Use this ratio only
        // until a real native quickNav row can be sampled.
        private const val GMMP_420_QUICK_NAV_TITLE_RATIO = 1.225f
        private const val QUICK_NAV_METRICS_PREFS =
            "gonesmart_gmmp_quicknav_metrics"
        private const val QUICK_NAV_TITLE_RATIO_KEY = "title_ratio"
        private const val QUICK_NAV_VERIFIED_FIRST_X_KEY = "verified_first_text_x_dpi_"
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
        val paddingTopPx: Int,
        val paddingBottomPx: Int,
        val nativeFirstTextStartPx: Int,
        val nativeSeparatorWidthPx: Int?,
        val nativeTouchBackground: Drawable.ConstantState?,
        val edgeEffectFactory: RecyclerView.EdgeEffectFactory?,
        val nativeOverScrollMode: Int,
        val nativeClipToPadding: Boolean,
        val nativeNestedScrolling: Boolean,
        val nativeHeaderStartPx: Int,
        val nativeHeaderEndPx: Int,
        val nativeItemAnimator: SimpleItemAnimator?,
        val signature: String
    )

    private data class Browser(
        val list: ViewGroup,
        val parent: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val breadcrumbScroller: RecyclerView,
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
        var breadcrumbContentSignature: String? = null,
        var lastBreadcrumbFolderId: String? = null,
        var breadcrumbRenderGeneration: Long = 0L,
        var lastRenderedFolderId: String? = null,
        var lastRenderedOrder: List<String>? = null,
        var breadcrumbAdapter: NativeQuickNavAdapter? = null,
        val mainSelection: NativeMainPlaylistSelectionMirror =
            NativeMainPlaylistSelectionMirror(),
        var folderFab: View? = null,
        var playlistFab: View? = null,
        var pickerAddExpanded: Boolean = false,
        var forwardingOriginalFab: Boolean = false,
        // Destination-selection mode reuses the actual Playlists-tab
        // browser, original GMMP row XML and already sampled quickNav.
        // No independent folder-list dialog or guessed native UI.
        var moveSources: List<String>? = null,
        var movePreviousFolder: String? = null,
        var moveActionMode: android.view.ActionMode? = null,
        var moveFab: View? = null,
        var moveFabColor: Int? = null,
        var moveLastColor: Int? = null,
        var moveBarTintApplied: Boolean = false,
        var moveLastBottomOcclusion: Int = -1,
        var moveThemeObserver: Any? = null,
        val moveThemeDisposables: MutableList<Any> = arrayListOf()
    )

    /**
     * The actual GMMP 4.2.0 qg1 adapter alternates the native
     * rv_horiz_metadata and rv_horiz_separator layouts (positions % 2).
     * Reuse both of those exact resource layouts and RecyclerView's real
     * per-item insert/remove animations. The old one-wide-holder layout
     * forced guesses for the first inset and used a 40dp separator where
     * GMMP's real separator layout measures 64dp on the tested device.
     */
    private inner class NativeQuickNavAdapter(
        private val browser: Browser
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val segments = arrayListOf<PlaylistBreadcrumbPath.Segment>()
        private var rootLabel = ""
        private var currentStyle = ""

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int =
            NativeQuickNavDiff.itemCount(segments.size)

        override fun getItemViewType(position: Int): Int = position % 2

        override fun getItemId(position: Int): Long {
            val segment = segments[position / 2]
            val identifier = (segment.folderId ?: "root").hashCode().toLong()
            return (identifier shl 1) xor (position % 2).toLong()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder {
            val name = if (viewType == 0) {
                "rv_horiz_metadata"
            } else "rv_horiz_separator"
            val layoutId = parent.resources.getIdentifier(
                name, "layout", parent.context.packageName
            )
            val native = if (layoutId != 0) runCatching {
                LayoutInflater.from(parent.context).inflate(
                    layoutId, parent, false
                )
            }.onFailure {
                Log.w(TAG, "FOLDER QUICKNAV | native XML inflation failed", it)
            }.getOrNull() else null
            // Only for a future GMMP version that removes the two verified
            // 4.2.0 layouts. Normal operation uses the original native XML.
            val view = native ?: if (viewType == 0) {
                TextView(parent.context).apply {
                    minHeight = dp(parent, 48)
                    gravity = Gravity.CENTER_VERTICAL
                    background = typedSelectableBackground(
                        parent, borderless = true
                    )
                }
            } else {
                ImageView(parent.context).apply {
                    val icon = resources.getIdentifier(
                        "ic_gm_keyboard_arrow_right", "drawable",
                        context.packageName
                    )
                    if (icon != 0) setImageResource(icon)
                    setPadding(dp(parent, 8), 0, dp(parent, 8), 0)
                }
            }
            if (native == null) {
                Log.w(
                    TAG, "FOLDER QUICKNAV | XML missing | layout=" + name
                )
            }
            if (view.layoutParams == null) {
                view.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder, position: Int
        ) {
            if (position % 2 != 0) return
            val segment = segments.getOrNull(position / 2) ?: return
            val label = holder.itemView as? TextView ?: return
            val list = browser.list
            val nav = observedNativeBreadcrumbStyle
            val native = styles[list]
            label.text = if (position == 0) rootLabel else segment.name
            val nativePaint = nav?.effectivePaint ?: native?.effectivePaint
            if (nativePaint != null) {
                label.paint.set(nativePaint)
                val nativeSize = if (nav != null) nativePaint.textSize
                    else nativePaint.textSize * nativeQuickNavTitleRatio(list)
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, nativeSize)
            }
            label.typeface = nav?.effectivePaint?.typeface
                ?: Typeface.create(native?.typeface, Typeface.BOLD)
            label.setTextColor(nav?.effectivePaint?.color
                ?: native?.textColor ?: resolveTextColor(list))
            label.letterSpacing = nav?.letterSpacing
                ?: native?.letterSpacing ?: 0f
            label.includeFontPadding = nav?.includeFontPadding
                ?: native?.includeFontPadding ?: true
            // The original native XML already contains its own text
            // padding. The former early "native" sample read zero before
            // qg1 was bound, then ERASED the XML's true start padding.
            // Never overwrite this real layout with an unverified sample.
            // Native rv_horiz_metadata already supplies the exact item
            // width, minHeight, inset and ROUND selectable background.
            // Never overwrite its XML padding or ripple with guessed dp.
            label.isClickable = true
            label.isFocusable = true
            label.setOnClickListener {
                if (browsers[list] !== browser ||
                    browser.currentFolderId == segment.folderId
                ) return@setOnClickListener
                label.postOnAnimation {
                    if (browsers[list] !== browser ||
                        browser.currentFolderId == segment.folderId
                    ) return@postOnAnimation
                    browser.currentFolderId = segment.folderId
                    if (browser.moveSources == null) rememberFolder(browser)
                    activeBrowser = WeakReference(list)
                    safeRender(browser)
                    updatePlaylistMenu()
                    updatePickerFab(browser)
                }
            }
        }

        /**
         * qg1 is an alternating list of text and arrow holders:
         * [Storage, >, Music, >, House].
         * Native notifyItemRangeInserted/Removed, unlike notifyDataSetChanged
         * on a single giant holder, activates the installed ItemAnimator on
         * BOTH entering and returning from a nested folder.
         */
        fun submit(
            next: List<PlaylistBreadcrumbPath.Segment>,
            localizedRoot: String,
            styleSignature: String
        ): Boolean {
            val oldIds = segments.map { (it.folderId ?: "root") + "\u0000" + it.name }
            val newIds = next.map { (it.folderId ?: "root") + "\u0000" + it.name }
            val diff = NativeQuickNavDiff.between(oldIds, newIds)
            val styleChanged = styleSignature != currentStyle ||
                rootLabel != localizedRoot
            val pathChanged = oldIds != newIds
            rootLabel = localizedRoot
            currentStyle = styleSignature

            if (diff.removedCount > 0) {
                segments.subList(diff.sharedSegments, segments.size).clear()
                notifyItemRangeRemoved(diff.retainedItems, diff.removedCount)
            }
            if (diff.insertedCount > 0) {
                segments.addAll(next.drop(diff.sharedSegments))
                notifyItemRangeInserted(diff.retainedItems, diff.insertedCount)
            }
            if (styleChanged && itemCount > 0 && !pathChanged) {
                notifyItemRangeChanged(0, itemCount, "native-style")
            }
            return pathChanged
        }
    }

    private var settings = Settings()
    private var nativeMainCreateRedirectReady = false
    private var nativePickerCreateRedirectReady = false
    private var nativeFolderCreator: NativeGmmpFolderCreator? = null
    private var nativeFolderDeletion: NativeGmmpFolderDeletion? = null
    private data class PendingFolderDeletion(
        val plan: FolderDeletePolicy.Plan,
        var checksRemaining: Int = 100
    )
    private val pendingFolderDeletes = arrayListOf<PendingFolderDeletion>()

    fun setNativeFolderCreator(hostClassLoader: ClassLoader) {
        nativeFolderCreator = NativeGmmpFolderCreator(hostClassLoader)
        nativeFolderDeletion = NativeGmmpFolderDeletion(hostClassLoader)
        updatePlaylistMenu()
    }

    /** Only inspect directories: GMMP's native model owns all M3U files. */
    private fun physicalDirectorySnapshot(rootPath: String): List<String> {
        val root = runCatching { java.io.File(rootPath).canonicalFile }
            .getOrNull() ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val found = arrayListOf<String>()
        val pending = ArrayDeque<java.io.File>()
        pending.add(root)
        val prefix = root.path.trimEnd(java.io.File.separatorChar) +
            java.io.File.separator
        while (pending.isNotEmpty() && found.size < 1024) {
            val folder = pending.removeFirst()
            val children = folder.listFiles()?.filter { it.isDirectory }
                ?: continue
            for (child in children) {
                val canonical = runCatching { child.canonicalFile }
                    .getOrNull() ?: continue
                if (!canonical.path.startsWith(prefix) ||
                    canonical.path in found
                ) continue
                found.add(canonical.path)
                pending.add(canonical)
                if (found.size >= 1024) break
            }
        }
        return found
    }

    data class PhysicalCreationTarget(
        val nativeRoot: String,
        val destination: String
    )

    fun setNativeCreateRedirectReady(main: Boolean, picker: Boolean) {
        nativeMainCreateRedirectReady = main
        nativePickerCreateRedirectReady = picker
        updatePlaylistMenu()
        browsers.values.toList().forEach(::updatePickerFab)
    }

    fun nativeCreateRedirectReady(picker: Boolean): Boolean =
        if (picker) nativePickerCreateRedirectReady else nativeMainCreateRedirectReady

    /**
     * Resolve the current PHYSICAL folder only from the live native-model
     * folder index. Root / virtual Other Locations return null so their
     * verified original GMMP creation remains unchanged.
     */
    fun physicalCreationTarget(picker: Boolean): PhysicalCreationTarget? {
        if (!settings.enabled) return null
        val browser = currentBrowser(picker) ?: return null
        val destination = creationDestination(browser) ?: return null
        val root = runCatching {
            java.io.File(browser.rootPath).canonicalPath
        }.getOrNull() ?: return null
        if (destination == root ||
            browser.currentFolderId == PlaylistFolderIndex.OTHER_LOCATIONS_ID ||
            findFolder(browser.index, browser.currentFolderId) == null
        ) return null
        val folder = java.io.File(destination)
        return PhysicalCreationTarget(
            nativeRoot = root,
            destination = folder.canonicalPath
        )
    }

    fun blockUnsafeNativeCreation(picker: Boolean) {
        val browser = currentBrowser(picker)
        browser?.list?.let { list ->
            Toast.makeText(
                list.context,
                "Playlist folder destination could not be verified. " +
                    "No playlist was created.",
                Toast.LENGTH_LONG
            ).show()
        }
        Log.w(TAG, "FOLDER CREATE BLOCK | native destination verification failed")
    }
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val styles = WeakHashMap<ViewGroup, NativeRowStyle>()
    private val pendingRetries = WeakHashMap<ViewGroup, Int>()
    private val suspendedNativeLists = WeakHashMap<ViewGroup, NavigationHold>()
    private val folderMemory = PlaylistFolderNavigationMemory()
    private val observedPickerOwners = WeakHashMap<ViewGroup, Boolean>()
    private val calibratedHeaderStarts = WeakHashMap<RecyclerView, Pair<String, Int>>()
    private val nativeOriginalAlphas = WeakHashMap<ViewGroup, Float>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLayoutObservers = WeakHashMap<
        ViewGroup,
        android.view.ViewTreeObserver.OnGlobalLayoutListener
    >()
    private var activeBrowser = WeakReference<ViewGroup>(null)
    private var observedNativeBreadcrumbStyle: NativeBreadcrumbStyle? = null
    private val observedNativeNavLists = WeakHashMap<ViewGroup, Boolean>()
    private val nativeQuickNavObservers = WeakHashMap<
        RecyclerView,
        Pair<RecyclerView.Adapter<*>, RecyclerView.AdapterDataObserver>
    >()
    private val quickNavParityReports = hashSetOf<String>()
    private val nativePlaylistAnimatorReports = WeakHashMap<ViewGroup, String>()
    private var lastNativePlaylistTitlePx: Float? = null
    private var sampledQuickNavRatio: Float? = null
    private val observedMenus = linkedSetOf<String>()
    private val observedNativeFileMenus = linkedSetOf<String>()
    private var playlistTabMenu: WeakReference<android.view.Menu>? = null
    var onNativeMoveDiscovery: ((android.content.Context) -> Unit)? = null
    private val newFolderMenuId = View.generateViewId()
    private val movePlaylistMenuId = View.generateViewId()
    private val nativeContextPlaylist = ThreadLocal<String?>()
    private var activeNativePlaylistMode: WeakReference<Any>? = null
    private var nativePlaylistMover: NativeGmmpPlaylistMover? = null
    private var lastMoveLabelDiagnostic: String? = null
    private var lastSelectLabelDiagnostic: String? = null
    // One badge per ORIGINAL navigation MenuItem, not an added clickable
    // drawer row and not an overlay that interferes with its native ripple.
    private val originalDrawerPlaylistTitles =
        WeakHashMap<android.view.MenuItem, CharSequence>()
    private val observedDrawerLists = WeakHashMap<ViewGroup, Boolean>()
    private val drawerRefreshPending = WeakHashMap<ViewGroup, Boolean>()
    private val drawerBadgeProbes = WeakHashMap<ViewGroup, Int>()

    fun setNativePlaylistMover(loader: ClassLoader) {
        nativePlaylistMover = NativeGmmpPlaylistMover(loader)
    }

    fun observeNativePlaylistActionMode(mode: Any?) {
        if (mode != null) activeNativePlaylistMode = WeakReference(mode)
    }
    // Avoid replacing a native Material FAB's drawable every layout pass.
    private val miniFabBackgroundSource =
        WeakHashMap<View, Drawable.ConstantState>()

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
        // The drawer is retained while switching settings; immediately
        // add or restore our title decoration without reinflating GMMP's
        // original native menu or changing its click handlers.
        observedDrawerLists.keys.toList().forEach(::scheduleDrawerBadgeRefresh)

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
        (list as? RecyclerView)?.let(::observeNativeQuickNavEvents)
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
        // A fixed-size quickNav RecyclerView may get its qg1 adapter and
        // bound holders without ANY change to its own bounds. The observed
        // AndroidX class can be in GMMP's classloader rather than ours, so
        // neither a Kotlin RecyclerView cast nor our AdapterDataObserver
        // alone can guarantee a valid capture.
        val globalLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            weak.get()?.let { native ->
                if (native.isAttachedToWindow) {
                    captureNativeBreadcrumbStyle(native)
                }
            }
        }
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)
        }
        list.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    weak.get()?.let {
                        it.removeOnLayoutChangeListener(listener)
                        if (it.viewTreeObserver.isAlive) {
                            it.viewTreeObserver.removeOnGlobalLayoutListener(
                                globalLayout
                            )
                        }
                        observedNativeNavLists.remove(it)
                        (it as? RecyclerView)?.let { nav ->
                            nativeQuickNavObservers.remove(nav)?.let {
                                (adapter, observer) ->
                                adapter.unregisterAdapterDataObserver(observer)
                            }
                        }
                    }
                }
            }
        )
        var attempts = 0
        val retry = object : Runnable {
            override fun run() {
                val active = weak.get() ?: return
                if (!active.isAttachedToWindow) return
                (active as? RecyclerView)?.let(::observeNativeQuickNavEvents)
                if (captureNativeBreadcrumbStyle(active)) return
                if (++attempts < 8) active.postDelayed(this, ATTACH_RETRY_MS)
            }
        }
        list.post(retry)
    }

    /**
     * GMMP's qg1 may update while its RecyclerView bounds stay unchanged;
     * OnLayoutChange alone then misses new separator measurements. Listen
     * only to the original adapter's public observer API. Never call its
     * notify methods or hold its views across Activity destruction.
     */
    private fun observeNativeQuickNavEvents(list: RecyclerView) {
        val source = list.adapter ?: return
        val current = nativeQuickNavObservers[list]
        if (current?.first === source) return
        current?.let { (old, observer) ->
            old.unregisterAdapterDataObserver(observer)
        }
        val weakList = WeakReference(list)
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun refresh(what: String) {
                val live = weakList.get() ?: return
                Log.i(
                    TAG,
                    "FOLDER NATIVE QUICKNAV EVENT | kind=" + what +
                        " | count=" + (live.adapter?.itemCount ?: -1) +
                        " | animator=" +
                        (live.itemAnimator?.javaClass?.simpleName ?: "none")
                )
                live.post {
                    if (live.isAttachedToWindow &&
                        !live.isComputingLayout
                    ) captureNativeBreadcrumbStyle(live)
                }
            }
            override fun onChanged() = refresh("changed")
            override fun onItemRangeInserted(start: Int, count: Int) =
                refresh("insert:" + count)
            override fun onItemRangeRemoved(start: Int, count: Int) =
                refresh("remove:" + count)
            override fun onItemRangeMoved(from: Int, to: Int, count: Int) =
                refresh("move:" + count)
            override fun onItemRangeChanged(start: Int, count: Int) =
                refresh("update:" + count)
        }
        source.registerAdapterDataObserver(observer)
        nativeQuickNavObservers[list] = source to observer
    }

    /**
     * Exact installed GMMP 4.2.0 quickNav physics, where available.
     * EdgeEffectFactory manufactures independent native EdgeEffects for
     * this RecyclerView; never steal/reparent GMMP's live quickNav view.
     * Before Files was first opened, AndroidX RecyclerView's own default
     * factory implements the same native stretch engine on Android 12+.
     */
    private fun applyNativeQuickNavPhysics(header: RecyclerView) {
        val source = observedNativeBreadcrumbStyle ?: return
        source.edgeEffectFactory?.let {
            if (header.edgeEffectFactory !== it) header.edgeEffectFactory = it
        }
        header.overScrollMode = source.nativeOverScrollMode
        header.clipToPadding = source.nativeClipToPadding
        header.isNestedScrollingEnabled = source.nativeNestedScrolling
        // Preserve an already verified first-title correction across
        // unrelated rerenders. Otherwise every theme/selection refresh
        // erased the calibration and shifted the breadcrumb back left.
        val desiredStart = calibratedHeaderStarts[header]
            ?.takeIf { it.first == source.signature }
            ?.second ?: source.nativeHeaderStartPx
        if (header.paddingStart != desiredStart ||
            header.paddingEnd != source.nativeHeaderEndPx
        ) {
            header.setPaddingRelative(
                desiredStart,
                header.paddingTop,
                source.nativeHeaderEndPx,
                header.paddingBottom
            )
        }
        val observedAnimator = source.nativeItemAnimator
        if (observedAnimator != null &&
            header.itemAnimator?.javaClass != observedAnimator.javaClass
        ) {
            header.itemAnimator = cloneNativeItemAnimator(observedAnimator)
                ?: DefaultItemAnimator()
        }
    }

    /**
     * Persist only a REAL first-text offset measured on GMMP's original,
     * fully bound qg1 view. A different font-size ratio or unsampled native
     * XML must never be mistaken for verified horizontal geometry.
     * The physical pixel value is scoped to the device display density.
     */
    private fun verifiedNativeFirstTextX(list: View): Int? {
        val key = QUICK_NAV_VERIFIED_FIRST_X_KEY +
            list.resources.displayMetrics.densityDpi
        val prefs = list.context.getSharedPreferences(
            QUICK_NAV_METRICS_PREFS, android.content.Context.MODE_PRIVATE
        )
        if (!prefs.contains(key)) return null
        return prefs.getInt(key, -1).takeIf {
            it in 0..dp(list, 96)
        }
    }

    private fun nativeQuickNavTitleRatio(list: View): Float {
        sampledQuickNavRatio?.let { return it }
        val cached = list.context.getSharedPreferences(
            QUICK_NAV_METRICS_PREFS, android.content.Context.MODE_PRIVATE
        ).getFloat(QUICK_NAV_TITLE_RATIO_KEY, Float.NaN)
        return if (cached.isFinite() && cached in 0.8f..1.8f) {
            cached.also { sampledQuickNavRatio = it }
        } else GMMP_420_QUICK_NAV_TITLE_RATIO
    }

    /**
     * Only accept a REAL, bound qg1 quickNav metadata holder. Previously
     * we recorded a 58.8px TextView before its native adapter was available
     * and a Kotlin RecyclerView cast failed across classloaders. That
     * produced fake firstX=0/0 parity and erased the native XML start inset.
     */
    private fun captureNativeBreadcrumbStyle(list: ViewGroup): Boolean {
        val runtime = NativeRecyclerBridge.snapshot(list)
        val adapter = runtime.adapter ?: return false
        if (adapter.javaClass.simpleName != "qg1") return false
        if (list.width <= 0 || list.height <= 0 ||
            list.isLayoutRequested
        ) return false

        val visibleItems = (0 until list.childCount).map { list.getChildAt(it) }
            .map { NativeRecyclerBridge.childAdapterPosition(list, it) to it }
            .filter { (position, child) ->
                position >= 0 && child.width > 0 && child.height > 0
            }
        val rootItem = visibleItems.firstOrNull { it.first == 0 }?.second
        val previous = observedNativeBreadcrumbStyle
        // The very first capture MUST see position zero at scroll start.
        // Later theme changes can sample another visible even item while
        // retaining the previously verified first-item geometry.
        val metadataItem = rootItem ?: visibleItems.firstOrNull {
            it.first % 2 == 0
        }?.second ?: return false
        if (rootItem == null && previous == null) return false

        fun findNativeTitle(view: View, depth: Int): TextView? {
            if (depth > 4) return null
            if (view is TextView &&
                view.visibility == View.VISIBLE &&
                view.text?.any(Char::isLetterOrDigit) == true
            ) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findNativeTitle(view.getChildAt(index), depth + 1)
                        ?.let { return it }
                }
            }
            return null
        }
        val original = findNativeTitle(metadataItem, 0) ?: return false
        val paint = effectiveNativeTitlePaint(original)
        val nativeTouch = original.background?.constantState
            ?: (original.parent as? View)?.background?.constantState
        val firstTextStart = if (rootItem != null &&
            !list.canScrollHorizontally(-1)
        ) {
            // Measured relative to the *native* quickNav viewport, not
            // child.left alone (which ignores parent margins/nested views).
            val sourceScreen = IntArray(2)
            val titleScreen = IntArray(2)
            list.getLocationOnScreen(sourceScreen)
            original.getLocationOnScreen(titleScreen)
            titleScreen[0] - sourceScreen[0] +
                original.compoundPaddingStart
        } else previous?.nativeFirstTextStartPx ?: return false
        if (rootItem != null && firstTextStart in 0..dp(list, 96)) {
            val key = QUICK_NAV_VERIFIED_FIRST_X_KEY +
                list.resources.displayMetrics.densityDpi
            list.context.getSharedPreferences(
                QUICK_NAV_METRICS_PREFS, android.content.Context.MODE_PRIVATE
            ).edit().putInt(key, firstTextStart).apply()
        }
        val separatorWidth = visibleItems.firstOrNull {
            it.first == 1
        }?.second?.width ?: previous?.nativeSeparatorWidthPx
        // The native item's XML governs any additional per-item offset.
        // Only the native RecyclerView's OWN padding belongs on ours.
        val nativeHeaderStart = list.paddingStart
        val nativeHeaderEnd = list.paddingEnd
        val nativeAnimator = runtime.animator
        val nativeEdgeFactory =
            runtime.edgeEffectFactory as? RecyclerView.EdgeEffectFactory
        val signature = listOf(
            paint.textSize, paint.color, paint.typeface?.style ?: 0,
            original.letterSpacing, original.includeFontPadding, list.height,
            original.paddingStart, original.paddingEnd,
            nativeTouch?.javaClass?.name,
            runtime.edgeEffectFactory?.javaClass?.name,
            list.overScrollMode, list.clipToPadding,
            list.isNestedScrollingEnabled, nativeHeaderStart,
            nativeHeaderEnd, original.paddingTop,
            original.paddingBottom, firstTextStart,
            separatorWidth, nativeAnimator?.javaClass?.name,
            NativeRecyclerBridge.duration(nativeAnimator, "getAddDuration"),
            NativeRecyclerBridge.duration(nativeAnimator, "getMoveDuration")
        ).joinToString(":")
        if (previous?.signature == signature) return true
        lastNativePlaylistTitlePx?.takeIf { it > 0f }?.let { titlePx ->
            val ratio = paint.textSize / titlePx
            if (ratio.isFinite() && ratio in 0.8f..1.8f) {
                sampledQuickNavRatio = ratio
                list.context.getSharedPreferences(
                    QUICK_NAV_METRICS_PREFS, android.content.Context.MODE_PRIVATE
                ).edit().putFloat(QUICK_NAV_TITLE_RATIO_KEY, ratio).apply()
            }
        }
        observedNativeBreadcrumbStyle = NativeBreadcrumbStyle(
            effectivePaint = paint,
            letterSpacing = original.letterSpacing,
            includeFontPadding = original.includeFontPadding,
            rowHeightPx = list.height,
            paddingStartPx = original.paddingStart,
            paddingEndPx = original.paddingEnd,
            paddingTopPx = original.paddingTop,
            paddingBottomPx = original.paddingBottom,
            nativeFirstTextStartPx = firstTextStart,
            nativeSeparatorWidthPx = separatorWidth,
            nativeTouchBackground = nativeTouch,
            edgeEffectFactory = nativeEdgeFactory,
            nativeOverScrollMode = list.overScrollMode,
            nativeClipToPadding = list.clipToPadding,
            nativeNestedScrolling = list.isNestedScrollingEnabled,
            nativeHeaderStartPx = nativeHeaderStart,
            nativeHeaderEndPx = nativeHeaderEnd,
            nativeItemAnimator = cloneNativeItemAnimator(nativeAnimator),
            signature = signature
        )
        Log.i(
            TAG,
            "FOLDER NATIVE BREADCRUMB | bound qg1" +
                " | sizePx=" + paint.textSize +
                " | height=" + list.height +
                " | insetStart=" + nativeHeaderStart +
                " | firstTextX=" + firstTextStart +
                " | chevronWidth=" +
                (separatorWidth?.toString() ?: "pending") +
                " | classloaderMatch=" + runtime.moduleClassMatch +
                " | nativeAnimator=" +
                (nativeAnimator?.javaClass?.name ?: "none")
        )
        // qg1 is NEVER mutated by this observation.
        mainHandler.post {
            browsers.values.toList().forEach { browser ->
                if (browser.list.isAttachedToWindow &&
                    browser.currentFolderId != null &&
                    browser.overlay.visibility == View.VISIBLE &&
                    isFrontFragmentView(browser.list)
                ) {
                    applyNativeQuickNavPhysics(browser.breadcrumbScroller)
                    safeRender(browser)
                }
            }
        }
        return true
    }

    fun onNativeRecyclerObserved(view: View?) {
        val list = view as? ViewGroup ?: return
        if (resourceName(list) == "design_navigation_view") {
            observeDrawerPlaylistBadge(list)
            return
        }
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
     * GMMP 4.2.0's native AestheticNavigationView owns the actual drawer
     * menu and its existing Playlist item. Keep its original title/click
     * listener/icon/ripple; add the SAME purple two-star ReplacementSpan
     * as the approved Play Flipped context-menu entry, only while Playlist
     * folders is enabled. No extra drawer item or custom replacement row.
     */
    private fun observeDrawerPlaylistBadge(list: ViewGroup) {
        if (observedDrawerLists.put(list, true) != null) {
            scheduleDrawerBadgeRefresh(list)
            return
        }
        val weak = WeakReference(list)
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            weak.get()?.let(::scheduleDrawerBadgeRefresh)
        }
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
        list.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    weak.get()?.let(::scheduleDrawerBadgeRefresh)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    weak.get()?.let { drawer ->
                        if (drawer.viewTreeObserver.isAlive) {
                            drawer.viewTreeObserver
                                .removeOnGlobalLayoutListener(listener)
                        }
                        observedDrawerLists.remove(drawer)
                        drawerRefreshPending.remove(drawer)
                        drawerBadgeProbes.remove(drawer)
                    }
                }
            }
        )
        scheduleDrawerBadgeRefresh(list)
    }

    private fun scheduleDrawerBadgeRefresh(list: ViewGroup) {
        if (drawerRefreshPending.put(list, true) != null) return
        val weak = WeakReference(list)
        list.post {
            val drawer = weak.get() ?: return@post
            drawerRefreshPending.remove(drawer)
            updateDrawerPlaylistBadge(drawer)
        }
    }

    private fun updateDrawerPlaylistBadge(drawer: ViewGroup) {
        // A menu is available from GMMP's ACTUAL parent
        // AestheticNavigationView, not from the RecyclerView adapter.
        var parent: View? = drawer
        var menu: android.view.Menu? = null
        repeat(5) {
            val view = parent ?: return@repeat
            if (resourceName(view) == "mainNavigationView") {
                menu = runCatching {
                    view.javaClass.getMethod("getMenu").invoke(view)
                        as? android.view.Menu
                }.getOrNull()
                return@repeat
            }
            parent = view.parent as? View
        }
        if (menu == null) return
        val nativeMenu = menu ?: return
        val resources = drawer.resources
        val nativeNames = listOf("playlists", "playlist").mapNotNull {
            val id = resources.getIdentifier(
                it, "string", drawer.context.packageName
            )
            if (id != 0) {
                runCatching { drawer.context.getString(id) }.getOrNull()
            } else null
        }.map { it.trim() }
        var target: android.view.MenuItem? = null
        for (index in 0 until nativeMenu.size()) {
            val item = nativeMenu.getItem(index)
            val original = originalDrawerPlaylistTitles[item]
                ?: item.title ?: continue
            val nativeId = runCatching {
                resources.getResourceEntryName(item.itemId)
            }.getOrDefault("")
            if (PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                    nativeNames, nativeId, original.toString()
                )
            ) {
                target = item
                break
            }
        }
        if (target == null) {
            val attempts = drawerBadgeProbes[drawer] ?: 0
            if (attempts < 2 && settings.enabled) {
                drawerBadgeProbes[drawer] = attempts + 1
                Log.i(
                    TAG, "FOLDER DRAWER BADGE | native Playlists " +
                        "menu entry not bound yet | items=" + nativeMenu.size()
                )
            }
            return
        }
        val item = target
        if (settings.enabled) {
            if (originalDrawerPlaylistTitles.containsKey(item)) {
                val existing = item.title
                if (existing is Spanned &&
                    existing.getSpans(
                        0, existing.length,
                        BaselineCenteredSparkleSpan::class.java
                    ).isNotEmpty()
                ) return
                // The original GMMP menu might have rebound this title
                // after a theme/locale update. Restore the true CURRENT
                // native text before decorating it again.
                originalDrawerPlaylistTitles.remove(item)
            }
            val original = item.title ?: return
            val newTitle = android.text.SpannableStringBuilder(original)
                .append("  \uFFFC")
            val marker = newTitle.lastIndexOf('\uFFFC')
            newTitle.setSpan(
                BaselineCenteredSparkleSpan(
                    drawer.context,
                    PlayerAutoDjBadgeController.SparkleBadgeDrawable(
                        0xFFA39AFF.toInt(), scale = 1.85f
                    )
                ),
                marker, marker + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            originalDrawerPlaylistTitles[item] = original
            item.title = newTitle
            Log.i(
                TAG, "FOLDER DRAWER BADGE | existing native " +
                    "Playlists menu decorated"
            )
        } else {
            originalDrawerPlaylistTitles.remove(item)?.let { original ->
                item.title = original
                Log.i(TAG, "FOLDER DRAWER BADGE | native title restored")
            }
        }
    }

    /**
     * Bounded read-only discovery of the exact native Add Playlist menu ID.
     * Do not remove the overflow menu or guess a title/ID before the actual
     * GMMP menu structure and create callback have been verified.
     */
    fun onMenuInflated(
        resourceId: Int,
        menu: android.view.Menu?,
        originalInflater: Any?
    ) {
        if (!BuildConfig.DEBUG || !settings.enabled || menu == null) return
        // The native menu is often inflated before the RecyclerView exists.
        // Read the ORIGINAL inflater's own host context in that first frame.
        val context = knownLists.keys.firstOrNull()?.context
            ?: runCatching {
                originalInflater?.javaClass?.getMethod("getContext")
                    ?.invoke(originalInflater) as? android.content.Context
            }.getOrNull() ?: return
        // Passive, bounded discovery only. No native move method is called.
        onNativeMoveDiscovery?.invoke(context)
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
        // Native Files-tab menu inventory for discovering the ORIGINAL
        // move/rename UI. Read-only: do not invoke any unverified actions.
        if (name.startsWith("menu_gm_") &&
            name.contains("file", ignoreCase = true) &&
            observedNativeFileMenus.size < 8 &&
            observedNativeFileMenus.add(name)
        ) {
            Log.i(
                TAG,
                "FOLDER MOVE DISCOVERY | native Files menu=" + name +
                    " | items=" + items.joinToString(";")
            )
        }
        if (name == "menu_gm_playlist_list") {
            playlistTabMenu = WeakReference(menu)
            installNativeNewFolderMenu(menu, context)
            updatePlaylistMenu()
        } else if (name == "menu_gm_context_playlist_list") {
            val path = nativeContextPlaylist.get()
            if (path != null) {
                val browser = currentBrowser(picker = false)
                if (browser?.modelsByPath?.containsKey(path) == true) {
                    installPlaylistMoveMenu(menu, context) {
                        showNativeMoveDestinationPicker(browser, listOf(path))
                    }
                }
            }
        } else if (name == "menu_gm_action_playlist") {
            installPlaylistMoveMenu(menu, context) {
                val browser = currentBrowser(picker = false)
                val selected = browser?.mainSelection?.selectedPaths().orEmpty()
                if (browser != null && selected.isNotEmpty()) {
                    showNativeMoveDestinationPicker(browser, selected)
                }
            }
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


    /**
     * Only GoneSmart's own missing GMMP phrase lives in our translation
     * file. All other menu/dialog labels still use GMMP's live resources.
     */
    private fun nativeMoveLabel(context: android.content.Context): String {
        val locale = context.resources.configuration.locales[0]
        val label = GoneSmartGmmpStrings.move(locale)
        val source = if (GoneSmartGmmpStrings.hasMoveTranslation(locale)) {
            "GoneSmart i18n/" + locale.toLanguageTag()
        } else "GoneSmart i18n/en fallback/" + locale.toLanguageTag()
        if (lastMoveLabelDiagnostic != source) {
            lastMoveLabelDiagnostic = source
            Log.i(TAG, "NATIVE MOVE LABEL | source=$source")
        }
        return label
    }

    private fun nativeMoveSuccessLabel(context: android.content.Context): String? {
        val id = context.resources.getIdentifier(
            "playlist_saved", "string", context.packageName
        )
        return id.takeIf { it != 0 }?.let {
            runCatching { context.getString(it) }.getOrNull()
        }?.takeUnless { it.isBlank() || it.contains("%") }
    }

    private fun installPlaylistMoveMenu(
        menu: android.view.Menu,
        context: android.content.Context,
        click: () -> Unit
    ) {
        if (nativePlaylistMover == null ||
            menu.findItem(movePlaylistMenuId) != null
        ) return
        val item = menu.add(
            android.view.Menu.NONE,
            movePlaylistMenuId,
            android.view.Menu.NONE,
            nativeMoveLabel(context)
        )
        item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        item.setOnMenuItemClickListener {
            click()
            true
        }
    }

    /**
     * Destination navigation deliberately IS the existing playlist browser:
     * identical native row XML, original Files quickNav layouts, active
     * Aesthetic palette, existing folder grouping and physical-only index.
     * The normal list is temporarily in destination-selection mode, with
     * the chosen folder confirmed by the native-themed bottom action.
     */
    private fun showNativeMoveDestinationPicker(
        browser: Browser,
        nativeSourcePaths: List<String>
    ) {
        if (nativeSourcePaths.isEmpty() || isPicker(browser.list) ||
            nativePlaylistMover == null || browser.moveSources != null
        ) return
        val verified = readActualNativePaths(browser) ?: run {
            warn(browser.list, "Native playlist index unavailable")
            return
        }
        val selected = nativeSourcePaths.distinct()
        if (selected.any { it !in verified }) {
            Log.w(TAG, "PLAYLIST MOVE UI | selection changed before navigation")
            return
        }
        browser.movePreviousFolder = browser.currentFolderId
        browser.moveSources = selected
        browser.currentFolderId = null
        activeBrowser = WeakReference(browser.list)
        // The Move menu belongs to the ORIGINAL native ActionMode.
        // Capture actual paths first and end that mode before presenting
        // destination navigation; otherwise Back handles selection teardown.
        activeNativePlaylistMode?.get()?.let { mode ->
            runCatching { mode.javaClass.getMethod("finish").invoke(mode) }
        }
        safeRender(browser)
        browser.list.post {
            if (browsers[browser.list] === browser &&
                browser.moveSources != null
            ) installMoveChrome(browser)
        }
        Log.i(TAG, "PLAYLIST MOVE UI | native-style browser opened | count=" +
            selected.size)
    }

    private fun closeMoveBrowser(browser: Browser) {
        browser.moveSources = null
        endMoveChrome(browser)
        browser.currentFolderId = browser.movePreviousFolder?.takeIf {
            findFolder(browser.index, it) != null
        }
        browser.movePreviousFolder = null
        browser.moveLastBottomOcclusion = -1
        browser.lastRenderedOrder = null
        safeRender(browser)
    }

    private fun confirmMoveBrowser(browser: Browser) {
        val selected = browser.moveSources ?: return
        val list = browser.list
        Log.i(TAG, "PLAYLIST MOVE UI | select clicked | count=" +
            selected.size)
        val root = runCatching {
            java.io.File(browser.rootPath).canonicalFile
        }.getOrNull() ?: return
        val destination = if (browser.currentFolderId == null) root else {
            findFolder(browser.index, browser.currentFolderId)
                ?.takeUnless { it.virtual }
                ?.let { java.io.File(it.id) }
        } ?: return
        val indexed = readActualNativePaths(browser) ?: run {
            warn(list, "Native playlist index unavailable")
            return
        }
        val ready = PlaylistMovePolicy.prepare(
            root, destination, selected, indexed
        )
        val title = nativeMoveLabel(list.context)
        when (ready) {
            is PlaylistMovePolicy.Result.Blocked -> {
                Log.w(TAG, "PLAYLIST MOVE UI | destination rejected: " +
                    ready.reason)
                warn(list, title + ": " + ready.reason)
            }
            is PlaylistMovePolicy.Result.Ready -> {
                val weakList = WeakReference(list)
                val started = nativePlaylistMover?.start(
                    list.context, ready.plan,
                    {
                        weakList.get()?.let { current ->
                            browsers[current]?.let(::readActualNativePaths)
                        }
                    },
                    { success, message ->
                        Log.i(TAG, "PLAYLIST MOVE | result=" +
                            success + " | " + message)
                        // The native list refresh itself is the success
                        // feedback. An earlier branch emitted a bare Move
                        // Toast ONLY for multi-moves: inconsistent and
                        // misleading. Show one localized error only.
                        if (PlaylistMoveFeedbackPolicy.shouldShowToast(success)) {
                            weakList.get()?.takeIf { it.isAttachedToWindow }
                                ?.let { current ->
                                    Toast.makeText(
                                        current.context,
                                        title + ": " + message,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                ) == true
                if (started) {
                    // Pressing Select is the user's ONE final confirmation;
                    // only after durable staging can the original GMMP
                    // deletion worker be executed without another prompt.
                    closeMoveBrowser(browser)
                } else {
                    warn(list, "Native move not available")
                }
            }
        }
    }

    /**
     * The same ORIGINAL contextual ActionMode used by multi-playlist
     * selection: its own native back arrow cancels destination mode.
     * No separate bottom Cancel/Select action bar.
     */
    private fun installMoveChrome(browser: Browser) {
        if (browser.moveSources == null || browser.moveActionMode != null) return
        val list = browser.list
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode,
                menu: android.view.Menu
            ): Boolean {
                mode.title = nativeMoveLabel(list.context)
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode,
                menu: android.view.Menu
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode,
                item: android.view.MenuItem
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (browser.moveActionMode === mode) {
                    browser.moveActionMode = null
                    if (browser.moveSources != null &&
                        browsers[list] === browser
                    ) {
                        Log.i(TAG, "PLAYLIST MOVE UI | native back arrow cancelled")
                        closeMoveBrowser(browser)
                    }
                }
            }
        }
        val mode = runCatching {
            list.startActionMode(callback, android.view.ActionMode.TYPE_PRIMARY)
        }.onFailure {
            Log.w(TAG, "PLAYLIST MOVE UI | original ActionMode unavailable", it)
        }.getOrNull()
        if (mode == null) {
            // No lookalike toolbar if the original native component
            // cannot be instantiated on a different host version.
            closeMoveBrowser(browser)
            return
        }
        browser.moveActionMode = mode
        val fab = installMoveFab(browser)
        if (fab == null) {
            Log.w(TAG, "PLAYLIST MOVE UI | native AestheticFab unavailable")
            closeMoveBrowser(browser)
            return
        }
        Log.i(TAG, "PLAYLIST MOVE UI | native ActionMode and native AestheticFab ready")
        list.post {
            if (browsers[list] === browser && browser.moveSources != null) {
                positionOverlay(browser)
                observeMoveFabPalette(browser)
                syncMoveChromePalette(browser)
                positionMoveFab(browser)
            }
        }
    }

    /** Native GMMP 4.2.0 AestheticFab class, NOT an approximate button. */
    private fun installMoveFab(browser: Browser): View? = runCatching {
        val list = browser.list
        val native = list.javaClass.classLoader
            ?.loadClass("com.afollestad.aesthetic.views.AestheticFab")
            ?: error("GMMP AestheticFab class unavailable")
        val fab = native.getConstructor(
            android.content.Context::class.java,
            android.util.AttributeSet::class.java
        ).newInstance(list.context, null) as? View
            ?: error("Host AestheticFab not a View")
        val image = fab as? ImageView
            ?: error("Host AestheticFab not an ImageView")
        image.imageTintList = null
        image.setImageDrawable(PlaylistConfirmDrawable(dp(list, 24)))
        image.contentDescription = nativeMoveLabel(list.context)
        image.setOnClickListener {
            if (browser.moveSources != null) {
                Log.i(TAG, "PLAYLIST MOVE UI | native confirm FAB clicked")
                // The currently displayed PHYSICAL folder determines the
                // destination. Never use the original selection folder.
                confirmMoveBrowser(browser)
            }
        }

        val nativeSize = list.resources.getIdentifier(
            "design_fab_size_normal", "dimen", list.context.packageName
        ).takeIf { it != 0 }?.let {
            runCatching { list.resources.getDimensionPixelSize(it) }.getOrNull()
        } ?: dp(list, 56)
        runCatching {
            native.getMethod("setCustomSize", Int::class.javaPrimitiveType)
                .invoke(fab, nativeSize)
        }
        val params = FrameLayout.LayoutParams(
            nativeSize, nativeSize, Gravity.END or Gravity.BOTTOM
        )
        params.marginEnd = dp(list, 16)
        params.bottomMargin = dp(list, 16)
        browser.overlay.addView(fab, params)
        fab.elevation = dp(list, 8).toFloat()
        browser.moveFab = fab
        fab.visibility = View.INVISIBLE
        // No branding on the move confirmation FAB: the maintainer
        // wants just the same clean white checkmark as the Add picker.
        // Do not reveal the native FAB while its original page/mini-player
        // geometry is still being measured; that previously flashed it
        // underneath the persistent mini-player on first entry.
        fab.visibility = View.INVISIBLE
        fab.addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->
            if (browser.moveSources != null) {
                positionOverlay(browser)
                positionMoveFab(browser)
            }
        }
        fab.post {
            if (browser.moveFab === fab && browser.moveSources != null) {
                // First synchronize the overlay with the ORIGINAL native
                // recycler/mini-player geometry. The first button frame
                // must not appear in the pre-layout bottom position.
                positionOverlay(browser)
                if (positionMoveFab(browser)) {
                    runCatching { native.getMethod("show").invoke(fab) }
                        .onFailure { fab.visibility = View.VISIBLE }
                }
            }
        }
        fab
    }.onFailure {
        Log.w(TAG, "PLAYLIST MOVE UI | original AestheticFab clone failed", it)
    }.getOrNull()

    /**
     * Original GMMP Add-picker FAB uses dynamic color "!mainColorAccent".
     * Subscribe through the SAME Aesthetic/oy0.h/nf3 observable as the
     * existing GoneSmart multi-picker, rather than colorAccent (#8e0e00
     * on the tested skin, while the actual native picker FAB was #bfbfcc).
     */
    private fun observeMoveFabPalette(browser: Browser) {
        if (browser.moveThemeObserver != null || browser.moveFab == null) return
        runCatching {
            val list = browser.list
            val loader = list.javaClass.classLoader
                ?: error("GMMP classloader missing")
            val theme = runCatching {
                loader.loadClass("com.afollestad.aesthetic.a\$a")
                    .getDeclaredMethod("c")
                    .apply { isAccessible = true }.invoke(null)
            }.getOrElse {
                loader.loadClass("com.afollestad.aesthetic.Aesthetic")
                    .getDeclaredMethod("get")
                    .apply { isAccessible = true }.invoke(null)
            } ?: error("GMMP Aesthetic not initialized")
            val attr = list.resources.getIdentifier(
                "colorAccent", "attr", list.context.packageName
            )
            require(attr != 0)
            val fallback = theme.javaClass.getDeclaredMethod(
                "b", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(theme, attr)
                ?: error("GMMP accent observable missing")
            val utility = loader.loadClass("oy0")
            val method = utility.declaredMethods.first {
                it.name == "h" && it.parameterCount == 3 &&
                    it.parameterTypes[0].isAssignableFrom(theme.javaClass) &&
                    it.parameterTypes[1] == String::class.java
            }.apply { isAccessible = true }
            val observable = method.invoke(
                null, theme, "!mainColorAccent", fallback
            ) ?: error("GMMP native FAB observable unavailable")
            val observerType = loader.loadClass("nf3")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                observerType.classLoader, arrayOf(observerType)
            ) { _, callback, args ->
                when (callback.name) {
                    "a" -> (args?.firstOrNull() as? Number)?.let { value ->
                        list.post {
                            if (browsers[list] === browser &&
                                browser.moveSources != null
                            ) {
                                browser.moveFabColor = value.toInt()
                                syncMoveChromePalette(browser)
                            }
                        }
                    }
                    "c" -> args?.firstOrNull()?.let {
                        browser.moveThemeDisposables.add(it)
                    }
                    "onError" -> Log.w(
                        TAG, "PLAYLIST MOVE UI | GMMP FAB palette observer error",
                        args?.firstOrNull() as? Throwable
                    )
                }
                null
            }
            browser.moveThemeObserver = listener
            observable.javaClass.getMethod("b", observerType)
                .invoke(observable, listener)
            Log.i(TAG, "PLAYLIST MOVE UI | native !mainColorAccent observer active")
        }.onFailure {
            Log.w(
                TAG, "PLAYLIST MOVE UI | native FAB observer unavailable; " +
                    "using original AestheticFab's live background",
                it
            )
        }
    }

    private fun nativeMovePaletteFallback(browser: Browser): Int? {
        val fab = browser.moveFab ?: return null
        val tint = runCatching {
            fab.javaClass.getMethod("getBackgroundTintList")
                .invoke(fab) as? android.content.res.ColorStateList
        }.getOrNull() ?: nativeFabDrawableTint(fab.background)
        return tint?.getColorForState(
            fab.drawableState, tint.defaultColor
        )?.takeIf { Color.alpha(it) >= 200 }
    }

    private fun syncMoveChromePalette(browser: Browser) {
        if (browser.moveSources == null || browser.moveActionMode == null) return
        val fab = browser.moveFab ?: return
        // The observable delivers the ORIGINAL dynamic picker-FAB color.
        // Reading the attached native FAB itself is the defensive fallback
        // for an obfuscated class change; don't derive from colorAccent.
        val color = browser.moveFabColor
            ?: nativeMovePaletteFallback(browser)
            ?: return
        if (browser.moveLastColor == color &&
            browser.moveBarTintApplied
        ) return
        val changed = browser.moveLastColor != color
        browser.moveLastColor = color
        if (changed) {
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
        }
        browser.moveBarTintApplied =
            multiSelect.tintNativeContextBar(browser.list, color)
        if (changed || browser.moveBarTintApplied) {
            Log.i(TAG, "PLAYLIST MOVE UI | native picker FAB color=#" +
                Integer.toHexString(color) +
                " | originalActionBarTint=" + browser.moveBarTintApplied)
        }
    }

    /**
     * Place the floating confirm ABOVE the true visible native Playlists
     * viewport; original overlay extends behind the persistent mini-player.
     */
    private fun nativeMiniPlayerTop(list: View): Int? {
        val visible = Rect()
        if (!list.getGlobalVisibleRect(visible)) return null
        return listOf(
            "miniPlayerWrapper", "miniPlayerLayout", "libraryTabMiniPlayer"
        ).mapNotNull { name ->
            val id = list.resources.getIdentifier(
                name, "id", list.context.packageName
            )
            if (id == 0) return@mapNotNull null
            val player = list.rootView.findViewById<View>(id)
                ?: return@mapNotNull null
            val bounds = Rect()
            if (player.isShown && player.getGlobalVisibleRect(bounds) &&
                bounds.height() > 0 &&
                bounds.top > visible.top + visible.height() / 3
            ) bounds.top else null
        }.minOrNull()
    }

    private fun positionMoveFab(browser: Browser): Boolean {
        val fab = browser.moveFab ?: return false
        if (browser.moveSources == null ||
            !browser.list.isAttachedToWindow ||
            browser.overlay.height <= 0
        ) return false
        val visible = Rect()
        if (!browser.list.getGlobalVisibleRect(visible) ||
            visible.height() == 0
        ) return false

        val miniBottom = nativeMiniPlayerTop(browser.list)
        val safeBottom = MoveConfirmationUiPolicy.safeBottom(
            visible.bottom, miniBottom
        )
        val overlayLocation = IntArray(2)
        browser.overlay.getLocationOnScreen(overlayLocation)
        val overlayBottom = overlayLocation[1] + browser.overlay.height
        val occlusion = MoveConfirmationUiPolicy.bottomOcclusion(
            overlayBottomPx = overlayBottom,
            visibleBottomPx = safeBottom
        )
        val params = fab.layoutParams as? FrameLayout.LayoutParams
            ?: return false
        val margin = dp(browser.list, 16)
        val targetMargin = occlusion + margin
        if (params.bottomMargin != targetMargin) {
            params.bottomMargin = targetMargin
            fab.layoutParams = params
        }
        val wasUnpositioned = browser.moveLastBottomOcclusion == -1
        if (browser.moveLastBottomOcclusion != occlusion) {
            browser.moveLastBottomOcclusion = occlusion
            Log.i(
                TAG, "PLAYLIST MOVE UI | FAB above native mini-player | " +
                    "occlusion=" + occlusion +
                    " | nativeVisibleBottom=" + visible.bottom +
                    " | nativeMiniPlayerTop=" + (miniBottom ?: "unknown") +
                    " | initial=" + wasUnpositioned
            )
        }
        // This method is also called from the native page's global-layout
        // and 400 ms pre-draw callbacks: an initially unavailable wrapper
        // is automatically measured before revealing the confirm button.
        val geometryReady = fab.width > 0 && fab.height > 0 &&
            !fab.isLayoutRequested &&
            (miniBottom != null || occlusion > 0 ||
                nativeMiniPlayerTop(browser.list) == null)
        if (fab.visibility == View.INVISIBLE && geometryReady) {
            fab.visibility = View.VISIBLE
        }
        return geometryReady
    }

    private fun endMoveChrome(browser: Browser) {
        val mode = browser.moveActionMode
        browser.moveActionMode = null
        mode?.finish()
        browser.moveFabColor = null
        browser.moveLastColor = null
        browser.moveBarTintApplied = false
        browser.moveThemeDisposables.forEach { disposable ->
            runCatching {
                disposable.javaClass.getMethod("b").invoke(disposable)
            }
        }
        browser.moveThemeDisposables.clear()
        browser.moveThemeObserver = null
        val fab = browser.moveFab
        browser.moveFab = null
        if (fab != null) {
            fab.visibility = View.GONE
            (fab.parent as? ViewGroup)?.let { host ->
                mainHandler.post { if (fab.parent === host) host.removeView(fab) }
            }
        }
    }

    private fun readActualNativePaths(browser: Browser): List<String>? {
        val adapter = nativeAdapter(browser.list) ?: return null
        val itemCount = runCatching {
            adapter.javaClass.getMethod("getItemCount")
                .invoke(adapter) as Int
        }.getOrNull() ?: return null
        if (itemCount < 0) return null
        val native = NativePlaylistSourceInspector.inspect(
            adapter, itemCount
        )
        return native.paths.takeIf {
            !native.truncated && it.size == itemCount
        }
    }

    /** Native GMMP's existing translated New Folder action/icon. */
    private fun installNativeNewFolderMenu(
        menu: android.view.Menu,
        context: android.content.Context
    ) {
        if (menu.findItem(newFolderMenuId) != null) return
        val resources = context.resources
        val iconId = resources.getIdentifier(
            "ic_gm_new_folder", "drawable", context.packageName
        )
        if (iconId == 0) return
        val nativeAdd = (0 until menu.size()).map(menu::getItem)
            .firstOrNull {
                runCatching {
                    resources.getResourceEntryName(it.itemId) == "menuAdd"
                }.getOrDefault(false)
            }
        // Reuse the SAME GMMP-localized title as its adjacent menuAdd
        // item. A native GMMP folder drawable is prepended inline: Android
        // overflow menus do not consistently show MenuItem.icon.
        val originalAdd = nativeAdd ?: return
        val originalTitle = originalAdd.title ?: return
        val label = android.text.SpannableStringBuilder(originalTitle)
        val folderIcon = context.getDrawable(iconId)?.mutate()
        if (folderIcon != null) {
            val fallbackSize = (
                18f * context.resources.displayMetrics.density
            ).toInt()
            val width = folderIcon.intrinsicWidth.takeIf { it > 0 }
                ?: fallbackSize
            val height = folderIcon.intrinsicHeight.takeIf { it > 0 }
                ?: fallbackSize
            // The inline ImageSpan does not inherit its parent TextView's
            // text color. Prefer the original item's foreground span, then
            // the CURRENT bound native playlist row, then the host's own
            // active textColorPrimary. Never use the untinted black asset.
            val nativeSpanColor = (originalTitle as? Spanned)?.let { source ->
                source.getSpans(0, source.length, ForegroundColorSpan::class.java)
                    .lastOrNull()?.foregroundColor
            }
            val rowColor = currentBrowser(picker = false)?.let {
                styles[it.list]?.textColor
            }
            val nativeTextColor = nativeSpanColor ?: rowColor ?: run {
                val attrs = context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.textColorPrimary)
                )
                try {
                    attrs.getColorStateList(0)?.getColorForState(
                        intArrayOf(android.R.attr.state_enabled),
                        attrs.getColor(0, Color.WHITE)
                    ) ?: attrs.getColor(0, Color.WHITE)
                } finally {
                    attrs.recycle()
                }
            }
            folderIcon.setTint(nativeTextColor)
            folderIcon.setBounds(0, 0, width, height)
            // Keep all original GMMP localized/title spans: inserting
            // before the original text shifts them automatically.
            label.insert(0, "\uFFFC  ")
            label.setSpan(
                android.text.style.ImageSpan(
                    folderIcon, android.text.style.ImageSpan.ALIGN_BOTTOM
                ),
                0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val item = menu.add(
            android.view.Menu.NONE, newFolderMenuId,
            originalAdd.order + 1, label
        )
        item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        item.setOnMenuItemClickListener {
            currentBrowser(picker = false)?.let(::requestNativeFolderCreation)
            true
        }
        Log.i(TAG, "FOLDER CREATE MENU | native icon/title installed")
    }

    private fun physicalFolderParent(browser: Browser): java.io.File? {
        if (browser.currentFolderId == PlaylistFolderIndex.OTHER_LOCATIONS_ID) {
            return null
        }
        val root = runCatching {
            java.io.File(browser.rootPath).canonicalFile
        }.getOrNull() ?: return null
        val candidate = runCatching {
            browser.currentFolderId?.let { java.io.File(it) }?.canonicalFile ?: root
        }.getOrNull() ?: return null
        val inside = candidate.path == root.path ||
            candidate.path.startsWith(
                root.path.trimEnd(java.io.File.separatorChar) +
                    java.io.File.separator
            )
        return candidate.takeIf { inside && it.isDirectory && it.canWrite() }
    }

    private fun requestNativeFolderCreation(browser: Browser): Boolean {
        val parent = physicalFolderParent(browser) ?: return false
        val creator = nativeFolderCreator ?: return false
        val root = java.io.File(browser.rootPath).canonicalPath
        val previous = physicalDirectorySnapshot(root).toSet()
        val opened = creator.show(browser.list.context, parent) {
            mainHandler.post {
                val changed = physicalDirectorySnapshot(root).toSet() - previous
                if (changed.isEmpty()) {
                    Log.w(TAG, "FOLDER CREATE | native callback without new directory")
                    return@post
                }
                browsers.values.toList().forEach { current ->
                    if (current.rootPath != browser.rootPath ||
                        !current.list.isAttachedToWindow
                    ) return@forEach
                    val names = linkedMapOf<String, String>()
                    fun collect(folder: PlaylistFolderIndex.Folder) {
                        folder.playlists.forEach { names[it.path] = it.name }
                        folder.children.forEach(::collect)
                    }
                    current.index.topLevelFolders.forEach(::collect)
                    current.index.ungroupedPlaylists.forEach {
                        names[it.path] = it.name
                    }
                    current.index = PlaylistFolderIndex.build(
                        nativePlaylistPaths = current.nativeOrder,
                        mainPlaylistDirectory = current.rootPath,
                        groupExternalLocations = settings.groupExternal,
                        groupRootPlaylists = settings.groupRoot,
                        physicalDirectoryPaths = physicalDirectorySnapshot(root),
                        displayNamesByPath = names
                    )
                    safeRender(current)
                }
                Log.i(TAG, "FOLDER CREATE | original GMMP mkdir completed")
            }
        }
        if (!opened) {
            Log.w(TAG, "FOLDER CREATE | original GMMP prompt unavailable")
        }
        return opened
    }

    /**
     * Only the native bulk-delete dialog created by our OWN verified folder
     * request is eligible for a path label. The original buttons, click
     * callbacks, file list and WorkManager transaction are unchanged.
     */
    fun onOriginalPlaylistMoveDialogBeforeShow(
        dialog: android.app.Dialog
    ): Boolean = nativePlaylistMover?.onNativeDialogBeforeShow(dialog) == true

    fun onOriginalFolderDeleteDialogShown(dialog: android.app.Dialog) {
        nativeFolderDeletion?.onNativeDialogShown(dialog)
        nativePlaylistMover?.onNativeDialogShown(dialog)
    }

    /** Bound to GMMP 4.2.0's ORIGINAL yn3 -> n3 ActionMode lifecycle. */
    fun onNativeMainActionModeDestroyed() {
        activeNativePlaylistMode = null
        mainHandler.post {
            browsers.values.toList().forEach { browser ->
                if (isPicker(browser.list) || !browser.mainSelection.isSelecting) {
                    return@forEach
                }
                browser.mainSelection.clear()
                if (browser.list.isAttachedToWindow &&
                    browsers[browser.list] === browser
                ) safeRender(browser)
                Log.i(TAG, "FOLDER MAIN SELECT | original ActionMode destroyed; cleared")
            }
        }
    }

    private fun showNativeFolderContextMenu(
        browser: Browser,
        folder: PlaylistFolderIndex.Folder,
        anchor: View
    ) {
        if (folder.virtual || isPicker(browser.list)) return
        val context = anchor.context
        val menuId = context.resources.getIdentifier(
            "menu_gm_context_playlist_list",
            "menu", context.packageName
        )
        val deleteId = context.resources.getIdentifier(
            "menuContextDelete", "id", context.packageName
        )
        if (menuId == 0 || deleteId == 0) return
        val popup = android.widget.PopupMenu(context, anchor)
        if (!runCatching {
            popup.menuInflater.inflate(menuId, popup.menu)
        }.isSuccess) return
        val originalDelete = popup.menu.findItem(deleteId) ?: return
        val nativeTitle = originalDelete.title
        val nativeIcon = originalDelete.icon
        popup.menu.clear()
        popup.menu.add(
            android.view.Menu.NONE, deleteId, 0, nativeTitle
        ).setIcon(nativeIcon)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == deleteId) {
                requestNativeFolderDeletion(browser, folder)
                true
            } else false
        }
        popup.show()
    }

    /**
     * Use GMMP's original bulk-delete confirmation for EVERY native
     * playlist in a folder, then remove ONLY empty directories once the
     * original native model and physical M3Us both confirm deletion.
     * For a completely empty folder, use GMMP's original Files-tab
     * delete dialog/worker directly on the directory itself.
     */
    private fun requestNativeFolderDeletion(
        browser: Browser,
        folder: PlaylistFolderIndex.Folder
    ): Boolean {
        if (folder.virtual || nativeFolderDeletion == null) return false
        val plan = FolderDeletePolicy.prepare(
            java.io.File(browser.rootPath), java.io.File(folder.id),
            browser.nativeOrder
        )
        if (plan == null) {
            Log.w(
                TAG,
                "FOLDER DELETE | blocked: directory contains unindexed files" +
                    " or unsafe/unwritable path; no files changed"
            )
            val error = browser.list.resources.getIdentifier(
                "error", "string", browser.list.context.packageName
            )
            if (error != 0) Toast.makeText(
                browser.list.context, error, Toast.LENGTH_LONG
            ).show()
            return false
        }
        val nativeTargets = if (plan.nativePlaylistFiles.isNotEmpty()) {
            plan.nativePlaylistFiles
        } else listOf(plan.folder)
        val opened = nativeFolderDeletion!!.confirmNativeDeletion(
            browser.list.context, nativeTargets, plan.folder
        )
        if (!opened) return false
        val pending = PendingFolderDeletion(plan)
        pendingFolderDeletes.add(pending)
        waitForOriginalFolderDeletion(pending)
        return true
    }

    private fun waitForOriginalFolderDeletion(
        pending: PendingFolderDeletion
    ) {
        if (!pendingFolderDeletes.contains(pending)) return
        val plan = pending.plan
        val sameRoot = browsers.values.toList().filter {
            it.rootPath == plan.root.path && it.list.isAttachedToWindow
        }
        val nativeGone = sameRoot.isNotEmpty() &&
            sameRoot.all {
                FolderDeletePolicy.nativeRemovalComplete(
                    plan, it.nativeOrder
                )
            }
        if (nativeGone && plan.nativePlaylistFiles.isNotEmpty()) {
            if (FolderDeletePolicy.removeEmptyDirectories(plan)) {
                pendingFolderDeletes.remove(pending)
                refreshFoldersAfterNativeDeletion(plan.root.path)
                Log.i(TAG, "FOLDER DELETE | native DB/file removal" +
                    " verified; empty folders pruned")
                return
            }
        }
        if (plan.nativePlaylistFiles.isEmpty() && !plan.folder.exists()) {
            pendingFolderDeletes.remove(pending)
            refreshFoldersAfterNativeDeletion(plan.root.path)
            Log.i(TAG, "FOLDER DELETE | original GMMP removed empty folder")
            return
        }
        if (--pending.checksRemaining <= 0) {
            pendingFolderDeletes.remove(pending)
            Log.i(
                TAG,
                "FOLDER DELETE | not completed or canceled in native dialog;" +
                    " no manual playlist deletion attempted"
            )
            return
        }
        mainHandler.postDelayed({
            if (pendingFolderDeletes.contains(pending)) {
                waitForOriginalFolderDeletion(pending)
            }
        }, 600L)
    }

    private fun refreshFoldersAfterNativeDeletion(root: String) {
        browsers.values.toList().forEach { browser ->
            if (!browser.list.isAttachedToWindow ||
                browser.rootPath != root
            ) return@forEach
            val names = linkedMapOf<String, String>()
            fun collect(folder: PlaylistFolderIndex.Folder) {
                folder.playlists.forEach { names[it.path] = it.name }
                folder.children.forEach(::collect)
            }
            browser.index.topLevelFolders.forEach(::collect)
            browser.index.ungroupedPlaylists.forEach {
                names[it.path] = it.name
            }
            browser.index = PlaylistFolderIndex.build(
                nativePlaylistPaths = browser.nativeOrder,
                mainPlaylistDirectory = browser.rootPath,
                groupExternalLocations = settings.groupExternal,
                groupRootPlaylists = settings.groupRoot,
                physicalDirectoryPaths = physicalDirectorySnapshot(root),
                displayNamesByPath = names
            )
            if (browser.currentFolderId != null &&
                findFolder(browser.index, browser.currentFolderId) == null
            ) {
                browser.currentFolderId = null
                rememberFolder(browser)
            }
            safeRender(browser)
        }
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
        if (browser.moveSources != null) {
            if (browser.currentFolderId != null) {
                val current = findFolder(browser.index, browser.currentFolderId)
                browser.currentFolderId = parentFolderId(browser, current)
                safeRender(browser)
            } else closeMoveBrowser(browser)
            return true
        }
        if (!isPicker(browser.list) && browser.mainSelection.isSelecting) {
            // The original GMMP ActionMode handles Back; our synthetic
            // selection tint is only a visual mirror of its accepted clicks.
            browser.mainSelection.clear()
            mainHandler.post {
                if (browsers[browser.list] === browser) safeRender(browser)
            }
            return false
        }
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
            physicalDirectoryPaths = physicalDirectorySnapshot(root),
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
        // qg1 is NOT one wide holder: its real alternating metadata and
        // separator layouts determine exactly the first left inset, arrow
        // size, padding, typography bounds and native clickable/ripple area.
        val breadcrumbScroller = RecyclerView(list.context).apply {
            layoutManager = LinearLayoutManager(
                context, RecyclerView.HORIZONTAL, false
            )
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            clipToPadding = false
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
            }
            visibility = View.GONE
        }
        applyNativeQuickNavPhysics(breadcrumbScroller)
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

        // The owner is this GMMP picker dialog's native CoordinatorLayout,
        // NOT the global add-picker surface string. Its list may be replaced
        // while the same dialog remains open; a newly opened dialog starts
        // at the main playlist directory rather than an older selection.
        if (isPicker(list) && observedPickerOwners.put(parent, true) == null) {
            parent.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        folderMemory.clear("add-picker", parent)
                        observedPickerOwners.remove(parent)
                        parent.removeOnAttachStateChangeListener(this)
                    }
                }
            )
        }
        val rememberedFolder = folderMemory.restore(
            surface(list), if (isPicker(list)) parent else null
        ) {
            findFolder(index, it) != null
        }

        val browser = Browser(
            list = list,
            parent = parent,
            overlay = overlay,
            rows = rows,
            breadcrumbScroller = breadcrumbScroller,
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
        browser.breadcrumbAdapter = NativeQuickNavAdapter(browser)
        breadcrumbScroller.adapter = browser.breadcrumbAdapter
        browsers[list] = browser
        styles[list] = nativeStyle
        if (!isPicker(list)) {
            val weak = WeakReference(list)
            nativePlaylistMover?.resume(
                list.context,
                java.io.File(root),
                {
                    weak.get()?.let { actual ->
                        browsers[actual]?.let(::readActualNativePaths)
                    }
                }
            )
        }
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
        // A native sibling mini-player can cover our FAB despite its
        // correct margin. Clip the Move overlay itself above that sibling.
        val nativeMiniTop = if (browser.moveSources != null) {
            nativeMiniPlayerTop(list)
        } else null
        val screen = IntArray(2)
        list.getLocationOnScreen(screen)
        val availableHeight = nativeMiniTop
            ?.let { (it - screen[1]).coerceIn(1, list.height) }
            ?: list.height
        if (overlay.layoutParams.width != list.width ||
            overlay.layoutParams.height != availableHeight
        ) {
            overlay.layoutParams = overlay.layoutParams.apply {
                width = list.width
                height = availableHeight
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
        if (!nativeVisible) {
            if (browser.pickerAddExpanded) closePickerAddOptions(browser)
            return
        }
        updatePickerFab(browser)
        updatePlaylistMenu()
        // Aesthetic dynamically derives its colors from the current cover.
        // Keep the destination confirmation on the SAME native palette.
        syncMoveChromePalette(browser)
        positionMoveFab(browser)
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
        browser.moveSources = null
        endMoveChrome(browser)
        closePickerAddOptions(browser)
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
     * GMMP 4.2.0's original zn3 adapter extends its own obfuscated
     * RecyclerView$h (Adapter). The module observes the REAL adapter's
     * original notifyDataSetChanged/notifyItemRangeInserted/etc. methods.
     * These events are delivered even when an idle Playlists tab does not
     * redraw. Never notify or mutate the native adapter ourselves.
     */
    fun onNativePlaylistAdapterEvent(adapter: Any, reason: String) {
        if (!settings.enabled) return
        mainHandler.post {
            if (!settings.enabled) return@post
            val attached = browsers.values.toList().filter {
                it.list.isAttachedToWindow &&
                    browsers[it.list] === it &&
                    nativeAdapter(it.list) === adapter
            }
            for (browser in attached) {
                val count = runCatching {
                    adapter.javaClass.getMethod("getItemCount")
                        .invoke(adapter) as Int
                }.getOrNull() ?: continue
                if (!NativePlaylistAdapterEventPolicy.needsRefresh(
                        browser.nativeOrder.size, count
                    )
                ) continue
                Log.i(
                    TAG,
                    "FOLDER NATIVE ADAPTER EVENT | surface=" +
                        surface(browser.list) +
                        " | kind=" + reason +
                        " | previous=" + browser.nativeOrder.size +
                        " | native=" + count
                )
                scheduleNativePlaylistRefresh(browser)
            }
        }
    }

    /**
     * Refresh the already-visible folder browser when the ORIGINAL GMMP
     * playlist model changes. Native Adapter notifications call this as
     * the primary path; the throttled pre-draw check remains a fallback.
     * Work is posted outside the native adapter's notification dispatch.
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
        if (!NativePlaylistAdapterEventPolicy.needsRefresh(
                browser.nativeOrder.size, count
            )
        ) return
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
                if (!NativePlaylistAdapterEventPolicy.needsRefresh(
                        browser.nativeOrder.size, actualCount
                    )
                ) return@runCatching
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
                    physicalDirectoryPaths = physicalDirectorySnapshot(browser.rootPath),
                    displayNamesByPath = titles.names + visibleTitles
                )
                browser.index = refreshed
                browser.modelsByPath = native.nativeObjects
                browser.nativeOrder = native.paths
                // A pending batch may have survived process death, or the
                // original GMMP worker may finish AFTER its first adapter
                // attach. Resume from private durable stage whenever its
                // original playlist list changes, not only once at startup.
                if (!isPicker(browser.list)) {
                    val weak = WeakReference(browser.list)
                    nativePlaylistMover?.resume(
                        browser.list.context,
                        java.io.File(browser.rootPath),
                        {
                            weak.get()?.let { actual ->
                                browsers[actual]?.let(::readActualNativePaths)
                            }
                        }
                    )
                }
                // A single 600ms native-delete watcher is already running.
                // Calling it again per original adapter notification would
                // spawn overlapping poll loops and exhaust its safe timeout.
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
     * Use GMMP's exact qg1 XML layouts (not recreated TextViews/arrows).
     * qg1 emits [metadata, separator, metadata, ...] into an actual
     * horizontal RecyclerView. Native item insertions and removals activate
     * the sampled GMMP quickNav ItemAnimator for forward/back navigation.
     */
    private fun renderBreadcrumb(browser: Browser) {
        val list = browser.list
        val strip = browser.breadcrumbScroller
        val adapter = browser.breadcrumbAdapter ?: return
        val oldAtEnd = !strip.canScrollHorizontally(1)
        val folderChanged =
            browser.lastBreadcrumbFolderId != browser.currentFolderId
        browser.lastBreadcrumbFolderId = browser.currentFolderId
        val segments = PlaylistBreadcrumbPath.forFolder(
            browser.index, browser.currentFolderId
        )
        val nav = observedNativeBreadcrumbStyle
        val native = styles[list]
        browser.renderedBreadcrumbSignature =
            nav?.signature ?: "native-playlist-fallback"
        applyNativeQuickNavPhysics(strip)
        if (segments.isEmpty()) {
            adapter.submit(
                emptyList(), "", browser.renderedBreadcrumbSignature ?: ""
            )
            strip.visibility = View.GONE
            browser.breadcrumbContentSignature = null
            return
        }

        val height = nav?.rowHeightPx ?: native?.rowHeight ?: dp(list, 48)
        if (strip.layoutParams.height != height) {
            strip.layoutParams = strip.layoutParams.apply { this.height = height }
        }
        strip.visibility = View.VISIBLE
        native?.let {
            strip.background = nativeContentBackground(
                list, browser.parent, it
            )
        }
        val storageId = list.resources.getIdentifier(
            "storage", "string", list.context.packageName
        )
        val storageLabel = if (storageId != 0) {
            list.context.getString(storageId)
        } else "⌂"
        val appearance = listOf(
            browser.renderedBreadcrumbSignature,
            native?.signature ?: "-",
            storageLabel,
            segments.joinToString("|") {
                (it.folderId ?: "root") + "=" + it.name
            }
        ).joinToString("::")
        if (browser.breadcrumbContentSignature == appearance) return
        val styleOnly = browser.breadcrumbContentSignature != null &&
            !folderChanged
        val generation = ++browser.breadcrumbRenderGeneration
        browser.breadcrumbContentSignature = appearance
        val pathChanged = adapter.submit(segments, storageLabel, appearance)
        scheduleQuickNavParityCheck(browser, generation)

        // The original qg1 is a segmented adapter. Bring the newly added
        // last metadata holder into view, aligned to the right, AFTER the
        // native LayoutManager has completed its insertion/move animation.
        // A theme-only redraw must not undo the user's manual left swipe.
        if (!folderChanged && !pathChanged && !(styleOnly && oldAtEnd)) {
            return
        }
        val last = adapter.itemCount - 1
        if (last < 0) return
        var attempts = 0
        val reveal = object : Runnable {
            override fun run() {
                if (browsers[list] !== browser ||
                    strip.visibility != View.VISIBLE ||
                    browser.breadcrumbRenderGeneration != generation ||
                    !strip.isAttachedToWindow
                ) return
                val child = strip.layoutManager?.findViewByPosition(last)
                if (child == null || strip.isLayoutRequested) {
                    if (attempts++ < 12) {
                        strip.scrollToPosition(last)
                        strip.postOnAnimation(this)
                    }
                    return
                }
                // RecyclerView's native smooth scroller supplies the
                // forward/back movement; no custom ViewPropertyAnimator.
                val visibleEnd = strip.width - strip.paddingEnd
                val delta = child.right - visibleEnd
                if (delta > 0) {
                    strip.smoothScrollBy(delta, 0)
                } else if (folderChanged && strip.canScrollHorizontally(1)) {
                    strip.smoothScrollToPosition(last)
                }
            }
        }
        strip.postOnAnimation(reveal)
    }

    /**
     * Self-audit against GMMP's ACTUAL bound Files-tab quickNav. A normal
     * Logcat after one native Files-tab visit reports the original and
     * injected first-title X, arrow width and effective text size so the
     * maintainer does not have to notice every small UI discrepancy by eye.
     * No folder names, file paths or track information are logged.
     */
    private fun scheduleQuickNavParityCheck(
        browser: Browser,
        generation: Long
    ) {
        val source = observedNativeBreadcrumbStyle
        val expectedFirstX = source?.nativeFirstTextStartPx
            ?: verifiedNativeFirstTextX(browser.list) ?: return
        val styleKey = source?.signature ?: "cached:" + expectedFirstX
        val strip = browser.breadcrumbScroller
        strip.postOnAnimation {
            if (browsers[browser.list] !== browser ||
                browser.breadcrumbRenderGeneration != generation ||
                !strip.isAttachedToWindow
            ) return@postOnAnimation
            // Do not interpret a horizontally clipped first holder as
            // a permanent negative start-inset error.
            if (strip.canScrollHorizontally(-1)) return@postOnAnimation
            val first = strip.layoutManager?.findViewByPosition(0)
                as? TextView ?: return@postOnAnimation
            if (first.left < strip.paddingStart) return@postOnAnimation
            val expectedX = expectedFirstX
            val measuredX = first.left + first.compoundPaddingStart
            val correction = NativeQuickNavInsetPolicy.correctedPadding(
                currentPadding = strip.paddingStart,
                measuredTextStart = measuredX,
                expectedTextStart = expectedX,
                maxCorrection = dp(strip, 24)
            )
            if (correction != null) {
                calibratedHeaderStarts[strip] = styleKey to correction
                strip.setPaddingRelative(
                    correction, strip.paddingTop,
                    strip.paddingEnd, strip.paddingBottom
                )
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "FOLDER QUICKNAV INSET | surface=" +
                            surface(browser.list) +
                            " | nativeX=" + expectedX +
                            " | measuredX=" + measuredX +
                            " | paddingStart=" + correction +
                            " | source=" +
                            if (source != null) "live-qg1" else "verified-cache"
                    )
                }
                // Do not record parity until the original XML relayout
                // with its corrected native viewport inset has completed.
                return@postOnAnimation
            }
            if (!BuildConfig.DEBUG || source == null) return@postOnAnimation
            val separator = strip.layoutManager?.findViewByPosition(1)
            val actual = NativeQuickNavParity.Metrics(
                firstTextStartPx = first.left + first.paddingStart,
                separatorWidthPx = separator?.width,
                textSizePx = first.textSize
            )
            val expected = NativeQuickNavParity.Metrics(
                firstTextStartPx = source.nativeFirstTextStartPx,
                separatorWidthPx = source.nativeSeparatorWidthPx,
                textSizePx = source.effectivePaint.textSize
            )
            val difference = NativeQuickNavParity.compare(
                expected, actual
            )
            val signature = listOf(
                surface(browser.list),
                expected.toString(),
                actual.toString()
            ).joinToString("|")
            if (quickNavParityReports.add(signature)) {
                Log.i(
                    TAG,
                    "FOLDER QUICKNAV PARITY | surface=" +
                        surface(browser.list) +
                        " | firstX=" + actual.firstTextStartPx + "/" +
                        expected.firstTextStartPx +
                        " | chevronWidth=" +
                        (actual.separatorWidthPx ?: -1) + "/" +
                        (expected.separatorWidthPx ?: -1) +
                        " | textPx=" + actual.textSizePx + "/" +
                        expected.textSizePx +
                        " | match=" + difference.matches()
                )
            }
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
        val allFolders = folder?.children ?: browser.index.topLevelFolders
        val folders = if (browser.moveSources == null) allFolders else {
            allFolders.filter { !it.virtual }
        }
        val playlists = folder?.playlists ?: browser.index.ungroupedPlaylists
        val nextOrder = folders.map { "folder:" + it.id } +
            playlists.map { "playlist:" + it.path }
        val insertions = if (
            browser.lastRenderedOrder != null &&
            browser.lastRenderedFolderId == browser.currentFolderId
        ) {
            PlaylistFolderInsertionPlanner.plan(
                browser.lastRenderedOrder!!, nextOrder
            )
        } else null
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
        if (browser.moveSources != null) {
            syncMoveChromePalette(browser)
            positionMoveFab(browser)
        }
        browser.rows.removeAllViews()
        renderBreadcrumb(browser)
        updatePickerFab(browser)
        val renderedRows = arrayListOf<View>()

        for (child in folders) {
            lateinit var item: View
            item = row(
                list,
                child.name,
                folder = true,
                selected = false,
                nativeMenuButton = if (browser.moveSources == null &&
                    !isPicker(list) && !child.virtual
                ) {
                    nativeMenu
                } else null,
                onNativeContextMenu = if (browser.moveSources == null &&
                    !isPicker(list) && !child.virtual
                ) {
                    {
                        val id = nativeMenuResources(list).buttonId
                        val anchor = if (id != 0) {
                            item.findViewById<View>(id)
                        } else null
                        if (anchor != null) {
                            showNativeFolderContextMenu(
                                browser, child, anchor
                            )
                        }
                        Unit
                    }
                } else null
            ).apply {
                    setOnClickListener {
                        browser.currentFolderId = child.id
                        if (browser.moveSources == null) rememberFolder(browser)
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
            browser.rows.addView(item)
            renderedRows.add(item)
        }

        for (playlist in playlists) {
            val model = browser.modelsByPath[playlist.path]
            val selected = if (browser.moveSources != null) {
                false
            } else if (isPicker(list)) {
                multiSelect.isFolderPlaylistSelected(playlist.path)
            } else browser.mainSelection.isSelected(playlist.path)
            val item = row(
                    list,
                    playlist.name,
                    folder = false,
                    selected = selected,
                    nativeMenuButton = if (browser.moveSources == null) {
                        nativeMenu
                    } else null,
                    onNativeContextMenu = if (browser.moveSources == null &&
                        model != null && nativeMenu != null
                    ) {
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
                        if (browser.moveSources != null) return@setOnClickListener
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
                        if (browser.moveSources != null) return@setOnLongClickListener false
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
            browser.rows.addView(item)
            renderedRows.add(item)
        }

        // Empty folders deliberately have zero rows, just like GMMP Files.
        browser.lastRenderedFolderId = browser.currentFolderId
        browser.lastRenderedOrder = nextOrder
        if (insertions != null) {
            animateNativePlaylistInsertion(browser, insertions, renderedRows)
        }
    }

    /**
     * Reuse GMMP's actual installed ItemAnimator implementation, not just
     * approximate its timing with View.animate. A separate same-class
     * instance is required: sharing the original instance would mix the
     * two RecyclerViews' pending ViewHolders and corrupt GMMP's own list.
     *
     * DefaultItemAnimator's animateAdd/animateMove/runPendingAnimations
     * work with native XML views wrapped in standalone ViewHolders. The
     * source Animator supplies the exact implementation, durations and
     * interpolators of the live GMMP skin. Unclonable custom implementations
     * fall back to the previous visual approximation, with a bounded log.
     */
    /**
     * A native GMMP RecyclerView/ItemAnimator may be loaded in the host
     * classloader, while the overlay uses our packaged AndroidX 1.4.0.
     * A failed Kotlin cast DOES NOT mean GMMP has no ItemAnimator.
     * For a verified matching DefaultItemAnimator implementation, build
     * an independent module-side instance and import its native durations.
     * Never assign or run GMMP's live animator on synthetic ViewHolders.
     */
    private fun cloneNativeItemAnimator(source: Any?): SimpleItemAnimator? {
        if (source == null) return null
        val typed = source as? SimpleItemAnimator
        val clone: SimpleItemAnimator = if (typed != null) {
            runCatching {
                typed.javaClass.getDeclaredConstructor()
                    .apply { isAccessible = true }.newInstance()
                    as SimpleItemAnimator
            }.onFailure {
                Log.w(
                    TAG, "FOLDER NATIVE ANIMATOR | same-loader clone failed", it
                )
            }.getOrNull() ?: return null
        } else if (
            source.javaClass.name == DefaultItemAnimator::class.java.name ||
            NativeRecyclerBridge.isVerifiedGmmp420DefaultAnimator(source)
        ) {
            // Native GMMP 4.2.0: AndroidX DefaultItemAnimator was R8-
            // renamed to widget.o. The ORIGINAL AndroidX implementation
            // is already bundled by both APKs (RecyclerView 1.4.0).
            // Independently instantiate that SAME implementation because
            // a native host ItemAnimator cannot accept a module-loader
            // RecyclerView.ViewHolder without corrupting native state.
            DefaultItemAnimator()
        } else {
            Log.w(
                TAG, "FOLDER NATIVE ANIMATOR | unsupported host class=" +
                    source.javaClass.name
            )
            return null
        }
        NativeRecyclerBridge.duration(source, "getAddDuration")?.let {
            clone.addDuration = it
        }
        NativeRecyclerBridge.duration(source, "getMoveDuration")?.let {
            clone.moveDuration = it
        }
        NativeRecyclerBridge.duration(source, "getChangeDuration")?.let {
            clone.changeDuration = it
        }
        NativeRecyclerBridge.duration(source, "getRemoveDuration")?.let {
            clone.removeDuration = it
        }
        if (typed != null) {
            clone.supportsChangeAnimations = typed.supportsChangeAnimations
        }
        return clone
    }

    private fun animateNativePlaylistInsertion(
        browser: Browser,
        plan: PlaylistFolderInsertionPlanner.Plan,
        rowViews: List<View>
    ) {
        val list = browser.list
        val runtime = NativeRecyclerBridge.snapshot(list)
        val native = runtime.animator
        val clone = cloneNativeItemAnimator(native)
        val nativeDiagnostic = listOf(
            native?.javaClass?.name ?: "none",
            runtime.adapter?.javaClass?.simpleName ?: "none",
            runtime.moduleClassMatch.toString(),
            NativeRecyclerBridge.duration(native, "getAddDuration").toString()
        ).joinToString(":")
        if (nativePlaylistAnimatorReports[list] != nativeDiagnostic) {
            nativePlaylistAnimatorReports[list] = nativeDiagnostic
            Log.i(
                TAG,
                "FOLDER NATIVE PLAYLIST ANIMATION SOURCE" +
                    " | nativeAdapter=" +
                    (runtime.adapter?.javaClass?.simpleName ?: "none") +
                    " | itemAnimator=" +
                    (native?.javaClass?.name ?: "none") +
                    " | classloaderMatch=" + runtime.moduleClassMatch +
                    " | layoutAnimation=" +
                    (list.layoutAnimation?.javaClass?.name ?: "none") +
                    " | layoutTransition=" +
                    (list.layoutTransition?.javaClass?.name ?: "none")
            )
        }
        val moved = plan.shiftBefore.any { it > 0 }
        val expectedOrder = browser.lastRenderedOrder
        if (clone != null) {
            // Construct independent holders for our bound native XML rows;
            // the ORIGINAL player animator instance and adapter are never
            // modified or attached to the overlay.
            rowViews.forEachIndexed { index, row ->
                val holder = object : RecyclerView.ViewHolder(row) {}
                when {
                    plan.newKeys.contains(plan.nextOrder[index]) -> {
                        clone.animateAdd(holder)
                    }
                    plan.shiftBefore[index] > 0 -> {
                        val height = styles[list]?.rowHeight ?: dp(list, 48)
                        val shift = height * plan.shiftBefore[index]
                        clone.animateMove(holder, 0, -shift, 0, 0)
                    }
                }
            }
            browser.rows.postOnAnimation {
                if (browsers[list] === browser &&
                    browser.lastRenderedOrder === expectedOrder &&
                    browser.lastRenderedFolderId == browser.currentFolderId
                ) {
                    clone.runPendingAnimations()
                } else {
                    clone.endAnimations()
                    rowViews.forEach {
                        it.alpha = 1f
                        it.translationY = 0f
                    }
                }
            }
            Log.i(
                TAG,
                "FOLDER NATIVE ANIMATOR | exact class=" +
                    clone.javaClass.name + " | surface=" + surface(list) +
                    " | addMs=" + clone.addDuration +
                    " | moveMs=" + clone.moveDuration +
                    " | inserted=" + plan.newKeys.size
            )
            return
        }

        // The host has an original animator but its implementation is not
        // verified for this GMMP version. The maintainer explicitly forbids
        // approximating an available GMMP animation with View.animate().
        // Leave the row static and request a native source investigation.
        Log.w(
            TAG,
            "FOLDER NATIVE ANIMATOR | original unavailable for safe reuse;" +
                " insertion left static | surface=" + surface(list) +
                " | sourceClass=" + (native?.javaClass?.name ?: "none")
        )
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
        if (onNativeContextMenu == null) {
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
        val nativeWidth = nativeMenuButton?.width
            ?.takeIf { it > 0 } ?: dp(view, 48)
        val button = ImageButton(view.context).apply {
            id = nativeMenuResources(view).buttonId
            background = typedSelectableBackground(view)
            imageTintList = nativeMenuButton?.imageTintList
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
        if (onClick == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        val original = source?.drawable?.constantState
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
        source?.imageTintList?.let { button.imageTintList = it }
        val description = source?.contentDescription ?: run {
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
                val opensPlaylist = !longClick && !contextMenu &&
                    current?.mainSelection?.isSelecting != true
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
                            nativeContextPlaylist.set(targetPath)
                            try {
                                button.performClick()
                            } finally {
                                nativeContextPlaylist.remove()
                            }
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
                if (handled && !isPicker(list) && !contextMenu &&
                    current != null && browsers[list] === current
                ) {
                    if (current.mainSelection.onNativeAction(targetPath, longClick)) {
                        mainHandler.post {
                            if (browsers[list] === current &&
                                list.isAttachedToWindow &&
                                !current.nativeNavigationInProgress
                            ) safeRender(current)
                        }
                        Log.i(TAG, "FOLDER MAIN SELECT | selected=" +
                            current.mainSelection.selectedCount)
                    }
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
            browser.currentFolderId,
            if (isPicker(browser.list)) browser.parent else null
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
            lastNativePlaylistTitlePx = nativePaint.textSize
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
                hasPickerSelection = false,
                nativePhysicalCreateReady = nativeMainCreateRedirectReady
            )
        }
        menu.findItem(newFolderMenuId)?.isVisible =
            normal != null && nativeFolderCreator != null &&
                physicalFolderParent(normal) != null
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

    /**
     * Mirror the ACTUALLY displayed big native FAB instead of sampling an
     * app/theme accent: AestheticFab may assign its dynamic color only AFTER
     * attachment. MaterialShapeDrawable fill/tint is the read-only fallback
     * when its public backgroundTintList is absent.
     */
    private fun nativeFabDrawableTint(
        drawable: Drawable?,
        depth: Int = 0
    ): android.content.res.ColorStateList? {
        if (drawable == null || depth > 6) return null
        val nativeTint = runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "getTintList" && it.parameterCount == 0
            }?.invoke(drawable) as? android.content.res.ColorStateList
        }.getOrNull()
        if (nativeTint != null) return nativeTint
        val fill = runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "getFillColor" && it.parameterCount == 0
            }?.invoke(drawable) as? android.content.res.ColorStateList
        }.getOrNull()
        if (fill != null) return fill
        if (drawable is android.graphics.drawable.ColorDrawable) {
            return android.content.res.ColorStateList.valueOf(drawable.color)
        }
        if (drawable is android.graphics.drawable.LayerDrawable) {
            for (i in 0 until drawable.numberOfLayers) {
                nativeFabDrawableTint(
                    drawable.getDrawable(i), depth + 1
                )?.let { return it }
            }
        }
        if (drawable is android.graphics.drawable.InsetDrawable) {
            return nativeFabDrawableTint(drawable.drawable, depth + 1)
        }
        return null
    }

    private fun syncNativeMiniFabPalette(source: View, mini: View) {
        val nativeTint = runCatching {
            source.javaClass.getMethod("getBackgroundTintList")
                .invoke(source) as? android.content.res.ColorStateList
        }.getOrNull() ?: nativeFabDrawableTint(source.background)
        if (nativeTint != null) {
            val existing = runCatching {
                mini.javaClass.getMethod("getBackgroundTintList")
                    .invoke(mini) as? android.content.res.ColorStateList
            }.getOrNull()
            if (existing != nativeTint) {
                runCatching {
                    mini.javaClass.getMethod(
                        "setBackgroundTintList",
                        android.content.res.ColorStateList::class.java
                    ).invoke(mini, nativeTint)
                }.onFailure {
                    Log.w(TAG, "FOLDER PICKER FAB | native tint unavailable", it)
                }
            }
        } else {
            // Last-resort clone of the LIVE original native ripple/state,
            // never its shared instance. Material FAB may reject background
            // replacement, so prefer the native tint API above.
            val state = source.background?.constantState
            if (state != null && miniFabBackgroundSource[mini] !== state) {
                runCatching {
                    mini.background = state.newDrawable(source.resources).mutate()
                    miniFabBackgroundSource[mini] = state
                }.onFailure {
                    Log.w(TAG, "FOLDER PICKER FAB | background fallback failed", it)
                }
            }
        }
        (source as? ImageView)?.imageTintList?.let { nativeIconTint ->
            (mini as? ImageView)?.let { icon ->
                if (icon.imageTintList != nativeIconTint) {
                    icon.imageTintList = nativeIconTint
                }
            }
        }
    }

    /**
     * Reuse the live GMMP native FloatingActionButton CLASS, tint, ripple
     * and two genuine installed-player icons. The root plus remains the
     * original native control; only its presentation as a speed dial is new.
     */
    private fun nativeMiniFab(
        browser: Browser,
        source: View,
        icon: String,
        click: () -> Unit
    ): View? = runCatching {
        val iconId = source.resources.getIdentifier(
            icon, "drawable", source.context.packageName
        )
        if (iconId == 0) return@runCatching null
        // Native GMMP 4.2.0 AestheticFab has Context,AttributeSet
        // constructor, NOT the Context-only constructor previously tried.
        val item = source.javaClass.getConstructor(
            android.content.Context::class.java,
            android.util.AttributeSet::class.java
        ).newInstance(source.context, null)
            as? View ?: return@runCatching null
        (item as? ImageView)?.apply {
            setImageResource(iconId)
            (source as? ImageView)?.imageTintList?.let {
                imageTintList = it
            }
        }
        val size = dp(source, 44)
        runCatching {
            item.javaClass.getMethod("setCustomSize", Int::class.javaPrimitiveType)
                .invoke(item, size)
        }
        item.elevation = source.elevation
        item.setOnClickListener { click() }
        // The original picker FAB can be BELOW the RecyclerView's
        // viewport. Mini FABs must be siblings in its full-height native
        // coordinator rather than clipped inside our list overlay.
        val host = (source.parent as? ViewGroup)?.takeIf {
            it.height >= size * 4 && it.width >= source.width * 2
        } ?: browser.parent
        host.addView(
            item,
            ViewGroup.LayoutParams(size, size)
        )
        item.visibility = View.INVISIBLE
        // Native AestheticFab's theme observer can overwrite the tint it
        // received in its constructor; sync AFTER it joins the coordinator.
        syncNativeMiniFabPalette(source, item)
        item.post {
            if (item.parent != null) syncNativeMiniFabPalette(source, item)
        }
        item
    }.onFailure {
        Log.w(TAG, "FOLDER PICKER FAB | original native clone unavailable", it)
    }.getOrNull()

    private fun positionPickerAddOptions(
        browser: Browser,
        fab: View
    ) {
        val base = IntArray(2)
        fab.getLocationOnScreen(base)
        val size = dp(fab, 44)
        val gap = dp(fab, 12)
         listOf(browser.playlistFab, browser.folderFab)
            .filterNotNull().forEachIndexed { index, item ->
                val host = item.parent as? ViewGroup ?: return@forEachIndexed
                val hostPosition = IntArray(2)
                host.getLocationOnScreen(hostPosition)
                item.x = (base[0] - hostPosition[0] +
                    (fab.width - size) / 2).toFloat()
                item.y = (base[1] - hostPosition[1] -
                    (index + 1) * (size + gap)).toFloat()
                // Both minis were appended ABOVE the original FAB by
                // addView. bringToFront() here on every global-layout
                // callback would repeatedly invalidate the same host.
            }
    }

    private fun closePickerAddOptions(browser: Browser) {
        browser.pickerAddExpanded = false
        val oldButtons = listOf(browser.playlistFab, browser.folderFab)
            .filterNotNull()
        browser.playlistFab = null
        browser.folderFab = null
        // Native popup/fragment detach and global-layout traversal may
        // invoke this method while Android iterates children. Hide
        // synchronously, then remove each native FAB NEXT main-loop turn.
        oldButtons.forEach { mini -> mini.visibility = View.GONE }
        if (oldButtons.isNotEmpty()) {
            mainHandler.post {
                oldButtons.forEach { mini ->
                    (mini.parent as? ViewGroup)?.removeView(mini)
                }
            }
        }
    }

    private fun showPickerAddOptions(browser: Browser, fab: View): Boolean {
        closePickerAddOptions(browser)
        val normalCreate = PlaylistCreationUiPolicy.state(
            foldersEnabled = settings.enabled,
            currentFolderId = browser.currentFolderId,
            mainPlaylistDirectory = browser.rootPath,
            groupRootPlaylists = settings.groupRoot,
            hasPickerSelection = false,
            nativePhysicalCreateReady = nativePickerCreateRedirectReady
        ).pickerFabVisible
        if (normalCreate) {
            browser.playlistFab = nativeMiniFab(
                browser, fab, "ic_gm_playlist"
            ) {
                closePickerAddOptions(browser)
                browser.forwardingOriginalFab = true
                try {
                    fab.performClick()
                } finally {
                    browser.forwardingOriginalFab = false
                }
            }
        }
        browser.folderFab = nativeMiniFab(
            browser, fab, "ic_gm_new_folder"
        ) {
            closePickerAddOptions(browser)
            requestNativeFolderCreation(browser)
        }
        if (browser.folderFab == null &&
            browser.playlistFab == null
        ) {
            Log.w(TAG, "FOLDER PICKER FAB | no native buttons available;" +
                " passing original GMMP FAB click through")
            return false
        }
        browser.pickerAddExpanded = true
        positionPickerAddOptions(browser, fab)
        listOf(browser.playlistFab, browser.folderFab)
            .filterNotNull().forEach { mini ->
                runCatching {
                    mini.javaClass.getMethod("show").invoke(mini)
                    syncNativeMiniFabPalette(fab, mini)
                }.onFailure {
                    mini.visibility = View.VISIBLE
                    Log.w(TAG, "FOLDER PICKER FAB | native show unavailable", it)
                }
            }
        Log.i(TAG, "FOLDER PICKER FAB | original GMMP mini FABs shown")
        return true
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
            hasPickerSelection = selected,
            nativePhysicalCreateReady = nativePickerCreateRedirectReady
        )
        val show = state.pickerFabVisible ||
            (!selected && nativeFolderCreator != null &&
                physicalFolderParent(browser) != null)
        if (!show || selected) closePickerAddOptions(browser)
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
            if (browser.pickerAddExpanded) {
                listOf(browser.playlistFab, browser.folderFab)
                    .filterNotNull().forEach {
                        syncNativeMiniFabPalette(fab, it)
                    }
                positionPickerAddOptions(browser, fab)
            }
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
        if (multiSelect.hasFolderSelection(browser.list) ||
            browser.forwardingOriginalFab
        ) return false
        if (nativeFolderCreator != null &&
            physicalFolderParent(browser) != null
        ) {
            return if (browser.pickerAddExpanded) {
                closePickerAddOptions(browser)
                true
            } else showPickerAddOptions(browser, fab)
        }
        val state = PlaylistCreationUiPolicy.state(
            foldersEnabled = settings.enabled,
            currentFolderId = browser.currentFolderId,
            mainPlaylistDirectory = browser.rootPath,
            groupRootPlaylists = settings.groupRoot,
            hasPickerSelection = false,
            nativePhysicalCreateReady = nativePickerCreateRedirectReady
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
