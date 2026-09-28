package io.github.alagga.gonesmart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ImageSpan
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Physical folder navigation for GMMP 4.2.0's Smart-Playlists tab.
 *
 * Native-first contract:
 * - GMMP's original ls4 adapter remains installed on smartListRecyclerView.
 * - ls4.x / ls4.U(List) is GMMP's metadata-row configuration (List<t23>)
 *   and is NEVER modified by GoneSmart.
 * - Smart-Playlist items (List<ws4>) are submitted through the original
 *   ls4.y AsyncListDiffer, matching native os4.j2(List).
 * - Current-folder .spl files are parsed by original ws4.r(File).
 * - Current-folder ordering uses original ou4.e(...).
 * - GMMP itself renders and handles every real Smart-Playlist row through
 *   the unchanged native RecyclerView/ls4 adapter.
 * - GoneSmart adds only physical folder rows plus a breadcrumb and reserves
 *   native top padding for them. Folder rows scroll away with the native list;
 *   the breadcrumb stays fixed like the accepted normal Playlist-folder view.
 *   GoneSmart never hides or redraws real Smart-Playlist rows.
 */
internal class SmartPlaylistFolderController {
    companion object {
        private const val TAG = "GoneSmartSmartFolders"
        private const val SMART_LIST_ID = "smartListRecyclerView"
        private const val SMART_LIST_MENU = "menu_gm_smart_list"
        private const val SMART_CONTEXT_MENU = "menu_gm_context_smart"
        private const val MAX_ATTACH_RETRIES = 24
        private const val ATTACH_RETRY_MS = 120L
        private const val QUICK_NAV_METRICS_PREFS =
            "gonesmart_gmmp_quicknav_metrics"
        private const val QUICK_NAV_TITLE_RATIO_KEY = "title_ratio"
        private const val GMMP_420_QUICK_NAV_TITLE_RATIO = 1.225f
    }

    private data class Bindings(
        val loader: ClassLoader,
        val adapterClass: Class<*>,
        val holderClass: Class<*>,
        val modelClass: Class<*>,
        val presenterClass: Class<*>,
        val modelConstructor: Constructor<*>,
        val modelRead: Method,
        val modelName: Field,
        val modelFile: Field,
        val modelRules: Field,
        val holderModel: Field,
        val adapterDiffer: Field,
        val differSubmit: Method,
        val storagePath: Method,
        val smartStorageLocation: Any,
        val nativeSort: Method,
        val presenterState: Field,
        val stateSort: Field,
        val sortOrder: Method,
        val sortDescending: Method,
        val leafRuleClass: Class<*>,
        val leafRuleValue: Field,
        val groupRuleClass: Class<*>,
        val groupRules: Field,
        val actionModeBaseClass: Class<*>,
        val smartFragmentClass: Class<*>,
        val actionModeView: Field,
        val actionModeSelection: Field,
        val selectionEntries: Field,
        val selectionEntryModel: Field
    )

    private data class NativeStyle(
        val rowLayoutId: Int,
        val titleViewId: Int,
        val rowHeight: Int,
        val textColor: Int,
        val textSizePx: Float,
        val typeface: Typeface?,
        val titleGravity: Int,
        val titlePaddingStart: Int,
        val titlePaddingEnd: Int,
        val paint: TextPaint,
        val letterSpacing: Float,
        val textScaleX: Float,
        val includeFontPadding: Boolean,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val maxLines: Int,
        val ellipsize: android.text.TextUtils.TruncateAt?,
        val rowBackground: Drawable.ConstantState?
    )

    private data class Snapshot(
        val directory: File,
        val folders: List<File>,
        val models: List<Any>,
        val modelsByPath: Map<String, Any>
    )

    private data class BreadcrumbSegment(
        val key: String,
        val label: String,
        val directory: File? = null,
        val otherLocations: Boolean = false
    )

    private data class Browser(
        val list: ViewGroup,
        val nativeAdapter: Any,
        val host: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val folderBand: FrameLayout,
        val breadcrumb: RecyclerView,
        val originalAlpha: Float,
        val originalPaddingLeft: Int,
        val originalPaddingTop: Int,
        val originalPaddingRight: Int,
        val originalPaddingBottom: Int,
        val originalClipToPadding: Boolean,
        val root: File,
        var current: File,
        var otherLocations: Boolean,
        var style: NativeStyle?,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val scrollDrawListener: android.view.ViewTreeObserver.OnPreDrawListener,
        val detachListener: View.OnAttachStateChangeListener,
        var observer: FileObserver? = null,
        var generation: Long = 0L,
        var actionPending: Boolean = false,
        var nativeOrder: List<String> = emptyList(),
        var moveSources: List<String>? = null,
        var movePreviousDirectory: String? = null,
        var movePreviousOtherLocations: Boolean = false,
        var moveActionMode: android.view.ActionMode? = null,
        var moveFab: View? = null,
        var lastFolderScrollOffset: Int = Int.MIN_VALUE
    )

