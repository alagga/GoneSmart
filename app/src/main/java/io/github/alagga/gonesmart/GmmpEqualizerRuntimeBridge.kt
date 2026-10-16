package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
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
        val enabledSetter: Method
    )

    private val lock = Any()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var instance: Any? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var preSetter: Method? = null
    @Volatile private var bandSetter: Method? = null
    @Volatile private var enabledSetter: Method? = null

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
            instance = observedInstance
            if (looper != null) handler = Handler(looper)
            currentUnsafe() != null && (!wasReady || changedInstance)
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
        return Target(localInstance, localHandler, localPre, localBand, localEnabled)
    }

    private fun notifyReady() {
        readyListeners.forEach { listener -> runCatching(listener) }
    }
}
