package io.github.alagga.gonesmart

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView

/** One native progress presentation shared by both Playlist tabs. */
internal class NativeQuickNavLoading(
    private val overlay: FrameLayout,
    private val header: RecyclerView,
    private val create: () -> ProgressBar?
) {
    private var indicator: ProgressBar? = null
    private var generation: Long? = null
    private var cancelLayoutWait: (() -> Unit)? = null
    private val headerLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        position()
    }

    val isVisible: Boolean get() = indicator?.visibility == View.VISIBLE

    fun begin(next: Long) {
        cancelLayoutWait?.invoke()
        generation = next
        if (indicator == null) {
            create()?.let { bar ->
                indicator = bar
                overlay.addView(bar, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bar.layoutParams.height,
                    Gravity.TOP
                ))
                // Smart's header has elevation; keep the thin indicator
                // above adjacent content using the actual header elevation.
                bar.elevation = header.elevation
                header.addOnLayoutChangeListener(headerLayout)
            }
        }
        indicator?.visibility = View.VISIBLE
        position()
    }

    fun position() {
        indicator?.translationY =
            (if (header.visibility == View.VISIBLE) header.height else 0).toFloat()
    }

    fun finish(expected: Long) {
        if (generation != expected) return
        clear()
    }

    fun clear() {
        cancelLayoutWait?.invoke()
        cancelLayoutWait = null
        generation = null
        indicator?.visibility = View.GONE
    }

    /**
     * Normal Playlists are already indexed; the outstanding work is
     * RecyclerView binding/layout. Keep the real loading drawable until the
     * replacement viewport has drawn once. No fixed minimum spinner time.
     * Smart Playlists call finish at their verified projection commit instead.
     */
    fun finishAfterLayout(expected: Long, rows: ViewGroup, isCurrent: () -> Boolean) {
        if (generation != expected || !isVisible) return
        cancelLayoutWait?.invoke()
        val tree = rows.viewTreeObserver
        if (!tree.isAlive || !rows.isAttachedToWindow) {
            finish(expected)
            return
        }
        var attempts = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
        val stop: () -> Unit = {
            if (tree.isAlive) tree.removeOnPreDrawListener(listener)
            val live = rows.viewTreeObserver
            if (live !== tree && live.isAlive) live.removeOnPreDrawListener(listener)
            rows.removeOnAttachStateChangeListener(detach)
            cancelLayoutWait = null
        }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                finish(expected)
            }
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (generation != expected || !isCurrent()) {
                stop()
                finish(expected)
            } else {
                val recycler = rows as? RecyclerView
                val ready = !rows.isLayoutRequested &&
                    (recycler == null || !recycler.hasPendingAdapterUpdates())
                if (ready || ++attempts >= 12) {
                    stop()
                    // Let the actual completed layout draw; the next
                    // animation frame retires this generation only.
                    rows.postOnAnimation { finish(expected) }
                }
            }
            true
        }
        cancelLayoutWait = stop
        rows.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }
}