    private inner class BreadcrumbAdapter(
        private val browser: Browser
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val segments = arrayListOf<BreadcrumbSegment>()

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int =
            if (segments.isEmpty()) 0 else segments.size * 2 - 1

        override fun getItemViewType(position: Int): Int = position % 2

        override fun getItemId(position: Int): Long =
            (segments[position / 2].key.hashCode().toLong() shl 1) xor
                (position % 2).toLong()

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder {
            val layoutName = if (viewType == 0) {
                "rv_horiz_metadata"
            } else {
                "rv_horiz_separator"
            }
            val layoutId = parent.resources.getIdentifier(
                layoutName,
                "layout",
                parent.context.packageName
            )
            val native = if (layoutId != 0) {
                runCatching {
                    LayoutInflater.from(parent.context)
                        .inflate(layoutId, parent, false)
                }.getOrNull()
            } else null
            val view = native ?: if (viewType == 0) {
                TextView(parent.context).apply {
                    minHeight = dp(parent, 48)
                    gravity = Gravity.CENTER_VERTICAL
                    background = selectableBackground(parent)
                }
            } else {
                ImageView(parent.context).apply {
                    val icon = resources.getIdentifier(
                        "ic_gm_keyboard_arrow_right",
                        "drawable",
                        context.packageName
                    )
                    if (icon != 0) setImageResource(icon)
                }
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
            holder: RecyclerView.ViewHolder,
            position: Int
        ) {
            if (position % 2 != 0) return
            val segment = segments.getOrNull(position / 2) ?: return
            val label = (holder.itemView as? TextView)
                ?: findTextView(holder.itemView)
                ?: return
            label.text = segment.label
            browser.style?.let { style ->
                // Match the already accepted normal Playlist-folders
                // quick-nav typography. GMMP 4.2.0's live qg1 title is
                // 58.8 px vs 48 px row title = 1.225x until a verified
                // live ratio is persisted by the normal playlist surface.
                label.paint.set(style.paint)
                label.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    style.paint.textSize * quickNavTitleRatio(browser.list)
                )
                label.setTextColor(style.textColor)
                label.typeface = Typeface.create(style.typeface, Typeface.BOLD)
                label.letterSpacing = style.letterSpacing
                label.includeFontPadding = style.includeFontPadding
                label.requestLayout()
            }
            // Never overwrite rv_horiz_metadata's native XML padding/ripple.
            label.isClickable = true
            label.isFocusable = true
            label.setOnClickListener {
                if (browsers[browser.list] !== browser) return@setOnClickListener
                when {
                    segment.otherLocations -> {
                        if (!browser.otherLocations) navigateOtherLocations(browser)
                    }
                    segment.directory != null -> {
                        if (!browser.otherLocations &&
                            sameFile(browser.current, segment.directory)
                        ) return@setOnClickListener
                        navigate(browser, segment.directory)
                    }
                }
            }
        }

        fun submit(next: List<BreadcrumbSegment>) {
            segments.clear()
            segments.addAll(next)
            notifyDataSetChanged()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartSmartFolders").apply { isDaemon = true }
    }
    private val refreshGeneration = AtomicLong(0L)
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val menuRefs = arrayListOf<WeakReference<Menu>>()
    private val newFolderMenuId = View.generateViewId()
    private val moveMenuId = View.generateViewId()

    @Volatile private var enabled = false
    @Volatile private var groupRootPlaylists = false
    @Volatile private var bindings: Bindings? = null
    @Volatile private var presenterRef: WeakReference<Any>? = null
    @Volatile private var folderCreator: NativeGmmpFolderCreator? = null
    @Volatile private var rememberedDirectory: String? = null
    @Volatile private var rememberedOtherLocations = false
    @Volatile private var pendingCreationDirectory: String? = null
    @Volatile private var pendingCreationAt = 0L

    fun configure(loader: ClassLoader): Boolean {
        val configured = runCatching {
            val adapterClass = loader.loadClass("ls4")
            val holderClass = loader.loadClass("vs4")
            val modelClass = loader.loadClass("ws4")
            val presenterClass = loader.loadClass("ss4")
            val stateClass = loader.loadClass("ts4")
            val sortStateClass = loader.loadClass("zu4")
            val storageClass = loader.loadClass("tx4")
            val storageLocationClass = loader.loadClass("rx4")
            val sortExtensions = loader.loadClass("ou4")
            val leafRuleClass = loader.loadClass("ft4")
            val groupRuleClass = loader.loadClass("jt4")
            val actionModeBaseClass = loader.loadClass("n3")
            val smartFragmentClass = loader.loadClass("os4")
            val selectionTrackerClass = loader.loadClass("s3")
            val selectionEntryClass = loader.loadClass("s3\$a")

            Bindings(
                loader = loader,
                adapterClass = adapterClass,
                holderClass = holderClass,
                modelClass = modelClass,
                presenterClass = presenterClass,
                modelConstructor = modelClass.getDeclaredConstructor(
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    ArrayList::class.java,
                    Integer.TYPE
                ).apply { isAccessible = true },
                modelRead = modelClass.getDeclaredMethod(
                    "r",
                    File::class.java
                ).apply { isAccessible = true },
                modelName = modelClass.getDeclaredField("o")
                    .apply { isAccessible = true },
                modelFile = modelClass.getDeclaredField("v")
                    .apply { isAccessible = true },
                modelRules = findField(modelClass, "u")
                    .apply { isAccessible = true },
                holderModel = holderClass.getDeclaredField("A")
                    .apply { isAccessible = true },
                adapterDiffer = adapterClass.getDeclaredField("y")
                    .apply { isAccessible = true },
                differSubmit = adapterClass.getDeclaredField("y")
                    .apply { isAccessible = true }
                    .type
                    .getDeclaredMethod(
                        "b",
                        java.util.List::class.java
                    )
                    .apply { isAccessible = true },
                storagePath = storageClass.getDeclaredMethod(
                    "b",
                    storageLocationClass
                ).apply { isAccessible = true },
                smartStorageLocation = storageLocationClass
                    .getDeclaredField("s")
                    .apply { isAccessible = true }
                    .get(null),
                nativeSort = sortExtensions.getDeclaredMethod(
                    "e",
                    Integer.TYPE,
                    ArrayList::class.java,
                    java.lang.Boolean.TYPE
                ).apply { isAccessible = true },
                presenterState = presenterClass.getDeclaredField("x")
                    .apply { isAccessible = true },
                stateSort = stateClass.getDeclaredField("q")
                    .apply { isAccessible = true },
                sortOrder = sortStateClass.getDeclaredMethod("b")
                    .apply { isAccessible = true },
                sortDescending = sortStateClass.getDeclaredMethod("c")
                    .apply { isAccessible = true },
                leafRuleClass = leafRuleClass,
                leafRuleValue = findField(leafRuleClass, "q")
                    .apply { isAccessible = true },
                groupRuleClass = groupRuleClass,
                groupRules = findField(groupRuleClass, "o")
                    .apply { isAccessible = true },
                actionModeBaseClass = actionModeBaseClass,
                smartFragmentClass = smartFragmentClass,
                actionModeView = findField(actionModeBaseClass, "q")
                    .apply { isAccessible = true },
                actionModeSelection = findField(actionModeBaseClass, "s")
                    .apply { isAccessible = true },
                selectionEntries = findField(selectionTrackerClass, "c")
                    .apply { isAccessible = true },
                selectionEntryModel = findField(selectionEntryClass, "b")
                    .apply { isAccessible = true }
            )
        }.onFailure {
            Log.e(TAG, "SMART FOLDERS BINDINGS FAILED | native tab untouched", it)
        }.getOrNull()
        bindings = configured
        Log.i(TAG, "SMART FOLDERS BINDINGS | ready=" + (configured != null))
        return configured != null
    }

    fun setNativeFolderCreator(loader: ClassLoader) {
        folderCreator = NativeGmmpFolderCreator(loader)
    }

    fun setEnabled(next: Boolean) {
        setOptions(next, groupRootPlaylists)
    }

    fun setOptions(nextEnabled: Boolean, nextGroupRoot: Boolean) {
        val enabledChanged = enabled != nextEnabled
        val groupingChanged = groupRootPlaylists != nextGroupRoot
        if (!enabledChanged && !groupingChanged) return
        enabled = nextEnabled
        groupRootPlaylists = nextGroupRoot
        main.post {
            if (!groupRootPlaylists) {
                rememberedOtherLocations = false
                browsers.values.toList().forEach { browser ->
                    if (browser.otherLocations) {
                        browser.otherLocations = false
                        browser.current = browser.root
                    }
                }
            }
            updateMenus()
            if (enabledChanged && !nextEnabled) {
                pendingCreationDirectory = null
                browsers.values.toList().forEach(::restoreRootAndRemove)
            } else if (nextEnabled) {
                knownLists.keys.toList().forEach { list ->
                    if (browsers.containsKey(list)) {
                        browsers[list]?.let(::refresh)
                    } else {
                        scheduleAttach(list, 0)
                    }
                }
            }
        }
        Log.i(
            TAG,
            "SMART FOLDERS OPTION | enabled=" + nextEnabled +
                " | groupRoot=" + nextGroupRoot
        )
    }

    fun capturePresenter(presenter: Any?) {
        val native = bindings ?: return
        if (presenter != null && native.presenterClass.isInstance(presenter)) {
            presenterRef = WeakReference(presenter)
        }
    }

    fun onNativeRecyclerObserved(view: View?) {
        if (view == null || resourceName(view) != SMART_LIST_ID) return
        val list = view as? ViewGroup ?: return
        knownLists[list] = true
        if (enabled) scheduleAttach(list, 0)
    }

    /**
     * Native os4.j2(List<ws4>) has just submitted a Smart-Playlist list.
     * If a GoneSmart nested folder is open, re-apply that folder after the
     * original root refresh. Our own submit goes straight to ls4.y and
     * therefore cannot recurse through this callback.
     */
    fun onNativeSmartListSubmitted() {
        if (!enabled) return
        main.post {
            browsers.values.toList()
                .filter { it.list.isAttachedToWindow }
                .forEach(::refresh)
        }
    }

    fun markNativeCreateRequested() {
        if (!enabled) {
            pendingCreationDirectory = null
            return
        }
        val browser = currentBrowser() ?: run {
            pendingCreationDirectory = null
            return
        }
        pendingCreationDirectory = browser.current.path
        pendingCreationAt = android.os.SystemClock.uptimeMillis()
        Log.i(
            TAG,
            "SMART FOLDERS CREATE REQUEST | current=" +
                safePath(browser.current)
        )
    }

    /**
     * Called from the single original ws4.t(File) hook before GMMP writes.
     * Existing files are never moved. Only a brand-new root destination
     * created immediately after native ss4$b.onAdd is retargeted.
     */
    fun consumeRedirectedSaveDestination(original: File?): File? {
        if (!enabled || original == null) return null
        val requested = pendingCreationDirectory ?: return null
        if (android.os.SystemClock.uptimeMillis() - pendingCreationAt > 600_000L) {
            pendingCreationDirectory = null
            return null
        }
        val root = rootFile() ?: return null
        val targetPath = SmartPlaylistFolderPolicy.redirectNewSave(
            root.path,
            requested,
            original.path,
            original.exists()
        ) ?: return null
        val target = File(targetPath)
        if (!target.parentFile.isDirectory || target.exists()) {
            Log.w(
                TAG,
                "SMART FOLDERS SAVE REDIRECT BLOCKED | target unavailable | " +
                    safePath(target)
            )
            return null
        }
        pendingCreationDirectory = null
        Log.i(
            TAG,
            "SMART FOLDERS SAVE REDIRECT | " +
                safePath(original) + " -> " + safePath(target)
        )
        return target
    }

    fun onMenuInflated(
        menuResId: Int,
        menu: Menu?,
        inflater: Any?
    ) {
        if (menu == null) return
        val context = menuContext(menu, inflater) ?: return
        if (!NativeResourceIdPolicy.canResolveEntryName(menuResId)) return
        val name = runCatching {
            context.resources.getResourceEntryName(menuResId)
        }.getOrNull() ?: return
        when (name) {
            SMART_LIST_MENU -> {
                menuRefs.removeAll { it.get() == null }
                menuRefs += WeakReference(menu)
                if (enabled) {
                    installNewFolderMenu(menu, context)
                } else {
                    menu.findItem(newFolderMenuId)?.isVisible = false
                }
            }
            SMART_CONTEXT_MENU -> {
                if (enabled && currentBrowser() != null) {
                    installMoveMenu(menu, context)
                }
            }
        }
    }

    /** Exact GMMP 4.2.0 nt4.c(Context, zn0, MenuItem) dispatch. */
    fun interceptNativeContextMove(
        context: android.content.Context?,
        holder: Any?,
        item: MenuItem?
    ): Boolean {
        if (item?.itemId != moveMenuId) return false
        if (!enabled) return true
        val native = bindings ?: return true
        val browser = currentBrowser() ?: return true
        val model = holder?.takeIf(native.holderClass::isInstance)?.let {
            runCatching { native.holderModel.get(it) }.getOrNull()
        }
        val path = model?.let(::modelPath)
        if (path == null) {
            showMoveError(context ?: browser.list.context)
            return true
        }
        beginMove(browser, listOf(path), null)
        return true
    }

    /** Add GoneSmart Move to GMMP's ORIGINAL Smart selection ActionMode. */
    fun onNativeSmartActionModeCreated(callback: Any?, menu: Menu?) {
        if (!enabled || menu == null || !isSmartActionMode(callback)) return
        val context = currentBrowser()?.list?.context ?: return
        installMoveMenu(menu, context)
    }

    /** Exact n3 selected models: s3.c -> s3$a.b -> ws4. */
    fun interceptNativeSmartActionModeMove(
        callback: Any?,
        mode: Any?,
        item: MenuItem?
    ): Boolean {
        if (item?.itemId != moveMenuId) return false
        if (!enabled || !isSmartActionMode(callback)) return true
        val browser = currentBrowser() ?: return true
        val paths = selectedSmartPaths(callback)
        if (paths.isEmpty()) {
            showMoveError(browser.list.context)
            return true
        }
        beginMove(browser, paths, mode)
        return true
    }

    fun consumeBack(): Boolean {
        if (!enabled) return false
        val browser = currentBrowser() ?: return false
        if (browser.moveSources != null) {
            if (!sameFile(browser.current, browser.root)) {
                val parent = browser.current.parentFile?.canonicalFile ?: return true
                navigate(browser, parent)
            } else {
                closeMoveBrowser(browser)
            }
            return true
        }
        if (browser.otherLocations) {
            browser.otherLocations = false
            browser.current = browser.root
            rememberedDirectory = browser.root.path
            rememberedOtherLocations = false
            startObserver(browser)
            refresh(browser)
            updateMenus()
            return true
        }
        if (sameFile(browser.current, browser.root)) return false
        val parent = browser.current.parentFile?.canonicalFile ?: return false
        if (!SmartPlaylistFolderPolicy.isInsideRoot(
                browser.root.path,
                parent.path
            )
        ) return false
        navigate(browser, parent)
        return true
    }

    private fun scheduleAttach(list: ViewGroup, attempt: Int) {
        if (!enabled || browsers.containsKey(list)) return
        main.postDelayed({
            if (!enabled || browsers.containsKey(list) ||
                !list.isAttachedToWindow
            ) return@postDelayed
            val native = bindings ?: return@postDelayed
            val adapter = nativeAdapter(list)
            if (adapter == null || !native.adapterClass.isInstance(adapter) ||
                list.width <= 0 || list.height <= 0
            ) {
                if (attempt < MAX_ATTACH_RETRIES) {
                    scheduleAttach(list, attempt + 1)
                }
                return@postDelayed
            }
            val count = runCatching {
                adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
            }.getOrDefault(0)
            val style = sampleNativeStyle(list)
            if (style == null && count > 0 && attempt < MAX_ATTACH_RETRIES) {
                scheduleAttach(list, attempt + 1)
                return@postDelayed
            }
            attach(list, adapter, style)
        }, if (attempt == 0) 0L else ATTACH_RETRY_MS)
    }

    private fun attach(
        list: ViewGroup,
        adapter: Any,
        initialStyle: NativeStyle?
    ) {
        if (!enabled || browsers.containsKey(list)) return
        val root = rootFile() ?: run {
            Log.w(TAG, "SMART FOLDERS ATTACH STOP | native root unavailable")
            return
        }
        val host = safeOverlayHost(list) ?: run {
            Log.w(TAG, "SMART FOLDERS ATTACH STOP | page host unavailable")
            return
        }
        val remembered = rememberedDirectory
            ?.let(::File)
            ?.takeIf {
                it.isDirectory &&
                    SmartPlaylistFolderPolicy.isInsideRoot(root.path, it.path)
            }
            ?.canonicalFile
            ?: root
        val restoreOtherLocations =
            groupRootPlaylists &&
                rememberedOtherLocations &&
                sameFile(remembered, root)

        val nativeSurfaceColor = initialStyle?.let { nativeSurfaceBackground(list) }
            ?: resolveColor(list, android.R.attr.colorBackground, Color.BLACK)
        val nativeSurfaceState = cloneBackground(list)?.constantState
        fun headerSurface(): Drawable =
            nativeSurfaceState?.newDrawable(list.resources)?.mutate()
                ?: ColorDrawable(nativeSurfaceColor)

        val overlay = FrameLayout(list.context).apply {
            isClickable = false
            isFocusable = false
            clipChildren = true
            background = ColorDrawable(Color.TRANSPARENT)
        }
        val breadcrumb = RecyclerView(list.context).apply {
            layoutManager = LinearLayoutManager(
                context,
                RecyclerView.HORIZONTAL,
                false
            )
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            visibility = View.GONE
            background = headerSurface()
            elevation = dp(list, 2).toFloat()
        }
        val rows = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val folderBand = FrameLayout(list.context).apply {
            background = headerSurface()
            addView(
                rows,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val column = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                breadcrumb,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                folderBand,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        overlay.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        lateinit var browser: Browser
        val layoutListener =
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
                if (browsers[list] === browser) positionOverlay(browser)
            }
        val scrollDrawListener =
            android.view.ViewTreeObserver.OnPreDrawListener {
                if (browsers[list] === browser) syncFolderRowsScroll(browser)
                true
            }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (browsers[list] === browser) removeBrowser(browser)
            }
        }
        browser = Browser(
            list = list,
            nativeAdapter = adapter,
            host = host,
            overlay = overlay,
            rows = rows,
            folderBand = folderBand,
            breadcrumb = breadcrumb,
            originalAlpha = list.alpha,
            originalPaddingLeft = list.paddingLeft,
            originalPaddingTop = list.paddingTop,
            originalPaddingRight = list.paddingRight,
            originalPaddingBottom = list.paddingBottom,
            originalClipToPadding = list.clipToPadding,
            root = root,
            current = remembered,
            otherLocations = restoreOtherLocations,
            style = initialStyle,
            layoutListener = layoutListener,
            scrollDrawListener = scrollDrawListener,
            detachListener = detachListener
        )
        breadcrumb.adapter = BreadcrumbAdapter(browser)
        browsers[list] = browser
        list.addOnAttachStateChangeListener(detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            list.viewTreeObserver.addOnPreDrawListener(scrollDrawListener)
        }

        // Keep GMMP's real Smart-Playlist RecyclerView visible. The
        // GoneSmart overlay is header-only and never replaces native rows.
        list.alpha = browser.originalAlpha
        val contentChild = directChildInHost(list, host)
        val insertAt = if (contentChild == null) host.childCount else {
            (host.indexOfChild(contentChild) + 1).coerceAtMost(host.childCount)
        }
        host.addView(
            overlay,
            insertAt,
            ViewGroup.LayoutParams(
                list.width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        positionOverlay(browser)
        startObserver(browser)
        refresh(browser)
        updateMenus()
        Log.i(
            TAG,
            "SMART FOLDERS READY | root=" + safePath(root) +
                " | restored=" + safePath(remembered) +
                " | otherLocations=" + restoreOtherLocations +
                " | nativeAdapter=ls4"
        )
    }

    private fun navigate(browser: Browser, target: File) {
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return
        if (!canonical.isDirectory ||
            !SmartPlaylistFolderPolicy.isInsideRoot(
                browser.root.path,
                canonical.path
            )
        ) return
        browser.current = canonical
        browser.otherLocations = false
        if (browser.moveSources == null) {
            rememberedDirectory = canonical.path
            rememberedOtherLocations = false
        }
        startObserver(browser)
        refresh(browser)
        updateMenus()
        Log.i(TAG, "SMART FOLDERS NAV | current=" + safePath(canonical))
    }

    private fun navigateOtherLocations(browser: Browser) {
        if (!groupRootPlaylists || browser.moveSources != null) return
        browser.current = browser.root
        browser.otherLocations = true
        rememberedDirectory = browser.root.path
        rememberedOtherLocations = true
        startObserver(browser)
        refresh(browser)
        updateMenus()
        Log.i(TAG, "SMART FOLDERS NAV | virtual=other-locations")
    }

    private fun refresh(browser: Browser) {
        if (!enabled || browsers[browser.list] !== browser) return
        val generation = refreshGeneration.incrementAndGet()
        browser.generation = generation
        val directory = browser.current
        val otherLocations = browser.otherLocations
        worker.execute {
            val snapshot = runCatching {
                loadSnapshot(directory)
            }.onFailure {
                Log.e(TAG, "SMART FOLDERS LOAD FAILED | " + safePath(directory), it)
            }.getOrNull() ?: return@execute
            main.post {
                if (!enabled || browsers[browser.list] !== browser ||
                    browser.generation != generation ||
                    !sameFile(browser.current, snapshot.directory) ||
                    browser.otherLocations != otherLocations
                ) return@post
                val models = when {
                    browser.moveSources != null -> emptyList()
                    browser.otherLocations -> snapshot.models
                    groupRootPlaylists &&
                        sameFile(browser.current, browser.root) -> emptyList()
                    else -> snapshot.models
                }
                browser.nativeOrder = models.mapNotNull(::modelPath)
                applyNativeModels(browser, models)
                browser.style = sampleNativeStyle(browser.list) ?: browser.style
                render(browser, snapshot, models.size)
                positionOverlay(browser)
            }
        }
    }

    private fun loadSnapshot(directory: File): Snapshot {
        val native = bindings ?: error("Smart-folder bindings missing")
        val files = directory.listFiles()?.toList().orEmpty()
        val folders = files.filter { it.isDirectory }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter {
                SmartPlaylistFolderPolicy.isInsideRoot(
                    rootFile()?.path ?: directory.path,
                    it.path
                )
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.name }))

        val models = ArrayList<Any>()
        for (file in files.filter {
            it.isFile && it.extension.equals("spl", ignoreCase = true)
        }) {
            val model = runCatching {
                native.modelConstructor.newInstance(
                    null,
                    0,
                    0,
                    0,
                    null,
                    255
                ).also {
                    native.modelRead.invoke(it, file)
                }
            }.onFailure {
                Log.w(TAG, "SMART FOLDERS MODEL SKIP | " + safePath(file), it)
            }.getOrNull() ?: continue
            models += model
        }
        val sorted = sortNative(models)
        val byPath = linkedMapOf<String, Any>()
        sorted.forEach { model ->
            modelPath(model)?.let { byPath[it] = model }
        }
        return Snapshot(
            directory = directory.canonicalFile,
            folders = folders,
            models = sorted,
            modelsByPath = byPath
        )
    }

