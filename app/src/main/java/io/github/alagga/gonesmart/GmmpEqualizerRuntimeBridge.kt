package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local handoff from the single passive Equalizer observer hook to the
 * profile application path. This avoids installing a second competing hook on
 * the same GMMP Equalizer methods merely to capture the live owner instance.
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
        val observedBandCount = detectBandCount(observedInstance)
        val notify = synchronized(lock) {
            val wasReady = currentUnsafe() != null
            val changedInstance = instance !== observedInstance
            val previousLooper = handler?.looper
            val previousBandCount = bandCount

            if (changedInstance) bandCount = null
            instance = observedInstance
            if (observedBandCount != null) bandCount = observedBandCount

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
            val changedBandCount = previousBandCount != bandCount
            currentUnsafe() != null &&
                (!wasReady || changedInstance || changedDispatcher || changedBandCount)
        }
        if (notify) notifyReady()
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
