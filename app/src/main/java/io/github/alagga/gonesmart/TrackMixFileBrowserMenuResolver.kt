package io.github.alagga.gonesmart

internal enum class TrackMixFileBrowserSelectionKind {
    REGULAR_FILE,
    DIRECTORY,
    UNKNOWN
}

internal data class TrackMixFileBrowserSelection(
    val kind: TrackMixFileBrowserSelectionKind,
    val evidence: String
)

/**
 * GMMP 4.2.1 reuses menu_gm_context_file_browser for directories and playable
 * files, but its final native menu visibility differs deterministically after
 * the caller finishes configuring the row. Directory rows expose eight native
 * items; playable files expose ten. Any other cardinality fails closed.
 *
 * This is deliberately only a UI eligibility signal. Track Auto-DJ still has
 * to resolve one exact Track through the existing strong pre-Play resolver
 * before native Play can be dispatched.
 */
internal object TrackMixFileBrowserMenuShapePolicy {
    private const val GMMP_421_DIRECTORY_VISIBLE_ITEMS = 8
    private const val GMMP_421_SONG_VISIBLE_ITEMS = 10

    fun resolve(nativeVisibleItems: Int): TrackMixFileBrowserSelection =
        when (nativeVisibleItems) {
            GMMP_421_SONG_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.REGULAR_FILE,
                evidence = "gmmp421-native-song-menu-shape"
            )
            GMMP_421_DIRECTORY_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.DIRECTORY,
                evidence = "gmmp421-native-directory-menu-shape"
            )
            else -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                evidence = "unexpected-native-menu-shape"
            )
        }
}
