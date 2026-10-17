package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local handoff from the passive GMMP Equalizer observers to the
 * profile application path. The r61 setter observer supplies the live owner
 * and setters, while the native DspManager creation observer supplies the
 * validated 2..10 band layout.
 *
 * Keeping those two facts independent is intentional: GMMP 4.2.1 does not
 * expose the live band count as the size of r61's gain-array field, and using
 * that field as a readiness gate caused Equalizer profile application to stay
 * permanently deferred.
 */
object GmmpEqualizerRuntimeBridge {
    data class Target(
        val instance: Any,
        val handler: Handler,
        val preSetter: Method,
        val bandSetter: Method,
        val enabledSetter: Method,
        val bandCount: Int
    )

    private val lock = Any()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var instance: Any? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var preSetter: Method? = null
    @Volatile private var bandSetter: Method? = null
    @Volatile private var enabledSetter: Method? = null
    @Volatile private var bandCount: Int? = null

    fun registerSetters(pre: Method, band: Method, enabled: Method) {
        pre.isAccessible = true
        band.isAccessible = true
        enabled.isAccessible = true

        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            val changed = preSetter != pre || bandSetter != band || enabledSetter != enabled
            preSetter = pre
            bandSetter = band
            enabledSetter = enabled
            currentUnsafe() != null && (!wasReady || changed)
        }
        if (notify) notifyReady()
    }

    fun observeInstance(observedInstance: Any, looper: Looper?) {
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            val changedInstance = instance !== observedInstance
            val previousLooper = handler?.looper

            instance = observedInstance

            // GMMP's natural Equalizer initialization currently runs on a
            // worker without a Looper. Requiring that worker to expose a
            // Handler made an otherwise complete native target permanently
            // unavailable. The proven setters themselves have no discovered
            // Looper identity, so use the observed Looper when one exists and
            // otherwise serialize requested profile application on GMMP's main
            // Looper. A later natural call on a real Looper upgrades the target.
            if (looper != null) {
                if (previousLooper !== looper) handler = Handler(looper)
            } else if (handler == null) {
                handler = Handler(Looper.getMainLooper())
            }

            val changedDispatcher = previousLooper !== handler?.looper
            currentUnsafe() != null && (!wasReady || changedInstance || changedDispatcher)
        }
        if (notify) notifyReady()
    }

    /**
     * Accept a band count only from a separately validated native layout
     * observation. Returns true when the count actually changed.
     */
    fun observeBandCount(observedBandCount: Int): Boolean {
        if (!GmmpEqualizerBandPolicy.isSupported(observedBandCount)) return false
        var changed = false
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            changed = bandCount != observedBandCount
            bandCount = observedBandCount
            currentUnsafe() != null && (!wasReady || changed)
        }
        if (notify) notifyReady()
        return changed
    }

    fun current(): Target? = synchronized(lock) { currentUnsafe() }

    fun addReadyListener(listener: () -> Unit) {
        readyListeners += listener
        if (current() != null) listener()
    }

    private fun currentUnsafe(): Target? {
        val localInstance = instance ?: return null
        val localHandler = handler ?: return null
        val localPre = preSetter ?: return null
        val localBand = bandSetter ?: return null
        val localEnabled = enabledSetter ?: return null
        val localBandCount = bandCount?.takeIf(GmmpEqualizerBandPolicy::isSupported) ?: return null
        return Target(localInstance, localHandler, localPre, localBand, localEnabled, localBandCount)
    }

    private fun notifyReady() {
        readyListeners.forEach { listener -> runCatching(listener) }
    }
}
