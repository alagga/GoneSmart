package io.github.alagga.gonesmart

import io.github.alagga.gonesmart.AutoDjNativeFallbackAuditPolicy.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjNativeFallbackAuditPolicyTest {
    private val before = listOf(Row(11, 101, 1), Row(12, 102, 2))
    private val appended = before + Row(13, 103, 3)
    private fun audit(after: List<Row>?, requested: Int = 1, current: Long? = 12L) =
        AutoDjNativeFallbackAuditPolicy.audit(before, after, 12L, current, requested)

    @Test fun `original current and real appended fallback are verified`() {
        val result = audit(appended)
        assertTrue(result.verified)
        assertEquals(listOf(103L), result.addedTrackIds)
    }

    @Test fun `no native insertion must not claim fallback succeeded`() {
        assertEquals("no-rows-added", audit(before).status)
        assertEquals("after-queue-unavailable", audit(null).status)
        assertEquals("before-queue-unavailable",
            AutoDjNativeFallbackAuditPolicy.audit(null, appended, 12L, 12L, 1).status)
    }

    @Test fun `partial and extra native rows are separately diagnosed`() {
        assertEquals("partial-native-fill", audit(appended, requested = 2).status)
        assertEquals("native-overfilled",
            audit(appended + Row(14, 104, 4)).status)
    }

    @Test fun `changed current or existing title is rejected`() {
        assertFalse(audit(appended, current = 13L).verified)
        assertEquals("current-track-changed",
            audit(listOf(before[0], Row(12, 666, 2), Row(13, 103, 3))).status)
    }

    @Test fun `native fallback cannot leave duplicate or sparse positions`() {
        assertEquals("duplicate-queue-entry-id",
            audit(before + Row(12, 103, 3)).status)
        assertEquals("queue-positions-not-contiguous",
            audit(before + Row(13, 103, 4)).status)
    }

    @Test fun `native fallback must preserve and append after existing future`() {
        val future = before + Row(21, 121, 3)
        assertEquals("existing-current-or-future-changed",
            AutoDjNativeFallbackAuditPolicy.audit(
                future, appended, 12L, 12L, 1
            ).status)
        assertEquals("native-rows-not-appended",
            audit(listOf(Row(11, 101, 1), Row(13, 103, 2), Row(12, 102, 3))).status)
    }
}
