package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveConfirmationUiPolicyTest {
    @Test
    fun nativeFolderActionUsesHostLocalizedNounNotMoveFallback() {
        assertEquals(
            "→ Ordner",
            MoveConfirmationUiPolicy.nativeFolderDestinationLabel(
                "Ordner", isRtl = false
            )
        )
        assertEquals(
            "→ Folder",
            MoveConfirmationUiPolicy.nativeFolderDestinationLabel(
                "Folder", isRtl = false
            )
        )
        assertEquals(
            "→ Dossier",
            MoveConfirmationUiPolicy.nativeFolderDestinationLabel(
                "Dossier", isRtl = false
            )
        )
        assertEquals(
            "← مجلد",
            MoveConfirmationUiPolicy.nativeFolderDestinationLabel(
                "مجلد", isRtl = true
            )
        )
    }

    @Test
    fun reserveSpaceForBarOnlyWhileSelectingDestination() {
        assertEquals(72, MoveConfirmationUiPolicy.contentBottomInset(true, 72, 80))
        assertEquals(80, MoveConfirmationUiPolicy.contentBottomInset(true, 0, 80))
        assertEquals(0, MoveConfirmationUiPolicy.contentBottomInset(false, 72, 80))
        assertEquals(0, MoveConfirmationUiPolicy.contentBottomInset(true, 0, -10))
    }

    @Test
    fun moveBarRisesAboveClippedNativeViewport() {
        assertEquals(
            168,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2200,
                visibleBottomPx = 2032
            )
        )
        assertEquals(
            0,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2032,
                visibleBottomPx = 2032
            )
        )
        assertEquals(
            0,
            MoveConfirmationUiPolicy.bottomOcclusion(
                overlayBottomPx = 2000,
                visibleBottomPx = 2032
            )
        )
    }

    @Test
    fun chooseContrastAgainstActualDarkGmmpAccent() {
        assertEquals(
            0xffffffff.toInt(),
            MoveConfirmationUiPolicy.textColorForBackground(0xff8e0e00.toInt())
        )
        assertEquals(
            0xffffffff.toInt(),
            MoveConfirmationUiPolicy.textColorForBackground(0xff000000.toInt())
        )
    }

    @Test
    fun keepLightNativeAccentReadableWithoutHardcodedTint() {
        assertEquals(
            0xff000000.toInt(),
            MoveConfirmationUiPolicy.textColorForBackground(0xffbfbfcc.toInt())
        )
        assertEquals(
            0xff000000.toInt(),
            MoveConfirmationUiPolicy.textColorForBackground(0xffffffff.toInt())
        )
    }
}
