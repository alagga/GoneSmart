package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local handoff from the passive GMMP Equalizer observers to the
 * profile application path. The r61 setter observer supplies the live owner
 * and setters. Runtime band count prefers an authoritative passive layout or
 * preference observation, with the owner's unique DoubleArray size retained as
 * a compatibility fallback so Equalizer application is never blocked merely
 * because the auxiliary layout observer was unavailable.
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
    @Volatile private var fallbackBandCount: Int? = null

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
        val detectedFallback = detectBandCount(observedInstance)
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            val previousEffectiveBandCount = effectiveBandCountUnsafe()
            val changedInstance = instance !== observedInstance
            val previousLooper = handler?.looper

            if (changedInstance) fallbackBandCount = null
            instance = observedInstance
            if (detectedFallback != null) fallbackBandCount = detectedFallback

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
            val changedBandCount = previousEffectiveBandCount != effectiveBandCountUnsafe()
            currentUnsafe() != null &&
                (!wasReady || changedInstance || changedDispatcher || changedBandCount)
        }
        if (notify) notifyReady()
    }

    /**
     * Publish a band count proven outside the r61 fallback (for example from
     * GMMP's DspManager creation boundary or its uniquely resolved preference).
     * This authoritative value wins over a stale owner-array fallback.
     *
     * @return true when the effective runtime count changed.
     */
    fun observeBandCount(count: Int): Boolean {
        if (!GmmpEqualizerBandPolicy.isSupported(count)) return false
        var changed = false
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            val previous = effectiveBandCountUnsafe()
            observedBandCount = count
            changed = previous != effectiveBandCountUnsafe()
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
        val localBandCount = effectiveBandCountUnsafe() ?: return null
        return Target(localInstance, localHandler, localPre, localBand, localEnabled, localBandCount)
    }

    private fun effectiveBandCountUnsafe(): Int? =
        observedBandCount?.takeIf(GmmpEqualizerBandPolicy::isSupported)
            ?: fallbackBandCount?.takeIf(GmmpEqualizerBandPolicy::isSupported)

    private fun detectBandCount(observedInstance: Any): Int? {
        val field = observedInstance.javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filter { it.type == DoubleArray::class.java }
            .singleOrNull()
            ?.apply { isAccessible = true }
            ?: return null
        val count = runCatching { (field.get(observedInstance) as? DoubleArray)?.size }.getOrNull()
        return count?.takeIf(GmmpEqualizerBandPolicy::isSupported)
    }

    private fun notifyReady() {
        readyListeners.forEach { listener -> runCatching(listener) }
    }
}
