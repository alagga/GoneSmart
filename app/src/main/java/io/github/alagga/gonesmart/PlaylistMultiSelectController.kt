package io.github.alagga.gonesmart

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.lang.reflect.Method

/**
 * Experimental GMMP 4.2.0 integration, enabled only in GoneSmart debug builds.
 *
 * Uses the native playlist adapter (zn3 / bw), native jo3 playlist items and
 * native io3.r(Context, ie0) addition handler. Never writes .m3u files.
 *
 * One picker session owns its state. All methods run on GMMP's UI thread.
 */
internal class PlaylistMultiSelectController {

    companion object {
        private const val TAG = "GoneSmartPlaylist"
        private const val PINK = 0xFFFF4DA6.toInt()
        private const val CHECK_TAG = "gonesmart_playlist_check_v1"
    }

    private class Session(val fragment: Any) {
        var fab: View? = null
        var list: ViewGroup? = null
        var nativeHandler: Any? = null
        val selectedPaths = linkedSetOf<String>()
        var fabIconSaved = false
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
        val session = visibleSession() ?: return false
        val item = getPlaylistItem(session, view) ?: return false
        val path = playlistPath(item) ?: return false

        if (session.selectedPaths.add(path)) {
            Log.i(TAG, "MULTI SELECT | added=$path")
        }

        enableFab(session)
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

        if (!session.selectedPaths.add(path)) {
            session.selectedPaths.remove(path)
            Log.i(TAG, "MULTI SELECT | removed=$path")
        } else {
            Log.i(TAG, "MULTI SELECT | added=$path")
        }

        if (session.selectedPaths.isEmpty()) {
            exitSelection(session)
        } else {
            enableFab(session)
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

    private fun visibleSession(): Session? {
        val session = active ?: return null
        val list = session.list ?: return null
        val fab = session.fab ?: return null

        if (!list.isAttachedToWindow || !fab.isAttachedToWindow) {
            return null
        }

        if (list.rootView !== fab.rootView) return null
        return session
    }

    private fun getPlaylistItem(
        session: Session,
        view: View?
    ): Any? {
        val list = session.list ?: return null
        if (view !is FrameLayout || view.parent !== list) return null

        val position = runCatching {
            list.javaClass
                .getMethod("getChildAdapterPosition", View::class.java)
                .invoke(list, view) as? Int
        }.getOrNull() ?: return null

        return adapterItems(list)
            .getOrNull(position)
            ?.takeIf { it.javaClass.name == "jo3" }
    }

    private fun adapterItems(list: ViewGroup): List<Any> {
        return runCatching {
            val adapter = list.javaClass.getMethod("getAdapter")
                .invoke(list) ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (adapter.javaClass.getMethod("i0")
                .invoke(adapter) as? List<Any>)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun playlistPath(item: Any): String? {
        if (item.javaClass.name != "jo3") return null

        return runCatching {
            val modelField = item.javaClass.getDeclaredField("A")
            modelField.isAccessible = true
            val model = modelField.get(item) ?: return null

            val pathField = model.javaClass.getDeclaredField("q")
            pathField.isAccessible = true
            (pathField.get(model) as? String)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun enableFab(session: Session) {
        val fab = session.fab as? ImageView ?: return

        if (!session.fabIconSaved) {
            session.fabIconSaved = true
            session.originalIcon = fab.drawable
            session.originalTint = fab.imageTintList
            session.originalDescription = fab.contentDescription
        }

        fab.imageTintList = null
        fab.setImageDrawable(MultiConfirmDrawable(dp(fab, 24f)))
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
    }

    private fun refreshVisibleRows(session: Session) {
        val list = session.list ?: return
        val items = adapterItems(list)

        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) as? FrameLayout ?: continue
            val position = runCatching {
                list.javaClass
                    .getMethod("getChildAdapterPosition", View::class.java)
                    .invoke(list, row) as? Int
            }.getOrNull() ?: continue

            val path = items.getOrNull(position)?.let(::playlistPath)
            val selected = path != null && path in session.selectedPaths

            var marker = row.findViewWithTag<TextView>(CHECK_TAG)
            if (marker == null && selected) {
                marker = TextView(row.context).apply {
                    tag = CHECK_TAG
                    text = "✓"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PINK)
                    }
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }

                val size = dp(row, 28f)
                val params = FrameLayout.LayoutParams(
                    size,
                    size,
                    Gravity.END or Gravity.CENTER_VERTICAL
                )
                params.marginEnd = dp(row, 14f)
                row.addView(marker, params)
            }

            marker?.visibility =
                if (selected) View.VISIBLE else View.GONE
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

        // Re-resolve paths against the current native list so that deleted
        // or reordered playlists cannot be dispatched by stale row objects.
        val byPath = adapterItems(list).mapNotNull { item ->
            playlistPath(item)?.let { path -> path to item }
        }.toMap()

        val targets = session.selectedPaths.mapNotNull(byPath::get)
        if (targets.size != session.selectedPaths.size) {
            warn(fab.context, "Playlistliste geändert – bitte neu auswählen")
            Log.w(TAG, "MULTI CONFIRM | stale destination detected")
            exitSelection(session)
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

        session.submitting = true
        var accepted = 0
        try {
            for (item in targets) {
                val path = playlistPath(item) ?: continue
                val dispatched =
                    addMethod.invoke(handler, fab.context, item) as? Boolean
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

        val fab = session.fab as? ImageView
        if (fab != null && session.fabIconSaved) {
            fab.setImageDrawable(session.originalIcon)
            fab.imageTintList = session.originalTint
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

            stroke.color = PINK
            stroke.strokeWidth = w * 0.09f
            val sx = x + w * 0.83f
            val sy = y + h * 0.13f
            canvas.drawLine(sx, sy - h * 0.11f, sx, sy + h * 0.11f, stroke)
            canvas.drawLine(sx - w * 0.11f, sy, sx + w * 0.11f, sy, stroke)
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
