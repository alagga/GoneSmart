package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeActionModeClosePolicyTest {
    @Test fun appCompatCloseButtonIsStableSelectionCloseBoundary() {
        assertTrue(
            NativeActionModeClosePolicy.isCloseResource(
                "action_mode_close_button"
            )
        )
        assertFalse(
            NativeActionModeClosePolicy.isCloseResource(
                "action_mode_bar"
            )
        )
    }

    @Test fun acceptsBothObservedContextBarResourceNames() {
        assertTrue(
            NativeActionModeClosePolicy.isContextBarResource(
                "action_mode_bar"
            )
        )
        assertTrue(
            NativeActionModeClosePolicy.isContextBarResource(
                "action_context_bar"
            )
        )
    }
}
