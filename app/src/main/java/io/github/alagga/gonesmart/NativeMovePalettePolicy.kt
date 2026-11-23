package io.github.alagga.gonesmart

/**
 * Use independently evidenced GMMP chrome, never an unverified static
 * Aesthetic colorAccent (stale red on the installed 4.2.1 skin).
 */
internal object NativeMovePalettePolicy {
    fun preferredColor(
        liveNativePlaylistFab: Int?,
        verifiedNativeSelection: Int?,
        nativeContextBar: Int?
    ): Int? = liveNativePlaylistFab ?: verifiedNativeSelection
        ?: nativeContextBar
}
