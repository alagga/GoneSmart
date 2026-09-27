package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeGmmpUiTextTest {
    @Test fun preservesTheHostPlayersErrorTranslation() {
        assertEquals(
            "Fehler · Wiedergabelisten",
            NativeGmmpUiText.errorLabel("Fehler", "Wiedergabelisten")
        )
        assertEquals(
            "Error · Playlists",
            NativeGmmpUiText.errorLabel("Error", "Playlists")
        )
    }

    @Test fun symbolFallbackDoesNotLeakAnotherLanguage() {
        assertEquals("⚠", NativeGmmpUiText.errorLabel(null, null))
        assertEquals("⚠ · DJ automatique",
            NativeGmmpUiText.errorLabel(null, "DJ automatique"))
        assertEquals("Erreur", NativeGmmpUiText.errorLabel("Erreur", " "))
    }
}
