package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A one-shot, request-owned barrier for GMMP's proven natural next-source
 * lookup. The current song is already emitting audio; a pending cold Smart
 * Auto-DJ refill may finish on its existing worker before GMMP caches null
 * for current+1. This never starts a query, chooses a track or invokes player
 * methods. A timeout or any unverified queue result releases GMMP unchanged.
 */
internal class AutoDjDeferredNextSourceGate {
    companion object {
        const val MAX_WAIT_MS = 12_000L
        const val MAX_REQUEST_AGE_MS = 1_500L

        fun shouldAwait(
            acceptedNativeNextSource: Boolean,
            requestAgeMs: Long,
            currentEntryId: Long,
            requestedTracks: Int,
            audioActive: Boolean?,
            onMainThread: Boolean,
            onDeferredWorker: Boolean,
            specialTrackMixActive: Boolean
        ): Boolean = acceptedNativeNextSource &&
            requestAgeMs in 0L..MAX_REQUEST_AGE_MS &&
            currentEntryId > 0L && requestedTracks > 0 &&
            audioActive == true && !onMainThread &&
            !onDeferredWorker && !specialTrackMixActive
    }

    private val completed = CountDownLatch(1)
    @Volatile private var verified = false

    fun finish(queueVerified: Boolean) {
        verified = queueVerified
        completed.countDown()
    }

    fun abort() {
        completed.countDown()
    }

    fun awaitVerifiedQueue(timeoutMs: Long = MAX_WAIT_MS): Boolean = try {
        completed.await(timeoutMs.coerceIn(0L, MAX_WAIT_MS), TimeUnit.MILLISECONDS) &&
            verified
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
