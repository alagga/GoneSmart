package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeFallbackPrefillPolicyTest {
    @Test fun `verified zero-match native file session may call qr z directly`() {
        assertTrue(TrackMixInitialFillPolicy.canRunNativeFileFallbackPrefill(
            stage = "NATIVE_FILE_FALLBACK",
            hasVerifiedSeed = true, preparedCount = 0, receiverMatches = true
        ))
    }

    @Test fun `prepared matches and foreign playback never bypass providers`() {
        for (stage in listOf("NATIVE_FILE_PREPARE", "NATIVE_FILE_ENQUEUE", "DONE", null)) {
            assertFalse(TrackMixInitialFillPolicy.canRunNativeFileFallbackPrefill(
                stage, true, 0, true
            ))
        }
        assertFalse(TrackMixInitialFillPolicy.canRunNativeFileFallbackPrefill(
            "NATIVE_FILE_FALLBACK", false, 0, true
        ))
        assertFalse(TrackMixInitialFillPolicy.canRunNativeFileFallbackPrefill(
            "NATIVE_FILE_FALLBACK", true, 1, true
        ))
        assertFalse(TrackMixInitialFillPolicy.canRunNativeFileFallbackPrefill(
            "NATIVE_FILE_FALLBACK", true, 0, false
        ))
    }
}
