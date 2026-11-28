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
    private var cancelFirstDraw: (() -> Unit)? = null
    private var drawn = false
    private var finishRequested = false
    private val headerLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        position()
    }

    val isVisible: Boolean get() = indicator?.visibility == View.VISIBLE

    fun begin(next: Long) {
        cancelLayoutWait?.invoke()
        cancelFirstDraw?.invoke()
        generation = next
        drawn = false
        finishRequested = false
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
        indicator?.let { bar ->
            bar.visibility = View.VISIBLE
            bar.bringToFront()
            position()
            observeFirstDraw(bar, next)
        }
    }

    private fun observeFirstDraw(bar: ProgressBar, expected: Long) {
        val tree = bar.viewTreeObserver
        if (!tree.isAlive) return
        var attempts = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
        val stop: () -> Unit = {
            if (tree.isAlive) tree.removeOnPreDrawListener(listener)
            val live = bar.viewTreeObserver
            if (live !== tree && live.isAlive) live.removeOnPreDrawListener(listener)
            bar.removeOnAttachStateChangeListener(detach)
            cancelFirstDraw = null
        }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { clear() }
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (generation != expected) stop()
            else if (bar.isShown && bar.width > 0 && bar.height > 0) {
                position()
                stop()
                // A frame may still be deferred by another pre-draw
                // listener. Retire only on the next animation frame.
                bar.postOnAnimation {
                    if (generation == expected) {
                        drawn = true
                        if (BuildConfig.DEBUG) android.util.Log.d("GoneSmartPlaylist",
                            "FOLDER QUICKNAV LOADING | drawn | generation=" + expected +
                                " | running=" + (bar.indeterminateDrawable as?
                                    android.graphics.drawable.Animatable)?.isRunning)
                        if (finishRequested) clear()
                    }
                }
            } else if (++attempts >= 12) {
                stop()
                if (finishRequested) clear()
            }
            true
        }
        cancelFirstDraw = stop
        bar.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }

    fun position() {
        indicator?.translationY =
            (if (header.visibility == View.VISIBLE) header.height else 0).toFloat()
    }

    fun finish(expected: Long) {
        if (generation != expected) return
        if (!drawn && cancelFirstDraw != null && isVisible && overlay.isShown) {
            finishRequested = true
        } else {
            clear()
        }
    }

    fun clear() {
        cancelLayoutWait?.invoke()
        cancelLayoutWait = null
        cancelFirstDraw?.invoke()
        cancelFirstDraw = null
        generation = null
        drawn = false
        finishRequested = false
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
                if ((ready && header.adapter?.let {
                        !header.isLayoutRequested && !header.hasPendingAdapterUpdates()
                    } != false) || ++attempts >= 12) {
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
