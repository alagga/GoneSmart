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
import android.text.style.ImageSpan
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
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
 * - GoneSmart adds only a compact folder/breadcrumb header and reserves
 *   native top padding for it; it never hides or redraws Smart-Playlist rows.
 */
internal class SmartPlaylistFolderController {
    companion object {
        private const val TAG = "GoneSmartSmartFolders"
        private const val SMART_LIST_ID = "smartListRecyclerView"
        private const val SMART_LIST_MENU = "menu_gm_smart_list"
        private const val MAX_ATTACH_RETRIES = 24
        private const val ATTACH_RETRY_MS = 120L
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
        val holderModel: Field,
        val adapterDiffer: Field,
        val differSubmit: Method,
        val storagePath: Method,
        val smartStorageLocation: Any,
        val nativeSort: Method,
        val presenterState: Field,
        val stateSort: Field,
        val sortOrder: Method,
        val sortDescending: Method
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
        val rowBackground: Drawable.ConstantState?
    )

    private data class Snapshot(
        val directory: File,
        val folders: List<File>,
        val models: List<Any>,
        val modelsByPath: Map<String, Any>
    )

    private data class Browser(
        val list: ViewGroup,
        val nativeAdapter: Any,
        val host: ViewGroup,
        val overlay: FrameLayout,
        val rows: LinearLayout,
        val breadcrumb: RecyclerView,
        val originalAlpha: Float,
        val originalPaddingLeft: Int,
        val originalPaddingTop: Int,
        val originalPaddingRight: Int,
        val originalPaddingBottom: Int,
        val originalClipToPadding: Boolean,
        val root: File,
        var current: File,
        var style: NativeStyle?,
        val layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener,
        val detachListener: View.OnAttachStateChangeListener,
        var observer: FileObserver? = null,
        var generation: Long = 0L,
        var actionPending: Boolean = false,
        var nativeOrder: List<String> = emptyList()
    )

