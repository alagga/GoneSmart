package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A one-shot, request-owned barrier for an observed native next-source lookup
 * or the preceding native qr.z return. The current song is already emitting
 * audio; a pending cold Smart Auto-DJ refill can finish on its existing worker
 * before GMMP caches null for current+1. This never starts a query, chooses a
 * track or invokes player methods. A timeout or any unverified queue result
 * releases GMMP unchanged.
 */
internal class AutoDjDeferredNextSourceGate {
    companion object {
        // Match the existing 60 s smart-preparation budget and allow a short
        // grace for the same worker's native qr.z append/readback. A shorter
        // timeout would recreate the cached-null source on a slow provider.
        const val MAX_WAIT_MS = 63_000L
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
        ): Boolean = acceptedNativeNextSource && shouldHoldNativeReturn(
            requestAgeMs = requestAgeMs,
            currentEntryId = currentEntryId,
            requestedTracks = requestedTracks,
            audioActive = audioActive,
            onMainThread = onMainThread,
            onDeferredWorker = onDeferredWorker,
            specialTrackMixActive = specialTrackMixActive
        )

        /**
         * qr.z returned before GMMP queried CURRENT+1 in the R84 trace.
         * Unlike the I2 observer this is the directly observed native refill
         * boundary, so it can also guard a lookup when I2 is not intercepted.
         */
        fun shouldHoldNativeReturn(
            requestAgeMs: Long,
            currentEntryId: Long,
            requestedTracks: Int,
            audioActive: Boolean?,
            onMainThread: Boolean,
            onDeferredWorker: Boolean,
            specialTrackMixActive: Boolean
        ): Boolean =
            requestAgeMs in 0L..MAX_REQUEST_AGE_MS &&
            currentEntryId > 0L && requestedTracks > 0 &&
            audioActive == true && !onMainThread &&
            !onDeferredWorker && !specialTrackMixActive
    }

    private val completed = CountDownLatch(1)
    // 0=pending, 1=verified, 2=failed/cancelled; cancellation is irreversible.
    private val state = AtomicInteger(0)

    fun finish(queueVerified: Boolean) {
        if (state.compareAndSet(0, if (queueVerified) 1 else 2)) {
            completed.countDown()
        }
    }

    fun abort() {
        if (state.compareAndSet(0, 2)) {
            completed.countDown()
        }
    }

    fun awaitVerifiedQueue(timeoutMs: Long = MAX_WAIT_MS): Boolean = try {
        completed.await(timeoutMs.coerceIn(0L, MAX_WAIT_MS), TimeUnit.MILLISECONDS) &&
            state.get() == 1
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
