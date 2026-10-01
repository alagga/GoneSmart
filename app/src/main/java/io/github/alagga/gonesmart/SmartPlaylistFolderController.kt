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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.FrameLayout
import android.widget.ImageButton
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
import java.lang.reflect.Modifier
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
/**
 * Full-height synthetic Smart-folder viewport using Android's own EdgeEffect
 * renderer. Real Smart rows remain in GMMP's original RecyclerView.
 */
private class SmartFolderStretchViewport(
    context: android.content.Context
) : FrameLayout(context) {
    private val topEffect = EdgeEffect(context)
    private val bottomEffect = EdgeEffect(context)
    private var mirroredWidth = 0
    private var mirroredHeight = 0
    private var topTarget = 0f
    private var bottomTarget = 0f

    fun mirrorNativeEdges(
        top: Float,
        bottom: Float,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val width = viewportWidth.coerceAtLeast(1)
        val height = viewportHeight.coerceAtLeast(1)
        if (mirroredWidth != width || mirroredHeight != height) {
            mirroredWidth = width
            mirroredHeight = height
            topEffect.setSize(width, height)
            bottomEffect.setSize(width, height)
        }

        val nextTop = top.coerceIn(0f, 1f)
        val nextBottom = bottom.coerceIn(0f, 1f)
        val changed =
            kotlin.math.abs(topTarget - nextTop) > 0.00001f ||
                kotlin.math.abs(bottomTarget - nextBottom) > 0.00001f
        topTarget = nextTop
        bottomTarget = nextBottom
        syncDistance(topEffect, nextTop)
        syncDistance(bottomEffect, nextBottom)
        if (changed || nextTop > 0f || nextBottom > 0f) {
            postInvalidateOnAnimation()
        }
    }

    private fun syncDistance(effect: EdgeEffect, target: Float) {
        val current = effect.distance
        if (target <= 0f) {
            if (current > 0f || !effect.isFinished) effect.finish()
            return
        }
        val delta = target - current
        if (kotlin.math.abs(delta) > 0.00001f) {
            effect.onPullDistance(delta, 0.5f)
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (android.os.Build.VERSION.SDK_INT < 31 ||
            mirroredWidth <= 0 || mirroredHeight <= 0
        ) return
        var invalidate = false
        if (topTarget > 0f) {
            val save = canvas.save()
            invalidate = topEffect.draw(canvas)
            canvas.restoreToCount(save)
        }
        if (bottomTarget > 0f) {
            val save = canvas.save()
            canvas.rotate(180f)
            canvas.translate(
                -mirroredWidth.toFloat(),
                -mirroredHeight.toFloat()
            )
            invalidate = bottomEffect.draw(canvas) || invalidate
            canvas.restoreToCount(save)
        }
        if (invalidate) postInvalidateOnAnimation()
    }
}

internal class SmartPlaylistFolderController(
    private val multiSelect: PlaylistMultiSelectController
) {
    companion object {
        private const val TAG = "GoneSmartSmartFolders"
        private const val SMART_LIST_ID = "smartListRecyclerView"
        private const val SMART_LIST_MENU = "menu_gm_smart_list"
        private const val SMART_CONTEXT_MENU = "menu_gm_context_smart"
        private const val MAX_ATTACH_RETRIES = 24
        private const val MAX_PROJECTION_REVEAL_RETRIES = 120
        private const val ATTACH_RETRY_MS = 120L
        private const val QUICK_NAV_METRICS_PREFS =
            "gonesmart_gmmp_quicknav_metrics"
        private const val QUICK_NAV_TITLE_RATIO_KEY = "title_ratio"
        private const val QUICK_NAV_VERIFIED_FIRST_X_KEY =
            "verified_first_text_x_dpi_"
        private const val PLAYLIST_TITLE_INSET_KEY = "playlist_title_inset_dpi_"
        private const val GMMP_420_QUICK_NAV_TITLE_RATIO = 1.225f
    }

    private data class Bindings(
        val loader: ClassLoader,
        val adapterClass: Class<*>,
        val holderClass: Class<*>,
        val modelClass: Class<*>,
        val modelConstructor: Constructor<*>,
        val modelRead: Method,
        val modelWriter: Method?,
        val modelName: Field,
        val modelFile: Field,
        val modelRules: Field?,
        val holderModel: Field,
        val adapterDiffer: Field,
        val differSubmit: Method,
        val differCurrentList: Method?,
        val storagePath: Method?,
        val smartStorageLocation: Any?,
        val nativeSort: Method?,
        val presenterClass: Class<*>?,
        val presenterState: Field?,
        val stateSort: Field?,
        val sortOrder: Method?,
        val sortDescending: Method?,
        val leafRuleClass: Class<*>?,
        val leafRuleValue: Field?,
        val groupRuleClass: Class<*>?,
        val groupRules: Field?,
        val actionModeBaseClass: Class<*>?,
        val smartFragmentClass: Class<*>?,
        val actionModeView: Field?,
        val actionModeSelection: Field?,
        val selectionEntries: Field?,
        val selectionEntryModel: Field?
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
        val rowBackground: Drawable.ConstantState?,
        val accentColor: Int
    )

    private data class Snapshot(
        val directory: File,
        val folders: List<File>,
        val models: List<Any>,
        val modelsByPath: Map<String, Any>
    )

    private data class DirectoryScan(
        val directory: File,
        val files: List<File>,
        val folders: List<File>
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
        val folderViewport: SmartFolderStretchViewport,
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
        val layoutListener: View.OnLayoutChangeListener,
        val originalNativeNestedScrollingEnabled: Boolean?,
        val scrollDrawListener: android.view.ViewTreeObserver.OnPreDrawListener,
        val detachListener: View.OnAttachStateChangeListener,
        var observer: FileObserver? = null,
        var generation: Long = 0L,
        var actionPending: Boolean = false,
        var nativeOrder: List<String> = emptyList(),
        var nativeSignature: List<String> = emptyList(),
        var nativeSubmitted: Boolean = false,
        var projectionPrepared: Boolean = false,
        var renderedLocationKey: String? = null,
        var renderedHeaderSignature: String? = null,
        var moveSources: List<String>? = null,
        var movePreviousDirectory: String? = null,
        var movePreviousOtherLocations: Boolean = false,
        val moveChrome: PlaylistFolderMoveChrome.State =
            PlaylistFolderMoveChrome.State(),
        var nativeScrollDistancePx: Int = 0,
        var folderGestureDownY: Float = 0f,
        var folderGestureDragging: Boolean = false,
        var folderGestureDownEvent: MotionEvent? = null,
        var folderGestureReported: Boolean = false,
        val selectedSmartPaths: LinkedHashSet<String> = linkedSetOf(),
        var selectionActionMode: android.view.ActionMode? = null,
        var selectionTransitionToMove: Boolean = false,
        var suppressSelectionUpPath: String? = null,
        var selectionOverlayColor: Int? = null,
        var liveSelectionAccent: Int? = null,
        var selectionAccentSubscription: NativeGmmpAccent.Subscription? = null,
        val rowInteractionPaths: WeakHashMap<View, String> = WeakHashMap(),
        val selectionOverlays: WeakHashMap<View, ColorDrawable> = WeakHashMap(),
        var breadcrumbAlignmentGeneration: Long = 0L,
        var initialHeaderReady: Boolean = false,
        var nativeContentReady: Boolean = false,
        var projectionFailOpenAllowed: Boolean = false,
        var folderScrollSyncReady: Boolean = false,
        var pendingFolderScrollReset: Boolean = true,
        var scrollDeltaReported: Boolean = false,
        var touchGuardReported: Boolean = false,
        var overscrollReported: Boolean = false
    )

    private val main = Handler(Looper.getMainLooper())
    private val moveChromeUi = PlaylistFolderMoveChrome(
        multiSelect, main, TAG, "SMART"
    )
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GoneSmartSmartFolders").apply { isDaemon = true }
    }
    private val refreshGeneration = AtomicLong(0L)
    private val knownLists = WeakHashMap<ViewGroup, Boolean>()
    private val browsers = WeakHashMap<ViewGroup, Browser>()
    private val failedOverlayHosts = WeakHashMap<ViewGroup, Boolean>()
    private val pendingOriginalAlphas = WeakHashMap<ViewGroup, Float>()
    private val menuRefs = arrayListOf<WeakReference<Menu>>()
    private val newFolderMenuId = View.generateViewId()
    private val moveMenuId = View.generateViewId()
    private val alignedNativeTitles = WeakHashMap<TextView, Float>()
    private val alignedNativeTitleOwners =
        WeakHashMap<TextView, WeakReference<ViewGroup>>()
    private val titleAlignmentReports = linkedSetOf<String>()

    @Volatile private var enabled = false
    @Volatile private var groupRootPlaylists = false
    @Volatile private var multiSelectEnabled = true
    @Volatile private var bindings: Bindings? = null
    @Volatile private var runtimeRoot: File? = null
    @Volatile private var hostLoader: ClassLoader? = null
    @Volatile private var presenterRef: WeakReference<Any>? = null
    @Volatile private var modelWriterHookInstaller: ((Method) -> Unit)? = null
    private val publishedModelWriters = linkedSetOf<String>()
    private val runtimeBindingAttempts = WeakHashMap<ViewGroup, Int>()
    @Volatile private var folderCreator: NativeGmmpFolderCreator? = null
    @Volatile private var folderDeletion: NativeGmmpFolderDeletion? = null
    private data class PendingFolderDeletion(
        val plan: FolderDeletePolicy.Plan,
        var checksRemaining: Int = 100
    )
    private val pendingFolderDeletes = arrayListOf<PendingFolderDeletion>()
    @Volatile private var rememberedDirectory: String? = null
    @Volatile private var rememberedOtherLocations = false
    @Volatile private var pendingCreationDirectory: String? = null
    @Volatile private var pendingCreationAt = 0L

    fun configure(loader: ClassLoader): Boolean {
        hostLoader = loader
        val configured = runCatching {
            legacyBindings(loader)
        }.onFailure {
            Log.w(
                TAG,
                "SMART FOLDERS LEGACY BINDINGS | 4.2.0 mapping unavailable; " +
                    "waiting for live structural binding"
            )
            diagnoseCompatibilityBindings(loader)
        }.getOrNull()
        bindings = configured
        configured?.let(::publishModelWriter)
        Log.i(
            TAG,
            "SMART FOLDERS BINDINGS | ready=" + (configured != null) +
                " | source=" + if (configured != null) "legacy" else "runtime-pending"
        )
        return configured != null
    }

    private fun legacyBindings(loader: ClassLoader): Bindings {
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
        val differField = adapterClass.getDeclaredField("y")
            .apply { isAccessible = true }
        val differType = differField.type
        return Bindings(
            loader = loader,
            adapterClass = adapterClass,
            holderClass = holderClass,
            modelClass = modelClass,
            modelConstructor = modelClass.getDeclaredConstructor(
                String::class.java,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                ArrayList::class.java,
                Integer.TYPE
            ).apply { isAccessible = true },
            modelRead = modelClass.getDeclaredMethod(
                "r", File::class.java
            ).apply { isAccessible = true },
            modelWriter = runCatching {
                modelClass.getDeclaredMethod("t", File::class.java)
                    .apply { isAccessible = true }
            }.getOrNull(),
            modelName = modelClass.getDeclaredField("o")
                .apply { isAccessible = true },
            modelFile = modelClass.getDeclaredField("v")
                .apply { isAccessible = true },
            modelRules = findField(modelClass, "u")
                .apply { isAccessible = true },
            holderModel = holderClass.getDeclaredField("A")
                .apply { isAccessible = true },
            adapterDiffer = differField,
            differSubmit = differType.getDeclaredMethod(
                "b", java.util.List::class.java
            ).apply { isAccessible = true },
            differCurrentList = differType.declaredMethods
                .firstOrNull {
                    it.parameterCount == 0 &&
                        java.util.List::class.java
                            .isAssignableFrom(it.returnType)
                }?.apply { isAccessible = true },
            storagePath = storageClass.getDeclaredMethod(
                "b", storageLocationClass
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
            presenterClass = presenterClass,
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
    }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .toList()

    private fun hierarchyMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                !Modifier.isAbstract(it.modifiers) && !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    "|" + it.returnType.name
            }
            .toList()

    private fun runtimeSmartModelShape(type: Class<*>): Boolean {
        val fields = hierarchyFields(type)
        val fileFields = fields.count {
            File::class.java.isAssignableFrom(it.type)
        }
        val stringFields = fields.count {
            it.type == String::class.java
        }
        val listFields = fields.count {
            java.util.List::class.java.isAssignableFrom(it.type)
        }
        val constructors = type.declaredConstructors.count {
            val p = it.parameterTypes
            p.size == 6 &&
                p[0] == String::class.java &&
                p[1] == Integer.TYPE &&
                p[2] == Integer.TYPE &&
                p[3] == Integer.TYPE &&
                java.util.ArrayList::class.java.isAssignableFrom(p[4]) &&
                p[5] == Integer.TYPE
        }
        val fileReaders = hierarchyMethods(type).count {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == File::class.java &&
                it.returnType == java.lang.Void.TYPE
        }
        return constructors == 1 &&
            fileFields == 1 &&
            stringFields == 1 &&
            listFields >= 1 &&
            fileReaders == 1
    }

    private fun inferRuntimeRoot(
        models: List<Any>,
        modelFile: Field
    ): File? {
        val files = models.mapNotNull { model ->
            runCatching {
                modelFile.get(model) as? File
            }.getOrNull()?.let {
                runCatching { it.canonicalFile }.getOrNull()
            }
        }.filter {
            it.extension.equals("spl", ignoreCase = true)
        }
        if (files.isEmpty()) return null
        var candidate = files.first().parentFile?.canonicalFile ?: return null
        while (files.any { file ->
                val prefix = candidate.path.trimEnd(File.separatorChar) +
                    File.separator
                file.path != candidate.path &&
                    !file.path.startsWith(prefix)
            }
        ) {
            candidate = candidate.parentFile?.canonicalFile ?: return null
        }
        return candidate.takeIf { it.isDirectory }
    }

    private fun resolveRuntimeBindings(
        list: ViewGroup,
        adapter: Any
    ): Bindings? {
        val getHolder = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder", View::class.java
            )
        }.getOrNull() ?: return null

        var holder: Any? = null
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) ?: continue
            holder = runCatching {
                getHolder.invoke(list, row)
            }.getOrNull()
            if (holder != null) break
        }
        holder ?: return null

        val modelCandidates = hierarchyFields(holder.javaClass)
            .mapNotNull { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull() ?: return@mapNotNull null
                if (runtimeSmartModelShape(value.javaClass)) {
                    field to value
                } else null
            }
        val (holderModel, sampleModel) =
            modelCandidates.singleOrNull() ?: return null
        holderModel.isAccessible = true
        val modelClass = sampleModel.javaClass
        val modelFields = hierarchyFields(modelClass)

        val modelFile = modelFields.filter {
            File::class.java.isAssignableFrom(it.type)
        }.singleOrNull()?.apply { isAccessible = true } ?: return null
        val modelName = modelFields.filter {
            it.type == String::class.java
        }.singleOrNull()?.apply { isAccessible = true } ?: return null
        val modelRules = modelFields.filter {
            java.util.List::class.java.isAssignableFrom(it.type)
        }.singleOrNull()?.apply { isAccessible = true }

        val modelConstructor = modelClass.declaredConstructors.filter {
            val p = it.parameterTypes
            p.size == 6 &&
                p[0] == String::class.java &&
                p[1] == Integer.TYPE &&
                p[2] == Integer.TYPE &&
                p[3] == Integer.TYPE &&
                java.util.ArrayList::class.java.isAssignableFrom(p[4]) &&
                p[5] == Integer.TYPE
        }.singleOrNull()?.apply { isAccessible = true } ?: return null

        val modelRead = hierarchyMethods(modelClass).filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == File::class.java &&
                it.returnType == java.lang.Void.TYPE
        }.singleOrNull()?.apply { isAccessible = true } ?: return null

        val modelWriter = hierarchyMethods(modelClass).filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == File::class.java &&
                (it.returnType == java.lang.Boolean.TYPE ||
                    it.returnType == java.lang.Boolean::class.java)
        }.singleOrNull()?.apply { isAccessible = true }

        data class DifferCandidate(
            val field: Field,
            val value: Any,
            val submit: Method,
            val current: Method?
        )
        val differCandidates = hierarchyFields(adapter.javaClass)
            .mapNotNull { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull() ?: return@mapNotNull null
                val methods = hierarchyMethods(value.javaClass)
                val submits = methods.filter {
                    it.parameterCount == 1 &&
                        java.util.List::class.java
                            .isAssignableFrom(it.parameterTypes[0]) &&
                        it.returnType == java.lang.Void.TYPE
                }
                if (submits.size != 1) return@mapNotNull null
                val currents = methods.filter {
                    it.parameterCount == 0 &&
                        java.util.List::class.java
                            .isAssignableFrom(it.returnType)
                }
                DifferCandidate(
                    field.apply { isAccessible = true },
                    value,
                    submits.single().apply { isAccessible = true },
                    currents.singleOrNull()?.apply { isAccessible = true }
                )
            }
        val differ = differCandidates.singleOrNull() ?: return null

        val currentModels = (
            differ.current?.let {
                runCatching {
                    it.invoke(differ.value) as? List<*>
                }.getOrNull()
            }.orEmpty()
        ).filterNotNull().filter(modelClass::isInstance)
            .map { it as Any }
            .ifEmpty { listOf(sampleModel) }

        val resolvedRoot = inferRuntimeRoot(currentModels, modelFile)
            ?: return null
        runtimeRoot = resolvedRoot

        val resolved = Bindings(
            loader = hostLoader ?: adapter.javaClass.classLoader,
            adapterClass = adapter.javaClass,
            holderClass = holder.javaClass,
            modelClass = modelClass,
            modelConstructor = modelConstructor,
            modelRead = modelRead,
            modelWriter = modelWriter,
            modelName = modelName,
            modelFile = modelFile,
            modelRules = modelRules,
            holderModel = holderModel,
            adapterDiffer = differ.field,
            differSubmit = differ.submit,
            differCurrentList = differ.current,
            storagePath = null,
            smartStorageLocation = null,
            nativeSort = null,
            presenterClass = null,
            presenterState = null,
            stateSort = null,
            sortOrder = null,
            sortDescending = null,
            leafRuleClass = null,
            leafRuleValue = null,
            groupRuleClass = null,
            groupRules = null,
            actionModeBaseClass = null,
            smartFragmentClass = null,
            actionModeView = null,
            actionModeSelection = null,
            selectionEntries = null,
            selectionEntryModel = null
        )

        Log.i(
            TAG,
            "SMART FOLDERS RUNTIME BINDING | resolved=true" +
                " | adapter=" + adapter.javaClass.name +
                " | holder=" + holder.javaClass.name +
                " | model=" + modelClass.name +
                " | reader=" + modelRead.name +
                " | writer=" + (modelWriter?.name ?: "none") +
                " | differ=" + differ.field.name +
                " | submit=" + differ.submit.name +
                " | root=verified-from-native-models"
        )
        return resolved
    }

    private fun scheduleRuntimeBinding(
        list: ViewGroup,
        attempt: Int
    ) {
        val previous = runtimeBindingAttempts[list]
        if (previous != null && previous >= attempt) return
        runtimeBindingAttempts[list] = attempt
        main.postDelayed({
            runtimeBindingAttempts.remove(list)
            if (!enabled || !list.isAttachedToWindow || bindings != null) {
                return@postDelayed
            }
            val adapter = nativeAdapter(list) ?: return@postDelayed
            val resolved = runCatching {
                resolveRuntimeBindings(list, adapter)
            }.onFailure {
                Log.w(
                    TAG,
                    "SMART FOLDERS RUNTIME BINDING | attempt failed",
                    it
                )
            }.getOrNull()
            if (resolved != null) {
                bindings = resolved
                publishModelWriter(resolved)
                updateMenus()
                onNativeRecyclerObserved(list)
            } else if (attempt < MAX_ATTACH_RETRIES) {
                scheduleRuntimeBinding(list, attempt + 1)
            } else {
                Log.w(
                    TAG,
                    "SMART FOLDERS RUNTIME BINDING | unresolved after retries"
                )
            }
        }, if (attempt == 0) 0L else ATTACH_RETRY_MS)
    }

    private fun diagnoseCompatibilityBindings(
        loader: ClassLoader
    ) {
        listOf("ws4", "ts4").forEach { name ->
            runCatching {
                val type = loader.loadClass(name)
                Log.w(
                    TAG,
                    "SMART MODEL MAPPING | requested=" + name +
                        " | constructors=" +
                        GmmpReflectionDiagnostics.constructors(type) +
                        " | fileMethods=" +
                        GmmpReflectionDiagnostics.methods(
                            type = type,
                            limit = 20
                        ) {
                            it.parameterTypes.size == 1 &&
                                it.parameterTypes[0] == File::class.java
                        }
                )
            }
        }

        listOf("ls4", "is4").forEach { name ->
            runCatching {
                val type = loader.loadClass(name)
                Log.w(
                    TAG,
                    "SMART ADAPTER MAPPING | requested=" + name +
                        " | hierarchy=" +
                        GmmpReflectionDiagnostics.hierarchy(type) +
                        " | fields=" +
                        GmmpReflectionDiagnostics.fields(type, 32)
                )
            }
        }
    }

    fun setModelWriterHookInstaller(
        installer: (Method) -> Unit
    ) {
        modelWriterHookInstaller = installer
        bindings?.let(::publishModelWriter)
    }

    private fun publishModelWriter(native: Bindings) {
        val writer = native.modelWriter ?: return
        val key = writer.declaringClass.name + "|" + writer.name + "|" +
            writer.returnType.name
        val shouldPublish = synchronized(publishedModelWriters) {
            publishedModelWriters.add(key)
        }
        if (!shouldPublish) return
        runCatching {
            modelWriterHookInstaller?.invoke(writer)
        }.onFailure {
            synchronized(publishedModelWriters) {
                publishedModelWriters.remove(key)
            }
            Log.w(
                TAG,
                "SMART FOLDERS SAVE MAPPING | resolved writer hook failed",
                it
            )
        }
    }

    fun setNativeFolderCreator(loader: ClassLoader) {
        folderCreator = NativeGmmpFolderCreator(loader)
        folderDeletion = NativeGmmpFolderDeletion(loader)
    }

    fun setEnabled(next: Boolean) {
        setOptions(next, groupRootPlaylists, multiSelectEnabled)
    }

    fun setOptions(
        nextEnabled: Boolean,
        nextGroupRoot: Boolean,
        nextMultiSelect: Boolean = multiSelectEnabled
    ) {
        val enabledChanged = enabled != nextEnabled
        val groupingChanged = groupRootPlaylists != nextGroupRoot
        val multiChanged = multiSelectEnabled != nextMultiSelect
        if (!enabledChanged && !groupingChanged && !multiChanged) return
        enabled = nextEnabled
        groupRootPlaylists = nextGroupRoot
        multiSelectEnabled = nextMultiSelect
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
            if (multiChanged && !nextMultiSelect) {
                browsers.values.toList().forEach(::clearSmartSelection)
            }
            updateMenus()
            if (enabledChanged && !nextEnabled) {
                pendingCreationDirectory = null
                pendingOriginalAlphas.keys.toList()
                    .forEach(::restorePendingNativeList)
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
                " | groupRoot=" + nextGroupRoot +
                " | multiSelect=" + nextMultiSelect
        )
    }

    fun capturePresenter(presenter: Any?) {
        val native = bindings ?: return
        val presenterClass = native.presenterClass ?: return
        if (presenter != null && presenterClass.isInstance(presenter)) {
            presenterRef = WeakReference(presenter)
        }
    }

    fun onNativeRecyclerObserved(view: View?) {
        val list = view as? ViewGroup ?: return
        val native = bindings
        val adapter = nativeAdapter(list)
        val byResource = resourceName(list) == SMART_LIST_ID
        val adapterMatches =
            adapter != null &&
                native?.adapterClass?.isInstance(adapter) == true
        val nativeSmartSurface = byResource || adapterMatches
        if (!nativeSmartSurface) return
        knownLists[list] = true

        if (native == null && byResource && adapter != null) {
            if (enabled) scheduleRuntimeBinding(list, 0)
            return
        }

        if (!SmartFolderSurfaceCompatibilityPolicy.canMaskNativeList(
                bindingsReady = native != null,
                adapterPresent = adapter != null,
                adapterMatchesBindings = adapterMatches
            )
        ) {
            // setAdapter/attach order is not stable across GMMP versions.
            // If an earlier no-adapter observation hid this view, restore
            // it immediately instead of leaving a fully interactive alpha=0
            // native Smart list behind.
            if (pendingOriginalAlphas.containsKey(list)) {
                restorePendingNativeList(list)
            }
            return
        }
        if (BuildConfig.DEBUG && resourceName(list) != SMART_LIST_ID) {
            Log.i(
                TAG,
                "SMART NAV COMPAT | adapter=ls4 | viewId=" +
                    resourceName(list).ifBlank { "<none>" }
            )
        }
        if (failedOverlayHosts.containsKey(list)) return
        if (enabled && !browsers.containsKey(list)) {
            if (!pendingOriginalAlphas.containsKey(list)) {
                pendingOriginalAlphas[list] = list.alpha
            }
            // Hide only after a concrete adapter has matched the verified bindings.
            // The original alpha is restored only after the folder header
            // and current-directory native models are both ready.
            list.alpha = 0f
            scheduleAttach(list, 0)
        }
    }

    /**
     * Called from hooks installed on GMMP's OWN RecyclerView classloader.
     * The host RecyclerView cannot be cast to GoneSmart's RecyclerView even
     * though both bundle AndroidX 1.4.0, so host scroll/touch callbacks must
     * cross the boundary as framework View/MotionEvent values only.
     */
    fun onNativeRecyclerScrolled(view: View?, dy: Int) {
        if (dy == 0) return
        val list = view as? ViewGroup ?: return
        val browser = browsers[list] ?: return
        syncFolderRowsScrollByDelta(browser, dy)
    }

    fun onNativeRecyclerTouch(view: View?, event: MotionEvent?) {
        if (event == null) return
        val list = view as? ViewGroup ?: return
        val browser = browsers[list] ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                list.parent?.requestDisallowInterceptTouchEvent(true)
                if (!browser.touchGuardReported) {
                    browser.touchGuardReported = true
                    Log.i(
                        TAG,
                        "SMART FOLDERS TOUCH | host RecyclerView owns gesture" +
                            " | nestedScroll=" +
                            (nativeNestedScrollingEnabled(list)?.toString()
                                ?: "unknown") +
                            " | moduleRecyclerMatch=" + (list is RecyclerView)
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                list.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    /**
     * If a complete GoneSmart folder projection is already visible, the
     * native os4.j2 root submit is only an intermediate refresh and would
     * force an unnecessary hide/reveal cycle. Preserve the current projection
     * and refresh its directory directly instead.
     */
    fun shouldSuppressNativeSmartRootSubmission(): Boolean {
        if (!enabled) return false
        return browsers.values.toList()
            .filter { it.list.isAttachedToWindow }
            .any { browser ->
                SmartNativeSubmissionPolicy.shouldSuppressNativeRootRefresh(
                    projectionPrepared = browser.projectionPrepared,
                    nativeContentReady = browser.nativeContentReady,
                    currentIsRoot = sameFile(browser.current, browser.root),
                    otherLocations = browser.otherLocations,
                    groupRootPlaylists = groupRootPlaylists
                )
            }
    }

    fun onNativeSmartRootSubmissionSuppressed() {
        if (!enabled) return
        val refreshAttached = {
            browsers.values.toList()
                .filter { it.list.isAttachedToWindow }
                .forEach { browser ->
                    refresh(browser)
                }
        }
        if (Looper.myLooper() === Looper.getMainLooper()) {
            refreshAttached()
        } else {
            main.post { refreshAttached() }
        }
    }

    /**
     * Fallback for first construction / an unprepared projection: mask the
     * native root submit before it can draw, then let the normal post-submit
     * path rebuild the current folder.
     */
    fun onNativeSmartListSubmitting() {
        if (!enabled) return
        browsers.values.toList()
            .filter { it.list.isAttachedToWindow }
            .forEach { browser ->
                val mask = SmartNativeSubmissionPolicy.shouldMaskNativeRootRefresh(
                    currentIsRoot = sameFile(browser.current, browser.root),
                    otherLocations = browser.otherLocations,
                    groupRootPlaylists = groupRootPlaylists
                )
                if (mask) {
                    browser.nativeContentReady = false
                    browser.projectionPrepared = false
                    browser.projectionFailOpenAllowed = false
                    browser.list.alpha = 0f
                }
            }
    }

    /**
     * Native os4.j2(List<ws4>) has just submitted a Smart-Playlist list.
     * Re-apply GoneSmart's current folder projection through ls4.y. Our own
     * differ submit does not recurse through os4.j2.
     */
    fun onNativeSmartListSubmitted() {
        if (!enabled) return
        val refreshAttached = {
            browsers.values.toList()
                .filter { it.list.isAttachedToWindow }
                .forEach { browser ->
                    browser.nativeSubmitted = false
                    refresh(browser)
                }
        }
        if (Looper.myLooper() === Looper.getMainLooper()) {
            refreshAttached()
        } else {
            main.post { refreshAttached() }
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
                if (menuRefs.none { it.get() === menu }) {
                    menuRefs += WeakReference(menu)
                }
                if (enabled && bindings != null) {
                    installNewFolderMenu(menu, context)
                } else {
                    menu.findItem(newFolderMenuId)?.isVisible = false
                    if (enabled && bindings == null) {
                        Log.i(
                            TAG,
                            "SMART FOLDERS MENU | compatibility bindings " +
                                "unavailable; folder action hidden"
                        )
                    }
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
        if (!enabled || !multiSelectEnabled ||
            menu == null || !isSmartActionMode(callback)
        ) return
        val context = currentBrowser()?.list?.context ?: return
        installMoveMenu(menu, context)
        Log.i(TAG, "SMART MULTI SELECT | native ActionMode Move installed")
    }

    /** Exact n3 selected models: s3.c -> s3$a.b -> ws4. */
    fun interceptNativeSmartActionModeMove(
        callback: Any?,
        mode: Any?,
        item: MenuItem?
    ): Boolean {
        if (item?.itemId != moveMenuId) return false
        if (!enabled || !multiSelectEnabled ||
            !isSmartActionMode(callback)
        ) return true
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
            prepareFolderScrollForNavigation(browser)
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
            val native = bindings ?: run {
                restorePendingNativeList(list)
                return@postDelayed
            }
            val adapter = nativeAdapter(list)
            if (adapter == null || !native.adapterClass.isInstance(adapter) ||
                list.width <= 0 || list.height <= 0
            ) {
                if (attempt < MAX_ATTACH_RETRIES) {
                    scheduleAttach(list, attempt + 1)
                } else {
                    restorePendingNativeList(list)
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
            restorePendingNativeList(list)
            Log.w(TAG, "SMART FOLDERS ATTACH STOP | native root unavailable")
            return
        }
        val host = safeOverlayHost(list) ?: run {
            failedOverlayHosts[list] = true
            restorePendingNativeList(list)
            Log.w(
                TAG,
                "SMART FOLDERS ATTACH STOP | page host unavailable" +
                    if (BuildConfig.DEBUG) {
                        " | chain=" +
                            PlaylistNavigationSurfaceHost.parentChain(list)
                    } else ""
            )
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
            visibility = View.INVISIBLE
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
        val folderViewport = SmartFolderStretchViewport(list.context).apply {
            clipChildren = true
            clipToPadding = true
            background = ColorDrawable(Color.TRANSPARENT)
            addView(
                folderBand,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val column = LinearLayout(list.context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            addView(
                breadcrumb,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                folderViewport,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        overlay.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        lateinit var browser: Browser
        val layoutListener = View.OnLayoutChangeListener {
                _, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom ->
            if (browsers[list] === browser &&
                (left != oldLeft || top != oldTop ||
                    right != oldRight || bottom != oldBottom)
            ) {
                positionOverlay(browser)
            }
        }
        val scrollDrawListener =
            android.view.ViewTreeObserver.OnPreDrawListener {
                if (browsers[list] === browser) {
                    syncPagerOverlayVisibility(browser)
                    // If the current folder projection completed while a
                    // Smart-Playlist detail was covering this fragment,
                    // reveal it only in this first real front-surface pre-draw.
                    // Header/inset are committed before native alpha returns.
                    if (!browser.nativeContentReady &&
                        isFrontFragmentView(list)
                    ) {
                        val ready = nativeProjectionReady(browser)
                        if (ready || browser.projectionFailOpenAllowed) {
                            revealInitialContent(
                                browser,
                                allowProjectionMismatch =
                                    browser.projectionFailOpenAllowed
                            )
                        }
                    }
                    // Normal scrolling stays driven only by native consumed
                    // dy. PreDraw mirrors GMMP's own top EdgeEffect stretch
                    // so synthetic physical folders deform with native rows.
                    syncNativeVerticalOverscroll(browser)
                    alignVisibleNativeTitles(browser)
                    syncVisibleSmartRowInteractions(browser)
                }
                true
            }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (browsers[list] === browser) removeBrowser(browser)
            }
        }
        val originalAlpha = pendingOriginalAlphas.remove(list) ?: list.alpha
        val originalNativeNestedScrollingEnabled =
            nativeNestedScrollingEnabled(list)
        // GMMP and GoneSmart package the same AndroidX RecyclerView through
        // different classloaders on the tested build. Never use an
        // "as? RecyclerView" cast for the host list: that silently returned
        // null in the previous build, so neither scroll listeners nor the
        // nested-scrolling change were ever installed.
        if (originalNativeNestedScrollingEnabled == true) {
            setNativeNestedScrollingEnabled(list, false)
        }
        browser = Browser(
            list = list,
            nativeAdapter = adapter,
            host = host,
            overlay = overlay,
            rows = rows,
            folderBand = folderBand,
            folderViewport = folderViewport,
            breadcrumb = breadcrumb,
            originalAlpha = originalAlpha,
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
            originalNativeNestedScrollingEnabled =
                originalNativeNestedScrollingEnabled,
            scrollDrawListener = scrollDrawListener,
            detachListener = detachListener
        )
        breadcrumb.adapter = NativeFolderBreadcrumbAdapter(
            tag = TAG,
            host = list,
            onStyleLabel = { label ->
                browser.style?.let { style ->
                    label.paint.set(style.paint)
                    label.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        style.paint.textSize * quickNavTitleRatio(browser.list)
                    )
                    label.setTextColor(style.textColor)
                    label.typeface = Typeface.create(
                        style.typeface, Typeface.BOLD
                    )
                    label.letterSpacing = style.letterSpacing
                    label.includeFontPadding = style.includeFontPadding
                    label.requestLayout()
                }
            },
            onSegmentClick = { segment ->
                if (browsers[browser.list] !== browser) {
                    Unit
                } else if (segment.key == "other-locations") {
                    if (!browser.otherLocations) navigateOtherLocations(browser)
                } else if (segment.key == "root") {
                    if (browser.otherLocations ||
                        !sameFile(browser.current, browser.root)
                    ) navigate(browser, browser.root)
                } else if (segment.key.startsWith("dir:")) {
                    val directory = File(segment.key.removePrefix("dir:"))
                    if (browser.otherLocations ||
                        !sameFile(browser.current, directory)
                    ) navigate(browser, directory)
                }
            }
        )
        browsers[list] = browser
        subscribeSmartSelectionAccent(browser)
        list.addOnAttachStateChangeListener(detachListener)
        list.addOnLayoutChangeListener(layoutListener)
        if (list.viewTreeObserver.isAlive) {
            list.viewTreeObserver.addOnPreDrawListener(scrollDrawListener)
        }

        // Match the accepted normal Playlist-folder first frame: keep the
        // raw native Smart root hidden until GoneSmart has both the native
        // filtered snapshot and the physical-folder header ready.
        list.alpha = 0f
        if (!PlaylistNavigationSurfaceHost.addOverlay(
                host = host,
                list = list,
                overlay = overlay,
                width = list.width,
                height = list.height
            )
        ) {
            failedOverlayHosts[list] = true
            removeBrowser(browser)
            Log.w(
                TAG,
                "SMART FOLDERS ATTACH STOP | scoped overlay insertion failed" +
                    if (BuildConfig.DEBUG) {
                        " | chain=" +
                            PlaylistNavigationSurfaceHost.parentChain(list)
                    } else ""
            )
            return
        }
        positionOverlay(browser)
        startObserver(browser)
        refresh(browser)
        updateMenus()
        val weakList = WeakReference(list)
        main.postDelayed({
            val current = weakList.get()?.let { browsers[it] }
            if (current === browser && !browser.nativeContentReady &&
                list.isAttachedToWindow
            ) {
                Log.w(TAG, "SMART FOLDERS INITIAL WAIT | fail-open native list")
                browser.projectionFailOpenAllowed = true
                revealInitialContent(
                    browser,
                    allowProjectionMismatch = true
                )
            }
        }, 2500L)
        Log.i(
            TAG,
            "SMART FOLDERS READY | root=" + safePath(root) +
                " | restored=" + safePath(remembered) +
                " | otherLocations=" + restoreOtherLocations +
                " | nativeAdapter=ls4" +
                " | nestedScroll=" +
                (originalNativeNestedScrollingEnabled?.toString()
                    ?: "unknown") + "->" +
                (nativeNestedScrollingEnabled(list)?.toString()
                    ?: "unknown") +
                " | moduleRecyclerMatch=" +
                (list is RecyclerView)
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
        prepareFolderScrollForNavigation(browser)
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
        prepareFolderScrollForNavigation(browser)
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
            val scan = runCatching {
                scanDirectory(directory)
            }.onFailure {
                Log.e(TAG, "SMART FOLDERS SCAN FAILED | " + safePath(directory), it)
            }.getOrNull() ?: return@execute

            // Build one complete snapshot before changing the visible frame.
            // The earlier header-first stage could expose a partially laid-out
            // surface and then visibly shift when native Smart rows arrived.
            val snapshot = runCatching {
                loadSnapshot(scan)
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
                    browser.otherLocations -> snapshot.models
                    browser.moveSources != null -> snapshot.models
                    groupRootPlaylists &&
                        sameFile(browser.current, browser.root) -> emptyList()
                    else -> snapshot.models
                }
                val order = models.mapNotNull(::modelPath)
                val signature = nativeModelSignature(models)
                browser.nativeOrder = order
                if (SmartNativeSubmissionPolicy.shouldSubmit(
                        browser.nativeSubmitted,
                        browser.nativeSignature,
                        signature
                    )
                ) {
                    applyNativeModels(browser, models)
                    browser.nativeSignature = signature
                    browser.nativeSubmitted = true
                }
                browser.style = sampleNativeStyle(browser.list) ?: browser.style
                render(browser, snapshot, models.size)
                browser.projectionPrepared = true
                settleFolderScrollAfterRefresh(browser, generation)
                positionOverlay(browser)
            }
        }
    }

    private fun scanDirectory(directory: File): DirectoryScan {
        val canonical = directory.canonicalFile
        val files = canonical.listFiles()?.toList().orEmpty()
        val root = rootFile() ?: canonical
        val folders = files.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter {
                SmartPlaylistFolderPolicy.isInsideRoot(root.path, it.path)
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
            .toList()
        return DirectoryScan(canonical, files, folders)
    }

    private fun loadSnapshot(directory: File): Snapshot =
        loadSnapshot(scanDirectory(directory))

    private fun loadSnapshot(scan: DirectoryScan): Snapshot {
        val native = bindings ?: error("Smart-folder bindings missing")
        val models = ArrayList<Any>()
        for (file in scan.files.filter {
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
            directory = scan.directory,
            folders = scan.folders,
            models = sorted,
            modelsByPath = byPath
        )
    }

    private fun sortNative(models: ArrayList<Any>): List<Any> {
        val native = bindings ?: return models
        val presenter = presenterRef?.get()
        val presenterState = native.presenterState
        val stateSort = native.stateSort
        val sortOrder = native.sortOrder
        val sortDescending = native.sortDescending
        val nativeSort = native.nativeSort
        if (presenter != null &&
            presenterState != null &&
            stateSort != null &&
            sortOrder != null &&
            sortDescending != null &&
            nativeSort != null
        ) {
            runCatching {
                val state = presenterState.get(presenter)
                val sortState = stateSort.get(state)
                val orderPreference = sortOrder.invoke(sortState)
                val descendingPreference =
                    sortDescending.invoke(sortState)
                val order = preferenceValue(orderPreference) as Number
                val descending =
                    preferenceValue(descendingPreference) as Boolean
                @Suppress("UNCHECKED_CAST")
                return nativeSort.invoke(
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
    private fun nativeModelSignature(models: List<Any>): List<String> =
        models.mapNotNull { model ->
            modelPath(model)?.let { path ->
                val file = File(path)
                path + "|" + file.lastModified() + "|" + file.length()
            }
        }

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
        val moving = browser.moveSources != null
        val folders = if (browser.otherLocations) {
            emptyList()
        } else {
            snapshot.folders
        }
        val showOtherLocations =
            !moving &&
                groupRootPlaylists &&
                !browser.otherLocations &&
                sameFile(browser.current, browser.root)
        val locationKey = canonicalPath(snapshot.directory.path) +
            "|other=" + browser.otherLocations +
            "|move=" + moving
        val locationChanged = browser.renderedLocationKey != locationKey
        browser.renderedLocationKey = locationKey
        if (locationChanged) {
            browser.folderBand.translationY = 0f
            browser.nativeScrollDistancePx = 0
            browser.folderGestureDragging = false
            browser.folderGestureReported = false
            browser.folderScrollSyncReady = false
            browser.pendingFolderScrollReset = true
        }

        val headerSignature = buildString {
            append(locationKey)
            append("|folders=")
            folders.forEach {
                append(canonicalPath(it.path))
                append(';')
            }
            append("|otherNode=")
            append(showOtherLocations)
            append("|style=")
            browser.style?.let {
                append(it.rowLayoutId)
                append(':')
                append(it.titleViewId)
                append(':')
                append(it.rowHeight)
                append(':')
                append(it.textColor)
                append(':')
                append(it.textSizePx)
            }
        }
        if (browser.renderedHeaderSignature == headerSignature) {
            browser.overlay.post {
                if (browsers[browser.list] === browser) {
                    updateNativeInset(browser)
                    syncVisibleSmartRowInteractions(browser)
                    if (moving) positionMoveFab(browser)
                }
            }
            return
        }
        browser.renderedHeaderSignature = headerSignature
        browser.rows.removeAllViews()
        renderBreadcrumb(browser)

        val nativeMenuButton = firstNativeContextMenu(browser.list)
        folders.forEach { folder ->
            val row = createRow(
                browser,
                folder.name,
                folder = true,
                contextMenuSource = nativeMenuButton,
                onContext = if (moving) null else { anchor ->
                    showNativeFolderContextMenu(browser, folder, anchor)
                }
            )
            row.setOnClickListener { navigate(browser, folder) }
            browser.rows.addView(row)
        }

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
                if (moving) positionMoveFab(browser)
            }
        }

        Log.i(
            TAG,
            (if (visibleSmartCount < 0) {
                "SMART FOLDERS HEADER"
            } else {
                "SMART FOLDERS RENDER"
            }) + " | current=" + safePath(browser.current) +
                " | virtualOther=" + browser.otherLocations +
                " | folders=" + (folders.size + if (showOtherLocations) 1 else 0) +
                " | smart=" + visibleSmartCount +
                " | nativeRows=" + (visibleSmartCount >= 0) +
                " | move=" + moving
        )
    }

    private fun renderBreadcrumb(browser: Browser) {
        val next = arrayListOf<PlaylistFolderUiKit.BreadcrumbSegment>()
        val rootLabel = NativeGmmpUiText.storage(browser.list.context)
        next += PlaylistFolderUiKit.BreadcrumbSegment(
            key = "root",
            label = rootLabel
        )
        if (browser.otherLocations) {
            next += PlaylistFolderUiKit.BreadcrumbSegment(
                key = "other-locations",
                label = NativeGmmpUiText.otherLocations(browser.list.context)
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
                    next += PlaylistFolderUiKit.BreadcrumbSegment(
                        key = "dir:" + canonicalPath(cursor.path),
                        label = name
                    )
                }
        }
        val signature = browser.style?.let {
            it.rowLayoutId.toString() + ":" +
                it.titleViewId + ":" +
                it.rowHeight + ":" +
                it.textColor + ":" +
                it.textSizePx
        }.orEmpty()
        (browser.breadcrumb.adapter as? NativeFolderBreadcrumbAdapter)
            ?.submit(next, signature)
        browser.breadcrumb.visibility =
            if (next.size > 1) View.VISIBLE else View.GONE
        if (next.size > 1) {
            val generation = ++browser.breadcrumbAlignmentGeneration
            scheduleBreadcrumbAlignment(browser, generation)
        }
    }

    private fun expectedBreadcrumbTextStart(list: View): Int {
        val prefs = list.context.getSharedPreferences(
            QUICK_NAV_METRICS_PREFS,
            android.content.Context.MODE_PRIVATE
        )
        val verifiedKey = QUICK_NAV_VERIFIED_FIRST_X_KEY +
            list.resources.displayMetrics.densityDpi
        val verified = if (prefs.contains(verifiedKey)) {
            prefs.getInt(verifiedKey, -1).takeIf {
                it in 0..dp(list, 96)
            }
        } else null
        return verified ?: playlistTitleInset(list)
    }

    private fun scheduleBreadcrumbAlignment(
        browser: Browser,
        generation: Long,
        attempt: Int = 0
    ) {
        val strip = browser.breadcrumb
        strip.postOnAnimation {
            if (browsers[browser.list] !== browser ||
                browser.breadcrumbAlignmentGeneration != generation ||
                strip.visibility != View.VISIBLE ||
                !strip.isAttachedToWindow
            ) return@postOnAnimation
            val target = expectedBreadcrumbTextStart(browser.list)
            val result = PlaylistFolderUiKit.alignBreadcrumbStart(
                strip,
                expectedTextStart = target,
                maxCorrectionPx = dp(strip, 24)
            )
            if (result == null) {
                if (attempt < 12) {
                    scheduleBreadcrumbAlignment(
                        browser, generation, attempt + 1
                    )
                }
                return@postOnAnimation
            }
            if (result.appliedPaddingStart != null && attempt < 12) {
                scheduleBreadcrumbAlignment(
                    browser, generation, attempt + 1
                )
            }
        }
    }

    private fun sharedRowStyle(
        host: View,
        style: NativeStyle?
    ): PlaylistFolderUiKit.RowStyle? = style?.let {
        PlaylistFolderUiKit.RowStyle(
            rowLayoutId = it.rowLayoutId,
            titleViewId = it.titleViewId,
            rowHeight = it.rowHeight,
            textColor = it.textColor,
            textSizePx = it.textSizePx,
            typeface = it.typeface,
            titleGravity = it.titleGravity,
            titlePaddingStart = it.titlePaddingStart,
            titlePaddingEnd = it.titlePaddingEnd,
            effectivePaint = it.paint,
            letterSpacing = it.letterSpacing,
            textScaleX = it.textScaleX,
            includeFontPadding = it.includeFontPadding,
            lineSpacingExtra = it.lineSpacingExtra,
            lineSpacingMultiplier = it.lineSpacingMultiplier,
            maxLines = it.maxLines,
            ellipsize = it.ellipsize,
            rowBackground = it.rowBackground,
            titleInset = playlistTitleInset(host),
            accentColor = it.accentColor
        )
    }

    private fun createRow(
        browser: Browser,
        text: String,
        folder: Boolean,
        contextMenuSource: ImageView?,
        onContext: ((View) -> Unit)?
    ): View {
        val style = sharedRowStyle(browser.list, browser.style)
        return PlaylistFolderUiKit.createRow(
            parent = browser.rows,
            host = browser.list,
            text = text,
            style = style,
            folder = folder,
            selected = false,
            selectionAccent = style?.accentColor ?: Color.TRANSPARENT,
            contextMenuSource = contextMenuSource,
            onContext = onContext
        ).also { row ->
            installFolderRowScrollRelay(browser, row)
        }
    }

    /**
     * Synthetic physical-folder rows sit above GMMP's real Smart RecyclerView.
     * A drag that starts on such a row would otherwise never reach the native
     * list, so the folder can look pinned even though drags starting on a
     * native Smart row scroll correctly. Relay only vertical drags after the
     * platform touch slop; taps/context clicks remain the existing row action.
     * The native RecyclerView still owns actual scrolling, and its original
     * onScrolled(dy) callback remains the ONLY authority that moves the folder
     * band.
     */
    private fun installFolderRowScrollRelay(
        browser: Browser,
        row: View
    ) {
        val touchSlop = ViewConfiguration.get(row.context).scaledTouchSlop
        row.setOnTouchListener { touched, event ->
            if (browsers[browser.list] !== browser ||
                !browser.folderScrollSyncReady
            ) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    browser.folderGestureDownY = event.rawY
                    browser.folderGestureDragging = false
                    browser.folderGestureDownEvent?.recycle()
                    browser.folderGestureDownEvent = MotionEvent.obtain(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val total = kotlin.math.abs(
                        event.rawY - browser.folderGestureDownY
                    )
                    if (!browser.folderGestureDragging && total > touchSlop) {
                        browser.folderGestureDragging = true
                        touched.isPressed = false
                        browser.folderGestureDownEvent?.let { down ->
                            relayFolderMotionToNative(browser, down)
                        }
                        touched.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!browser.folderGestureDragging) {
                        false
                    } else {
                        relayFolderMotionToNative(browser, event)
                        if (!browser.folderGestureReported) {
                            browser.folderGestureReported = true
                            Log.i(
                                TAG,
                                "SMART FOLDERS FOLDER DRAG | nativeTouch=true" +
                                    " | canDown=" +
                                    nativeCanScrollVertically(browser.list, 1) +
                                    " | canUp=" +
                                    nativeCanScrollVertically(browser.list, -1) +
                                    " | nativeRows=" + browser.nativeOrder.size
                            )
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val consumed = browser.folderGestureDragging
                    if (consumed) relayFolderMotionToNative(browser, event)
                    browser.folderGestureDownEvent?.recycle()
                    browser.folderGestureDownEvent = null
                    touched.parent?.requestDisallowInterceptTouchEvent(false)
                    browser.folderGestureDragging = false
                    consumed
                }
                else -> {
                    if (browser.folderGestureDragging) {
                        relayFolderMotionToNative(browser, event)
                        true
                    } else false
                }
            }
        }
    }

    private fun relayFolderMotionToNative(
        browser: Browser,
        source: MotionEvent
    ): Boolean {
        val list = browser.list
        if (!list.isAttachedToWindow) return false
        val screen = IntArray(2)
        list.getLocationOnScreen(screen)
        val forwarded = MotionEvent.obtain(source)
        forwarded.setLocation(
            source.rawX - screen[0],
            source.rawY - screen[1]
        )
        return try {
            list.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun nativeCanScrollVertically(
        list: ViewGroup,
        direction: Int
    ): Boolean? = runCatching {
        list.javaClass.getMethod(
            "canScrollVertically",
            Integer.TYPE
        ).invoke(list, direction) as? Boolean
    }.getOrNull()

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
                rowBackground = row.background?.constantState,
                accentColor = resolveColor(
                    list,
                    android.R.attr.colorAccent,
                    0xFFA39AFF.toInt()
                )
            )
        }
        return null
    }

    private fun subscribeSmartSelectionAccent(browser: Browser) {
        browser.liveSelectionAccent = NativeGmmpAccent.lastObserved()
        browser.selectionAccentSubscription?.dispose()
        browser.selectionAccentSubscription = NativeGmmpAccent.observe(
            browser.list,
            onColor = { color ->
                if (browsers[browser.list] !== browser) return@observe
                if (browser.liveSelectionAccent == color) return@observe
                browser.liveSelectionAccent = color
                browser.selectionOverlayColor = null
                syncVisibleSmartRowInteractions(browser)
                Log.i(
                    TAG,
                    "SMART MULTI STYLE | !mainColorAccent=#" +
                        Integer.toHexString(color)
                )
            },
            onError = {
                Log.w(
                    TAG,
                    "SMART MULTI STYLE | live accent unavailable",
                    it
                )
            }
        )
    }

    private fun smartSelectionOverlayColor(browser: Browser): Int {
        val accent = browser.liveSelectionAccent
            ?: NativeGmmpAccent.lastObserved()
            ?: browser.style?.accentColor
            ?: resolveColor(
                browser.list,
                android.R.attr.colorAccent,
                0xFFA39AFF.toInt()
            )
        return Color.argb(
            0x80,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent)
        )
    }

    private fun selectionTitle(
        browser: Browser,
        count: Int
    ): String = PlaylistFolderUiKit.selectionTitle(
        browser.list.context, count
    )

    private fun beginSmartSelection(
        browser: Browser,
        path: String
    ): Boolean {
        if (!multiSelectEnabled || browser.moveSources != null) return false
        val canonical = canonicalPath(path)
        if (canonical !in browser.nativeOrder) return false
        browser.selectedSmartPaths.add(canonical)
        browser.suppressSelectionUpPath = canonical
        ensureSmartSelectionActionMode(browser)
        syncVisibleSmartRowInteractions(browser)
        Log.i(
            TAG,
            "SMART MULTI SELECT | started | count=" +
                browser.selectedSmartPaths.size
        )
        return true
    }

    private fun toggleSmartSelection(
        browser: Browser,
        path: String
    ) {
        val canonical = canonicalPath(path)
        if (!browser.selectedSmartPaths.remove(canonical)) {
            browser.selectedSmartPaths.add(canonical)
        }
        if (browser.selectedSmartPaths.isEmpty()) {
            browser.selectionActionMode?.finish()
            if (browser.selectionActionMode == null) {
                clearSmartSelection(browser)
            }
        } else {
            ensureSmartSelectionActionMode(browser)
            browser.selectionActionMode?.title =
                selectionTitle(browser, browser.selectedSmartPaths.size)
            syncVisibleSmartRowInteractions(browser)
        }
        Log.i(
            TAG,
            "SMART MULTI SELECT | toggled | count=" +
                browser.selectedSmartPaths.size
        )
    }

    private fun ensureSmartSelectionActionMode(browser: Browser) {
        if (browser.selectedSmartPaths.isEmpty()) return
        browser.selectionActionMode?.let {
            it.title = selectionTitle(browser, browser.selectedSmartPaths.size)
            if (browser.selectionOverlayColor == null) {
                browser.selectionOverlayColor =
                    smartSelectionOverlayColor(browser)
            }
            return
        }
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean {
                mode.title = selectionTitle(
                    browser, browser.selectedSmartPaths.size
                )
                installMoveMenu(menu, browser.list.context)
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode,
                item: MenuItem
            ): Boolean {
                if (item.itemId != moveMenuId) return false
                val selected = browser.selectedSmartPaths.toList()
                if (selected.isEmpty()) return true
                browser.selectionTransitionToMove = true
                browser.selectedSmartPaths.clear()
                syncVisibleSmartRowInteractions(browser)
                mode.finish()
                beginMove(browser, selected, null)
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (browser.selectionActionMode !== mode) return
                browser.selectionActionMode = null
                if (browser.selectionTransitionToMove) {
                    browser.selectionTransitionToMove = false
                    browser.suppressSelectionUpPath = null
                    syncVisibleSmartRowInteractions(browser)
                } else {
                    clearSmartSelection(browser)
                }
            }
        }
        browser.selectionActionMode = runCatching {
            browser.list.startActionMode(
                callback,
                android.view.ActionMode.TYPE_PRIMARY
            )
        }.onFailure {
            Log.w(TAG, "SMART MULTI SELECT | ActionMode unavailable", it)
        }.getOrNull()
        if (browser.selectionActionMode == null) {
            browser.selectedSmartPaths.clear()
            browser.suppressSelectionUpPath = null
            browser.selectionOverlayColor = null
        } else if (browser.selectionOverlayColor == null) {
            browser.selectionOverlayColor =
                smartSelectionOverlayColor(browser)
        }
    }

    private fun clearSmartSelection(browser: Browser) {
        val mode = browser.selectionActionMode
        browser.selectionActionMode = null
        browser.selectionTransitionToMove = false
        browser.suppressSelectionUpPath = null
        browser.selectedSmartPaths.clear()
        restoreSmartSelectionOverlays(browser)
        mode?.finish()
    }

    private fun restoreSmartSelectionOverlays(browser: Browser) {
        browser.selectionOverlays.toList().forEach { (row, overlay) ->
            row.overlay.remove(overlay)
        }
        browser.selectionOverlays.clear()
        browser.selectionOverlayColor = null
    }

    private fun syncVisibleSmartRowInteractions(browser: Browser) {
        val native = bindings ?: return
        val list = browser.list
        if (!list.isAttachedToWindow || browsers[list] !== browser) return
        val holderGetter = runCatching {
            list.javaClass.getMethod(
                "getChildViewHolder", View::class.java
            )
        }.getOrNull() ?: return

        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) ?: continue
            val holder = runCatching {
                holderGetter.invoke(list, row)
            }.getOrNull() ?: continue
            if (!native.holderClass.isInstance(holder)) continue
            val model = runCatching {
                native.holderModel.get(holder)
            }.getOrNull() ?: continue
            val path = modelPath(model) ?: continue
            val previous = browser.rowInteractionPaths[row]
            if (previous != path) {
                browser.selectionOverlays.remove(row)?.let {
                    row.overlay.remove(it)
                }
                browser.rowInteractionPaths[row] = path
                row.setOnLongClickListener {
                    val handled = beginSmartSelection(browser, path)
                    if (handled) {
                        row.post { row.isPressed = false }
                    }
                    handled
                }
                row.setOnTouchListener { view, event ->
                    if (browser.selectedSmartPaths.isEmpty() ||
                        browser.moveSources != null
                    ) {
                        return@setOnTouchListener false
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.isPressed = true
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.isPressed = false
                            if (browser.suppressSelectionUpPath == path) {
                                browser.suppressSelectionUpPath = null
                            } else {
                                toggleSmartSelection(browser, path)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.isPressed = false
                            if (browser.suppressSelectionUpPath == path) {
                                browser.suppressSelectionUpPath = null
                            }
                            true
                        }
                        else -> true
                    }
                }
            }

            val selected = path in browser.selectedSmartPaths
            if (selected) {
                val color = browser.selectionOverlayColor
                    ?: smartSelectionOverlayColor(browser)
                        .also { browser.selectionOverlayColor = it }
                var overlay = browser.selectionOverlays[row]
                if (overlay == null) {
                    overlay = ColorDrawable(color)
                    browser.selectionOverlays[row] = overlay
                    row.overlay.add(overlay)
                } else if (overlay.color != color) {
                    overlay.color = color
                }
                overlay.setBounds(0, 0, row.width, row.height)
            } else {
                browser.selectionOverlays.remove(row)?.let {
                    row.overlay.remove(it)
                }
            }
        }
    }

    private fun playlistTitleInset(list: View): Int {
        val key = PLAYLIST_TITLE_INSET_KEY +
            list.resources.displayMetrics.densityDpi
        return list.context.getSharedPreferences(
            QUICK_NAV_METRICS_PREFS,
            android.content.Context.MODE_PRIVATE
        ).getInt(key, dp(list, 12)).coerceIn(0, dp(list, 96))
    }

    private fun alignVisibleNativeTitles(browser: Browser) {
        val native = bindings ?: return
        val targetInset = playlistTitleInset(browser.list)
        val holderGetter = runCatching {
            browser.list.javaClass.getMethod(
                "getChildViewHolder", View::class.java
            )
        }.getOrNull() ?: return
        for (index in 0 until browser.list.childCount) {
            val row = browser.list.getChildAt(index) ?: continue
            val holder = runCatching {
                holderGetter.invoke(browser.list, row)
            }.getOrNull() ?: continue
            if (!native.holderClass.isInstance(holder)) continue
            val model = runCatching {
                native.holderModel.get(holder)
            }.getOrNull() ?: continue
            val title = findTextView(row, modelName(model)) ?: continue
            if (!alignedNativeTitles.containsKey(title)) {
                alignedNativeTitles[title] = title.translationX
                alignedNativeTitleOwners[title] = WeakReference(browser.list)
            }
            val rowPosition = IntArray(2)
            val titlePosition = IntArray(2)
            row.getLocationOnScreen(rowPosition)
            title.getLocationOnScreen(titlePosition)
            val currentInset = titlePosition[0] - rowPosition[0] +
                title.compoundPaddingStart
            val delta = targetInset - currentInset
            if (kotlin.math.abs(delta) >= 1) {
                val base = alignedNativeTitles[title] ?: 0f
                title.translationX = (title.translationX + delta)
                    .coerceIn(
                        base - dp(browser.list, 48),
                        base + dp(browser.list, 48)
                    )
            }
            if (BuildConfig.DEBUG && titleAlignmentReports.size < 8) {
                val signature = "$currentInset->$targetInset"
                if (titleAlignmentReports.add(signature)) {
                    Log.i(
                        TAG,
                        "SMART TITLE ALIGN | nativeInset=" + currentInset +
                            " | playlistInset=" + targetInset +
                            " | delta=" + delta
                    )
                }
            }
        }
    }

    private fun restoreAlignedNativeTitles(browser: Browser) {
        val titles = alignedNativeTitleOwners.entries
            .filter { it.value.get() === browser.list }
            .map { it.key }
        titles.forEach { title ->
            alignedNativeTitles.remove(title)?.let { base ->
                title.translationX = base
            }
            alignedNativeTitleOwners.remove(title)
        }
    }

    private fun firstNativeContextMenu(list: ViewGroup): ImageView? =
        PlaylistFolderUiKit.firstBoundContextMenu(list)

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
        val base = native.actionModeBaseClass ?: return false
        val viewField = native.actionModeView ?: return false
        val fragment = native.smartFragmentClass ?: return false
        if (callback == null || !base.isInstance(callback)) return false
        val view = runCatching {
            viewField.get(callback)
        }.getOrNull() ?: return false
        return fragment.isInstance(view)
    }

    private fun selectedSmartPaths(callback: Any?): List<String> {
        val native = bindings ?: return emptyList()
        val base = native.actionModeBaseClass ?: return emptyList()
        val selectionField = native.actionModeSelection ?: return emptyList()
        val entriesField = native.selectionEntries ?: return emptyList()
        val entryModelField = native.selectionEntryModel ?: return emptyList()
        if (callback == null || !base.isInstance(callback)) return emptyList()
        val tracker = runCatching {
            selectionField.get(callback)
        }.getOrNull() ?: return emptyList()
        val entries = runCatching {
            entriesField.get(tracker) as? Iterable<*>
        }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (entry == null) null else {
                val model = runCatching {
                    entryModelField.get(entry)
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
        val modelRules = native.modelRules ?: run {
            Log.w(TAG, "SMART MOVE | native rule graph unavailable")
            return true
        }
        if (native.leafRuleClass == null ||
            native.leafRuleValue == null ||
            native.groupRuleClass == null ||
            native.groupRules == null
        ) {
            Log.w(TAG, "SMART MOVE | native link-rule mapping unavailable")
            return true
        }
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
                modelRules.get(model) as? Iterable<*>
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
        val groupRuleClass = native.groupRuleClass ?: return false
        val groupRules = native.groupRules ?: return false
        val leafRuleClass = native.leafRuleClass ?: return false
        val leafRuleValue = native.leafRuleValue ?: return false
        if (groupRuleClass.isInstance(rule)) {
            val children = runCatching {
                groupRules.get(rule) as? Iterable<*>
            }.getOrNull() ?: return false
            return children.any {
                ruleReferencesSelected(native, it, selected)
            }
        }
        if (!leafRuleClass.isInstance(rule)) return false
        val value = runCatching {
            leafRuleValue.get(rule) as? String
        }.getOrNull() ?: return false
        if (PlaylistBridgeReference.isBridgeValue(value) ||
            !PlaylistBridgePolicy
                .isNativeSmartPlaylistReference(value)
        ) return false
        val path = value.substringBefore('|', "").takeUnless(String::isBlank)
            ?: return false
        return canonicalPath(path) in selected
    }

    private fun installMoveChrome(browser: Browser) {
        if (browser.moveSources == null ||
            browser.moveChrome.actionMode != null
        ) return
        val installed = moveChromeUi.install(
            state = browser.moveChrome,
            list = browser.list,
            label = nativeMoveLabel(browser.list.context),
            isActive = {
                browsers[browser.list] === browser &&
                    browser.moveSources != null
            },
            addFab = { fab, size ->
                browser.host.addView(
                    fab,
                    ViewGroup.LayoutParams(size, size)
                )
            },
            positionFab = {
                positionMoveFab(browser)
            },
            onConfirm = {
                if (browser.moveSources != null) {
                    confirmMoveBrowser(browser)
                }
            },
            onCancel = {
                if (browser.moveSources != null &&
                    browsers[browser.list] === browser
                ) {
                    closeMoveBrowser(browser)
                }
            }
        )
        if (!installed) {
            Log.w(TAG, "SMART MOVE UI | shared native chrome unavailable")
            closeMoveBrowser(browser)
        }
    }

    private fun positionMoveFab(browser: Browser): Boolean {
        val fab = browser.moveChrome.fab ?: return false
        if (browser.moveSources == null ||
            !browser.list.isAttachedToWindow
        ) return false
        val visible = Rect()
        if (!browser.list.getGlobalVisibleRect(visible) ||
            visible.height() <= 0
        ) return false
        val safeBottom = minOf(
            visible.bottom,
            moveChromeUi.nativeMiniPlayerTop(browser.list) ?: visible.bottom
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
        moveChromeUi.close(browser.moveChrome)
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


    fun onOriginalFolderDeleteDialogShown(dialog: android.app.Dialog) {
        folderDeletion?.onNativeDialogShown(dialog)
    }

    private fun allSmartPlaylistPaths(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter {
                it.isFile && it.extension.equals("spl", ignoreCase = true)
            }
            .take(4096)
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .toList()
    }

    private fun showNativeFolderContextMenu(
        browser: Browser,
        folder: File,
        anchor: View
    ) {
        if (browser.moveSources != null || browser.otherLocations) return
        PlaylistFolderUiKit.showDeleteOnlyPopup(
            anchor = anchor,
            menuResourceName = "menu_gm_context_smart"
        ) {
            requestNativeFolderDeletion(browser, folder)
        }
    }

    private fun requestNativeFolderDeletion(
        browser: Browser,
        folder: File
    ): Boolean {
        val deletion = folderDeletion ?: return false
        val plan = FolderDeletePolicy.prepare(
            browser.root, folder, allSmartPlaylistPaths(browser.root)
        )
        if (plan == null) {
            Log.w(
                TAG,
                "SMART FOLDER DELETE | blocked: unsafe path or non-Smart files"
            )
            showMoveError(browser.list.context)
            return false
        }
        val targets = if (plan.nativePlaylistFiles.isNotEmpty()) {
            plan.nativePlaylistFiles
        } else {
            listOf(plan.folder)
        }
        if (!deletion.confirmNativeDeletion(
                browser.list.context, targets, plan.folder
            )
        ) return false
        val pending = PendingFolderDeletion(plan)
        pendingFolderDeletes.add(pending)
        waitForNativeFolderDeletion(pending)
        return true
    }

    private fun waitForNativeFolderDeletion(
        pending: PendingFolderDeletion
    ) {
        if (!pendingFolderDeletes.contains(pending)) return
        val plan = pending.plan
        val filesGone = FolderDeletePolicy.nativeRemovalComplete(
            plan, allSmartPlaylistPaths(plan.root)
        )
        if (filesGone && plan.nativePlaylistFiles.isNotEmpty()) {
            if (FolderDeletePolicy.removeEmptyDirectories(plan)) {
                pendingFolderDeletes.remove(pending)
                refreshAfterFolderDeletion(plan)
                Log.i(
                    TAG,
                    "SMART FOLDER DELETE | native files removed; folders pruned"
                )
                return
            }
        }
        if (plan.nativePlaylistFiles.isEmpty() && !plan.folder.exists()) {
            pendingFolderDeletes.remove(pending)
            refreshAfterFolderDeletion(plan)
            Log.i(TAG, "SMART FOLDER DELETE | native empty-folder delete complete")
            return
        }
        if (--pending.checksRemaining <= 0) {
            pendingFolderDeletes.remove(pending)
            Log.i(
                TAG,
                "SMART FOLDER DELETE | canceled or not completed by native dialog"
            )
            return
        }
        main.postDelayed({
            if (pendingFolderDeletes.contains(pending)) {
                waitForNativeFolderDeletion(pending)
            }
        }, 600L)
    }

    private fun refreshAfterFolderDeletion(plan: FolderDeletePolicy.Plan) {
        browsers.values.toList().forEach { browser ->
            if (!browser.list.isAttachedToWindow ||
                !sameFile(browser.root, plan.root)
            ) return@forEach
            val deletedPrefix =
                canonicalPath(plan.folder.path) + File.separator
            if (!browser.current.exists() ||
                canonicalPath(browser.current.path).startsWith(deletedPrefix)
            ) {
                browser.current = browser.root
                browser.otherLocations = false
            }
            startObserver(browser)
            refresh(browser)
        }
    }

    private fun installNewFolderMenu(
        menu: Menu,
        context: android.content.Context
    ) {
        val existing = menu.findItem(newFolderMenuId)
        if (existing != null) {
            val browser = currentBrowser()
            val visible =
                enabled &&
                    (browser == null ||
                        (!browser.otherLocations &&
                            browser.moveSources == null))
            if (existing.isVisible != visible) {
                existing.isVisible = visible
            }
            return
        }
        val rowColor = currentBrowser()?.style?.textColor
        val item = PlaylistFolderUiKit.installNativeFolderAddMenu(
            menu = menu,
            context = context,
            itemId = newFolderMenuId,
            rowTextColor = rowColor,
            preferRememberedAddTitle = true
        ) {
            requestFolderCreation()
        }
        if (item != null) {
            Log.i(
                TAG,
                "SMART FOLDERS MENU | native Add label/folder icon installed"
            )
        }
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
            val context = currentBrowser()?.list?.context
                ?: menuContext(menu, null)
            if (enabled && bindings != null &&
                menu.findItem(newFolderMenuId) == null &&
                context != null
            ) {
                // GMMP may rebuild/clear the same Menu after inflation.
                // Reinstall only after the native Smart bindings are usable.
                installNewFolderMenu(menu, context)
            }
            menu.findItem(newFolderMenuId)?.let { item ->
                val visible =
                    enabled && bindings != null &&
                        (browser == null ||
                            (!browser.otherLocations &&
                                browser.moveSources == null))
                if (item.isVisible != visible) {
                    item.isVisible = visible
                }
            }
        }
    }

    private fun restorePendingNativeList(list: ViewGroup) {
        val alpha = pendingOriginalAlphas.remove(list) ?: return
        list.alpha = alpha
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
        browser.folderGestureDownEvent?.recycle()
        browser.folderGestureDownEvent = null
        browser.selectionAccentSubscription?.dispose()
        browser.selectionAccentSubscription = null
        clearSmartSelection(browser)
        endMoveChrome(browser)
        browser.list.alpha = browser.originalAlpha
        browser.list.setPadding(
            browser.originalPaddingLeft,
            browser.originalPaddingTop,
            browser.originalPaddingRight,
            browser.originalPaddingBottom
        )
        browser.list.clipToPadding = browser.originalClipToPadding
        restoreAlignedNativeTitles(browser)
        browser.list.removeOnAttachStateChangeListener(browser.detachListener)
        browser.list.removeOnLayoutChangeListener(browser.layoutListener)
        browser.list.parent?.requestDisallowInterceptTouchEvent(false)
        resetFolderOverscroll(browser)
        browser.originalNativeNestedScrollingEnabled?.let {
            setNativeNestedScrollingEnabled(browser.list, it)
        }
        if (browser.list.viewTreeObserver.isAlive) {
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

    private fun nativeNestedScrollingEnabled(list: ViewGroup): Boolean? =
        runCatching {
            list.javaClass.getMethod("isNestedScrollingEnabled")
                .invoke(list) as? Boolean
        }.getOrNull()

    private fun setNativeNestedScrollingEnabled(
        list: ViewGroup,
        enabled: Boolean
    ): Boolean = runCatching {
        list.javaClass.getMethod(
            "setNestedScrollingEnabled",
            java.lang.Boolean.TYPE
        ).invoke(list, enabled)
        true
    }.onFailure {
        Log.w(
            TAG,
            "SMART FOLDERS NESTED | host method unavailable",
            it
        )
    }.getOrDefault(false)

    private fun syncNativeVerticalOverscroll(browser: Browser) {
        if (android.os.Build.VERSION.SDK_INT < 31 ||
            browsers[browser.list] !== browser
        ) {
            resetFolderOverscroll(browser)
            return
        }
        val list = browser.list
        val atTop = nativeCanScrollVertically(list, -1) != true
        val atBottom = nativeCanScrollVertically(list, 1) != true
        val topDistance = if (atTop) {
            nativeEdgeDistance(list, "mTopGlow")
        } else 0f
        val bottomDistance = if (atBottom) {
            nativeEdgeDistance(list, "mBottomGlow")
        } else 0f

        browser.folderViewport.mirrorNativeEdges(
            top = topDistance,
            bottom = bottomDistance,
            viewportWidth = list.width,
            viewportHeight = browser.folderViewport.height
        )

        val edge = when {
            bottomDistance > topDistance && bottomDistance > 0f -> "bottom"
            topDistance > 0f -> "top"
            else -> null
        }
        if (edge == null) {
            browser.overscrollReported = false
            return
        }
        if (!browser.overscrollReported) {
            browser.overscrollReported = true
            Log.i(
                TAG,
                "SMART FOLDERS OVERSCROLL | edge=" + edge +
                    " | nativeDistance=" +
                    (if (edge == "bottom") bottomDistance else topDistance) +
                    " | renderer=android.widget.EdgeEffect" +
                    " | viewport=" + list.width + "x" +
                    browser.folderViewport.height
            )
        }
    }

    private fun resetFolderOverscroll(browser: Browser) {
        browser.folderViewport.mirrorNativeEdges(
            top = 0f,
            bottom = 0f,
            viewportWidth = browser.list.width,
            viewportHeight = browser.folderViewport.height
        )
        browser.overscrollReported = false
    }

    private fun nativeEdgeDistance(
        list: ViewGroup,
        preferredFieldName: String
    ): Float {
        val fields = nativeEdgeEffectFields(list.javaClass)
        val named = fields.firstOrNull { it.name == preferredFieldName }
        if (named != null) {
            return edgeEffectDistance(named, list)
        }
        // R8 may rename RecyclerView's private glow fields. This method is
        // called only while the requested vertical boundary is reached; use
        // the active EdgeEffect with the largest distance as a fail-open
        // fallback. On the tested AndroidX build the standard names remain.
        return fields.maxOfOrNull { edgeEffectDistance(it, list) } ?: 0f
    }

    private fun edgeEffectDistance(field: Field, list: ViewGroup): Float =
        runCatching {
            field.isAccessible = true
            val glow = field.get(list) ?: return@runCatching 0f
            (glow.javaClass.getMethod("getDistance")
                .invoke(glow) as? Number)?.toFloat() ?: 0f
        }.getOrDefault(0f)

    private fun nativeEdgeEffectFields(type: Class<*>): List<Field> {
        val result = arrayListOf<Field>()
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredFields.filterTo(result) {
                it.type.name == "android.widget.EdgeEffect"
            }
            cursor = cursor.superclass
        }
        return result
    }

    private fun syncFolderRowsScrollByDelta(
        browser: Browser,
        dy: Int
    ) {
        val list = browser.list
        if (!list.isAttachedToWindow || browsers[list] !== browser ||
            !browser.folderScrollSyncReady
        ) return
        val folderHeight = browser.folderBand.height.coerceAtLeast(0)
        val distance = SmartFolderHeaderScrollPolicy.scrollDistanceAfterDelta(
            currentDistance = browser.nativeScrollDistancePx,
            dy = dy
        )
        val offset = SmartFolderHeaderScrollPolicy.folderTranslation(
            folderHeight = folderHeight,
            scrollDistance = distance
        )
        if (!browser.scrollDeltaReported) {
            browser.scrollDeltaReported = true
            Log.i(
                TAG,
                "SMART FOLDERS SCROLL | firstConsumedDy=" + dy +
                    " | folderHeight=" + folderHeight +
                    " | distance=" + distance +
                    " | nestedScroll=false"
            )
        }
        browser.nativeScrollDistancePx = distance
        if (browser.folderBand.translationY == -offset.toFloat()) return
        browser.folderBand.translationY = -offset.toFloat()
    }

    private fun prepareFolderScrollForNavigation(browser: Browser) {
        browser.folderScrollSyncReady = false
        browser.pendingFolderScrollReset = true
        browser.nativeContentReady = false
        browser.projectionPrepared = false
        browser.projectionFailOpenAllowed = false
        browser.list.alpha = 0f
        browser.folderBand.translationY = 0f
        resetFolderOverscroll(browser)
        browser.nativeScrollDistancePx = 0
        browser.scrollDeltaReported = false
        browser.overscrollReported = false
    }

    private fun settleFolderScrollAfterRefresh(
        browser: Browser,
        generation: Long
    ) {
        if (!browser.pendingFolderScrollReset) {
            browser.folderScrollSyncReady = true
            awaitNativeProjectionAndReveal(browser, generation)
            return
        }
        browser.folderScrollSyncReady = false
        browser.folderBand.translationY = 0f
        resetFolderOverscroll(browser)
        browser.nativeScrollDistancePx = 0
        browser.scrollDeltaReported = false
        browser.overscrollReported = false
        browser.list.postOnAnimation {
            if (browsers[browser.list] !== browser ||
                browser.generation != generation ||
                !browser.list.isAttachedToWindow
            ) return@postOnAnimation
            runCatching {
                browser.list.javaClass.getMethod(
                    "scrollToPosition", Integer.TYPE
                ).invoke(browser.list, 0)
            }
            browser.list.postOnAnimation {
                if (browsers[browser.list] !== browser ||
                    browser.generation != generation ||
                    !browser.list.isAttachedToWindow
                ) return@postOnAnimation
                browser.pendingFolderScrollReset = false
                browser.folderScrollSyncReady = true
                browser.folderBand.translationY = 0f
                resetFolderOverscroll(browser)
                browser.nativeScrollDistancePx = 0
                browser.scrollDeltaReported = false
                browser.overscrollReported = false
                awaitNativeProjectionAndReveal(browser, generation)
            }
        }
    }

    private fun awaitNativeProjectionAndReveal(
        browser: Browser,
        generation: Long,
        attempt: Int = 0
    ) {
        browser.list.postOnAnimation {
            if (browsers[browser.list] !== browser ||
                browser.generation != generation ||
                !browser.list.isAttachedToWindow
            ) return@postOnAnimation

            val ready = nativeProjectionReady(browser)
            if (!ready && attempt < MAX_PROJECTION_REVEAL_RETRIES) {
                awaitNativeProjectionAndReveal(
                    browser,
                    generation,
                    attempt + 1
                )
                return@postOnAnimation
            }
            if (!ready) {
                browser.projectionFailOpenAllowed = true
                Log.w(
                    TAG,
                    "SMART FOLDERS PROJECTION WAIT | fail-open after " +
                        MAX_PROJECTION_REVEAL_RETRIES + " frames" +
                        " | expected=" + browser.nativeOrder.size +
                        " | actual=" + (nativeAdapterItemCount(browser) ?: -1)
                )
                revealInitialContent(browser, allowProjectionMismatch = true)
                return@postOnAnimation
            }
            // A completed projection may belong to the list fragment behind
            // an open Smart-Playlist detail. Keep it masked there; the
            // pre-draw listener above reveals it atomically when it becomes
            // the front fragment again.
            if (isFrontFragmentView(browser.list)) {
                revealInitialContent(browser)
            }
        }
    }

    private fun nativeProjectionReady(browser: Browser): Boolean {
        if (!browser.projectionPrepared) return false
        val expected = browser.nativeOrder.toSet()
        return SmartNativeSubmissionPolicy.projectionReady(
            expectedCount = browser.nativeOrder.size,
            adapterCount = nativeAdapterItemCount(browser),
            visiblePaths = visibleNativeModelPaths(browser),
            expectedPaths = expected
        )
    }

    private fun nativeAdapterItemCount(browser: Browser): Int? = runCatching {
        browser.nativeAdapter.javaClass
            .getMethod("getItemCount")
            .invoke(browser.nativeAdapter) as? Int
    }.getOrNull()

    private fun visibleNativeModelPaths(browser: Browser): List<String> {
        val native = bindings ?: return emptyList()
        val holderGetter = runCatching {
            browser.list.javaClass.getMethod(
                "getChildViewHolder",
                View::class.java
            )
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until browser.list.childCount) {
                val row = browser.list.getChildAt(index) ?: continue
                val holder = runCatching {
                    holderGetter.invoke(browser.list, row)
                }.getOrNull() ?: continue
                if (!native.holderClass.isInstance(holder)) continue
                val model = runCatching {
                    native.holderModel.get(holder)
                }.getOrNull() ?: continue
                modelPath(model)?.let(::add)
            }
        }
    }

    /**
     * A ViewPager decor overlay is fixed to the pager rather than to one page.
     * Toggle only its visibility/coordinates on tab changes; never rebuild or
     * reload the Smart snapshot from this per-frame check.
     */
    private fun syncPagerOverlayVisibility(browser: Browser) {
        if (!PlaylistNavigationSurfaceHost.isPagerHost(browser.host)) return
        val list = browser.list
        if (!list.isAttachedToWindow) return

        val rect = Rect()
        val visible = browser.initialHeaderReady &&
            browser.nativeContentReady &&
            isFrontFragmentView(list) &&
            list.isShown &&
            list.getGlobalVisibleRect(rect) &&
            rect.width() > dp(list, 30) &&
            rect.height() > dp(list, 30)
        val next = if (visible) View.VISIBLE else View.GONE
        if (browser.overlay.visibility == next) return

        if (visible) {
            val listLocation = IntArray(2)
            val hostLocation = IntArray(2)
            list.getLocationOnScreen(listLocation)
            browser.host.getLocationOnScreen(hostLocation)
            browser.overlay.x =
                (listLocation[0] - hostLocation[0]).toFloat()
            browser.overlay.y =
                (listLocation[1] - hostLocation[1]).toFloat()
        }
        browser.overlay.visibility = next
        updateMenus()
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
        val targetX = (listLocation[0] - hostLocation[0]).toFloat()
        val targetY = (listLocation[1] - hostLocation[1]).toFloat()
        if (kotlin.math.abs(browser.overlay.x - targetX) >= 0.5f) {
            browser.overlay.x = targetX
        }
        if (kotlin.math.abs(browser.overlay.y - targetY) >= 0.5f) {
            browser.overlay.y = targetY
        }
        val params = browser.overlay.layoutParams
        if (params.width != list.width ||
            params.height != list.height
        ) {
            params.width = list.width
            params.height = list.height
            browser.overlay.layoutParams = params
        }
        val rect = Rect()
        val visible = isFrontFragmentView(list) &&
            list.isShown &&
            list.getGlobalVisibleRect(rect) &&
            rect.width() > dp(list, 30) &&
            rect.height() > dp(list, 30)
        val nextVisibility = if (!browser.initialHeaderReady ||
            !browser.nativeContentReady
        ) {
            View.INVISIBLE
        } else if (visible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (browser.overlay.visibility != nextVisibility) {
            browser.overlay.visibility = nextVisibility
        }
        if (visible) {
            val nextStyle = sampleNativeStyle(list)
            if (nextStyle != null) browser.style = nextStyle
        }
        browser.overlay.post {
            if (browsers[list] === browser) {
                updateNativeInset(browser)
                alignVisibleNativeTitles(browser)
                syncVisibleSmartRowInteractions(browser)
                if (browser.moveSources != null) {
                    positionMoveFab(browser)
                }
            }
        }
    }

    private fun revealInitialContent(
        browser: Browser,
        allowProjectionMismatch: Boolean = false
    ) {
        if (browser.nativeContentReady || browsers[browser.list] !== browser) {
            return
        }
        if (!allowProjectionMismatch && !nativeProjectionReady(browser)) {
            return
        }
        if (!isFrontFragmentView(browser.list)) {
            return
        }

        // Commit the complete visible geometry before restoring the native
        // RecyclerView alpha. This prevents one frame with root/no-header
        // padding followed by a visible vertical shift.
        browser.initialHeaderReady = true
        browser.nativeContentReady = true
        browser.projectionFailOpenAllowed = false
        positionOverlay(browser)
        updateNativeInset(browser)
        browser.list.alpha = browser.originalAlpha
        alignVisibleNativeTitles(browser)
        syncVisibleSmartRowInteractions(browser)
    }

    private fun isFrontFragmentView(list: ViewGroup): Boolean {
        PlaylistNavigationSurfaceHost.isPagerPageFront(list)?.let {
            return it
        }
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
        val native = bindings ?: return runtimeRoot
        val storagePath = native.storagePath
        val storageLocation = native.smartStorageLocation
        if (storagePath != null && storageLocation != null) {
            val path = runCatching {
                storagePath.invoke(null, storageLocation) as? String
            }.getOrNull()?.takeUnless(String::isBlank)
            val legacy = path?.let {
                runCatching { File(it).canonicalFile }.getOrNull()
            }?.takeIf { it.isDirectory }
            if (legacy != null) return legacy
        }
        return runtimeRoot?.takeIf { it.isDirectory }
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
        val host = PlaylistNavigationSurfaceHost.resolve(list)
        if (host != null && BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "SMART FOLDERS HOST | class=" + host.javaClass.name +
                    " | id=" + resourceName(host) +
                    " | pagerDecor=" +
                    PlaylistNavigationSurfaceHost.isPagerHost(host)
            )
        }
        return host
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
        PlaylistBridgePolicy.safePath(
            runCatching { file.canonicalPath }.getOrDefault(file.path)
        )

    private fun dp(view: View, value: Int): Int =
        (view.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun dp(context: android.content.Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + 0.5f).toInt()


}
