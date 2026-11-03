package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoDjPrewarmPolicyTest {
    @Test fun `verified ordinary playback prewarms Smart DJ without waiting for EOF`() {
        assertTrue(SmartAutoDjPrewarmPolicy.shouldPrewarm(
            enabled = true, currentPosition = 6,
            trackMixPending = false, managedTrackMix = false
        ))
    }

    @Test fun `unknown or invalid current positions do not start a search`() {
        for (pos in listOf(null, 0, -1)) {
            assertFalse(SmartAutoDjPrewarmPolicy.shouldPrewarm(
                true, pos, false, false
            ))
        }
    }

    @Test fun `disabled Smart DJ and explicit Track Mix are isolated`() {
        assertFalse(SmartAutoDjPrewarmPolicy.shouldPrewarm(false, 6, false, false))
        assertFalse(SmartAutoDjPrewarmPolicy.shouldPrewarm(true, 6, true, false))
        assertFalse(SmartAutoDjPrewarmPolicy.shouldPrewarm(true, 6, false, true))
    }
}
