package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun overlappingNativeSourceRequestWaitsForTheFirstRefillToComplete() {
        val gate = AutoDjRefillSingleFlight()
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val ownerFinished = CountDownLatch(1)
        val joinerFinished = CountDownLatch(1)
        val joined = AtomicReference<AutoDjRefillSingleFlight.JoinResult>()
        val owner = Thread {
            assertTrue(gate.tryEnter())
            ownerEntered.countDown()
            try {
                releaseOwner.await(5, TimeUnit.SECONDS)
            } finally {
                gate.leave()
                ownerFinished.countDown()
            }
        }
        owner.start()
        val joiner = Thread {
            joined.set(gate.joinActive(3_000))
            joinerFinished.countDown()
        }
        try {
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS))
            assertFalse(gate.tryEnter())
            joiner.start()
            assertFalse(joinerFinished.await(50, TimeUnit.MILLISECONDS))
        } finally {
            releaseOwner.countDown()
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS))
            assertTrue(joinerFinished.await(5, TimeUnit.SECONDS))
            owner.join(5_000)
            joiner.join(5_000)
        }
        assertEquals(AutoDjRefillSingleFlight.JoinResult.COMPLETED, joined.get())
        assertEquals(
            AutoDjRefillSingleFlight.JoinResult.NO_ACTIVE_REFILL,
            gate.joinActive(0)
        )
    }

    @Test
    fun reentrantAndSlowRefillsCannotDeadlockPlayback() {
        val gate = AutoDjRefillSingleFlight()
        assertTrue(gate.tryEnter())
        try {
            assertEquals(
                AutoDjRefillSingleFlight.JoinResult.SAME_THREAD,
                gate.joinActive(500)
            )
            val result = AtomicReference<AutoDjRefillSingleFlight.JoinResult>()
            val worker = Thread { result.set(gate.joinActive(20)) }
            worker.start()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertEquals(AutoDjRefillSingleFlight.JoinResult.TIMED_OUT, result.get())
            assertFalse(gate.tryEnter())
        } finally {
            gate.leave()
        }
        assertTrue(gate.tryEnter())
        gate.leave()
    }
}
