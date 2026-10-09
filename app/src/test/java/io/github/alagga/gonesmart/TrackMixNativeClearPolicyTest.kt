package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeClearPolicyTest {
    @Test
    fun `single selected CURRENT row is accepted independently of queue entry identity`() {
        assertTrue(
            TrackMixNativeClearPolicy.isIsolated(
                selectedTrackId = 19086L,
                queueSize = 1,
                currentTrackId = 19086L,
                currentRows = 1
            )
        )
    }

    @Test
    fun `wrong current track is rejected`() {
        assertFalse(
            TrackMixNativeClearPolicy.isIsolated(
                selectedTrackId = 19086L,
                queueSize = 1,
                currentTrackId = 19084L,
                currentRows = 1
            )
        )
    }

    @Test
    fun `multiple rows or ambiguous CURRENT state are rejected`() {
        assertFalse(
            TrackMixNativeClearPolicy.isIsolated(
                selectedTrackId = 19086L,
                queueSize = 2,
                currentTrackId = 19086L,
                currentRows = 1
            )
        )
        assertFalse(
            TrackMixNativeClearPolicy.isIsolated(
                selectedTrackId = 19086L,
                queueSize = 1,
                currentTrackId = 19086L,
                currentRows = 0
            )
        )
    }
}
