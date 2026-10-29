package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutoDjRefillSingleFlightTest {
    @Test
    fun overlappingRefillIsCoalescedButLaterRefillCanProceed() {
        val gate = AutoDjRefillSingleFlight()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val firstAcquired = AtomicBoolean(false)
        val worker = Thread {
            if (gate.tryEnter()) {
                firstAcquired.set(true)
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    gate.leave()
                    finished.countDown()
                }
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(firstAcquired.get())
            assertFalse(gate.tryEnter())
        } finally {
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            worker.join(5_000)
        }
        assertTrue(gate.tryEnter())
        gate.leave()
    }
}
