package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local handoff from the passive GMMP Equalizer observers to the
 * profile application path. The r61 setter observer supplies the live owner
 * and setters. Runtime band count is accepted only from an independently
 * verified native layout/configuration observation. The owner's DoubleArray is
 * deliberately opaque: GMMP 4.2.1 stores one extra element in that array for
 * smaller layouts, so its size must never be treated as the band count.
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
    @Volatile private var observedBandCount: Int? = null

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
     * Publish a band count proved by a separate passive native boundary, for
     * example GMMP's DspManager creation flow or its uniquely resolved
     * dspSettings_bandCount preference.
     *
     * @return true when the effective runtime count changed.
     */
    fun observeBandCount(count: Int): Boolean {
        if (!GmmpEqualizerBandPolicy.isSupported(count)) return false
        var changed = false
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            changed = observedBandCount != count
            observedBandCount = count
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
        val localBandCount = observedBandCount?.takeIf(GmmpEqualizerBandPolicy::isSupported) ?: return null
        return Target(localInstance, localHandler, localPre, localBand, localEnabled, localBandCount)
    }

    private fun notifyReady() {
        readyListeners.forEach { listener -> runCatching(listener) }
    }
}
