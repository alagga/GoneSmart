package io.github.alagga.gonesmart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjDeferredNextSourceGateTest {
    private fun eligible(
        accepted: Boolean = true,
        age: Long = 2L,
        entryId: Long = 17L,
        requested: Int = 1,
        audioActive: Boolean? = true,
        main: Boolean = false,
        deferredWorker: Boolean = false,
        specialTrackMix: Boolean = false
    ) = AutoDjDeferredNextSourceGate.shouldAwait(
        acceptedNativeNextSource = accepted,
        requestAgeMs = age,
        currentEntryId = entryId,
        requestedTracks = requested,
        audioActive = audioActive,
        onMainThread = main,
        onDeferredWorker = deferredWorker,
        specialTrackMixActive = specialTrackMix
    )

    @Test fun onlyEarlyAcceptedActiveAudioCanWait() {
        assertTrue(eligible())
        assertFalse(eligible(accepted = false))
        assertFalse(eligible(age = -1L))
        assertFalse(eligible(age = AutoDjDeferredNextSourceGate.MAX_REQUEST_AGE_MS + 1))
        assertFalse(eligible(entryId = 0L))
        assertFalse(eligible(requested = 0))
        assertFalse(eligible(audioActive = false))
        assertFalse(eligible(audioActive = null))
        assertFalse(eligible(main = true))
        assertFalse(eligible(deferredWorker = true))
        assertFalse(eligible(specialTrackMix = true))
    }

    @Test fun verifiedNativeQueueReleasesPendingSource() {
        val gate = AutoDjDeferredNextSourceGate()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val waiter = Thread {
            entered.countDown()
            result.set(gate.awaitVerifiedQueue(1_000L))
            finished.countDown()
        }
        waiter.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(25L, TimeUnit.MILLISECONDS))
            gate.finish(true)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(result.get())
        } finally {
            gate.abort()
            waiter.join(1_000L)
        }
    }

    @Test fun failedRefillOrTimeoutNeverVerifiesSource() {
        val failed = AutoDjDeferredNextSourceGate()
        failed.finish(false)
        assertFalse(failed.awaitVerifiedQueue(0L))
        val abandoned = AutoDjDeferredNextSourceGate()
        abandoned.abort()
        assertFalse(abandoned.awaitVerifiedQueue(0L))
        assertFalse(AutoDjDeferredNextSourceGate().awaitVerifiedQueue(5L))
    }
}
