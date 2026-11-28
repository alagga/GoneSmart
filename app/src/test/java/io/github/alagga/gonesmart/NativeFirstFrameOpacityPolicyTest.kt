package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFirstFrameOpacityPolicyTest {
    @Test fun positiveNativeOriginalSurvivesReentrantMasking() {
        assertEquals(1f, NativeFolderAlphaLeasePolicy.original(0f, null), 0f)
        assertEquals(.8f, NativeFolderAlphaLeasePolicy.original(0f, .8f), 0f)
    }

    @Test fun independentFirstFrameScopesRequireExactOwnership() {
        assertTrue(FirstFrameOpacityPolicy.matches("PLAYLIST_LIST", "PLAYLIST_LIST"))
        assertTrue(FirstFrameOpacityPolicy.matches("SMART_LIST", "SMART_LIST"))
        assertFalse(FirstFrameOpacityPolicy.matches("PLAYLIST_LIST", "SMART_LIST"))
        assertFalse(FirstFrameOpacityPolicy.matches("SMART_LIST", "PLAYLIST_LIST"))
    }

    @Test fun staleTimeoutTokenMustNotRestoreNewLease() {
        val a = Any()
        val b = Any()
        assertTrue(FirstFrameOpacityPolicy.current(a, a))
        assertFalse(FirstFrameOpacityPolicy.current(a, b))
    }
}
