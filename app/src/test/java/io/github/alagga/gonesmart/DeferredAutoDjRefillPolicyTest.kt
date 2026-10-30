package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredAutoDjRefillPolicyTest {
    @Test fun `manual enqueue does not cancel a deferred refill`() {
        assertTrue(eligible(original = setOf(20, 21), current = setOf(20, 21, 99)))
    }
    @Test fun `natural playback advancement can retain same session`() {
        assertTrue(eligible(original = setOf(20, 21), current = setOf(21)))
    }
    @Test fun `fresh playback with new queue entries cancels stale refill`() {
        assertFalse(eligible(original = setOf(20, 21), current = setOf(80, 81)))
    }
    @Test fun `session replacement cancels even with overlapping history`() {
        assertFalse(eligible(original = setOf(20, 21), current = setOf(20, 81), observedSession = 2))
    }
    @Test fun `reset recommendation pool cancels pending work`() {
        assertFalse(eligible(original = setOf(20), current = setOf(20), poolCurrent = false))
    }
    @Test fun `turning smart dj off cancels pending work`() {
        assertFalse(eligible(original = setOf(20), current = setOf(20), enabled = false))
    }

    private fun eligible(
        original: Set<Int>, current: Set<Int>, observedSession: Long = 1,
        poolCurrent: Boolean = true, enabled: Boolean = true
    ) = DeferredAutoDjRefillPolicy.canResume(
        expectedSessionId = 1, observedSessionId = observedSession,
        originalEntryIds = original.map(Int::toLong).toSet(),
        observedEntryIds = current.map(Int::toLong).toSet(),
        recommendationSessionIsCurrent = poolCurrent,
        smartDjEnabled = enabled
    )
}
