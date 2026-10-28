package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatBufferPolicyTest {
    @Test
    fun parsesOnlyCapacityNotConsumedOrReadableMetrics() {
        val parsed = LogcatBufferPolicy.parse(
            "main: ring buffer is 5 MiB (4 MiB consumed, 20 MiB readable)\n" +
                "system: ring buffer is 2 MiB (717 KiB consumed)\n" +
                "crash: ring buffer is 512 KiB (0 B consumed)\n" +
                "kernel: ring buffer is 4 MiB (0 B consumed)\n"
        )
        assertEquals(5L * 1024 * 1024, parsed["main"]?.sizeBytes)
        assertEquals(2L * 1024 * 1024, parsed["system"]?.sizeBytes)
        assertEquals(512L * 1024, parsed["crash"]?.sizeBytes)
        assertEquals(3, parsed.size)
        assertEquals("main: 5 MiB  •  system: 2 MiB  •  crash: 512 KiB", LogcatBufferPolicy.display(parsed))
    }

    @Test
    fun unknownOrOverflowingCapacityFailsClosed() {
        assertTrue(LogcatBufferPolicy.parse("main: error reading size").isEmpty())
        assertTrue(LogcatBufferPolicy.parse("main: ring buffer is 9999999999999999 GiB").isEmpty())
        assertFalse(LogcatBufferPolicy.restorable(0))
        assertFalse(LogcatBufferPolicy.restorable(1025))
        assertFalse(LogcatBufferPolicy.restorable(128L * 1024 * 1024))
        assertTrue(LogcatBufferPolicy.restorable(512L * 1024))
    }

    @Test
    fun acceptsAnOemClampedIncreaseAndVerifiesTheActualAppliedSize() {
        val original = 128L * 1024
        val actual = 5L * 1024 * 1024
        assertTrue(LogcatBufferPolicy.acceptedIncrease(original, actual))
        assertFalse(LogcatBufferPolicy.acceptedIncrease(original, original))
        assertFalse(LogcatBufferPolicy.acceptedIncrease(actual, original))
        assertFalse(LogcatBufferPolicy.acceptedIncrease(original, 16L * 1024 * 1024))
        assertEquals(
            LogcatBufferPolicy.RestoreState.VERIFIED_APPLIED_SIZE,
            LogcatBufferPolicy.restoreState(original, actual, actual)
        )
        assertEquals(
            LogcatBufferPolicy.RestoreState.CHANGED_EXTERNALLY,
            LogcatBufferPolicy.restoreState(original, actual, 4L * 1024 * 1024)
        )
    }

    @Test
    fun previousBuildCanRestoreOnlyAfterSeparateLegacyConfirmation() {
        val original = 128L * 1024
        val actual = 5L * 1024 * 1024
        assertEquals(
            LogcatBufferPolicy.RestoreState.LEGACY_APPLIED_SIZE_UNKNOWN,
            LogcatBufferPolicy.restoreState(original, null, actual)
        )
        assertEquals(
            LogcatBufferPolicy.RestoreState.ALREADY_ORIGINAL,
            LogcatBufferPolicy.restoreState(original, null, original)
        )
    }
}
