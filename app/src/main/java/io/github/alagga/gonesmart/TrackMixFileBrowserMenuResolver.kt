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
 * files. Device evidence originally showed eight visible native items for a
 * directory and ten for a playable file. A later device pass with the same
 * GMMP build showed a playable-file menu with nine native items because one
 * unrelated native action was conditionally hidden. Accept only the two
 * device-observed playable-file shapes (9/10). The historical directory shape
 * remains 8; every other cardinality still fails closed.
 *
 * This is deliberately only a UI eligibility signal. Track Auto-DJ still has
 * to resolve one exact Track through the existing strong pre-Play resolver
 * before native Play can be dispatched.
 */
internal object TrackMixFileBrowserMenuShapePolicy {
    private val GMMP_421_SONG_VISIBLE_ITEMS = setOf(9, 10)
    private const val GMMP_421_DIRECTORY_VISIBLE_ITEMS = 8

    fun resolve(nativeVisibleItems: Int): TrackMixFileBrowserSelection =
        when {
            nativeVisibleItems in GMMP_421_SONG_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.REGULAR_FILE,
                evidence = "gmmp421-native-song-menu-shape"
            )
            nativeVisibleItems == GMMP_421_DIRECTORY_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.DIRECTORY,
                evidence = "gmmp421-native-directory-menu-shape"
            )
            else -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                evidence = "unexpected-native-menu-shape"
            )
        }
}
