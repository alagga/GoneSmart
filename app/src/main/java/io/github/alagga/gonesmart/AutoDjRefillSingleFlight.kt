package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * GMMP may call qr.z() concurrently while both callers wait for the same
 * recommendation-pool fill. Only one normal Smart-DJ refill may own the
 * subsequent native queue insertion at a time. A second native playback
 * worker can wait for the writer's actual completion before GMMP queries
 * current+1. The gate is never held across the asynchronous provider fill.
 *
 * Track Mix's verified pre-play bootstrap/zero-pool source preparation paths
 * remain outside this gate; later GMMP-owned continuation enters normally.
 */
internal class AutoDjRefillSingleFlight {
    private class Flight(
        val owner: Thread = Thread.currentThread(),
        val completed: CountDownLatch = CountDownLatch(1)
    )

    enum class JoinResult {
        COMPLETED,
        SAME_THREAD,
        NO_ACTIVE_REFILL,
        INTERRUPTED
    }

    private val active = AtomicReference<Flight?>(null)

    fun tryEnter(): Boolean = active.compareAndSet(null, Flight())

    /**
     * Waits for the already-started native refill to finish; never invokes a
     * second native writer or provider query. There is no guessed elapsed-time
     * completion threshold: the latch opens only when the native writer leaves.
     * The caller must guard against waiting from the UI thread.
     */
    fun joinActive(): JoinResult {
        val flight = active.get() ?: return JoinResult.NO_ACTIVE_REFILL
        if (flight.owner === Thread.currentThread()) return JoinResult.SAME_THREAD
        return try {
            flight.completed.await()
            JoinResult.COMPLETED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            JoinResult.INTERRUPTED
        }
    }

    fun leave() {
        val flight = active.get() ?: return
        if (flight.owner !== Thread.currentThread()) return
        if (active.compareAndSet(flight, null)) flight.completed.countDown()
    }
}
