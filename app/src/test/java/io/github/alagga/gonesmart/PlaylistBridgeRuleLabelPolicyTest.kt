package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBridgeRuleLabelPolicyTest {
    @Test fun bridgeRuleAlwaysKeepsPlaylistLabel() {
        assertEquals("Playlist: Anjuna Deep", PlaylistBridgeRuleLabelPolicy.rewrite("Playlist: Anjuna Deep", "Playlist", "Smart-Playlist", "Anjuna Deep", null))
    }
    @Test fun nativeSmartReferenceUsesSmartPlaylistLabel() {
        assertEquals("Smart-Playlist: Favorites", PlaylistBridgeRuleLabelPolicy.rewrite("Playlist: Favorites", "Playlist", "Smart-Playlist", null, "Favorites"))
    }
    @Test fun unknownRuleProvenanceLeavesNativeLabelUntouched() {
        assertEquals("Playlist: Favorites", PlaylistBridgeRuleLabelPolicy.rewrite("Playlist: Favorites", "Playlist", "Smart-Playlist", null, null))
    }
}
