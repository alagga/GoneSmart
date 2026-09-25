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
        hasPickerSelection: Boolean,
        nativePhysicalCreateReady: Boolean = false
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
            destination != null && root != null && destination != root &&
                !nativePhysicalCreateReady

        return State(
            // Show inside a real subfolder only when BOTH the original
            // native create lambda and the scoped root-getter hook installed.
            // The callback revalidates the configured native root before
            // any file is written, and otherwise cancels safely.
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
