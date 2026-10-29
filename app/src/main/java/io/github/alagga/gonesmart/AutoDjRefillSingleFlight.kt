package io.github.alagga.gonesmart

import java.util.concurrent.atomic.AtomicBoolean

/**
 * GMMP may call qr.z() concurrently while both callers wait for the same
 * recommendation-pool fill. Only one normal Smart-DJ refill may own the
 * subsequent native queue insertion at a time; its duplicate caller is
 * already covered by the in-flight request.
 *
 * Track Mix's native continuity and pre-play bootstrap paths are intentionally
 * not routed through this gate.
 */
internal class AutoDjRefillSingleFlight {
    private val active = AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun leave() {
        active.set(false)
    }
}
