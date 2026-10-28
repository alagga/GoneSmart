package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixPreparedNextSourceGateTest {
    @Test fun onlyPreparedQueueContextDuringNativePlayGetsTheBarrier() {
        listOf("WAIT_PLAY", "ISOLATING_PREPARED", "NORMALIZE_PREPARED", "PRUNE", "FILLING")
            .forEach { stage ->
                assertTrue(
                    TrackMixPreparedNextSourceGate.shouldWait(
                        "menu_gm_context_queue", true, 4, 5, stage
                    )
                )
            }
        assertFalse(TrackMixPreparedNextSourceGate.shouldWait(
            "menu_gm_context_queue", false, 4, 5, "WAIT_PLAY"
        ))
        assertFalse(TrackMixPreparedNextSourceGate.shouldWait(
            "menu_gm_context_queue", true, 0, 5, "WAIT_PLAY"
        ))
        assertFalse(TrackMixPreparedNextSourceGate.shouldWait(
            "menu_gm_context_queue", true, 4, 1, "WAIT_PLAY"
        ))
        assertFalse(TrackMixPreparedNextSourceGate.shouldWait(
            "menu_gm_context_playlist_details", true, 4, 5, "WAIT_PLAY"
        ))
        assertFalse(TrackMixPreparedNextSourceGate.shouldWait(
            "menu_gm_context_queue", true, 4, 5, "DONE"
        ))
    }

    @Test fun earlyNativeNextSourceResumesOnlyAfterVerifiedQueue() {
        val gate = TrackMixPreparedNextSourceGate()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val verified = AtomicBoolean(false)
        val waiter = Thread {
            entered.countDown()
            verified.set(gate.awaitVerifiedQueue(1_000L))
            finished.countDown()
        }
        waiter.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(30, TimeUnit.MILLISECONDS))
            gate.verifiedQueueReady()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(verified.get())
            assertTrue(gate.awaitVerifiedQueue(0L))
        } finally {
            gate.abort()
            waiter.join(1_000L)
        }
    }

    @Test fun failedRequestUnblocksWithoutClaimingNativeQueueReady() {
        val gate = TrackMixPreparedNextSourceGate()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val verified = AtomicBoolean(true)
        val waiter = Thread {
            entered.countDown()
            verified.set(gate.awaitVerifiedQueue(1_000L))
            finished.countDown()
        }
        waiter.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(30, TimeUnit.MILLISECONDS))
            gate.abort()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertFalse(verified.get())
        } finally {
            gate.abort()
            waiter.join(1_000L)
        }
    }

    @Test fun missingVerificationHasBoundedTimeout() {
        val gate = TrackMixPreparedNextSourceGate()
        assertFalse(gate.awaitVerifiedQueue(10L))
    }
}
