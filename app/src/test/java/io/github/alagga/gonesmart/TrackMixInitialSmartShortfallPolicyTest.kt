package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixInitialSmartShortfallPolicyTest {
    private fun defer(
        actualSessionId: Long = 8L,
        actualCurrentEntryId: Long? = 100L,
        generated: Set<Long> = setOf(101L, 102L, 103L),
        upcoming: Set<Long> = setOf(101L, 102L, 103L),
        sameReceiver: Boolean = true
    ) = TrackMixInitialSmartShortfallPolicy.shouldDefer(
        expectedSessionId = 8L,
        actualSessionId = actualSessionId,
        expectedCurrentEntryId = 100L,
        actualCurrentEntryId = actualCurrentEntryId,
        expectedGeneratedEntryIds = generated,
        actualUpcomingEntryIds = upcoming,
        receiverMatches = sameReceiver
    )

    @Test fun `partial smart initial refill is not immediately padded natively`() {
        assertTrue(defer())
        // User manually added a song: preserve it without revoking ownership.
        assertTrue(defer(upcoming = setOf(101L, 102L, 103L, 999L)))
    }

    @Test fun `new current or playback session permits normal fallback`() {
        assertFalse(defer(actualCurrentEntryId = 101L))
        assertFalse(defer(actualCurrentEntryId = null))
        assertFalse(defer(actualSessionId = 9L))
        assertFalse(defer(sameReceiver = false))
    }

    @Test fun `zero smart matches or altered queue never holds GMMP`() {
        assertFalse(defer(generated = emptySet()))
        assertFalse(defer(upcoming = setOf(101L, 102L)))
        assertFalse(defer(upcoming = emptySet()))
    }
}
