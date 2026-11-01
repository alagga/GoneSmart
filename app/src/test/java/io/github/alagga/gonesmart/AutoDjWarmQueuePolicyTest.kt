package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDjWarmQueuePolicyTest {
    @Test fun `one track before tail gets a spare before end of audio`() {
        assertEquals(1, AutoDjWarmQueuePolicy.refillCount(13, 11, 1))
        assertEquals(2, AutoDjWarmQueuePolicy.refillCount(13, 12, 1))
    }

    @Test fun `a buffered queue is left untouched`() {
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(13, 9, 1))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(13, 8, 3))
    }

    @Test fun `invalid current cannot create random auto dj tracks`() {
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(0, 0, 1))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(3, -1, 1))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(3, 3, 1))
    }
}
