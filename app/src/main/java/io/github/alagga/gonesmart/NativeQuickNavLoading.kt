package io.github.alagga.gonesmart

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
    private var cancelHeaderWait: (() -> Unit)? = null
    private var drawn = false
    private var finishRequested = false
    private var firstFramePending = false
    private var accentSubscription: NativeGmmpAccent.Subscription? = null

    val isVisible: Boolean get() = indicator?.visibility == View.VISIBLE

    fun begin(next: Long) {
        cancelLayoutWait?.invoke()
        cancelFirstDraw?.invoke()
        cancelHeaderWait?.invoke()
        generation = next
        drawn = false
        finishRequested = false
        firstFramePending = false
        if (indicator == null) {
            create()?.let { bar ->
                val frame = header.parent as? FrameLayout ?: return@let
                indicator = bar
                // Preserve FrameLayout.LayoutParams read from the original
                // resource, including BOTTOM gravity. No translation overlay.
                frame.addView(bar, bar.layoutParams)
            }
        }
        indicator?.let { bar ->
            // Original mg1.u0(true): setIndeterminate then VISIBLE.
            bar.isIndeterminate = true
            bar.visibility = View.VISIBLE
            if (accentSubscription == null) {
                accentSubscription = NativeQuickNavLoadingSource.bindAccent(bar)
            }
            observeFirstDraw(bar, next)
        }
    }

    private fun observeFirstDraw(bar: ProgressBar, expected: Long) {
        val tree = bar.viewTreeObserver
        if (!tree.isAlive) return
        firstFramePending = true
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
                stop()
                // A frame may still be deferred by another pre-draw
                // listener. Retire only on the next animation frame.
                bar.postOnAnimation {
                    if (generation == expected) {
                        drawn = true
                        firstFramePending = false
                        if (BuildConfig.DEBUG) android.util.Log.d("GoneSmartPlaylist",
                            "FOLDER QUICKNAV LOADING | drawn | generation=" + expected +
                                " | running=" + (bar.indeterminateDrawable as?
                                    android.graphics.drawable.Animatable)?.isRunning)
                        if (finishRequested) finishAfterHeader(expected)
                    }
                }
            } else if (++attempts >= 12) {
                stop()
                firstFramePending = false
                if (finishRequested) clear()
            }
            true
        }
        cancelFirstDraw = stop
        bar.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }

    fun finish(expected: Long) {
        if (generation != expected) return
        if (!isVisible || !overlay.isShown || !overlay.isAttachedToWindow) {
            clear()
            return
        }
        finishRequested = true
        // The first pre-draw listener may already have been removed but its
        // next-frame callback has not executed. Do not clear in that gap.
        if (!drawn && firstFramePending) return
        finishAfterHeader(expected)
    }

    private fun finishAfterHeader(expected: Long) {
        if (generation != expected || cancelHeaderWait != null) return
        val tree = header.viewTreeObserver
        if (!tree.isAlive || !header.isAttachedToWindow || !overlay.isShown) {
            clear()
            return
        }
        var attempts = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
        val stop: () -> Unit = {
            if (tree.isAlive) tree.removeOnPreDrawListener(listener)
            val live = header.viewTreeObserver
            if (live !== tree && live.isAlive) live.removeOnPreDrawListener(listener)
            header.removeOnAttachStateChangeListener(detach)
            cancelHeaderWait = null
        }
        fun done() {
            if (generation == expected) clear()
        }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { done() }
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            val pendingRoot = (header.adapter as? NativeFolderBreadcrumbAdapter)
                ?.rootTransitionPending == true
            val ready = !pendingRoot && (header.visibility != View.VISIBLE ||
                (!header.isLayoutRequested && !header.hasPendingAdapterUpdates() &&
                    !header.isComputingLayout))
            if (generation != expected || !overlay.isShown) {
                stop()
                done()
            } else if (ready || ++attempts >= 12) {
                stop()
                header.postOnAnimation {
                    if (generation == expected) {
                        val animator = header.itemAnimator
                        if (animator == null) done() else animator.isRunning { done() }
                    }
                }
            }
            true
        }
        cancelHeaderWait = stop
        header.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
        // A real pending layout/animation drives subsequent frames. This
        // single invalidation also covers already-completed empty paths.
        overlay.invalidate()
    }

    fun clear() {
        cancelLayoutWait?.invoke()
        cancelLayoutWait = null
        cancelFirstDraw?.invoke()
        cancelFirstDraw = null
        cancelHeaderWait?.invoke()
        cancelHeaderWait = null
        generation = null
        drawn = false
        firstFramePending = false
        finishRequested = false
        // Original mg1.z1(): GONE. No forced drawable restart.
        indicator?.visibility = View.GONE
        accentSubscription?.dispose()
        accentSubscription = null
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
