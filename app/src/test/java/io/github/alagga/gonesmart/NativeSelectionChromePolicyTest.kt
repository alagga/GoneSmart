package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionChromePolicyTest {
    @Test fun verifiedSelectionColorBeatsThemeFabFallbacks() {
        assertTrue(
            NativeSelectionChromePolicy.chooseSelectionColor(
                verifiedNativeSelection = 0x11,
                nativeBar = 0x22,
                nativeHighlight = 0x33,
                controlHighlight = 0x44,
                nativeFab = 0x55,
                observedAccent = 0x66,
                fallback = 0x77
            ) == 0x11
        )
    }

    @Test fun hostBarAndHighlightBeatFabWhenNoVerifiedWitnessExists() {
        assertTrue(
            NativeSelectionChromePolicy.chooseSelectionColor(
                verifiedNativeSelection = null,
                nativeBar = 0x22,
                nativeHighlight = 0x33,
                controlHighlight = 0x44,
                nativeFab = 0x55,
                observedAccent = 0x66,
                fallback = 0x77
            ) == 0x22
        )
    }

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
