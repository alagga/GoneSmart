package io.github.alagga.gonesmart

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Experimental GMMP 4.2.0 integration, enabled only in GoneSmart debug builds.
 *
 * Uses native jo3 view holders and xn3 models, and dispatches additions
 * through the native io3.r(Context, ie0) handler. Never writes .m3u files.
 *
 * One picker session owns its state. All methods run on GMMP's UI thread.
 */
internal class PlaylistMultiSelectController {

    companion object {
        private const val TAG = "GoneSmartPlaylist"
        // GoneSmart lilac is the default even while GMMP's Aesthetic
        // palette changes. Only switch to an alternate live GMMP palette
        // color if lilac becomes too similar to the actual FAB background.
        private const val FALLBACK_LILAC = 0xFFA39AFF.toInt()
        private const val OLD_CHECK_TAG = "gonesmart_playlist_check_v1"
    }

    private class Session(val fragment: Any) {
        var fab: View? = null
        var list: ViewGroup? = null
        var nativeHandler: Any? = null
        val selectedPaths = linkedSetOf<String>()
        val selectedModels = linkedMapOf<String, Any>()
        var dispatchHolder: Any? = null
        var fabIconSaved = false
        var sparkle: PlayerAutoDjBadgeController.SparkleBadgeDrawable? = null
        var sparkleColor: Int? = null
        // A foreground overlay preserves GMMP's native row background and
        // ripple. Recycled RecyclerView rows get their overlays re-keyed by
        // the CURRENT bound playlist path on every bind and layout.
        val appliedRowOverlays = WeakHashMap<View, ColorDrawable>()
        var lastLoggedAccent: Int? = null
        var livePrimary: Int? = null
        var liveAccent: Int? = null
        var liveFabAccent: Int? = null
        var fabPaletteSubscribed = false
        var nativeTheme: Any? = null
        var nativeAccentAttr: Int = 0
        var nativePrimaryAttr: Int = 0
        var themePreferences: SharedPreferences? = null
        var themePreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        val themeDisposables = mutableListOf<Any>()
        // Rx observers must have strong references while the picker lives.
        val themeObservers = mutableListOf<Any>()
        var themeInitialized = false
        var selectionBar: ActionMode? = null
        var selectionBarUnavailableLogged = false
        var barView: View? = null
        var originalBarBackground: Drawable? = null
        var barBackgroundSaved = false
        var lastBarColor: Int? = null
        var originalIcon: Drawable? = null
        var originalTint: ColorStateList? = null
        var originalDescription: CharSequence? = null
        var submitting = false
    }

    private var active: Session? = null
    private val eventReporter = GoneSmartRuntimeReporter()

    @Volatile
    private var enabled = false

    /**
     * UI feature is separate from the Smart DJ recommendation switch.
     * Enabling it does not affect regular GMMP playback or Auto-DJ.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value && active != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                reset()
            } else {
                mainHandler.post {
                    if (!enabled) reset()
                }
            }
        }
    }


    /*
     * Native io3.r() publishes a j83 "close the add-to-playlist picker"
     * event after EACH successful destination. The host activity handles
     * every j83 by navigating back. A multi-add must publish it exactly
     * once, even though each playlist still uses the native add handler.
     *
     * io3.r creates a jd(mode=4) callback for every destination. Tag only
     * callbacks constructed during our reflective native dispatch; wrap
     * their execution with a thread-local scope and suppress duplicate j83
     * emissions from that scope. Ordinary GMMP playlist adds and unrelated
     * navigation remain completely untouched.
     *
     * The callback registry outlives the picker session because the FIRST
     * j83 can detach its fragment before later Rx callbacks finish.
     */
    private class NativeNavigationBatch(
        val sourceCount: Int,
        val context: Context
    ) {
        var closeEventSent = false
        var successfulDestinations = 0
        var callbacksFinished = 0
        var dispatchFinished = false
        var acceptedDestinations = 0
        var nativeToastsSuppressed = 0
        var summaryScheduled = false
    }

    private val navigationLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nativeCallbacks = WeakHashMap<Any, NativeNavigationBatch>()
    private val constructingNativeCallback = ThreadLocal<NativeNavigationBatch?>()
    private val runningNativeCallback = ThreadLocal<NativeNavigationBatch?>()

    fun onNativeResultCallbackConstructed(callback: Any?, mode: Any?) {
        if (callback == null || (mode as? Number)?.toInt() != 4) return
        val batch = constructingNativeCallback.get() ?: return
        synchronized(navigationLock) {
            nativeCallbacks[callback] = batch
        }
        Log.i(TAG, "MULTI NAV | native playlist result callback registered")
    }

    fun aroundNativeResultCallback(
        callback: Any?,
        proceed: () -> Any?
    ): Any? {
        val batch = synchronized(navigationLock) {
            nativeCallbacks.remove(callback)
        } ?: return proceed()

        val previous = runningNativeCallback.get()
        runningNativeCallback.set(batch)
        try {
            return proceed()
        } finally {
            if (previous == null) {
                runningNativeCallback.remove()
            } else {
                runningNativeCallback.set(previous)
            }
            synchronized(navigationLock) {
                batch.callbacksFinished++
            }
            maybeShowNativeSummary(batch)
        }
    }

    fun shouldSuppressNativeCloseEvent(event: Any?): Boolean {
        if (event?.javaClass?.name != "j83") return false
        val batch = runningNativeCallback.get() ?: return false

        val suppress = synchronized(navigationLock) {
            batch.successfulDestinations++
            if (!batch.closeEventSent) {
                batch.closeEventSent = true
                Log.i(TAG, "MULTI NAV | first native close event allowed")
                false
            } else {
                Log.i(TAG, "MULTI NAV | duplicate native close event suppressed")
                true
            }
        }
        maybeShowNativeSummary(batch)
        return suppress
    }

    /**
     * Android renders Toast.makeText with GMMP's own icon and system UI,
     * matching the native screenshot. Suppress Toast.show only while
     * executing a native jd(mode=4) completion from OUR multi-add batch.
     * Ordinary one-playlist operations and unrelated GMMP Toasts are left
     * untouched. The aggregate is posted after all accepted callbacks.
     */
    fun shouldSuppressNativeResultToast(): Boolean {
        // GMMP versions may create their native success Toast either
        // synchronously inside io3.r() or in its jd(mode=4) completion.
        // Both scopes belong exclusively to this multi-add batch.
        val batch = runningNativeCallback.get()
            ?: constructingNativeCallback.get()
            ?: return false
        synchronized(navigationLock) {
            batch.nativeToastsSuppressed++
        }
        Log.i(TAG, "MULTI TOAST | individual GMMP result hidden")
        return true
    }

