package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDrawerBadgePolicyTest {
    @Test fun matchesActualLocalizedGmmpDrawerTitle() {
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Wiedergabelisten", "Wiedergabeliste"),
                "", "Wiedergabelisten"
            )
        )
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Playlists", "Playlist"),
                "menuPlaylists", "Playlists"
            )
        )
    }
    @Test fun neverDecoratesUnrelatedDrawerEntries() {
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Playlists"),
                "menuQueue", "Queue"
            )
        )
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Playlists"),
                "menuFiles", "Files"
            )
        )
    }

    @Test fun distinguishesSmartPlaylistDrawerEntry() {
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Wiedergabelisten", "Wiedergabeliste"),
                "menuSmartPlaylists", "Smarte Playlists"
            )
        )
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativeSmartPlaylist(
                listOf("Smarte Playlists", "Smart-Playlist"),
                "menuSmartPlaylists", "Smarte Playlists"
            )
        )
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesNativeSmartPlaylist(
                listOf("Smarte Playlists"),
                "menuPlaylists", "Wiedergabelisten"
            )
        )
    }

    @Test fun matchesNativeSmartDrawerIdEvenWhenItDoesNotRepeatPlaylist() {
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativeSmartPlaylist(
                listOf("Smarte Wiedergabelisten"),
                "menuSmart", "Smarte Wiedergabelisten"
            )
        )
    }

    @Test fun exactNavigationTitleMatcherRejectsOtherSmartFeatures() {
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
                listOf("Smarte Playlists", "Smart-Playlist"),
                "Smarte Playlists"
            )
        )
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
                listOf("Smart Playlists"),
                "Smart-Playlists"
            )
        )
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
                listOf("Smart Playlists", "Smart-Playlist"),
                "Smart DJ"
            )
        )
    }

    @Test fun smartTitleMatchingToleratesHostPunctuationAndProtectsPlaylist() {
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativeSmartPlaylist(
                listOf("Smart Playlists"),
                "", "Smart-Playlists"
            )
        )
        assertTrue(
            PlaylistDrawerBadgePolicy.matchesNativeSmartPlaylist(
                emptyList(),
                "", "Smart-Playlists"
            )
        )
        assertFalse(
            PlaylistDrawerBadgePolicy.matchesNativePlaylist(
                listOf("Playlists"),
                "menuSmart", "Playlists"
            )
        )
    }
}
