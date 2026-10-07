package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMenuIdentityPolicyTest {
    @Test fun acceptsOnlyTopLevelPlaylistMenu() {
        assertTrue(
            PlaylistMenuIdentityPolicy.isMainPlaylistMenu(
                "menu_gm_playlist_list"
            )
        )
        assertFalse(
            PlaylistMenuIdentityPolicy.isMainPlaylistMenu(
                "menu_gm_sort_playlist_list"
            )
        )
        assertFalse(
            PlaylistMenuIdentityPolicy.isMainPlaylistMenu(
                "menu_gm_context_playlist_list"
            )
        )
        assertFalse(
            PlaylistMenuIdentityPolicy.isMainPlaylistMenu(
                "menu_gm_action_playlist"
            )
        )
        assertFalse(
            PlaylistMenuIdentityPolicy.isMainPlaylistMenu(
                "menu_gm_smart_playlist_list"
            )
        )
    }
}
