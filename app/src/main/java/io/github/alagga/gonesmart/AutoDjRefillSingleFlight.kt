package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        INTERRUPTED,
        TIMED_OUT
    }

    private val active = AtomicReference<Flight?>(null)

    fun tryEnter(): Boolean = active.compareAndSet(null, Flight())

    /**
     * Wait for real native writer completion, without allowing a permanently
     * blocked owner to pin a second playback worker forever. On timeout the
     * caller must fail closed: NEVER start a duplicate native refill. The
     * owner retains the gate until its actual finally/leave() runs.
     *
     * The budget is only a liveness bound, NOT evidence that native queue
     * insertion succeeded. Normal refills release the latch immediately.
     * The caller must also avoid invoking this on GMMP's UI thread.
     */
    fun joinActive(timeoutMs: Long = 10_000L): JoinResult {
        require(timeoutMs > 0L)
        val flight = active.get() ?: return JoinResult.NO_ACTIVE_REFILL
        if (flight.owner === Thread.currentThread()) return JoinResult.SAME_THREAD
        return try {
            if (flight.completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
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
