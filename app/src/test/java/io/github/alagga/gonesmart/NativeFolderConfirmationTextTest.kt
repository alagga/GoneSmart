package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeFolderConfirmationTextTest {
    @Test fun replacesNativePluralLabelWithFolderPath() {
        assertEquals(
            "/playlists/House",
            NativeFolderConfirmationText.withFolderPath(
                "Dateien", "Dateien", "/playlists/House"
            )
        )
    }

    @Test fun preservesNativeWarningWhenLabelIsNotGeneric() {
        assertEquals(
            "Delete selected files?\n/playlists/House",
            NativeFolderConfirmationText.withFolderPath(
                "Delete selected files?", "Files", "/playlists/House"
            )
        )
    }

    @Test fun doesNotDuplicatePathOrInsertAnUnknownEmptyPath() {
        assertEquals(
            "Delete /playlists/House?",
            NativeFolderConfirmationText.withFolderPath(
                "Delete /playlists/House?", "Files", "/playlists/House"
            )
        )
        assertEquals(
            "Files",
            NativeFolderConfirmationText.withFolderPath(
                "Files", "Files", ""
            )
        )
    }
}
