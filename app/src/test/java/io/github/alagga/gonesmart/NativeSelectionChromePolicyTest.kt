package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionChromePolicyTest {
    @Test fun actionModeCallbackBeforeFirstLayoutDoesNotClearSelection() {
        assertFalse(
            NativeSelectionChromePolicy.shouldClear(
                selectionActive = true,
                visibleChromeSeen = false,
                chromeVisibleNow = false
            )
        )
    }

    @Test fun disappearanceAfterActuallyVisibleChromeClearsSelection() {
        assertTrue(
            NativeSelectionChromePolicy.shouldClear(
                selectionActive = true,
                visibleChromeSeen = true,
                chromeVisibleNow = false
            )
        )
    }

    @Test fun visibleChromeNeverClearsSelection() {
        assertFalse(
            NativeSelectionChromePolicy.shouldClear(
                selectionActive = true,
                visibleChromeSeen = true,
                chromeVisibleNow = true
            )
        )
    }
}
