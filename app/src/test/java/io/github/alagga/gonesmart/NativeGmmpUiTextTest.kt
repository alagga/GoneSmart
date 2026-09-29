package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeGmmpUiTextTest {
    @Test fun pureGoneSmartNoticesStayUsefulEnglishInsteadOfSymbols() {
        assertEquals(
            "Preparing GoneSmart cache… This may take a moment.",
            NativeGmmpUiText.smartDjNoticeLabel(
                "cache-preparing", "Auto-DJ", "Bewertung", "Fehler"
            )
        )
        assertEquals(
            "GoneSmart cache ready.",
            NativeGmmpUiText.smartDjNoticeLabel(
                "cache-ready", "Auto-DJ", "Bewertung", "Fehler"
            )
        )
        assertEquals(
            "GoneSmart is offline. Using GMMP Auto-DJ fallback.",
            NativeGmmpUiText.smartDjNoticeLabel(
                "offline-native-fallback", "Auto-DJ", "Bewertung", "Fehler"
            )
        )
        assertEquals(
            "GoneSmart could not build a recommendation context for this queue.",
            NativeGmmpUiText.smartDjNoticeLabel(
                "no-seeds", "Auto-DJ", "Bewertung", "Fehler"
            )
        )
        assertEquals(
            "GoneSmart found no suitable tracks in your library. Auto-DJ stopped.",
            NativeGmmpUiText.smartDjNoticeLabel(
                "no-matches-stopped", "Auto-DJ", "Bewertung", "Fehler"
            )
        )
    }

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

    @Test fun moveSuccessPrefersCompleteNativeHostPhraseAndFailsClosed() {
        assertEquals(
            "Playlist gespeichert",
            NativeGmmpUiText.playlistMoveSuccessLabel("Playlist gespeichert")
        )
        assertEquals("✓", NativeGmmpUiText.playlistMoveSuccessLabel(null))
        assertEquals(
            "✓",
            NativeGmmpUiText.playlistMoveSuccessLabel("Saved %s")
        )
    }

    @Test fun playlistBridgeDerivesGermanAndEnglishFromNativeEditorWording() {
        assertEquals(
            "Smart-Playlist",
            NativeGmmpUiText.smartPlaylistLabel(
                "de",
                "Smart-Playlist-Editor",
                "Smarte Playlists"
            )
        )
        assertEquals(
            "Smart-Playlist",
            NativeGmmpUiText.smartPlaylistLabel(
                "en",
                "Smart Playlist Editor",
                "Smart Playlists"
            )
        )
        assertEquals(
            "Smart-Playlist verlinken",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "Playlist verlinken",
                "Playlist",
                "Smart-Playlist"
            )
        )
        assertEquals(
            "Link Smart-Playlist",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "Link Playlist",
                "Playlist",
                "Smart-Playlist"
            )
        )
    }

    @Test fun playlistBridgeKeepsNativePluralWhenSingularCannotBeDerivedSafely() {
        assertEquals(
            "Listes intelligentes",
            NativeGmmpUiText.smartPlaylistLabel(
                "fr",
                "Éditeur de listes intelligentes",
                "Listes intelligentes"
            )
        )
        assertEquals(
            "Lier Smart local",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "Lier Playlist",
                "Playlist",
                "Smart local"
            )
        )
        assertEquals(
            "Smart local",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "Forme fléchie",
                "Playlist",
                "Smart local"
            )
        )
    }
}
