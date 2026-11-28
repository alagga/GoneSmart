package io.github.alagga.gonesmart

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

/**
 * The same AndroidX DefaultItemAnimator as the verified native Files tab.
 * Observe native RecyclerView add scheduling, and supply only the missing
 * appearances when a hidden/empty strip has its first real layout.
 * No manual alpha/translation values or guessed durations.
 */
internal class NativeQuickNavItemAnimator : DefaultItemAnimator() {
    private var forwardToken: Any? = null
    private val scheduled = WeakHashMap<RecyclerView.ViewHolder, Long>()
    private var cancelPending: (() -> Unit)? = null

    fun beginForward(): Any {
        cancelPending?.invoke()
        scheduled.clear()
        return Any().also { forwardToken = it }
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        if (forwardToken != null) scheduled[holder] = holder.itemId
        return super.animateAdd(holder)
    }

    fun completeForward(
        header: RecyclerView, token: Any, first: Int, endExclusive: Int,
        isCurrent: () -> Boolean
    ) {
        if (forwardToken !== token) return
        val tree = header.viewTreeObserver
        if (!header.isAttachedToWindow || !tree.isAlive) {
            forwardToken = null
            scheduled.clear()
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
            if (forwardToken === token) {
                forwardToken = null
                scheduled.clear()
                cancelPending = null
            }
        }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = stop()
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (forwardToken !== token || header.itemAnimator !== this ||
                !isCurrent() || header.visibility != View.VISIBLE ||
                ++attempts > 12
            ) {
                stop()
                return@OnPreDrawListener true
            }
            if (!header.isShown || header.isLayoutRequested ||
                header.isComputingLayout || header.hasPendingAdapterUpdates()
            ) return@OnPreDrawListener true
            var missing = 0
            for (position in first until endExclusive) {
                val holder = header.findViewHolderForAdapterPosition(position)
                    ?: continue
                if (scheduled[holder] == holder.itemId) continue
                // Mirror RecyclerView.animateAppearance's recyclability
                // ownership. DefaultItemAnimator dispatches the standard
                // finish callback; RecyclerView balances this hold.
                holder.setIsRecyclable(false)
                if (animateAdd(holder)) {
                    missing++
                } else {
                    // Standard DefaultItemAnimator always schedules adds.
                    // Defensive balance if that contract changes.
                    holder.setIsRecyclable(true)
                }
            }
            stop()
            if (missing > 0) runPendingAnimations()
            true
        }
        cancelPending = stop
        header.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }
}