    private inner class BreadcrumbAdapter(
        private val browser: Browser
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val segments = arrayListOf<Pair<File, String>>()

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int =
            if (segments.isEmpty()) 0 else segments.size * 2 - 1

        override fun getItemViewType(position: Int): Int = position % 2

        override fun getItemId(position: Int): Long {
            val pair = segments[position / 2]
            return (pair.first.path.hashCode().toLong() shl 1) xor
                (position % 2).toLong()
        }

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
            val label = findTextView(holder.itemView) ?: return
            label.text = segment.second
            browser.style?.let {
                label.paint.set(it.paint)
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSizePx)
                label.setTextColor(it.textColor)
                label.typeface = Typeface.create(it.typeface, Typeface.BOLD)
            }
            label.isClickable = true
            label.isFocusable = true
            label.setOnClickListener {
                if (browsers[browser.list] !== browser) return@setOnClickListener
                if (sameFile(browser.current, segment.first)) {
                    return@setOnClickListener
                }
                navigate(browser, segment.first)
            }
        }

        fun submit(next: List<Pair<File, String>>) {
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

    @Volatile private var enabled = false
    @Volatile private var bindings: Bindings? = null
    @Volatile private var presenterRef: WeakReference<Any>? = null
    @Volatile private var folderCreator: NativeGmmpFolderCreator? = null
    @Volatile private var rememberedDirectory: String? = null
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
        if (enabled == next) return
        enabled = next
        main.post {
            updateMenus()
            if (next) {
                knownLists.keys.toList().forEach { scheduleAttach(it, 0) }
            } else {
                pendingCreationDirectory = null
                browsers.values.toList().forEach(::restoreRootAndRemove)
            }
        }
        Log.i(TAG, "SMART FOLDERS OPTION | enabled=" + next)
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
        if (name != SMART_LIST_MENU) return
        menuRefs.removeAll { it.get() == null }
        menuRefs += WeakReference(menu)
        if (enabled) {
            installNewFolderMenu(menu, context)
        } else {
            menu.findItem(newFolderMenuId)?.isVisible = false
        }
    }

    fun consumeBack(): Boolean {
        if (!enabled) return false
        val browser = currentBrowser() ?: return false
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

        val overlay = FrameLayout(list.context).apply {
            isClickable = true
            isFocusable = true
            background = cloneBackground(list) ?: ColorDrawable(
                initialStyle?.let { nativeSurfaceBackground(list) }
                    ?: resolveColor(list, android.R.attr.colorBackground, Color.BLACK)
            )
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
        }
        val rows = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
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
                rows,
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
            breadcrumb = breadcrumb,
            originalAlpha = list.alpha,
            originalPaddingLeft = list.paddingLeft,
            originalPaddingTop = list.paddingTop,
            originalPaddingRight = list.paddingRight,
            originalPaddingBottom = list.paddingBottom,
            originalClipToPadding = list.clipToPadding,
            root = root,
            current = remembered,
            style = initialStyle,
            layoutListener = layoutListener,
            detachListener = detachListener
        )
        breadcrumb.adapter = BreadcrumbAdapter(browser)
        browsers[list] = browser
        list.addOnAttachStateChangeListener(detachListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
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
        rememberedDirectory = canonical.path
        startObserver(browser)
        refresh(browser)
        Log.i(TAG, "SMART FOLDERS NAV | current=" + safePath(canonical))
    }

    private fun refresh(browser: Browser) {
        if (!enabled || browsers[browser.list] !== browser) return
        val generation = refreshGeneration.incrementAndGet()
        browser.generation = generation
        val directory = browser.current
        worker.execute {
            val snapshot = runCatching {
                loadSnapshot(directory)
            }.onFailure {
                Log.e(TAG, "SMART FOLDERS LOAD FAILED | " + safePath(directory), it)
            }.getOrNull() ?: return@execute
            main.post {
                if (!enabled || browsers[browser.list] !== browser ||
                    browser.generation != generation ||
                    !sameFile(browser.current, snapshot.directory)
                ) return@post
                applyNativeModels(browser, snapshot.models)
                browser.style = sampleNativeStyle(browser.list) ?: browser.style
                render(browser, snapshot)
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

    private fun render(browser: Browser, snapshot: Snapshot) {
        browser.rows.removeAllViews()
        renderBreadcrumb(browser)

        snapshot.folders.forEach { folder ->
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

        // Real Smart-Playlist rows remain 100% native. They are already
        // filtered by applyNativeModels() through GMMP's original differ.
        browser.overlay.post {
            if (browsers[browser.list] === browser) {
                updateNativeInset(browser)
            }
        }

        Log.i(
            TAG,
            "SMART FOLDERS RENDER | current=" + safePath(browser.current) +
                " | folders=" + snapshot.folders.size +
                " | smart=" + snapshot.models.size +
                " | nativeRows=true"
        )
    }

    private fun renderBreadcrumb(browser: Browser) {
        val next = arrayListOf<Pair<File, String>>()
        val rootLabel = NativeGmmpUiText.string(
            browser.list.context,
            "smart_playlists"
        ) ?: NativeGmmpUiText.smartPlaylist(browser.list.context)
        next += browser.root to rootLabel
        if (!sameFile(browser.current, browser.root)) {
            val relative = runCatching {
                browser.current.relativeTo(browser.root).path
            }.getOrDefault("")
            var cursor = browser.root
            relative.split(File.separatorChar)
                .filter(String::isNotBlank)
                .forEach { name ->
                    cursor = File(cursor, name)
                    next += cursor to name
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
        // Only clone a row layout after it was sampled from a real bound
        // native Smart row. An empty root has no bound text/style template.
        val layoutId = style?.rowLayoutId?.takeIf { it != 0 } ?: 0
        val root = if (layoutId != 0) {
            runCatching {
                LayoutInflater.from(browser.list.context).inflate(
                    layoutId,
                    browser.rows,
                    false
                )
            }.getOrNull()
        } else null

        if (root != null) {
            val title = style?.titleViewId
                ?.takeIf { it != 0 }
                ?.let { root.findViewById<TextView>(it) }
                ?: findTextView(root)
            if (title != null) {
                title.text = text
                style?.let {
                    title.paint.set(it.paint)
                    title.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        it.textSizePx
                    )
                    title.setTextColor(it.textColor)
                    title.typeface = it.typeface
                    title.gravity = it.titleGravity
                    title.setPaddingRelative(
                        it.titlePaddingStart,
                        title.paddingTop,
                        it.titlePaddingEnd,
                        title.paddingBottom
                    )
                }
                if (folder) {
                    title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        FolderOutlineDrawable(
                            style?.textColor
                                ?: resolveColor(
                                    browser.list,
                                    android.R.attr.textColorPrimary,
                                    Color.WHITE
                                ),
                            dp(browser.list, 24)
                        ),
                        null,
                        null,
                        null
                    )
                    title.compoundDrawablePadding = dp(browser.list, 12)
                }
                hideOtherText(root, title)
            }
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                style?.rowHeight ?: dp(browser.list, 54)
            )
            root.minimumHeight = style?.rowHeight ?: dp(browser.list, 54)
            style?.rowBackground?.newDrawable(browser.list.resources)
                ?.mutate()?.let { root.background = it }
            configureContextMenu(
                root,
                browser.list,
                folder,
                contextMenuSource,
                onContext
            )
            root.isClickable = true
            root.isFocusable = true
            return root
        }

        return TextView(browser.list.context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(browser.list, 54)
            setPadding(dp(browser.list, 16), 0, dp(browser.list, 16), 0)
            setTextColor(
                style?.textColor ?: resolveColor(
                    browser.list,
                    android.R.attr.textColorPrimary,
                    Color.WHITE
                )
            )
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                style?.textSizePx
                    ?: browser.list.resources.displayMetrics.scaledDensity * 16f
            )
            background = selectableBackground(browser.list)
            if (folder) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    FolderOutlineDrawable(
                        currentTextColor,
                        dp(browser.list, 24)
                    ),
                    null,
                    null,
                    null
                )
                compoundDrawablePadding = dp(browser.list, 12)
            }
        }
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
            return NativeStyle(
                rowLayoutId = row.sourceLayoutResId,
                titleViewId = title.id,
                rowHeight = row.height.coerceAtLeast(dp(list, 44)),
                textColor = title.currentTextColor,
                textSizePx = title.textSize,
                typeface = title.typeface,
                titleGravity = title.gravity,
                titlePaddingStart = title.paddingStart,
                titlePaddingEnd = title.paddingEnd,
                paint = TextPaint(title.paint),
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
            existing.isVisible = enabled
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
        menuRefs.forEach { reference ->
            val menu = reference.get() ?: return@forEach
            menu.findItem(newFolderMenuId)?.isVisible =
                enabled && currentBrowser() != null
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
            browser.overlay.height.coerceAtLeast(0)
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
        // Clip native rows out of the header's reserved area instead of
        // letting them paint behind clickable folder/breadcrumb controls.
        list.clipToPadding = true
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
            if (browsers[list] === browser) updateNativeInset(browser)
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
