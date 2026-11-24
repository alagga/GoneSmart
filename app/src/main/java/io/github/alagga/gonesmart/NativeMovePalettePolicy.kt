package io.github.alagga.gonesmart

/**
 * Use independently evidenced GMMP chrome, never an unverified static
 * Aesthetic colorAccent (stale red on the installed 4.2.1 skin).
 */
internal object NativeMovePalettePolicy {
    /** Reject black/near-black static primary placeholders on GMMP 4.2.1. */
    fun acceptLivePrimary(color: Int): Boolean {
        val a = color ushr 24 and 0xff
        val r = color ushr 16 and 0xff
        val g = color ushr 8 and 0xff
        val b = color and 0xff
        return a >= 200 && maxOf(r, g, b) >= 28
    }

    fun preferredColor(
        liveNativePlaylistFab: Int?,
        verifiedNativeSelection: Int?,
        nativeContextBar: Int?
    ): Int? = liveNativePlaylistFab ?: verifiedNativeSelection
        ?: nativeContextBar
}
