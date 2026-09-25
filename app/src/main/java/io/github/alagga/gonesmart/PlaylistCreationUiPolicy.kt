package io.github.alagga.gonesmart

/**
 * Pure visibility policy for the two native GMMP playlist-create controls.
 *
 * The Group external playlists option intentionally does not participate:
 * it changes grouping/presentation only. Group root playlists determines
 * whether root creation lives in the main/root view or virtual Other
 * Locations.
 */
internal object PlaylistCreationUiPolicy {
    data class State(
        val normalMenuVisible: Boolean,
        val pickerFabVisible: Boolean,
        val destination: String?,
        val physicalDestinationUnsupported: Boolean
    )

    fun state(
        foldersEnabled: Boolean,
        currentFolderId: String?,
        mainPlaylistDirectory: String,
        groupRootPlaylists: Boolean,
        hasPickerSelection: Boolean
    ): State {
        if (!foldersEnabled) {
            return State(
                normalMenuVisible = true,
                pickerFabVisible = true,
                destination = mainPlaylistDirectory,
                physicalDestinationUnsupported = false
            )
        }

        val destination = PlaylistCreationPolicy.destination(
            currentFolderId = currentFolderId,
            mainPlaylistDirectory = mainPlaylistDirectory,
            groupRootPlaylists = groupRootPlaylists
        )
        val root = runCatching {
            java.io.File(mainPlaylistDirectory).canonicalPath
        }.getOrNull()
        val physicalUnsupported =
            destination != null && root != null && destination != root

        return State(
            // Do not expose GMMP's root-only native menu callback inside a
            // physical subfolder until its destination can be redirected
            // without desynchronizing GMMP's DB and M3U file.
            normalMenuVisible =
                destination != null && !physicalUnsupported,
            // In the picker, confirmation must always win. Physical folders
            // keep the native + visible so the user can test/see the intended
            // control, but its create click is guarded separately until the
            // native destination hook is verified.
            pickerFabVisible =
                hasPickerSelection || destination != null,
            destination = destination,
            physicalDestinationUnsupported = physicalUnsupported
        )
    }
}
