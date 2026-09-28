package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeGmmpUiTextTest {
    @Test fun smartDjNoticesUseHostNativeNounsAndNeutralStatusSymbols() {
        assertEquals("GoneSmart ⏳",
            NativeGmmpUiText.smartDjNoticeLabel(
                "cache-preparing", "Auto-DJ", "Bewertung", "Fehler"))
        assertEquals("Auto-DJ ↩",
            NativeGmmpUiText.smartDjNoticeLabel(
                "offline-native-fallback", "Auto-DJ", "Bewertung", "Fehler"))
        assertEquals("Fehler · Auto-DJ",
            NativeGmmpUiText.smartDjNoticeLabel(
                "no-seeds", "Auto-DJ", "Bewertung", "Fehler"))
        assertEquals("Bewertung ↩",
            NativeGmmpUiText.smartDjNoticeLabel(
                "rating-fallback", "Auto-DJ", "Bewertung", "Fehler"))
        assertEquals("Auto-DJ ⏹",
            NativeGmmpUiText.smartDjNoticeLabel(
                "no-matches-stopped", "Auto-DJ", "Bewertung", "Fehler"))
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

    @Test fun playlistBridgeUsesExactGermanAndEnglishSmartPlaylistWording() {
        assertEquals(
            "Smart Playlist",
            NativeGmmpUiText.smartPlaylistLabel("de", "Smart-Playlists")
        )
        assertEquals(
            "Smart Playlist verlinken",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "de",
                "Playlist verlinken",
                "Playlist",
                "Smart Playlist"
            )
        )
        assertEquals(
            "Link Smart Playlist",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "en",
                "Link playlist",
                "Playlist",
                "Smart Playlist"
            )
        )
    }

    @Test fun playlistBridgeKeepsHostLocalizedFallbackOutsideVerifiedGrammar() {
        assertEquals(
            "Listes intelligentes",
            NativeGmmpUiText.smartPlaylistLabel(
                "fr",
                "Listes intelligentes"
            )
        )
        assertEquals(
            "Lier Smart",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "xx",
                "Lier Playlist",
                "Playlist",
                "Smart"
            )
        )
        assertEquals(
            "Smart local",
            NativeGmmpUiText.linkSmartPlaylistLabel(
                "xx",
                "Forme fléchie",
                "Playlist",
                "Smart local"
            )
        )
    }
}
