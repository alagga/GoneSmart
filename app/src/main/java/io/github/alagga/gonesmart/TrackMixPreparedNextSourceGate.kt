package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Request-local handoff for GMMP 4.2.1's natural next-source lookup.
 *
 * The prepared Queue-context Track Mix worker must establish native Current+1
 * before MusicService reads it. This gate never changes GMMP's source or
 * invokes playback; it merely bounds the wait for the independently verified
 * native queue. It is allocated only for an explicit Track Mix request.
 */
internal class TrackMixPreparedNextSourceGate {
    companion object {
        const val MAX_WAIT_MS = 3_000L

        fun shouldWait(
            source: String,
            nativePlayAccepted: Boolean,
            preparedCount: Int,
            initialSize: Int,
            stage: String
        ): Boolean = source == "menu_gm_context_queue" &&
            nativePlayAccepted &&
            preparedCount > 0 &&
            initialSize > 1 &&
            stage in setOf(
                "WAIT_PLAY",
                "ISOLATING_PREPARED",
                "NORMALIZE_PREPARED",
                "PRUNE",
                "FILLING"
            )
    }

    private val finished = CountDownLatch(1)
    @Volatile private var verified = false

    fun verifiedQueueReady() {
        verified = true
        finished.countDown()
    }

    fun abort() {
        finished.countDown()
    }

    fun awaitVerifiedQueue(timeoutMs: Long = MAX_WAIT_MS): Boolean = try {
        finished.await(timeoutMs.coerceIn(0L, MAX_WAIT_MS), TimeUnit.MILLISECONDS) && verified
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
