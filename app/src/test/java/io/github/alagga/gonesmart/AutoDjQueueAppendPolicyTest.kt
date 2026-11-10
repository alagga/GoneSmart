package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDjQueueAppendPolicyTest {
    private val before = listOf(12L to 1, 13L to 2)

    @Test fun `native callback must actually append a real queue entry`() {
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            before, before, 13, 13, 1
        ))
        assertEquals(true, AutoDjQueueAppendPolicy.confirmed(
            before, before + (14L to 3), 13, 13, 1
        ))
    }

    @Test fun `current identity must stay unchanged`() {
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            before, before + (14L to 3), 13, 14, 1
        ))
    }

    @Test fun `duplicate IDs and gaps in queue positions are inconsistent`() {
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            before, before + (13L to 3), 13, 13, 1
        ))
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            before, before + (14L to 4), 13, 13, 1
        ))
    }

    @Test fun `the native request cannot overfill or lose a future entry`() {
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            before, before + (14L to 3) + (15L to 4), 13, 13, 1
        ))
        val future = before + (15L to 3)
        assertEquals(false, AutoDjQueueAppendPolicy.confirmed(
            future, before + (14L to 3), 13, 13, 1
        ))
    }
}
