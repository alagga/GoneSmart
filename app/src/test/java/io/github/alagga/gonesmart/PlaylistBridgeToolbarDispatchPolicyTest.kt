package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBridgeToolbarDispatchPolicyTest {
    @Test
    fun gmmp421KeepsNativeToolbarDispatchSoChooserContinuationCanBeCaptured() {
        assertFalse(
            PlaylistBridgeToolbarDispatchPolicy.installDirectListener(
                hasAcceptedLegacyAction = false
            )
        )
    }

    @Test
    fun acceptedLegacyActionMayOwnToolbarClickDirectly() {
        assertTrue(
            PlaylistBridgeToolbarDispatchPolicy.installDirectListener(
                hasAcceptedLegacyAction = true
            )
        )
    }
}