    private fun markNativeDispatchFinished(
        batch: NativeNavigationBatch?,
        accepted: Int
    ) {
        if (batch == null) return
        synchronized(navigationLock) {
            batch.acceptedDestinations = accepted
            batch.dispatchFinished = true
        }
        maybeShowNativeSummary(batch)
    }

    private fun maybeShowNativeSummary(batch: NativeNavigationBatch) {
        // Distinguish "still waiting" from "every callback finished, but
        // GMMP confirmed zero successful destinations". Emit one final
        // event for either outcome, never intermediate optimistic results.
        val completed = synchronized(navigationLock) {
            if (batch.summaryScheduled ||
                !batch.dispatchFinished ||
                batch.acceptedDestinations <= 0 ||
                batch.callbacksFinished < batch.acceptedDestinations
            ) {
                null
            } else {
                batch.summaryScheduled = true
                batch.successfulDestinations.coerceAtMost(
                    batch.acceptedDestinations
                )
            }
        } ?: return
        if (completed == 0) {
            Log.w(TAG, "MULTI RESULT | no successful destinations confirmed")
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_PLAYLISTS,
                "Adding songs to multiple playlists was not confirmed."
            )
            return
        }
        val successful = completed
        eventReporter.reportEvent(
            GoneSmartRuntimeContract.CATEGORY_PLAYLISTS,
            "Added ${batch.sourceCount} " +
                "song${if (batch.sourceCount == 1) "" else "s"} to " +
                "$successful playlist${if (successful == 1) "" else "s"}." +
                if (successful < batch.acceptedDestinations) {
                    " (Partial: ${batch.acceptedDestinations} accepted.)"
                } else ""
        )

        // Reuse GMMP's actual localized toast and its own playlist nouns.
        // GMMP's add_to_playlist_toast contains only ONE placeholder for
        // the number of files, not a second one for destination count.
        // Appending the native plural noun is language-neutral and avoids
        // maintaining translated templates with differing word order.
        val files = batch.sourceCount
        val context = batch.context
        val nativeToast = gmmpString(
            context,
            "add_to_playlist_toast",
            files
        )
        val playlistLabel = gmmpString(
            context,
            if (successful == 1) "playlist" else "playlists"
        )

        val message = if (nativeToast != null) {
            if (playlistLabel != null) {
                "$nativeToast ($successful $playlistLabel)"
            } else {
                nativeToast
            }
        } else {
            val fileLabel = gmmpString(
                context,
                if (files == 1) "file" else "files"
            ).orEmpty()
            "$files $fileLabel · $successful ${playlistLabel.orEmpty()}"
        }

