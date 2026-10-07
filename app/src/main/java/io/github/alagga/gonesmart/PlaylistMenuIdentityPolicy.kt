package io.github.alagga.gonesmart

/**
 * Semantic guard for the Playlists-tab toolbar menu.
 *
 * Submenus such as menu_gm_sort_playlist_list contain the same broad
 * "playlist/list" tokens but must never replace the retained top-level menu.
 * Future renamed top-level menus are discovered from the live ActionMenuView
 * instead of guessed by resource-name substrings.
 */
internal object PlaylistMenuIdentityPolicy {
    fun isMainPlaylistMenu(resourceName: String): Boolean =
        resourceName == "menu_gm_playlist_list"
}
