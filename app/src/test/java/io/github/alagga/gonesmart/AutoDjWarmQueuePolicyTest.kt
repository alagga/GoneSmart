package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDjWarmQueuePolicyTest {
    @Test fun `only the last current track requests a refill`() {
        for (index in 0..3) {
            assertEquals(0, AutoDjWarmQueuePolicy.refillCount(5, index, 1))
        }
        assertEquals(1, AutoDjWarmQueuePolicy.refillCount(5, 4, 1))
    }

    @Test fun `uses GMMP upcoming track count without extra safety buffer`() {
        assertEquals(1, AutoDjWarmQueuePolicy.refillCount(13, 12, 1))
        assertEquals(3, AutoDjWarmQueuePolicy.refillCount(13, 12, 3))
        assertEquals(4, AutoDjWarmQueuePolicy.refillCount(13, 12, 4))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(13, 11, 3))
    }

    @Test fun `invalid current fails closed`() {
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(0, 0, 1))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(3, -1, 1))
        assertEquals(0, AutoDjWarmQueuePolicy.refillCount(3, 3, 1))
    }
}
