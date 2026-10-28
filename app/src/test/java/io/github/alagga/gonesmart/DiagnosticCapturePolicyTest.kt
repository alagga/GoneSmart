package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCapturePolicyTest {
    @Test
    fun onlyGoneSmartAndGmmpUidsAreSelected() {
        assertEquals("10123,10311", DiagnosticCapturePolicy.uidFilter(10123, 10311))
        assertEquals("10123", DiagnosticCapturePolicy.uidFilter(10123, 10123))
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticCapturePolicy.uidFilter(0, 10311)
        }
    }

    @Test
    fun snapshotIsBoundedByLineCountAndByteCount() {
        assertFalse(DiagnosticCapturePolicy.shouldTruncate(29_999, 7_999_990, 10))
        assertTrue(DiagnosticCapturePolicy.shouldTruncate(30_000, 0, 1))
        assertTrue(DiagnosticCapturePolicy.shouldTruncate(10, 7_999_999, 2))
        assertTrue(DiagnosticCapturePolicy.shouldTruncate(10, 0, 8_000_001))
    }

    @Test
    fun onlyRealThreadtimeEntriesContributeToSnapshotCoverage() {
        assertEquals(
            "10-28 15:28:48.333",
            DiagnosticCapturePolicy.threadtimeStamp(
                "10-28 15:28:48.333  6792  6792 I GoneSmart: SMART DJ"
            )
        )
        assertEquals(null, DiagnosticCapturePolicy.threadtimeStamp("--------- beginning of main"))
        assertEquals(null, DiagnosticCapturePolicy.threadtimeStamp("Logcat failed: permission denied"))
    }
}