        // Post outside the jd callback's thread-local scope. Otherwise our
        // own summary Toast would be swallowed by the native Toast hook.
        mainHandler.post {
            Toast.makeText(batch.context, message, Toast.LENGTH_SHORT).show()
            Log.i(
                TAG,
                "MULTI TOAST | combined=$message" +
                    " | nativeSuppressed=${batch.nativeToastsSuppressed}"
            )
        }
    }

    fun beginPicker(fragment: Any?) {
        if (!enabled || fragment == null) return
        if (active?.fragment === fragment) return

        reset()
        active = Session(fragment)
        Log.i(TAG, "MULTI PICKER | new session")
    }

    fun onFabFound(fab: View) {
        if (!enabled) return
        val session = active ?: return
        if (resourceName(fab) != "playlistFab") return
        if (session.fab === fab) return

        session.fab = fab
        Log.i(TAG, "MULTI FAB | attached")
        if (session.nativeTheme != null) {
            observeNativeFabColor(session)
        }
        if (session.selectedPaths.isNotEmpty()) {
            enableFab(session)
        }
    }

    fun onListFound(list: View) {
        if (!enabled) return
        val session = active ?: return
        if (resourceName(list) != "playlistListRecyclerView") return
        val group = list as? ViewGroup ?: return
        if (session.list === group) return

        session.list = group
        group.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    if (active === session) {
                        Log.i(TAG, "MULTI PICKER | view detached; clearing session")
                        reset()
                    }
                }
            }
        )

        val observer = group.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnGlobalLayoutListener {
                if (active === session && session.selectedPaths.isNotEmpty()) {
                    refreshVisibleRows(session)
                    pinFab(session)
                }
            }
            observer.addOnScrollChangedListener {
                if (active === session && session.selectedPaths.isNotEmpty()) {
                    // RecyclerView reuses row views; refresh AFTER the native
                    // adapter has rebound them to their new playlist models.
                    group.post {
                        if (active === session) {
                            refreshVisibleRows(session)
                            pinFab(session)
                        }
                    }
                }
            }
        }

        observeNativeGmmpPalette(session)
        Log.i(TAG, "MULTI LIST | attached")
    }

    fun onNativeHandler(handler: Any?) {
        if (!enabled) return
        val session = active ?: return
        if (handler?.javaClass?.name != "io3") return

        session.nativeHandler = handler
        Log.i(
            TAG,
            "MULTI NATIVE HANDLER | io3 captured | sourceCount=" +
                sourceCount(handler)
        )
    }

    fun onLongClick(view: View?): Boolean {
        if (!enabled) return false
        Log.i(
            TAG,
            "MULTI LONG | received | view=" +
                (view?.javaClass?.name ?: "null") +
                " | parent=" +
                (view?.parent?.javaClass?.name ?: "null")
        )

        val session = visibleSession(trace = true) ?: return false
        val item = getPlaylistItem(session, view, trace = true)
            ?: return false
        val path = playlistPath(item, trace = true) ?: return false
        val model = holderModel(item) ?: return false

        session.dispatchHolder = item
        session.selectedModels[path] = model
        // Paths, never reused row Views or adapter positions, own selection.

        if (session.selectedPaths.add(path)) {
            Log.i(TAG, "MULTI SELECT | added=$path")
        }

        enableFab(session)
        updateSelectionBar(session)
        refreshVisibleRows(session)
        return true
    }

    fun onClick(view: View?): Boolean {
        if (!enabled) return false
        val session = visibleSession() ?: return false

        if (view === session.fab && session.selectedPaths.isNotEmpty()) {
            confirm(session)
            return true
        }

        if (session.selectedPaths.isEmpty()) return false

        val item = getPlaylistItem(session, view) ?: return false
        val path = playlistPath(item) ?: return false
        val model = holderModel(item) ?: return false

        session.dispatchHolder = item
        if (!session.selectedPaths.add(path)) {
            session.selectedPaths.remove(path)
            session.selectedModels.remove(path)
            Log.i(TAG, "MULTI SELECT | removed=$path")
        } else {
            session.selectedModels[path] = model
            Log.i(TAG, "MULTI SELECT | added=$path")
        }

        if (session.selectedPaths.isEmpty()) {
            exitSelection(session)
        } else {
            enableFab(session)
            updateSelectionBar(session)
            refreshVisibleRows(session)
        }

        return true
    }

    fun consumeBack(): Boolean {
        if (!enabled) return false
        val session = visibleSession() ?: return false
        if (session.selectedPaths.isEmpty()) return false

        Log.i(TAG, "MULTI BACK | selection cancelled")
        exitSelection(session)
        return true
    }

    fun shouldBlockFabHide(receiver: Any?): Boolean {
        if (!enabled) return false
        val session = active ?: return false
        return session.selectedPaths.isNotEmpty() &&
            receiver === session.fab &&
            session.list?.isAttachedToWindow == true
    }

    private fun visibleSession(trace: Boolean = false): Session? {
        val session = active
        if (session == null) {
            if (trace) Log.w(TAG, "MULTI LONG STOP | no active picker")
            return null
        }

        val list = session.list
        val fab = session.fab
        if (list == null || fab == null) {
            if (trace) {
                Log.w(
                    TAG,
                    "MULTI LONG STOP | missing views | list=${list != null}" +
                        " | fab=${fab != null}"
                )
            }
            return null
        }

        if (!list.isAttachedToWindow || !fab.isAttachedToWindow) {
            if (trace) {
                Log.w(
                    TAG,
                    "MULTI LONG STOP | detached | list=${list.isAttachedToWindow}" +
                        " | fab=${fab.isAttachedToWindow}"
                )
            }
            return null
        }

        if (list.rootView !== fab.rootView) {
            if (trace) Log.w(TAG, "MULTI LONG STOP | list/FAB roots differ")
            return null
        }

        if (trace) Log.i(TAG, "MULTI LONG | visible session OK")
        return session
    }

    private fun getPlaylistItem(
        session: Session,
        view: View?,
        trace: Boolean = false
    ): Any? {
        val list = session.list ?: return null
        if (view !is FrameLayout || view.parent !== list) {
            if (trace) Log.w(TAG, "MULTI LONG STOP | not a picker row")
            return null
        }

        // bw.i0() returns t23 groups, not jo3 row holders. Ask the
        // RecyclerView for the real, currently bound view holder.
        val holder = runCatching {
            list.javaClass
                .getMethod("getChildViewHolder", View::class.java)
                .invoke(list, view)
        }.onFailure { error ->
            if (trace) Log.w(TAG, "MULTI LONG STOP | holder lookup failed", error)
        }.getOrNull()

        if (trace) {
            Log.i(
                TAG,
                "MULTI LONG | rowHolder=${holder?.javaClass?.name ?: "null"}"
            )
        }
        if (holder?.javaClass?.name != "jo3") {
            if (trace) Log.w(TAG, "MULTI LONG STOP | expected jo3 holder")
            return null
        }
        return holder
    }

    private fun holderModel(holder: Any): Any? =
        runCatching {
            holder.javaClass.getDeclaredField("A").apply {
                isAccessible = true
            }.get(holder)?.takeIf { it.javaClass.name == "xn3" }
        }.getOrNull()

    private fun modelPath(model: Any): String? =
        runCatching {
            model.javaClass.getDeclaredField("q").apply {
                isAccessible = true
            }.get(model) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun adapterItems(
        list: ViewGroup,
        trace: Boolean = false
    ): List<Any> {
        return runCatching {
            val adapter = list.javaClass
                .getMethod("getAdapter").invoke(list)
                ?: return emptyList()
            val groups = adapter.javaClass
                .getMethod("i0").invoke(adapter) as? List<*>
                ?: return emptyList()
            val models = groups.flatMap { group ->
                if (group?.javaClass?.name != "t23") {
                    emptyList()
                } else {
                    val entries = group.javaClass
                        .getMethod("r").invoke(group) as? List<*>
                    entries.orEmpty().filterNotNull()
                        .filter { it.javaClass.name == "xn3" }
                }
            }
            if (trace) {
                Log.i(
                    TAG,
                    "MULTI LONG | adapterGroups=${groups.size}" +
                        " | playlistModels=${models.size}" +
                        " | firstGroupEntries=" +
                        groups.firstOrNull()?.let { group ->
                            runCatching {
                                val entries = group.javaClass
                                    .getMethod("r").invoke(group) as? List<*>
                                entries?.take(3)?.map { it?.javaClass?.name }
                            }.getOrNull()
                        }
                )
            }
            models
        }.onFailure { error ->
            if (trace) Log.w(TAG, "MULTI LONG | adapter model lookup failed", error)
        }.getOrDefault(emptyList())
    }

    private fun playlistPath(
        item: Any,
        trace: Boolean = false
    ): String? {
        val model = holderModel(item)
        if (model == null) {
            if (trace) Log.w(TAG, "MULTI LONG STOP | jo3.A model unavailable")
            return null
        }
        val path = modelPath(model)
        if (trace) {
            Log.i(
                TAG,
                "MULTI LONG | model=${model.javaClass.name}" +
                    " | pathAvailable=${path != null}"
            )
        }
        return path
    }

    /**
     * An Android primary contextual action bar lives in the picker window,
     * mirroring GMMP's own queue-selection UI: back arrow plus selection
     * count. Starting it on the RecyclerView (not the host Activity) avoids
     * replacing an unrelated ActionMode under a dialog.
     *
     * If this GMMP screen does not support ActionMode, selection and FAB
     * continue normally; no native toolbar is replaced.
     */
    /**
     * GMMP 4.2.0 bundles Aesthetic under its obfuscated runtime name
     * com.afollestad.aesthetic.a. The native Aesthetic attribute Observable
     * emits every time GMMP changes its theme, including album-art-derived
     * dynamic colors. Reflection keeps these third-party classes out of the
     * GoneSmart compile-time dependency graph.
     *
     * This is the authoritative color source. A static Android theme attr
     * and the FAB tint are only fallback paths if GMMP changes internals.
     */
    private fun observeNativeGmmpPalette(session: Session) {
        if (session.themeInitialized) return
        session.themeInitialized = true
        val list = session.list ?: return
        val resources = list.resources
        val pkg = list.context.packageName
        val accentAttr = resources.getIdentifier("colorAccent", "attr", pkg)
        val primaryAttr = resources.getIdentifier("colorPrimary", "attr", pkg)
        session.nativeAccentAttr = accentAttr
        session.nativePrimaryAttr = primaryAttr

        runCatching {
            val loader = session.fragment.javaClass.classLoader
                ?: throw ClassNotFoundException("GMMP class loader")
            val theme = runCatching {
                val companion = loader.loadClass(
                    "com.afollestad.aesthetic.a\$a"
                )
                companion.getDeclaredMethod("c").apply {
                    isAccessible = true
                }.invoke(null)
            }.getOrElse {
                val klass = loader.loadClass(
                    "com.afollestad.aesthetic.Aesthetic"
                )
                klass.getDeclaredMethod("get").apply {
                    isAccessible = true
                }.invoke(null)
            } ?: throw IllegalStateException("Aesthetic not attached")

            session.nativeTheme = theme
            val themeClass = theme.javaClass

            // Synchronous initialization prevents a stale/default color
            // from being displayed during the first frame of multi-mode.
            updateCurrentNativePalette(session)

            // Native Aesthetic palette observables notify while the
            // currently playing track or the user's custom theme changes.
            val observerType = loader.loadClass("nf3")
            val observeAttribute = themeClass.getDeclaredMethod(
                "b",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            for ((name, attributeId) in listOf(
                "primary" to primaryAttr,
                "accent" to accentAttr
            )) {
                if (attributeId == 0) continue
                val observer = Proxy.newProxyInstance(
                    observerType.classLoader,
                    arrayOf(observerType)
                ) { _, method, args ->
                    when (method.name) {
                        // GMMP's obfuscated RxJava Observer.onNext(Object).
                        "a" -> {
                            val color = args?.firstOrNull() as? Number
                            if (color != null) {
                                list.post {
                                    if (active === session) {
                                        receiveNativePalette(
                                            session,
                                            name,
                                            color.toInt()
                                        )
                                    }
                                }
                            }
                        }
                        // Observer.onSubscribe(Disposable).
                        "c" -> {
                            args?.firstOrNull()?.let {
                                session.themeDisposables.add(it)
                            }
                        }
                        "onError" -> Log.w(
                            TAG,
                            "MULTI PALETTE | $name observer error",
                            args?.firstOrNull() as? Throwable
                        )
                    }
                    null
                }
                session.themeObservers.add(observer)
                val observable = observeAttribute.invoke(theme, attributeId)
                    ?: continue
                observable.javaClass
                    .getMethod("b", observerType)
                    .invoke(observable, observer)
            }

            // A secondary change notification protects against future
            // updates that GMMP writes to Aesthetic's preferences without
            // emitting an attribute Observable.
            val preferences = themeClass.getDeclaredMethod("k").apply {
                isAccessible = true
            }.invoke(theme) as? SharedPreferences
            if (preferences != null) {
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        list.post {
                            if (active === session) {
                                updateCurrentNativePalette(session)
                            }
                        }
                    }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                session.themePreferences = preferences
                session.themePreferenceListener = listener
            }
            observeNativeFabColor(session)
            Log.i(
                TAG,
                "MULTI PALETTE | native Aesthetic subscription active" +
                    " | primaryAttr=$primaryAttr | accentAttr=$accentAttr"
            )
        }.onFailure { error ->
            Log.w(
                TAG,
                "MULTI PALETTE | Aesthetic observer unavailable; using live FAB tint",
                error
            )
        }
    }

    /**
     * AestheticFab has a special gmDynamicColor attribute. The player's
     * album-art theme may update this *named* color without changing the
     * conventional colorPrimary / colorAccent attributes immediately.
     * Subscribe to the same observable the native picker FAB itself uses,
     * so our queue-style selection cannot get stuck on an old color.
     */
    private fun observeNativeFabColor(session: Session) {
        if (session.fabPaletteSubscribed) return
        val fab = session.fab ?: return
        val theme = session.nativeTheme ?: return
        val list = session.list ?: return
        val loader = session.fragment.javaClass.classLoader ?: return

        runCatching {
            val getter = generateSequence<Class<*>>(fab.javaClass) {
                it.superclass
            }.mapNotNull { klass ->
                klass.declaredMethods.firstOrNull {
                    it.name == "getColorValue" && it.parameterCount == 0
                }
            }.firstOrNull() ?: return

            getter.isAccessible = true
            val rawColorValue = getter.invoke(fab) as? String
                ?: return

            // A regular static FAB color must not override the genuinely
            // dynamic primary observable (otherwise selection freezes on
            // whatever accent was active when the picker opened).
            val dynamicField = generateSequence<Class<*>>(fab.javaClass) {
                it.superclass
            }.mapNotNull { klass ->
                klass.declaredFields.firstOrNull {
                    it.name == "dynamicColorValue"
                }
            }.firstOrNull()
            dynamicField?.isAccessible = true
            val dynamicAttribute = dynamicField?.get(fab) as? String
            if (dynamicAttribute.isNullOrBlank() ||
                dynamicAttribute != rawColorValue
            ) {
                Log.i(
                    TAG,
                    "MULTI PALETTE | FAB has no active dynamic color; " +
                        "following GMMP primary/accent observables"
                )
                return
            }
            val accentAttr = session.nativeAccentAttr
            if (accentAttr == 0) return

            val fallback = theme.javaClass.getDeclaredMethod(
                "b",
                Int::class.javaPrimitiveType
            ).apply {
                isAccessible = true
            }.invoke(theme, accentAttr) ?: return

            // The obfuscated utility oy0.h(theme, rawAttr, fallback)
            // is precisely the path used by AestheticFab.onAttachedToWindow.
            val utility = loader.loadClass("oy0")
            val observableMethod = utility.declaredMethods.first {
                it.name == "h" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0].isAssignableFrom(theme.javaClass) &&
                    it.parameterTypes[1] == String::class.java
            }.apply { isAccessible = true }
            val observable = observableMethod.invoke(
                null,
                theme,
                rawColorValue,
                fallback
            ) ?: return

            val observerType = loader.loadClass("nf3")
            val observer = Proxy.newProxyInstance(
                observerType.classLoader,
                arrayOf(observerType)
            ) { _, method, args ->
                when (method.name) {
                    "a" -> {
                        val next = args?.firstOrNull() as? Number
                        if (next != null) {
                            list.post {
                                if (active === session) {
                                    receiveNativePalette(
                                        session,
                                        "fab",
                                        next.toInt()
                                    )
                                }
                            }
                        }
                    }
                    "c" -> args?.firstOrNull()?.let {
                        session.themeDisposables.add(it)
                    }
                    "onError" -> Log.w(
                        TAG,
                        "MULTI PALETTE | native FAB observer error",
                        args?.firstOrNull() as? Throwable
                    )
                }
                null
            }
            session.themeObservers.add(observer)
            observable.javaClass.getMethod("b", observerType)
                .invoke(observable, observer)
            session.fabPaletteSubscribed = true
            Log.i(
                TAG,
                "MULTI PALETTE | observing native FAB dynamic color" +
                    " | rawAttribute=$rawColorValue"
            )
        }.onFailure {
            Log.w(
                TAG,
                "MULTI PALETTE | no native FAB color observable; " +
                    "using Aesthetic primary/accent",
                it
            )
        }
    }

    private fun updateCurrentNativePalette(session: Session) {
        val theme = session.nativeTheme ?: return
        val getter = theme.javaClass.declaredMethods.firstOrNull {
            it.name == "e" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: return

        if (session.nativePrimaryAttr != 0) {
            (getter.invoke(theme, session.nativePrimaryAttr) as? Number)
                ?.toInt()?.let {
                    receiveNativePalette(session, "primary", it)
                }
        }
        if (session.nativeAccentAttr != 0) {
            (getter.invoke(theme, session.nativeAccentAttr) as? Number)
                ?.toInt()?.let {
                    receiveNativePalette(session, "accent", it)
                }
        }
    }

    private fun receiveNativePalette(
        session: Session,
        name: String,
        color: Int
    ) {
        if (Color.alpha(color) < 200) return
        val previous = when (name) {
            "primary" -> session.livePrimary
            "fab" -> session.liveFabAccent
            else -> session.liveAccent
        }
        if (previous == color) return

        when (name) {
            "primary" -> session.livePrimary = color
            "fab" -> session.liveFabAccent = color
            else -> session.liveAccent = color
        }
        Log.i(
            TAG,
            "MULTI PALETTE | native $name=#" +
                Integer.toHexString(color)
        )
        if (active === session && session.selectedPaths.isNotEmpty()) {
            session.lastLoggedAccent = null
            session.lastBarColor = null
            tintSelectionBar(session)
            refreshVisibleRows(session)
            updateSparkleColor(session)
        }
    }

    private fun stopNativeGmmpPalette(session: Session) {
        val prefs = session.themePreferences
        val listener = session.themePreferenceListener
        if (prefs != null && listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        session.themePreferenceListener = null
        session.themePreferences = null
        session.themeDisposables.forEach { subscription ->
            runCatching {
                // GMMP's obfuscated RxJava Disposable.dispose().
                subscription.javaClass.getMethod("b").invoke(subscription)
            }
        }
        session.themeDisposables.clear()
        session.themeObservers.clear()
        session.nativeTheme = null
        session.fabPaletteSubscribed = false
    }

    /**
     * Read resource strings directly from the HOST GMMP APK at runtime,
     * using the current Activity context so GMMP/Android picks the locale.
     * No GoneSmart translation bundles or copied GMMP translations.
     */
    private fun gmmpString(
        context: Context,
        name: String,
        vararg args: Any
    ): String? {
        val id = context.resources.getIdentifier(
            name,
            "string",
            context.packageName
        )
        if (id == 0) {
            Log.w(TAG, "MULTI I18N | GMMP string unavailable: $name")
            return null
        }
        return runCatching {
            if (args.isEmpty()) context.getString(id)
            else context.getString(id, *args)
        }.onFailure {
            Log.w(TAG, "MULTI I18N | failed native GMMP string: $name", it)
        }.getOrNull()
    }

    private fun selectionTitle(
        session: Session,
        count: Int
    ): String {
        val context = session.list?.context
            ?: return count.toString()
        return gmmpString(context, "num_selected", count)
            ?: count.toString()
    }

    private fun updateSelectionBar(session: Session) {
        if (session.selectedPaths.isEmpty()) return

        if (session.selectionBar == null) {
            val list = session.list ?: return
            val callback = object : ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    mode.title = selectionTitle(
                        session,
                        session.selectedPaths.size
                    )
                    return true
                }

                override fun onPrepareActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean = false

                override fun onActionItemClicked(
                    mode: ActionMode,
                    item: MenuItem
                ): Boolean = false

                override fun onDestroyActionMode(mode: ActionMode) {
                    if (session.selectionBar === mode) {
                        session.selectionBar = null
                        if (active === session &&
                            session.selectedPaths.isNotEmpty()
                        ) {
                            // Native ActionMode's own back arrow cancels
                            // selection, but must keep the playlist picker.
                            exitSelection(session)
                        }
                    }
                }
            }

            session.selectionBar = runCatching {
                list.startActionMode(callback, ActionMode.TYPE_PRIMARY)
            }.onFailure { error ->
                Log.w(TAG, "MULTI BAR | contextual action mode failed", error)
            }.getOrNull()

            if (session.selectionBar == null) {
                if (!session.selectionBarUnavailableLogged) {
                    session.selectionBarUnavailableLogged = true
                    Log.w(TAG, "MULTI BAR | unsupported in picker window")
                }
                return
            }
            Log.i(TAG, "MULTI BAR | native contextual bar started")
            list.post {
                if (active === session && session.selectionBar != null) {
                    tintSelectionBar(session)
                }
            }
        }

        session.selectionBar?.title = selectionTitle(
            session,
            session.selectedPaths.size
        )
        tintSelectionBar(session)
    }

    private fun tintSelectionBar(session: Session) {
        if (session.selectionBar == null) return
        val root = session.list?.rootView ?: return
        val bar = session.barView
            ?: findContextBar(root)
            ?: return
        if (!session.barBackgroundSaved) {
            session.barView = bar
            session.originalBarBackground = bar.background
            session.barBackgroundSaved = true
        }

        // Native queue ActionMode uses the live colorPrimary, not a
        // one-time snapshot of the playlist FAB's background tint.
        val accent = gmmpPrimary(session, bar)
        if (session.lastBarColor != accent ||
            (bar.background as? ColorDrawable)?.color != accent
        ) {
            session.lastBarColor = accent
            bar.background = ColorDrawable(accent)
        }
    }

    private fun findContextBar(root: View): View? {
        if (resourceName(root) == "action_mode_bar" &&
            root.visibility == View.VISIBLE
        ) return root

        val children = root as? ViewGroup ?: return null
        for (index in 0 until children.childCount) {
            val found = findContextBar(children.getChildAt(index))
            if (found != null) return found
        }
        return null
    }

    private fun enableFab(session: Session) {
        val fab = session.fab as? ImageView ?: return

        if (!session.fabIconSaved) {
            session.fabIconSaved = true
            // Clone the native plus before changing the FAB tint. Otherwise
            // clearing the tint for the white confirm glyph can mutate the
            // very drawable we intend to restore on cancellation.
            session.originalIcon = fab.drawable?.constantState
                ?.newDrawable(fab.resources)?.mutate()
                ?: fab.drawable
            session.originalTint = fab.imageTintList
            session.originalDescription = fab.contentDescription
        }

        fab.imageTintList = null
        fab.setImageDrawable(MultiConfirmDrawable(dp(fab, 24f)))
        if (session.sparkle == null) {
            val color = currentSparkleColor(session, fab)
            session.sparkleColor = color
            // The primary star is BELOW the tick, while the small star
            // remains near its tip. Auto-DJ retains the original geometry.
            val sparkle = PlayerAutoDjBadgeController.SparkleBadgeDrawable(
                color,
                scale = 2f,
                playlistPlacement = true
            )
            session.sparkle = sparkle
            fab.overlay.add(sparkle)
        } else {
            updateSparkleColor(session)
        }
        fab.contentDescription =
            "GoneSmart: Zu ${session.selectedPaths.size} Playlists hinzufügen"
        pinFab(session)
    }

    /**
     * Use GMMP's *other* native palette slot, colorAccent, rather than
     * reusing colorPrimary for both the FAB/selection and the sparkle.
     * All three colors are subscribed to Aesthetic updates in this session.
     */
    private fun currentSparkleColor(session: Session, view: View): Int {
        val fabColor = session.liveFabAccent
            ?: (session.fab as?
                com.google.android.material.floatingactionbutton.FloatingActionButton)
                ?.backgroundTintList?.let { tint ->
                    val fab = session.fab
                    tint.getColorForState(
                        fab?.drawableState ?: intArrayOf(),
                        tint.defaultColor
                    )
                }
            ?: session.livePrimary

        // Most covers contrast nicely with GoneSmart lilac. Do NOT
        // normally replace it with album-art red/blue: that made the
        // sparkle look like another GMMP theme indicator.
        if (fabColor == null ||
            colorDistance(FALLBACK_LILAC, fabColor) >= 100f
        ) {
            return FALLBACK_LILAC
        }

        // Only when the native FAB is itself lilac/purple, prefer a
        // DISTINCT live Aesthetic palette slot with better contrast.
        val alternatives = listOfNotNull(
            session.liveAccent,
            session.livePrimary,
            session.nativeAccentAttr.takeIf { it != 0 }
                ?.let { themeColor(view, it) }
        ).distinct().filter { Color.alpha(it) >= 200 }

        val strongest = alternatives.maxByOrNull {
            colorDistance(it, fabColor)
        }
        if (strongest != null &&
            colorDistance(strongest, fabColor) >= 125f
        ) {
            return strongest
        }

        // If GMMP's available slots are all nearly the same color,
        // retain a contrasting violet rather than an illegible badge.
        val hsv = FloatArray(3)
        Color.colorToHSV(fabColor, hsv)
        return if (hsv[2] > 0.55f) {
            0xFF34305F.toInt()
        } else {
            0xFFE3DFFF.toInt()
        }
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val red = Color.red(a) - Color.red(b)
        val green = Color.green(a) - Color.green(b)
        val blue = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt(
            (red * red + green * green + blue * blue).toFloat()
        )
    }

    private fun updateSparkleColor(session: Session) {
        val fab = session.fab ?: return
        val sparkle = session.sparkle ?: return
        val current = currentSparkleColor(session, fab)
        if (session.sparkleColor == current) return
        session.sparkleColor = current
        sparkle.updateColor(current)
        Log.i(
            TAG,
            "MULTI SPARKLE | dynamic color=#" +
                Integer.toHexString(current)
        )
        fab.invalidate()
    }

    private fun pinFab(session: Session) {
        if (session.selectedPaths.isEmpty()) return
        val fab = session.fab ?: return

        if (fab.visibility != View.VISIBLE ||
            fab.alpha < 1f ||
            fab.translationY != 0f
        ) {
            fab.animate().cancel()
            fab.clearAnimation()
            fab.visibility = View.VISIBLE
            fab.alpha = 1f
            fab.translationY = 0f
        }

        session.sparkle?.setBounds(0, 0, fab.width, fab.height)
        if (session.selectionBar != null) {
            tintSelectionBar(session)
        }
        fab.invalidate()
    }

    /**
     * The row palette deliberately comes from GMMP, not GoneSmart. The
     * native FAB is the most reliable live source when the Aesthetic theme
     * derives its palette from the current album art. Theme attributes are
     * fallbacks for fixed/custom themes or a currently untinted FAB.
     */
    private fun gmmpPrimary(session: Session, view: View): Int =
        session.liveFabAccent
            ?: session.livePrimary
            ?: session.liveAccent
            ?: gmmpAccent(session, view)

    private fun gmmpAccent(session: Session, view: View): Int {
        // Aesthetic's observable value is the real GMMP accent, even
        // when a new cover updates it while this picker remains open.
        session.liveFabAccent?.let { return it }
        session.liveAccent?.let { return it }
        val fab = session.fab as? com.google.android.material.floatingactionbutton.FloatingActionButton
        val liveFabColor = fab?.backgroundTintList?.let { tint ->
            tint.getColorForState(fab.drawableState, tint.defaultColor)
        }?.takeIf { Color.alpha(it) >= 200 }

        if (liveFabColor != null) return liveFabColor

        val resources = view.context.resources
        val packageName = view.context.packageName
        val appAccent = resources.getIdentifier(
            "colorAccent", "attr", packageName
        )
        val appPrimary = resources.getIdentifier(
            "colorPrimary", "attr", packageName
        )
        for (attr in intArrayOf(
            appAccent,
            android.R.attr.colorAccent,
            appPrimary
        )) {
            val resolved = if (attr != 0) themeColor(view, attr) else null
            if (resolved != null && Color.alpha(resolved) >= 200) {
                return resolved
            }
        }

        // Only an ultimate fallback: the normal path reads GMMP's runtime
        // color. Never tint playlist selection with GoneSmart lilac.
        return 0xFF36A8BE.toInt()
    }

    private fun themeColor(view: View, attr: Int): Int? {
        val value = TypedValue()
        if (!view.context.theme.resolveAttribute(attr, value, true)) {
            return null
        }
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..
            TypedValue.TYPE_LAST_COLOR_INT
        ) {
            return value.data
        }
        return if (value.resourceId != 0) {
            runCatching { view.context.getColor(value.resourceId) }
                .getOrNull()
        } else {
            null
        }
    }

    /**
     * GMMP's own rvHighlightOverlay is used for native row press feedback.
     * Prefer its live pressed-state color when exposed by the current
     * Aesthetic theme, rather than approximating the current accent with a
     * hard-coded blend of the row's existing background.
     */
    /**
     * Match the queue's tinted selection rows: overlay the live native
     * dynamic primary/selection color on the ordinary GMMP row background.
     * Using the current Observable rather than a cached pressed drawable
     * ensures cover-driven theme changes immediately recolor the selection.
     */
    private fun selectionOverlayColor(
        session: Session,
        row: FrameLayout
    ): Int {
        val accent = gmmpPrimary(session, row)
        val color = Color.argb(
            128,
            Color.red(accent),
            Color.green(accent),
            Color.blue(accent)
        )
        if (session.lastLoggedAccent != accent) {
            session.lastLoggedAccent = accent
            Log.i(
                TAG,
                "MULTI STYLE | native dynamic primary=#" +
                    Integer.toHexString(accent) +
                    " | row overlay=#" +
                    Integer.toHexString(color)
            )
        }
        return color
    }

    /** A recycled row is always painted from its CURRENT bound xn3 path. */
    private fun refreshVisibleRows(session: Session) {
        val list = session.list ?: return
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? FrameLayout ?: continue
            val holder = getPlaylistItem(session, row)
            refreshRow(session, row, holder)
        }
    }

    /**
     * Called after GMMP's native adapter rebinds a holder, in addition to
     * layout/scroll callbacks. This prevents old highlight state leaking
     * onto unrelated playlists during RecyclerView view recycling.
     */
    fun onRowBound(holder: Any?) {
        val session = active ?: return
        if (session.selectedPaths.isEmpty() &&
            session.appliedRowOverlays.isEmpty()
        ) return
        if (holder?.javaClass?.name != "jo3") return

        val row = runCatching {
            holder.javaClass.getField("itemView").get(holder) as? FrameLayout
        }.getOrNull() ?: return

        if (row.parent === session.list) {
            refreshRow(session, row, holder)
        } else {
            row.post {
                if (active === session && row.parent === session.list) {
                    refreshRow(session, row, holder)
                }
            }
        }
    }

    private fun refreshRow(
        session: Session,
        row: FrameLayout,
        holder: Any?
    ) {
        // Remove artifacts left by the previous experimental implementation.
        val oldMarker = row.findViewWithTag<View>(OLD_CHECK_TAG)
        if (oldMarker != null && oldMarker.parent === row) {
            row.removeView(oldMarker)
        }

        val path = holder?.let(::playlistPath)
        val selected = path != null && path in session.selectedPaths

        if (selected) {
            val color = selectionOverlayColor(session, row)
            var overlay = session.appliedRowOverlays[row]
            if (overlay == null) {
                overlay = ColorDrawable(color)
                session.appliedRowOverlays[row] = overlay
                row.overlay.add(overlay)
            } else if (overlay.color != color) {
                overlay.color = color
            }
            // A single FrameLayout is rebound to many playlists while
            // scrolling; always resize its tint to the current row bounds.
            overlay.setBounds(0, 0, row.width, row.height)
        } else {
            session.appliedRowOverlays.remove(row)?.let {
                row.overlay.remove(it)
            }
        }
    }

    private fun confirm(session: Session) {
        if (session.submitting) return

        val fab = session.fab ?: return
        val list = session.list ?: return
        val handler = session.nativeHandler

        if (handler == null || handler.javaClass.name != "io3") {
            warn(fab.context, "GMMP-Playlistfunktion nicht verfügbar")
            Log.w(TAG, "MULTI CONFIRM | native io3 handler missing")
            return
        }

        val sourceCount = sourceCount(handler)
        if (sourceCount <= 0) {
            warn(fab.context, "Keine ausgewählten Titel mehr vorhanden")
            Log.w(TAG, "MULTI CONFIRM | native source selection empty")
            return
        }

        // bw.i0() yields t23 groups. The groups' r() lists contain
        // xn3 playlist models. Re-resolve by stable native playlist path.
        val currentModels = adapterItems(list)
        val byPath = currentModels.mapNotNull { model ->
            modelPath(model)?.let { path -> path to model }
        }.toMap()
        val targets = session.selectedPaths.mapNotNull { path ->
            if (currentModels.isEmpty()) {
                // Only fall back when the adapter cannot expose models;
                // captured models still have their original native path.
                session.selectedModels[path]
                    ?.takeIf { modelPath(it) == path }
            } else {
                byPath[path]
            }
        }
        if (targets.size != session.selectedPaths.size) {
            warn(fab.context, "Playlistliste geändert – bitte neu auswählen")
            Log.w(TAG, "MULTI CONFIRM | stale destination detected")
            exitSelection(session)
            return
        }

        val holder = session.dispatchHolder
        val modelField = runCatching {
            holder?.javaClass?.getDeclaredField("A")?.also {
                it.isAccessible = true
            }
        }.getOrNull()
        if (holder?.javaClass?.name != "jo3" || modelField == null) {
            warn(fab.context, "GMMP-Playlistzeile nicht verfügbar")
            Log.w(TAG, "MULTI CONFIRM | jo3 dispatch holder missing")
            return
        }

        val addMethod: Method = runCatching {
            handler.javaClass.declaredMethods.first {
                it.name == "r" &&
                    it.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) &&
                    it.parameterTypes[1].name == "ie0" &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }.also { it.isAccessible = true }
        }.getOrElse { error ->
            warn(fab.context, "Native GMMP-Methode nicht gefunden")
            Log.e(TAG, "MULTI CONFIRM | io3.r missing", error)
            return
        }

        // GMMP io3.r(Context, ie0) accepts a jo3 view holder but reads
        // only jo3.A -> xn3.q synchronously. Temporarily switch A to each
        // selected model, call the native handler, then restore it.
        // Never retain recycled view objects as destination identity.
        val originalModel = modelField.get(holder)
        // One batch spans every destination, but only the native completion
        // callback is allowed to dismiss the picker, and only once.
        val navigationBatch = if (targets.size > 1) {
            NativeNavigationBatch(
                sourceCount = sourceCount,
                // Use the picker Activity context: GMMP may apply a
                // different in-app language than the application default.
                context = fab.context
            )
        } else {
            null
        }
        session.submitting = true
        var accepted = 0
        try {
            for (model in targets) {
                val path = modelPath(model) ?: continue
                modelField.set(holder, model)

                val previous = constructingNativeCallback.get()
                if (navigationBatch != null) {
                    constructingNativeCallback.set(navigationBatch)
                }
                val dispatched = try {
                    addMethod.invoke(handler, fab.context, holder) as? Boolean
                        ?: false
                } finally {
                    if (previous == null) {
                        constructingNativeCallback.remove()
                    } else {
                        constructingNativeCallback.set(previous)
                    }
                }

                Log.i(
                    TAG,
                    "MULTI NATIVE ADD | destination=$path | " +
                        "accepted=$dispatched | sourceCount=$sourceCount"
                )
                if (dispatched) accepted++
            }
        } catch (error: Throwable) {
            Log.e(TAG, "MULTI CONFIRM | native dispatch failed", error)
        } finally {
            runCatching { modelField.set(holder, originalModel) }
                .onFailure { Log.e(TAG, "MULTI CONFIRM | holder restore failed", it) }
            markNativeDispatchFinished(navigationBatch, accepted)
            // A retry after partial dispatch could duplicate tracks.
            exitSelection(session)
            session.submitting = false
        }

        if (navigationBatch == null) {
            // This case is a single destination dispatched from a selection
            // session; preserve GMMP's normal native completion toast.
            if (accepted == 0) {
                warn(fab.context, "Hinzufügen nicht gestartet")
            }
        } else if (accepted == 0) {
            warn(fab.context, "Hinzufügen zu keiner Playlist gestartet")
        } else {
            Log.i(
                TAG,
                "MULTI TOAST | awaiting native confirmations for " +
                    "$accepted playlist(s) and $sourceCount file(s)"
            )
        }

        if (accepted == 0) {
            eventReporter.reportEvent(
                GoneSmartRuntimeContract.CATEGORY_PLAYLISTS,
                "Could not start adding the selected songs to playlists."
            )
        }
        Log.i(
            TAG,
            "MULTI CONFIRM | accepted=$accepted / ${targets.size}" +
                " | sourceCount=$sourceCount"
        )
    }

    private fun exitSelection(session: Session) {
        session.selectedPaths.clear()
        session.selectedModels.clear()
        session.dispatchHolder = null

        // Finish our contextual bar without dismissing the picker. Clear
        // the reference first so its destroy callback does not recurse.
        val actionMode = session.selectionBar
        session.selectionBar = null
        // The saved background dates from selection START. After a track
        // change it contains the PREVIOUS album-art color and briefly
        // flashes during ActionMode's exit animation if restored here.
        // Keep the live GMMP color through the dismissal instead.
        session.barView?.let { bar ->
            bar.background = ColorDrawable(gmmpPrimary(session, bar))
        }
        actionMode?.finish()
        session.barView = null
        session.originalBarBackground = null
        session.barBackgroundSaved = false
        session.lastBarColor = null

        // Remove transient foreground overlays from ALL previously tinted
        // views, including those now recycled or detached from the list.
        session.appliedRowOverlays.toList().forEach { (row, overlay) ->
            row.overlay.remove(overlay)
        }
        session.appliedRowOverlays.clear()

        val fab = session.fab as? ImageView
        session.sparkle?.let { badge -> fab?.overlay?.remove(badge) }
        session.sparkle = null
        session.sparkleColor = null
        if (fab != null && session.fabIconSaved) {
            fab.setImageDrawable(session.originalIcon)
            // Aesthetic/Material may apply the original white plus via
            // drawable color filtering, leaving imageTintList null. After
            // going into confirm mode it then resolves to black unless
            // we explicitly restore the original visible icon color.
            fab.imageTintList = session.originalTint
                ?: ColorStateList.valueOf(Color.WHITE)
            fab.contentDescription = session.originalDescription
        }

        session.fabIconSaved = false
        session.originalIcon = null
        session.originalTint = null
        session.originalDescription = null
        refreshVisibleRows(session)
        Log.i(TAG, "MULTI MODE | exited")
    }

    private fun reset() {
        active?.let { session ->
            exitSelection(session)
            stopNativeGmmpPalette(session)
        }
        active = null
    }

    private fun sourceCount(handler: Any): Int {
        return runCatching {
            val sourceField = handler.javaClass.getDeclaredField("r")
            sourceField.isAccessible = true
            val source = sourceField.get(handler) ?: return 0
            val listField = source.javaClass.getDeclaredField("a")
            listField.isAccessible = true
            (listField.get(source) as? List<*>)?.size ?: 0
        }.getOrDefault(0)
    }

    private fun resourceName(view: View): String {
        return runCatching {
            view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun warn(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private class MultiConfirmDrawable(
        private val iconSizePx: Int
    ) : Drawable() {
        override fun getIntrinsicWidth(): Int = iconSizePx
        override fun getIntrinsicHeight(): Int = iconSizePx

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val rect = bounds
            val w = rect.width().toFloat()
            val h = rect.height().toFloat()
            val x = rect.left.toFloat()
            val y = rect.top.toFloat()

            stroke.color = Color.WHITE
            stroke.strokeWidth = w * 0.115f
            canvas.drawLine(
                x + w * 0.15f, y + h * 0.54f,
                x + w * 0.42f, y + h * 0.78f,
                stroke
            )
            canvas.drawLine(
                x + w * 0.42f, y + h * 0.78f,
                x + w * 0.87f, y + h * 0.26f,
                stroke
            )

        // The shared Auto-DJ badge renderer draws the lilac sparkle as
        // a separate overlay in the top-right corner of the native FAB.
        }

        override fun setAlpha(alpha: Int) {
            stroke.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            stroke.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
