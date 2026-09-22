package io.github.alagga.gonesmart

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
        // Keep GoneSmart's accent on the sparkle only. Row highlights use
        // GMMP's live dynamic/fixed accent color instead.
        private const val GONESMART_LILAC = 0xFFA39AFF.toInt()
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
        // A foreground overlay preserves GMMP's native row background and
        // ripple. Recycled RecyclerView rows get their overlays re-keyed by
        // the CURRENT bound playlist path on every bind and layout.
        val appliedRowOverlays = WeakHashMap<View, ColorDrawable>()
        var lastLoggedAccent: Int? = null
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

    fun beginPicker(fragment: Any?) {
        if (fragment == null) return
        if (active?.fragment === fragment) return

        reset()
        active = Session(fragment)
        Log.i(TAG, "MULTI PICKER | new session")
    }

    fun onFabFound(fab: View) {
        val session = active ?: return
        if (resourceName(fab) != "playlistFab") return
        if (session.fab === fab) return

        session.fab = fab
        Log.i(TAG, "MULTI FAB | attached")
        if (session.selectedPaths.isNotEmpty()) {
            enableFab(session)
        }
    }

    fun onListFound(list: View) {
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

        Log.i(TAG, "MULTI LIST | attached")
    }

    fun onNativeHandler(handler: Any?) {
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
        val session = visibleSession() ?: return false
        if (session.selectedPaths.isEmpty()) return false

        Log.i(TAG, "MULTI BACK | selection cancelled")
        exitSelection(session)
        return true
    }

    fun shouldBlockFabHide(receiver: Any?): Boolean {
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
    private fun updateSelectionBar(session: Session) {
        if (session.selectedPaths.isEmpty()) return

        if (session.selectionBar == null) {
            val list = session.list ?: return
            val callback = object : ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    mode.title =
                        "${session.selectedPaths.size} ausgewählt"
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

        session.selectionBar?.title =
            "${session.selectedPaths.size} ausgewählt"
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

        val accent = gmmpAccent(session, bar)
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
        // The checkmark stays clean and white. Reuse the EXACT renderer
        // used on the Auto-DJ button as a separately overlaid lilac badge.
        fab.setImageDrawable(MultiConfirmDrawable(dp(fab, 24f)))
        if (session.sparkle == null) {
            val sparkle = PlayerAutoDjBadgeController.SparkleBadgeDrawable(
                GONESMART_LILAC,
                scale = 2f
            )
            session.sparkle = sparkle
            fab.overlay.add(sparkle)
        }
        fab.contentDescription =
            "GoneSmart: Zu ${session.selectedPaths.size} Playlists hinzufügen"
        pinFab(session)
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
    private fun gmmpAccent(session: Session, view: View): Int {
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
    private fun nativePressedHighlight(row: FrameLayout): Int? {
        val id = row.resources.getIdentifier(
            "rvHighlightOverlay",
            "id",
            row.context.packageName
        )
        if (id == 0) return null
        val overlay = row.findViewById<View>(id) ?: return null
        val pressed = intArrayOf(android.R.attr.state_pressed)
        val tint = overlay.backgroundTintList?.let {
            it.getColorForState(pressed, it.defaultColor)
        }
        if (tint != null && Color.alpha(tint) > 0) return tint

        val drawable = overlay.background ?: return null
        val nativeColor = when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.color?.let {
                it.getColorForState(pressed, it.defaultColor)
            }
            is RippleDrawable -> drawable.color?.let {
                it.getColorForState(pressed, it.defaultColor)
            }
            else -> null
        }
        return nativeColor?.takeIf { Color.alpha(it) > 0 }
    }

    private fun selectionOverlayColor(
        session: Session,
        row: FrameLayout
    ): Int {
        // Some GMMP skins expose a neutral white/black ripple instead
        // of an accent-colored native highlight. In that case use the
        // dynamic accent, not the ripple's neutral color.
        val rawNative = nativePressedHighlight(row)
        val hsv = FloatArray(3)
        if (rawNative != null) Color.colorToHSV(rawNative, hsv)
        val native = rawNative?.takeIf {
            hsv[1] >= 0.14f && hsv[2] >= 0.15f
        }
        val fallback = gmmpAccent(session, row)
        val color = when {
            native == null -> Color.argb(
                128,
                Color.red(fallback),
                Color.green(fallback),
                Color.blue(fallback)
            )
            // Opaque pressed-state colors need an alpha to match GMMP's
            // translucent queue-selection treatment on dark surfaces.
            Color.alpha(native) > 220 -> Color.argb(
                128,
                Color.red(native),
                Color.green(native),
                Color.blue(native)
            )
            else -> native
        }

        if (session.lastLoggedAccent != color) {
            session.lastLoggedAccent = color
            Log.i(
                TAG,
                "MULTI STYLE | origin=" +
                    (if (native != null) "native-rvHighlightOverlay"
                        else "live-gmmp-theme") +
                    " | gmmpAccent=#" +
                    Integer.toHexString(fallback) +
                    " | overlay=#" +
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
        session.submitting = true
        var accepted = 0
        try {
            for (model in targets) {
                val path = modelPath(model) ?: continue
                modelField.set(holder, model)
                val dispatched =
                    addMethod.invoke(handler, fab.context, holder) as? Boolean
                        ?: false
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
            // A retry after partial dispatch could duplicate tracks.
            exitSelection(session)
            session.submitting = false
        }

        if (accepted == targets.size) {
            warn(
                fab.context,
                "Hinzufügen zu $accepted Playlists gestartet"
            )
        } else {
            warn(
                fab.context,
                "$accepted von ${targets.size} Playlist-Aktionen gestartet"
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
        actionMode?.finish()
        if (session.barBackgroundSaved) {
            session.barView?.background = session.originalBarBackground
        }
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
        active?.let(::exitSelection)
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
