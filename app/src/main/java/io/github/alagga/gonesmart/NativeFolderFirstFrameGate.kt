package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Shared opacity/first-frame lease for the two native Playlist tabs.
 *
 * Controller-specific native adapter, picker, folder projection and readiness
 * logic remains separate. This manager only owns the ORIGINAL positive alpha,
 * an idempotent 2.5s fail-open and cancellation of its exact lease generation.
 * Native owner/resource validation MUST happen at the caller before masking.
 */
internal object NativeFolderFirstFrameGate {
    enum class Surface { PLAYLIST_LIST, SMART_LIST, SMART_PAGE }

    private const val FAIL_OPEN_MS = 2_500L
    private val main = Handler(Looper.getMainLooper())
    private val lastPositiveOriginal = WeakHashMap<View, Float>()

    private data class Lease(
        val surface: Surface,
        val original: Float,
        val token: Any,
        val timeout: Runnable,
        val listener: ViewTreeObserver.OnPreDrawListener?,
        val originalTree: ViewTreeObserver?
    )
    private val leases = WeakHashMap<View, Lease>()

    fun original(view: View, observed: Float = view.alpha): Float {
        val resolved = NativeFolderAlphaLeasePolicy.original(
            observed, lastPositiveOriginal[view]
        )
        lastPositiveOriginal[view] = resolved
        return resolved
    }

    fun isActive(view: View?, surface: Surface): Boolean =
        view != null && leases[view]?.surface == surface

    fun hasActive(surface: Surface): Boolean =
        leases.values.any { it.surface == surface }

    /**
     * The same View/scope can never acquire a second timer. An unrelated
     * surface cannot take over an existing opacity lease.
     */
    fun mask(
        view: View,
        surface: Surface,
        guardNativeAlpha: Boolean = false,
        onNativeAlphaOverride: ((View) -> Unit)? = null,
        onTimeout: ((View) -> Unit)? = null
    ): Float? {
        leases[view]?.let { prior ->
            return prior.original.takeIf { FirstFrameOpacityPolicy.matches(
                prior.surface.name, surface.name
            ) }
        }
        val alpha = original(view)
        val token = Any()
        val weak = WeakReference(view)
        val listener = if (guardNativeAlpha) {
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val current = weak.get() ?: return true
                    if (leases[current]?.token?.let {
                        FirstFrameOpacityPolicy.current(it, token)
                    } == true &&
                        current.alpha != 0f
                    ) {
                        current.alpha = 0f
                        onNativeAlphaOverride?.invoke(current)
                    }
                    return true
                }
            }
        } else null
        val timeout = Runnable {
            val current = weak.get() ?: return@Runnable
            if (leases[current]?.token?.let {
                FirstFrameOpacityPolicy.current(it, token)
            } != true) return@Runnable
            release(current, surface, restoreNative = true)
            onTimeout?.invoke(current)
        }
        val observerTree = if (listener != null) view.viewTreeObserver else null
        leases[view] = Lease(
            surface, alpha, token, timeout, listener, observerTree
        )
        view.alpha = 0f
        if (listener != null && observerTree?.isAlive == true) {
            observerTree.addOnPreDrawListener(listener)
        }
        main.postDelayed(timeout, FAIL_OPEN_MS)
        return alpha
    }

    /**
     * Browser adoption cancels the native fail-open but keeps rows hidden:
     * the respective controller's verified projection determines reveal.
     */
    fun release(
        view: View,
        surface: Surface,
        restoreNative: Boolean
    ): Float? {
        val lease = leases[view]?.takeIf { FirstFrameOpacityPolicy.matches(
                it.surface.name, surface.name
            ) }
            ?: return null
        leases.remove(view)
        main.removeCallbacks(lease.timeout)
        lease.listener?.let { listener ->
            lease.originalTree?.let {
                if (it.isAlive) it.removeOnPreDrawListener(listener)
            }
            val liveTree = view.viewTreeObserver
            if (liveTree.isAlive && liveTree !== lease.originalTree) {
                liveTree.removeOnPreDrawListener(listener)
            }
        }
        if (restoreNative) view.alpha = lease.original
        return lease.original
    }

    fun restoreAll(surface: Surface) {
        leases.keys.toList().forEach { view ->
            release(view, surface, restoreNative = true)
        }
    }
}
