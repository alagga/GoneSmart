package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredAutoDjNextPolicyTest {
    @Test fun `only exact native Next controls are eligible`() {
        assertTrue(DeferredAutoDjNextPolicy.isNativeNextButton("player_next_button"))
        assertTrue(DeferredAutoDjNextPolicy.isNativeNextButton("nextTrack"))
        assertFalse(DeferredAutoDjNextPolicy.isNativeNextButton("next_playlist"))
        assertFalse(DeferredAutoDjNextPolicy.isNativeNextButton("skip_silence"))
        assertFalse(DeferredAutoDjNextPolicy.isNativeNextButton(null))
    }

    @Test fun `repeat presses are bounded`() {
        assertEquals(1, DeferredAutoDjNextPolicy.increment(0))
        assertEquals(DeferredAutoDjNextPolicy.MAX_PENDING_SKIPS,
            DeferredAutoDjNextPolicy.increment(DeferredAutoDjNextPolicy.MAX_PENDING_SKIPS))
    }

    @Test fun `manual append is allowed but new playback is not`() {
        val ids = setOf(30L, 31L)
        assertTrue(DeferredAutoDjNextPolicy.canReplay(3, 3, ids, setOf(31L, 40L), true, true))
        assertFalse(DeferredAutoDjNextPolicy.canReplay(3, 4, ids, setOf(31L, 40L), true, true))
        assertFalse(DeferredAutoDjNextPolicy.canReplay(3, 3, ids, setOf(40L), true, true))
        assertFalse(DeferredAutoDjNextPolicy.canReplay(3, 3, ids, ids, false, true))
        assertFalse(DeferredAutoDjNextPolicy.canReplay(3, 3, ids, ids, true, false))
    }

    @Test fun `extra Next requires a new native queue entry`() {
        val before = setOf(12L, 13L)
        assertFalse(DeferredAutoDjNextPolicy.hasNativeQueueAddition(before, before))
        assertTrue(DeferredAutoDjNextPolicy.hasNativeQueueAddition(before, setOf(12L, 13L, 14L)))
        assertFalse(DeferredAutoDjNextPolicy.hasNativeQueueAddition(before, setOf(13L)))
    }
}
