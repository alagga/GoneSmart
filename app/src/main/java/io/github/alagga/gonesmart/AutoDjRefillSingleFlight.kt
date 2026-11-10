package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * GMMP may call qr.z() concurrently while both callers wait for the same
 * recommendation-pool fill. Only one normal Smart-DJ refill may own the
 * subsequent native queue insertion at a time. A second caller can join the
 * active refill before GMMP queries current+1: simply returning immediately
 * allowed its next-source lookup to cache null while the first refill was
 * still writing the follower.
 *
 * Track Mix's native continuity and pre-play bootstrap paths are intentionally
 * not routed through this gate.
 */
internal class AutoDjRefillSingleFlight {
    private class Flight(
        val owner: Thread = Thread.currentThread(),
        val completed: CountDownLatch = CountDownLatch(1)
    )

    enum class JoinResult {
        COMPLETED,
        TIMED_OUT,
        SAME_THREAD,
        NO_ACTIVE_REFILL,
        INTERRUPTED
    }

    private val active = AtomicReference<Flight?>(null)

    fun tryEnter(): Boolean = active.compareAndSet(null, Flight())

    /**
     * Waits for the already-started native refill to finish; never invokes a
     * second native writer or provider query. The owner cannot wait on itself,
     * and the caller controls the short playback-critical timeout.
     */
    fun joinActive(timeoutMs: Long): JoinResult {
        val flight = active.get() ?: return JoinResult.NO_ACTIVE_REFILL
        if (flight.owner === Thread.currentThread()) return JoinResult.SAME_THREAD
        return try {
            if (flight.completed.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) {
                JoinResult.COMPLETED
            } else {
                JoinResult.TIMED_OUT
            }
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