    private fun sortNative(models: ArrayList<Any>): List<Any> {
        val native = bindings ?: return models
        val presenter = presenterRef?.get()
        if (presenter != null) {
            runCatching {
                val state = native.presenterState.get(presenter)
                val sortState = native.stateSort.get(state)
                val orderPreference = native.sortOrder.invoke(sortState)
                val descendingPreference =
                    native.sortDescending.invoke(sortState)
                val order = preferenceValue(orderPreference) as Number
                val descending = preferenceValue(descendingPreference) as Boolean
                @Suppress("UNCHECKED_CAST")
                return native.nativeSort.invoke(
                    null,
                    order.toInt(),
                    ArrayList(models),
                    descending
                ) as List<Any>
            }.onFailure {
                Log.w(TAG, "SMART FOLDERS SORT | native sort unavailable", it)
            }
        }
        return models.sortedWith(
            compareBy(
                { modelName(it).lowercase() },
                { modelName(it) }
            )
        )
    }

    private fun preferenceValue(preference: Any?): Any? {
        if (preference == null) return null
        return preference.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterCount == 0
        }?.invoke(preference)
    }

    /**
     * DEX-proven GMMP 4.2.0 contract:
     * - ls4.x + U(List) = List<t23> metadata configuration.
     * - ls4.y = AsyncListDiffer whose ns4 callback compares ws4 objects.
     * - os4.j2(List<ws4>) calls ls4.y.b(List).
     *
     * Never route ws4 through U(List): r1.c(...) casts that list to t23 and
     * crashes SmartListAdapter.onCreateViewHolder.
     */
    private fun applyNativeModels(browser: Browser, models: List<Any>) {
        val native = bindings ?: return
        val differ = runCatching {
            native.adapterDiffer.get(browser.nativeAdapter)
        }.onFailure {
            Log.e(TAG, "SMART FOLDERS DIFFER | native ls4.y unavailable", it)
        }.getOrNull() ?: return
        runCatching {
            native.differSubmit.invoke(differ, models)
        }.onFailure {
            Log.e(TAG, "SMART FOLDERS DIFFER | native ws4 submit failed", it)
        }
    }

    private fun render(
        browser: Browser,
        snapshot: Snapshot,
        visibleSmartCount: Int
    ) {
        browser.rows.removeAllViews()
        browser.folderBand.translationY = 0f
        browser.lastFolderScrollOffset = Int.MIN_VALUE
        renderBreadcrumb(browser)

        val moving = browser.moveSources != null
        val folders = if (browser.otherLocations) {
            emptyList()
        } else {
            snapshot.folders
        }
        folders.forEach { folder ->
            val row = createRow(
                browser,
                folder.name,
                folder = true,
                contextMenuSource = null,
                onContext = null
            )
            row.setOnClickListener { navigate(browser, folder) }
            browser.rows.addView(row)
        }

        val showOtherLocations =
            !moving &&
                groupRootPlaylists &&
                !browser.otherLocations &&
                sameFile(browser.current, browser.root)
        if (showOtherLocations) {
            val otherLabel = NativeGmmpUiText.otherLocations(browser.list.context)
            val row = createRow(
                browser,
                otherLabel,
                folder = true,
                contextMenuSource = null,
                onContext = null
            )
            row.setOnClickListener { navigateOtherLocations(browser) }
            browser.rows.addView(row)
        }

        // Real Smart-Playlist rows remain 100% native. They are already
        // filtered by applyNativeModels() through GMMP's original differ.
        browser.overlay.post {
            if (browsers[browser.list] === browser) {
                updateNativeInset(browser)
                syncFolderRowsScroll(browser)
                if (moving) positionMoveFab(browser)
            }
        }

        Log.i(
            TAG,
            "SMART FOLDERS RENDER | current=" + safePath(browser.current) +
                " | virtualOther=" + browser.otherLocations +
                " | folders=" + (folders.size + if (showOtherLocations) 1 else 0) +
                " | smart=" + visibleSmartCount +
                " | nativeRows=true | move=" + moving
        )
    }

    private fun renderBreadcrumb(browser: Browser) {
        val next = arrayListOf<BreadcrumbSegment>()
        val rootLabel = NativeGmmpUiText.string(
            browser.list.context,
            "smart_playlists"
        ) ?: NativeGmmpUiText.smartPlaylist(browser.list.context)
        next += BreadcrumbSegment(
            key = "root",
            label = rootLabel,
            directory = browser.root
        )
        if (browser.otherLocations) {
            next += BreadcrumbSegment(
                key = "other-locations",
                label = NativeGmmpUiText.otherLocations(browser.list.context),
                otherLocations = true
            )
        } else if (!sameFile(browser.current, browser.root)) {
            val relative = runCatching {
                browser.current.relativeTo(browser.root).path
            }.getOrDefault("")
            var cursor = browser.root
            relative.split(File.separatorChar)
                .filter(String::isNotBlank)
                .forEach { name ->
                    cursor = File(cursor, name)
                    next += BreadcrumbSegment(
                        key = "dir:" + canonicalPath(cursor.path),
                        label = name,
                        directory = cursor
                    )
                }
        }
        (browser.breadcrumb.adapter as? BreadcrumbAdapter)?.submit(next)
        browser.breadcrumb.visibility =
            if (next.size > 1) View.VISIBLE else View.GONE
    }

    private fun createRow(
        browser: Browser,
        text: String,
        folder: Boolean,
        contextMenuSource: ImageView?,
        onContext: (() -> Unit)?
    ): View {
        val style = browser.style
        val layoutId = style?.rowLayoutId?.takeIf { it != 0 } ?: 0
        val template = if (layoutId != 0) {
            runCatching {
                LayoutInflater.from(browser.list.context).inflate(
                    layoutId,
                    browser.rows,
                    false
                )
            }.getOrNull()
        } else null
        val title = if (template != null && style?.titleViewId != 0) {
            template.findViewById<TextView>(style!!.titleViewId)
        } else null

        if (template != null && title != null && style != null) {
            // Same complete live native title copy used by the accepted
            // normal Playlist-folders implementation.
            title.text = text
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx)
            title.setTextColor(style.textColor)
            if (style.typeface != null) title.typeface = style.typeface
            title.gravity = style.titleGravity
            title.letterSpacing = style.letterSpacing
            title.textScaleX = style.textScaleX
            title.includeFontPadding = style.includeFontPadding
            title.setLineSpacing(
                style.lineSpacingExtra,
                style.lineSpacingMultiplier
            )
            title.maxLines = style.maxLines
            title.ellipsize = style.ellipsize
            title.setPaddingRelative(
                style.titlePaddingStart,
                title.paddingTop,
                style.titlePaddingEnd,
                title.paddingBottom
            )
            title.paint.set(style.paint)
            title.requestLayout()

            template.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                style.rowHeight
            )
            template.minimumHeight = style.rowHeight
            hideOtherText(template, title)
            configureContextMenu(
                template,
                browser.list,
                folder,
                contextMenuSource,
                onContext
            )
            if (folder) addNativeFolderIcon(template, title, browser.list, style)
            style.rowBackground?.newDrawable(browser.list.resources)
                ?.mutate()?.let { template.background = it }
            template.isClickable = true
            template.isFocusable = true
            return template
        }

        // Defensive fallback when a live row template is not available.
        return TextView(browser.list.context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            val textColor = style?.textColor ?: resolveColor(
                browser.list,
                android.R.attr.textColorPrimary,
                Color.WHITE
            )
            minHeight = style?.rowHeight ?: dp(browser.list, 54)
            setPadding(dp(browser.list, 12), 0, dp(browser.list, 16), 0)
            setTextColor(textColor)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                style?.textSizePx
                    ?: browser.list.resources.displayMetrics.scaledDensity * 16f
            )
            typeface = style?.typeface
            background = style?.rowBackground
                ?.newDrawable(browser.list.resources)?.mutate()
                ?: selectableBackground(browser.list)
            if (folder) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    FolderOutlineDrawable(textColor, dp(browser.list, 24)),
                    null,
                    null,
                    null
                )
                compoundDrawablePadding = dp(browser.list, 12)
            }
        }
    }

    private fun addNativeFolderIcon(
        root: View,
        title: TextView,
        host: View,
        style: NativeStyle
    ) {
        val content = root as? ViewGroup ?: return
        val image = ImageView(host.context).apply {
            setImageDrawable(
                FolderOutlineDrawable(style.textColor, dp(host, 24))
            )
            contentDescription = NativeGmmpUiText.string(host.context, "folder")
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = dp(host, 24)
        if (content is FrameLayout) {
            content.addView(
                image,
                FrameLayout.LayoutParams(
                    size,
                    size,
                    Gravity.START or Gravity.CENTER_VERTICAL
                ).apply {
                    marginStart = dp(host, 12)
                }
            )
        } else {
            content.addView(image, ViewGroup.LayoutParams(size, size))
        }
        title.setPaddingRelative(
            title.paddingStart + dp(host, 28),
            title.paddingTop,
            title.paddingEnd,
            title.paddingBottom
        )
    }

    private fun configureContextMenu(
        root: View,
        host: View,
        folder: Boolean,
        source: ImageView?,
        onContext: (() -> Unit)?
    ) {
        val id = host.resources.getIdentifier(
            "rvContextMenu",
            "id",
            host.context.packageName
        )
        if (id == 0) return
        val button = root.findViewById<ImageView>(id) ?: return
        if (folder || onContext == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        val clone = source?.drawable?.constantState
            ?.newDrawable(host.resources)?.mutate()
        if (clone != null) {
            button.setImageDrawable(clone)
        } else {
            val icon = host.resources.getIdentifier(
                "ic_gm_more_vert",
                "drawable",
                host.context.packageName
            )
            if (icon != 0) button.setImageResource(icon)
        }
        source?.imageTintList?.let { button.imageTintList = it }
        button.visibility = View.VISIBLE
        button.isClickable = true
        button.setOnClickListener { onContext() }
    }

    private fun dispatchNativeAction(
        browser: Browser,
        targetPath: String,
        longClick: Boolean,
        contextMenu: Boolean
    ): Boolean {
        if (browser.actionPending || !browser.list.isAttachedToWindow) {
            return false
        }
        if (performMatchingNativeAction(
                browser,
                targetPath,
                longClick,
                contextMenu
            )
        ) return true

        // ls4.x is List<t23> metadata configuration, not Smart items.
        // The submitted ws4 snapshot order is the native adapter order.
        val position = browser.nativeOrder.indexOf(
            canonicalPath(targetPath)
        )
        if (position < 0) return false
        browser.actionPending = true
        runCatching {
            browser.list.javaClass.getMethod(
                "scrollToPosition",
                Integer.TYPE
            ).invoke(browser.list, position)
        }.onFailure {
            browser.actionPending = false
            return false
        }
        browser.list.postOnAnimation {
            browser.list.postOnAnimation {
                browser.actionPending = false
                performMatchingNativeAction(
                    browser,
                    targetPath,
                    longClick,
                    contextMenu
                )
            }
        }
        return true
    }

    private fun performMatchingNativeAction(
        browser: Browser,
        targetPath: String,
        longClick: Boolean,
        contextMenu: Boolean
    ): Boolean {
        val native = bindings ?: return false
        val getHolder = runCatching {
            browser.list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return false
        val expected = canonicalPath(targetPath)
        for (index in 0 until browser.list.childCount) {
            val row = browser.list.getChildAt(index) ?: continue
            val holder = runCatching {
                getHolder.invoke(browser.list, row)
            }.getOrNull() ?: continue
            if (!native.holderClass.isInstance(holder)) continue
            val model = runCatching {
                native.holderModel.get(holder)
            }.getOrNull() ?: continue
            if (modelPath(model) != expected) continue
            return runCatching {
                when {
                    contextMenu -> {
                        val id = row.resources.getIdentifier(
                            "rvContextMenu",
                            "id",
                            row.context.packageName
                        )
                        val button = if (id != 0) {
                            row.findViewById<View>(id)
                        } else null
                        button?.takeIf {
                            it.visibility == View.VISIBLE &&
                                it.hasOnClickListeners()
                        }?.performClick() ?: false
                    }
                    longClick -> row.performLongClick()
                    else -> row.performClick()
                }
            }.onFailure {
                Log.e(TAG, "SMART FOLDERS NATIVE ACTION FAILED", it)
            }.getOrDefault(false)
        }
        return false
    }

    private fun sampleNativeStyle(list: ViewGroup): NativeStyle? {
        val native = bindings ?: return null
        val getHolder = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return null
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) ?: continue
            val holder = runCatching {
                getHolder.invoke(list, row)
            }.getOrNull() ?: continue
            if (!native.holderClass.isInstance(holder)) continue
            val model = runCatching {
                native.holderModel.get(holder)
            }.getOrNull() ?: continue
            val name = modelName(model)
            val title = findTextView(row, name) ?: continue
            val effectivePaint = effectiveNativeTitlePaint(title)
            return NativeStyle(
                rowLayoutId = row.sourceLayoutResId,
                titleViewId = title.id,
                rowHeight = row.height.coerceAtLeast(dp(list, 44)),
                textColor = effectivePaint.color,
                textSizePx = effectivePaint.textSize,
                typeface = effectivePaint.typeface,
                titleGravity = title.gravity,
                titlePaddingStart = title.paddingStart,
                titlePaddingEnd = title.paddingEnd,
                paint = effectivePaint,
                letterSpacing = title.letterSpacing,
                textScaleX = title.textScaleX,
                includeFontPadding = title.includeFontPadding,
                lineSpacingExtra = title.lineSpacingExtra,
                lineSpacingMultiplier = title.lineSpacingMultiplier,
                maxLines = title.maxLines,
                ellipsize = title.ellipsize,
                rowBackground = row.background?.constantState
            )
        }
        return null
    }

    private fun firstNativeContextMenu(list: ViewGroup): ImageView? {
        val id = list.resources.getIdentifier(
            "rvContextMenu",
            "id",
            list.context.packageName
        )
        if (id == 0) return null
        for (index in 0 until list.childCount) {
            val button = list.getChildAt(index)
                ?.findViewById<ImageView>(id)
                ?: continue
            if (button.visibility == View.VISIBLE) return button
        }
        return null
    }

    private fun nativeMoveLabel(context: android.content.Context): String =
        GoneSmartGmmpStrings.move(context.resources.configuration.locales[0])

    private fun installMoveMenu(
        menu: Menu,
        context: android.content.Context
    ) {
        if (menu.findItem(moveMenuId) != null) return
        val order = (0 until menu.size())
            .map(menu::getItem)
            .maxOfOrNull { it.order }
            ?.plus(1)
            ?: 0
        menu.add(
            Menu.NONE,
            moveMenuId,
            order,
            nativeMoveLabel(context)
        ).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    private fun isSmartActionMode(callback: Any?): Boolean {
        val native = bindings ?: return false
        if (callback == null ||
            !native.actionModeBaseClass.isInstance(callback)
        ) return false
        val view = runCatching {
            native.actionModeView.get(callback)
        }.getOrNull() ?: return false
        return native.smartFragmentClass.isInstance(view)
    }

    private fun selectedSmartPaths(callback: Any?): List<String> {
        val native = bindings ?: return emptyList()
        if (callback == null ||
            !native.actionModeBaseClass.isInstance(callback)
        ) return emptyList()
        val tracker = runCatching {
            native.actionModeSelection.get(callback)
        }.getOrNull() ?: return emptyList()
        val entries = runCatching {
            native.selectionEntries.get(tracker) as? Iterable<*>
        }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry == null) null else {
                val model = runCatching {
                    native.selectionEntryModel.get(entry)
                }.getOrNull()
                model?.takeIf(native.modelClass::isInstance)?.let(::modelPath)
            }
        }.distinct()
    }

    private fun beginMove(
        browser: Browser,
        sources: List<String>,
        originalMode: Any?
    ) {
        if (browser.moveSources != null) return
        val rootPath = canonicalPath(browser.root.path)
        val selected = sources
            .map(::canonicalPath)
            .distinct()
            .filter {
                SmartPlaylistFolderPolicy.isInsideRoot(rootPath, it) &&
                    File(it).isFile &&
                    File(it).extension.equals("spl", ignoreCase = true)
            }
        if (selected.size != sources.distinct().size || selected.isEmpty()) {
            Log.w(TAG, "SMART MOVE | invalid native selection")
            showMoveError(browser.list.context)
            return
        }
        browser.movePreviousDirectory = browser.current.path
        browser.movePreviousOtherLocations = browser.otherLocations
        browser.moveSources = selected
        browser.current = browser.root
        browser.otherLocations = false
        runCatching {
            originalMode?.javaClass?.getMethod("finish")?.invoke(originalMode)
        }
        startObserver(browser)
        refresh(browser)
        updateMenus()
        browser.list.post {
            if (browsers[browser.list] === browser &&
                browser.moveSources != null
            ) installMoveChrome(browser)
        }
        Log.i(TAG, "SMART MOVE UI | destination browser opened | count=" +
            selected.size)
    }

    private fun closeMoveBrowser(browser: Browser) {
        val previous = browser.movePreviousDirectory
        val previousOther = browser.movePreviousOtherLocations
        browser.moveSources = null
        browser.movePreviousDirectory = null
        browser.movePreviousOtherLocations = false
        endMoveChrome(browser)
        val restored = previous
            ?.let(::File)
            ?.takeIf {
                it.isDirectory &&
                    SmartPlaylistFolderPolicy.isInsideRoot(
                        browser.root.path,
                        it.path
                    )
            }
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: browser.root
        browser.current = restored
        browser.otherLocations =
            groupRootPlaylists &&
                previousOther &&
                sameFile(restored, browser.root)
        startObserver(browser)
        refresh(browser)
        updateMenus()
    }

    private fun confirmMoveBrowser(browser: Browser) {
        val paths = browser.moveSources ?: return
        val root = browser.root
        val destination = browser.current
        val prepared = SmartPlaylistMovePolicy.prepare(
            root = root,
            destination = destination,
            sources = paths.map(::File)
        )
        if (prepared is SmartPlaylistMovePolicy.Result.Blocked) {
            Log.w(TAG, "SMART MOVE BLOCKED | " + prepared.reason)
            showMoveError(browser.list.context)
            return
        }
        val plan = (prepared as SmartPlaylistMovePolicy.Result.Ready).moves
        val context = browser.list.context
        worker.execute {
            val inbound = runCatching {
                selectedSourcesHaveInboundNativeLinks(root, paths.toSet())
            }.onFailure {
                Log.e(TAG, "SMART MOVE | inbound-link scan failed", it)
            }.getOrElse { true }
            if (inbound) {
                main.post {
                    if (browsers[browser.list] === browser) {
                        Log.w(
                            TAG,
                            "SMART MOVE BLOCKED | selected Smart-Playlist " +
                                "is referenced by a native Smart-Playlist link"
                        )
                        showMoveError(context)
                    }
                }
                return@execute
            }

            val completed = arrayListOf<SmartPlaylistMovePolicy.Move>()
            val success = runCatching {
                for (move in plan) {
                    moveFile(move.source, move.target)
                    completed += move
                }
            }.onFailure { error ->
                Log.e(TAG, "SMART MOVE | file move failed; rolling back", error)
                completed.asReversed().forEach { move ->
                    runCatching {
                        moveFile(move.target, move.source)
                    }.onFailure {
                        Log.e(TAG, "SMART MOVE | rollback failed", it)
                    }
                }
            }.isSuccess

            main.post {
                if (browsers[browser.list] !== browser) return@post
                if (success) {
                    Log.i(TAG, "SMART MOVE | completed | count=" + plan.size)
                    Toast.makeText(
                        context,
                        NativeGmmpUiText.playlistMoveSuccess(context),
                        Toast.LENGTH_SHORT
                    ).show()
                    closeMoveBrowser(browser)
                    browsers.values.toList()
                        .filter { it.list.isAttachedToWindow }
                        .forEach(::refresh)
                } else {
                    showMoveError(context)
                }
            }
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.let {
            require(it.isDirectory) { "Move destination is unavailable" }
        }
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun selectedSourcesHaveInboundNativeLinks(
        root: File,
        selected: Set<String>
    ): Boolean {
        val native = bindings ?: return true
        val canonicalSelected = selected.map(::canonicalPath).toSet()
        val candidates = root.walkTopDown()
            .filter {
                it.isFile && it.extension.equals("spl", ignoreCase = true)
            }
        for (file in candidates) {
            val model = runCatching {
                native.modelConstructor.newInstance(
                    null, 0, 0, 0, null, 255
                ).also {
                    native.modelRead.invoke(it, file)
                }
            }.getOrNull() ?: continue
            val rules = runCatching {
                native.modelRules.get(model) as? Iterable<*>
            }.getOrNull() ?: continue
            if (rules.any { ruleReferencesSelected(native, it, canonicalSelected) }) {
                Log.i(
                    TAG,
                    "SMART MOVE LINK BLOCK | owner=" + safePath(file)
                )
                return true
            }
        }
        return false
    }

    private fun ruleReferencesSelected(
        native: Bindings,
        rule: Any?,
        selected: Set<String>
    ): Boolean {
        if (rule == null) return false
        if (native.groupRuleClass.isInstance(rule)) {
            val children = runCatching {
                native.groupRules.get(rule) as? Iterable<*>
            }.getOrNull() ?: return false
            return children.any {
                ruleReferencesSelected(native, it, selected)
            }
        }
        if (!native.leafRuleClass.isInstance(rule)) return false
        val value = runCatching {
            native.leafRuleValue.get(rule) as? String
        }.getOrNull() ?: return false
        if (PlaylistBridgeReference.isBridgeValue(value) ||
            !PlaylistBridgeDiagnosticPolicy
                .isNativeSmartPlaylistReference(value)
        ) return false
        val path = value.substringBefore('|', "").takeUnless(String::isBlank)
            ?: return false
        return canonicalPath(path) in selected
    }

    private fun installMoveChrome(browser: Browser) {
        if (browser.moveSources == null || browser.moveActionMode != null) return
        val list = browser.list
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean {
                mode.title = nativeMoveLabel(list.context)
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode,
                item: MenuItem
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (browser.moveActionMode === mode) {
                    browser.moveActionMode = null
                    if (browser.moveSources != null &&
                        browsers[list] === browser
                    ) {
                        closeMoveBrowser(browser)
                    }
                }
            }
        }
        val mode = runCatching {
            list.startActionMode(
                callback,
                android.view.ActionMode.TYPE_PRIMARY
            )
        }.onFailure {
            Log.w(TAG, "SMART MOVE UI | ActionMode unavailable", it)
        }.getOrNull()
        if (mode == null) {
            closeMoveBrowser(browser)
            return
        }
        browser.moveActionMode = mode
        val fab = installMoveFab(browser)
        if (fab == null) {
            Log.w(TAG, "SMART MOVE UI | native AestheticFab unavailable")
            closeMoveBrowser(browser)
            return
        }
        browser.list.post {
            if (browsers[list] === browser &&
                browser.moveSources != null &&
                positionMoveFab(browser)
            ) {
                runCatching {
                    fab.javaClass.getMethod("show").invoke(fab)
                }.onFailure {
                    fab.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun installMoveFab(browser: Browser): View? = runCatching {
        val list = browser.list
        val clazz = list.javaClass.classLoader
            ?.loadClass("com.afollestad.aesthetic.views.AestheticFab")
            ?: error("GMMP AestheticFab unavailable")
        val fab = clazz.getConstructor(
            android.content.Context::class.java,
            android.util.AttributeSet::class.java
        ).newInstance(list.context, null) as? View
            ?: error("GMMP AestheticFab is not a View")
        val image = fab as? ImageView
            ?: error("GMMP AestheticFab is not an ImageView")
        image.imageTintList = null
        image.setImageDrawable(PlaylistConfirmDrawable(dp(list, 24)))
        image.contentDescription = nativeMoveLabel(list.context)
        image.setOnClickListener {
            if (browser.moveSources != null) confirmMoveBrowser(browser)
        }
        val size = list.resources.getIdentifier(
            "design_fab_size_normal",
            "dimen",
            list.context.packageName
        ).takeIf { it != 0 }?.let {
            runCatching {
                list.resources.getDimensionPixelSize(it)
            }.getOrNull()
        } ?: dp(list, 56)
        runCatching {
            clazz.getMethod(
                "setCustomSize",
                Int::class.javaPrimitiveType
            ).invoke(fab, size)
        }
        browser.host.addView(
            fab,
            ViewGroup.LayoutParams(size, size)
        )
        fab.elevation = dp(list, 8).toFloat()
        fab.visibility = View.INVISIBLE
        browser.moveFab = fab
        fab.post {
            if (browser.moveFab === fab &&
                browser.moveSources != null &&
                positionMoveFab(browser)
            ) {
                runCatching { clazz.getMethod("show").invoke(fab) }
                    .onFailure { fab.visibility = View.VISIBLE }
            }
        }
        fab
    }.onFailure {
        Log.w(TAG, "SMART MOVE UI | native AestheticFab clone failed", it)
    }.getOrNull()

    private fun nativeMiniPlayerTop(list: View): Int? {
        val visible = Rect()
        if (!list.getGlobalVisibleRect(visible)) return null
        return listOf(
            "miniPlayerWrapper",
            "miniPlayerLayout",
            "libraryTabMiniPlayer"
        ).mapNotNull { name ->
            val id = list.resources.getIdentifier(
                name,
                "id",
                list.context.packageName
            )
            if (id == 0) return@mapNotNull null
            val player = list.rootView.findViewById<View>(id)
                ?: return@mapNotNull null
            val bounds = Rect()
            if (player.isShown &&
                player.getGlobalVisibleRect(bounds) &&
                bounds.height() > 0 &&
                bounds.top > visible.top + visible.height() / 3
            ) {
                bounds.top
            } else null
        }.minOrNull()
    }

    private fun positionMoveFab(browser: Browser): Boolean {
        val fab = browser.moveFab ?: return false
        if (browser.moveSources == null ||
            !browser.list.isAttachedToWindow
        ) return false
        val visible = Rect()
        if (!browser.list.getGlobalVisibleRect(visible) ||
            visible.height() <= 0
        ) return false
        val safeBottom = minOf(
            visible.bottom,
            nativeMiniPlayerTop(browser.list) ?: visible.bottom
        )
        val hostLocation = IntArray(2)
        browser.host.getLocationOnScreen(hostLocation)
        val margin = dp(browser.list, 16)
        val width = fab.width.takeIf { it > 0 }
            ?: fab.layoutParams?.width?.takeIf { it > 0 }
            ?: return false
        val height = fab.height.takeIf { it > 0 }
            ?: fab.layoutParams?.height?.takeIf { it > 0 }
            ?: return false
        fab.x = (
            visible.right - hostLocation[0] - width - margin
        ).toFloat()
        fab.y = (
            safeBottom - hostLocation[1] - height - margin
        ).toFloat()
        return true
    }

    private fun endMoveChrome(browser: Browser) {
        val mode = browser.moveActionMode
        browser.moveActionMode = null
        mode?.finish()
        val fab = browser.moveFab
        browser.moveFab = null
        if (fab != null) {
            fab.visibility = View.GONE
            val host = fab.parent as? ViewGroup
            if (host != null) {
                main.post {
                    if (fab.parent === host) host.removeView(fab)
                }
            }
        }
    }

    private fun showMoveError(context: android.content.Context) {
        Toast.makeText(
            context,
            NativeGmmpUiText.error(
                context,
                nativeMoveLabel(context)
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startObserver(browser: Browser) {
        browser.observer?.stopWatching()
        val path = browser.current.path
        browser.observer = object : FileObserver(
            path,
            CREATE or DELETE or MOVED_FROM or MOVED_TO or
                CLOSE_WRITE or DELETE_SELF or MOVE_SELF
        ) {
            override fun onEvent(event: Int, path: String?) {
                main.post {
                    if (enabled && browsers[browser.list] === browser) {
                        refresh(browser)
                    }
                }
            }
        }.also { it.startWatching() }
    }

    private fun installNewFolderMenu(menu: Menu, context: android.content.Context) {
        val existing = menu.findItem(newFolderMenuId)
        if (existing != null) {
            val browser = currentBrowser()
            existing.isVisible =
                enabled &&
                    browser != null &&
                    !browser.otherLocations &&
                    browser.moveSources == null
            return
        }
        val title = NativeGmmpUiText.string(context, "files_new_folder")
            ?: NativeGmmpUiText.string(context, "folder")
            ?: return
        val add = (0 until menu.size()).map(menu::getItem).firstOrNull {
            NativeResourceIdPolicy.canResolveEntryName(it.itemId) &&
                runCatching {
                    context.resources.getResourceEntryName(it.itemId) == "menuAdd"
                }.getOrDefault(false)
        }
        val label = SpannableStringBuilder(title)
        val iconId = context.resources.getIdentifier(
            "ic_gm_new_folder",
            "drawable",
            context.packageName
        )
        if (iconId != 0) context.getDrawable(iconId)?.mutate()?.let { icon ->
            val size = dp(context, 18)
            val color = resolveColor(
                context,
                android.R.attr.textColorPrimary,
                Color.WHITE
            )
            icon.setTint(color)
            icon.setBounds(0, 0, size, size)
            label.insert(0, "\uFFFC  ")
            label.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        menu.add(
            Menu.NONE,
            newFolderMenuId,
            (add?.order ?: 0) + 1,
            label
        ).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                requestFolderCreation()
                true
            }
        }
        Log.i(TAG, "SMART FOLDERS MENU | native New folder action added")
    }

    private fun requestFolderCreation() {
        val browser = currentBrowser() ?: return
        if (browser.otherLocations || browser.moveSources != null) return
        val creator = folderCreator ?: return
        creator.show(
            browser.list.context,
            browser.current
        ) {
            main.post {
                if (browsers[browser.list] === browser) {
                    refresh(browser)
                }
            }
        }
    }

    private fun updateMenus() {
        menuRefs.removeAll { it.get() == null }
        val browser = currentBrowser()
        menuRefs.forEach { reference ->
            val menu = reference.get() ?: return@forEach
            menu.findItem(newFolderMenuId)?.isVisible =
                enabled &&
                    browser != null &&
                    !browser.otherLocations &&
                    browser.moveSources == null
        }
    }

    private fun restoreRootAndRemove(browser: Browser) {
        browser.observer?.stopWatching()
        worker.execute {
            val models = runCatching {
                loadSnapshot(browser.root).models
            }.getOrNull()
            main.post {
                if (models != null && browser.list.isAttachedToWindow) {
                    applyNativeModels(browser, models)
                }
                removeBrowser(browser)
            }
        }
    }

    private fun removeBrowser(browser: Browser) {
        if (browsers.remove(browser.list) !== browser) return
        browser.observer?.stopWatching()
        endMoveChrome(browser)
        browser.list.alpha = browser.originalAlpha
        browser.list.setPadding(
            browser.originalPaddingLeft,
            browser.originalPaddingTop,
            browser.originalPaddingRight,
            browser.originalPaddingBottom
        )
        browser.list.clipToPadding = browser.originalClipToPadding
        browser.list.removeOnAttachStateChangeListener(browser.detachListener)
        if (browser.list.viewTreeObserver.isAlive) {
            browser.list.viewTreeObserver.removeOnGlobalLayoutListener(
                browser.layoutListener
            )
            browser.list.viewTreeObserver.removeOnPreDrawListener(
                browser.scrollDrawListener
            )
        }
        browser.overlay.visibility = View.GONE
        val host = browser.host
        val overlay = browser.overlay
        main.post {
            if (overlay.parent === host) host.removeView(overlay)
        }
        updateMenus()
    }

    private fun updateNativeInset(browser: Browser) {
        val list = browser.list
        if (!list.isAttachedToWindow || browsers[list] !== browser) return
        val headerHeight = if (
            browser.overlay.visibility == View.VISIBLE
        ) {
            val breadcrumbHeight = if (
                browser.breadcrumb.visibility == View.VISIBLE
            ) browser.breadcrumb.height.coerceAtLeast(0) else 0
            breadcrumbHeight + browser.folderBand.height.coerceAtLeast(0)
        } else {
            0
        }
        val top = browser.originalPaddingTop + headerHeight
        if (list.paddingLeft != browser.originalPaddingLeft ||
            list.paddingTop != top ||
            list.paddingRight != browser.originalPaddingRight ||
            list.paddingBottom != browser.originalPaddingBottom
        ) {
            list.setPadding(
                browser.originalPaddingLeft,
                top,
                browser.originalPaddingRight,
                browser.originalPaddingBottom
            )
        }
        list.clipToPadding = false
    }

    private fun syncFolderRowsScroll(browser: Browser) {
        val list = browser.list
        if (!list.isAttachedToWindow || browsers[list] !== browser) return
        val folderHeight = browser.folderBand.height.coerceAtLeast(0)
        if (folderHeight == 0) {
            if (browser.lastFolderScrollOffset != 0) {
                browser.folderBand.translationY = 0f
                browser.lastFolderScrollOffset = 0
            }
            return
        }
        var firstPosition = Int.MAX_VALUE
        var firstTop: Int? = null
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index) ?: continue
            val position = NativeRecyclerBridge.childAdapterPosition(list, child)
            if (position >= 0 && position < firstPosition) {
                firstPosition = position
                firstTop = child.top
            }
        }
        val offset = if (firstTop == null) {
            if (list.canScrollVertically(-1)) folderHeight else 0
        } else {
            SmartFolderHeaderScrollPolicy.folderScrollOffset(
                folderHeight = folderHeight,
                listPaddingTop = list.paddingTop,
                firstChildTop = firstTop,
                firstAdapterPosition = firstPosition
            )
        }
        if (browser.lastFolderScrollOffset == offset) return
        browser.lastFolderScrollOffset = offset
        browser.folderBand.translationY = -offset.toFloat()
    }

    private fun positionOverlay(browser: Browser) {
        val list = browser.list
        if (!list.isAttachedToWindow || list.width <= 0 || list.height <= 0) {
            return
        }
        val listLocation = IntArray(2)
        val hostLocation = IntArray(2)
        list.getLocationOnScreen(listLocation)
        browser.host.getLocationOnScreen(hostLocation)
        browser.overlay.x = (listLocation[0] - hostLocation[0]).toFloat()
        browser.overlay.y = (listLocation[1] - hostLocation[1]).toFloat()
        val params = browser.overlay.layoutParams
        if (params.width != list.width ||
            params.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            params.width = list.width
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            browser.overlay.layoutParams = params
        }
        val rect = Rect()
        val visible = isFrontFragmentView(list) &&
            list.isShown &&
            list.getGlobalVisibleRect(rect) &&
            rect.width() > dp(list, 30) &&
            rect.height() > dp(list, 30)
        browser.overlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val nextStyle = sampleNativeStyle(list)
            if (nextStyle != null) browser.style = nextStyle
        }
        browser.overlay.post {
            if (browsers[list] === browser) {
                updateNativeInset(browser)
                syncFolderRowsScroll(browser)
                if (browser.moveSources != null) positionMoveFab(browser)
            }
        }
    }

    private fun isFrontFragmentView(list: ViewGroup): Boolean {
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
        val container = slot ?: return true
        var child: View? = list
        while (child?.parent !== container) {
            child = child?.parent as? View ?: return false
        }
        for (index in container.childCount - 1 downTo 0) {
            val candidate = container.getChildAt(index)
            if (candidate.visibility == View.VISIBLE &&
                candidate.alpha > 0.01f
            ) {
                return candidate === child
            }
        }
        return false
    }

    private fun rootFile(): File? {
        val native = bindings ?: return null
        val path = runCatching {
            native.storagePath.invoke(
                null,
                native.smartStorageLocation
            ) as? String
        }.getOrNull()?.takeUnless(String::isBlank) ?: return null
        return runCatching { File(path).canonicalFile }
            .getOrNull()
            ?.takeIf { it.isDirectory }
    }

    private fun modelName(model: Any): String {
        val native = bindings ?: return ""
        return runCatching {
            native.modelName.get(model) as? String
        }.getOrNull()?.takeUnless(String::isBlank)
            ?: modelPath(model)
                ?.let { File(it).nameWithoutExtension }
                .orEmpty()
    }

    private fun modelPath(model: Any): String? {
        val native = bindings ?: return null
        val file = runCatching {
            native.modelFile.get(model) as? File
        }.getOrNull() ?: return null
        return canonicalPath(file.path)
    }

    private fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    private fun sameFile(a: File, b: File): Boolean =
        canonicalPath(a.path) == canonicalPath(b.path)

    private fun currentBrowser(): Browser? =
        browsers.values.firstOrNull {
            it.list.isAttachedToWindow &&
                it.overlay.visibility == View.VISIBLE
        }

    private fun nativeAdapter(list: ViewGroup): Any? = runCatching {
        list.javaClass.getMethod("getAdapter").invoke(list)
    }.getOrNull()

    private fun safeOverlayHost(list: ViewGroup): ViewGroup? {
        var parent = list.parent as? ViewGroup
        while (parent != null && parent !== list.rootView) {
            if (parent.javaClass.simpleName.contains(
                    "CoordinatorLayout",
                    ignoreCase = true
                )
            ) return parent
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private fun directChildInHost(list: View, host: ViewGroup): View? {
        var node: View = list
        while (node.parent != null && node.parent !== host) {
            node = node.parent as? View ?: return null
        }
        return node.takeIf { it.parent === host }
    }

    private fun effectiveNativeTitlePaint(title: TextView): TextPaint {
        val paint = TextPaint(title.paint)
        val source = title.text as? Spanned ?: return paint
        if (source.isEmpty()) return paint
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

    private fun findTextView(
        root: View,
        expectedText: String? = null
    ): TextView? {
        if (root is TextView && expectedText.isNullOrBlank()) return root
        val found = arrayListOf<TextView>()
        fun walk(view: View, depth: Int) {
            if (depth > 8 || found.size > 40) return
            if (view is TextView) {
                found += view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index), depth + 1)
                }
            }
        }
        walk(root, 0)
        if (!expectedText.isNullOrBlank()) {
            found.firstOrNull {
                it.text?.toString()?.trim()
                    .equals(expectedText.trim(), ignoreCase = true)
            }?.let { return it }
        }
        return found.maxByOrNull { it.textSize }
    }

    private fun hideOtherText(root: View, title: TextView) {
        fun walk(view: View) {
            if (view is TextView && view !== title) {
                view.visibility = View.GONE
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(root)
    }

    private fun firstTextView(root: View): TextView? =
        if (root is TextView) root else findTextView(root)

    private fun cloneBackground(view: View): Drawable? =
        runCatching {
            view.rootView.background?.constantState
                ?.newDrawable(view.resources)?.mutate()
        }.getOrNull()

    private fun nativeSurfaceBackground(view: View): Int {
        val window = view.rootView.background as? ColorDrawable
        if (window != null && Color.alpha(window.color) == 255) {
            return window.color
        }
        return resolveColor(
            view,
            android.R.attr.colorBackground,
            Color.BLACK
        )
    }

    private fun selectableBackground(view: View): Drawable? {
        val value = TypedValue()
        if (!view.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                value,
                true
            )
        ) return null
        return if (value.resourceId != 0) {
            runCatching { view.context.getDrawable(value.resourceId) }.getOrNull()
        } else null
    }

    private fun resolveColor(view: View, attr: Int, fallback: Int): Int =
        resolveColor(view.context, attr, fallback)

    private fun resolveColor(
        context: android.content.Context,
        attr: Int,
        fallback: Int
    ): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            runCatching { context.getColor(value.resourceId) }
                .getOrDefault(fallback)
        } else value.data
    }

    private fun menuContext(menu: Menu, inflater: Any?): android.content.Context? {
        val direct = runCatching {
            menu.javaClass.methods.firstOrNull {
                it.name == "getContext" && it.parameterCount == 0
            }?.invoke(menu) as? android.content.Context
        }.getOrNull()
        if (direct != null) return direct
        if (inflater !is MenuInflater) return null
        return runCatching {
            var type: Class<*>? = inflater.javaClass
            while (type != null) {
                val field = runCatching {
                    type.getDeclaredField("mContext")
                }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(inflater) as? android.content.Context
                }
                type = type.superclass
            }
            null
        }.getOrNull()
    }

    private fun resourceName(view: View): String {
        val id = view.id
        if (!NativeResourceIdPolicy.canResolveEntryName(id)) return ""
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrDefault("")
    }

    private fun quickNavTitleRatio(view: View): Float =
        view.context.getSharedPreferences(
            QUICK_NAV_METRICS_PREFS,
            android.content.Context.MODE_PRIVATE
        ).getFloat(
            QUICK_NAV_TITLE_RATIO_KEY,
            GMMP_420_QUICK_NAV_TITLE_RATIO
        ).takeIf { it in 0.8f..1.8f }
            ?: GMMP_420_QUICK_NAV_TITLE_RATIO

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(type.name + "." + name)
    }

    private fun safePath(file: File): String =
        PlaylistBridgeDiagnosticPolicy.safePath(
            runCatching { file.canonicalPath }.getOrDefault(file.path)
        )

    private fun dp(view: View, value: Int): Int =
        (view.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun dp(context: android.content.Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + 0.5f).toInt()

    private class FolderOutlineDrawable(
        color: Int,
        private val sizePx: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
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
            val path = Path().apply {
                moveTo(3f, 6f)
                lineTo(9f, 6f)
                lineTo(11f, 8.5f)
                lineTo(21f, 8.5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: android.graphics.ColorFilter?) {
            paint.colorFilter = filter
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int =
            android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }
}
