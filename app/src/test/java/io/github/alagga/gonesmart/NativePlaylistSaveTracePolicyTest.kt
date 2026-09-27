package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistSaveTracePolicyTest {
    private fun stack(vararg frames: String): Array<StackTraceElement> =
        frames.map {
            val className = it.substringBeforeLast('.')
            val methodName = it.substringAfterLast('.')
            StackTraceElement(className, methodName, "redacted.kt", 1)
        }.toTypedArray()

    @Test fun recognizesBothNativeCreateCallers() {
        assertEquals(
            NativePlaylistSaveTracePolicy.Origin.MAIN_CREATE,
            NativePlaylistSaveTracePolicy.origin(
                stack("java.lang.Thread.getStackTrace", "hp3.d", "sp3.invoke")
            )
        )
        assertEquals(
            NativePlaylistSaveTracePolicy.Origin.PICKER_CREATE,
            NativePlaylistSaveTracePolicy.origin(
                stack("hp3.d", "fo3.invoke")
            )
        )
    }

    @Test fun identifiesExistingPlaylistOperationsWithoutExposingArguments() {
        val frames = stack(
            "io.github.alagga.gonesmart.GoneSmartModule.hook",
            "hp3.d", "io3.r", "zp3.M"
        )
        assertEquals(
            NativePlaylistSaveTracePolicy.Origin.EXISTING_PLAYLIST_FLOW,
            NativePlaylistSaveTracePolicy.origin(frames)
        )
        assertEquals(
            listOf("hp3.d", "io3.r", "zp3.M"),
            NativePlaylistSaveTracePolicy.visibleFrames(frames)
        )
        assertFalse(
            NativePlaylistSaveTracePolicy.visibleFrames(frames)
                .any { it.contains("redacted.kt") }
        )
    }

    @Test fun unknownNativeCallsRemainUnknown() {
        assertEquals(
            NativePlaylistSaveTracePolicy.Origin.OTHER,
            NativePlaylistSaveTracePolicy.origin(stack("x6.b", "t6.f"))
        )
    }

    @Test fun identifiesOnlyOurOwnScannerInvocation() {
        assertTrue(NativePlaylistSaveTracePolicy.isGoneSmartScanner(
            stack("io.github.alagga.gonesmart.NativeGmmpPlaylistMover.scan", "t6.f")
        ))
        assertFalse(NativePlaylistSaveTracePolicy.isGoneSmartScanner(
            stack("sp3.invoke", "t6.f")
        ))
    }
}
