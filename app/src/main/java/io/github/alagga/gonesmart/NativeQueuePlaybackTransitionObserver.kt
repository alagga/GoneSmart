package io.github.alagga.gonesmart

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Passive observer for GMMP's natural playback/event call chain.
 *
 * Discovery never invokes a candidate method. The original invocation is
 * allowed to proceed exactly once; before/after state is sampled read-only.
 * Delayed checks are deliberately bounded so compatibility discovery cannot
 * turn nested playback callbacks into sustained main-thread work.
 */
internal class NativeQueuePlaybackTransitionObserver {
    companion object {
        private const val TAG = "GoneSmartFlip"
        private const val MAX_LOGS = 24
        private const val MAX_PENDING_DELAYED = 8
        private const val DELAY_MS = 120L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logCount = AtomicInteger(0)
    private val pendingDelayed = AtomicInteger(0)
    private val reported =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun aroundNaturalInvocation(
        service: Any?,
        method: Method,
        args: List<Any?>,
        autoDj: Any?,
        proceed: () -> Any?
    ): Any? {
        if (autoDj == null) return proceed()
        val before = NativeQueuePlaybackDiagnostics.snapshot(service, autoDj)
        val result = proceed()
        val after = NativeQueuePlaybackDiagnostics.snapshot(service, autoDj)
        val immediate = NativeQueuePlaybackDiagnostics.changes(before, after)
        if (immediate.isNotEmpty()) {
            report(method, args, "immediate", immediate)
        } else if (
            logCount.get() < MAX_LOGS &&
            pendingDelayed.incrementAndGet() <= MAX_PENDING_DELAYED
        ) {
            mainHandler.postDelayed({
                try {
                    val delayed = NativeQueuePlaybackDiagnostics.snapshot(
                        service,
                        autoDj
                    )
                    val changes = NativeQueuePlaybackDiagnostics.changes(
                        before,
                        delayed
                    )
                    if (changes.isNotEmpty()) {
                        report(method, args, "delayed-${DELAY_MS}ms", changes)
                    }
                } finally {
                    pendingDelayed.decrementAndGet()
                }
            }, DELAY_MS)
        } else if (pendingDelayed.get() > MAX_PENDING_DELAYED) {
            pendingDelayed.decrementAndGet()
        }
        return result
    }

    private fun report(
        method: Method,
        args: List<Any?>,
        phase: String,
        changes: List<String>
    ) {
        if (logCount.get() >= MAX_LOGS) return
        val methodSignature = method.declaringClass.name + "." + method.name +
            "(" + method.parameterTypes.joinToString(",") { it.name } + ")"
        val key = methodSignature + "|" + changes.joinToString(";")
        if (!reported.add(key)) return
        if (logCount.incrementAndGet() > MAX_LOGS) return
        Log.i(
            TAG,
            "QUEUE PLAYBACK STATE TRANSITION | method=$methodSignature | " +
                "phase=$phase | changes=${changes.joinToString(",")} | " +
                "args=${NativeQueuePlaybackDiagnostics.describeArguments(args)}"
        )
    }
}
