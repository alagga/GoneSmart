package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjPlaybackTransitionProbePolicyTest {
    @Test fun `active music after a stable Current needs no additional probe`() {
        assertEquals("audio-active",
            AutoDjPlaybackTransitionProbePolicy.status(true, true))
        assertFalse(AutoDjPlaybackTransitionProbePolicy.shouldRecheck(true, true))
    }

    @Test fun `inactive music on the same Current schedules one confirmation`() {
        assertEquals("audio-inactive-suspected",
            AutoDjPlaybackTransitionProbePolicy.status(true, false))
        assertTrue(AutoDjPlaybackTransitionProbePolicy.shouldRecheck(true, false))
    }

    @Test fun `manual skip or unknown audio state does not imply a hang`() {
        assertEquals("current-changed-or-unavailable",
            AutoDjPlaybackTransitionProbePolicy.status(false, false))
        assertEquals("audio-state-unavailable",
            AutoDjPlaybackTransitionProbePolicy.status(true, null))
        assertFalse(AutoDjPlaybackTransitionProbePolicy.shouldRecheck(false, false))
        assertFalse(AutoDjPlaybackTransitionProbePolicy.shouldRecheck(true, null))
    }
}
