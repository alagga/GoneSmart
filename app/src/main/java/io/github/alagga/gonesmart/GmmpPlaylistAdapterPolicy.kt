package io.github.alagga.gonesmart

/**
 * Main Playlist RecyclerView adapters verified from real GMMP surfaces.
 *
 * 4.2.0: zn3
 * 4.2.1: ao3
 *
 * Keep every main-playlist compatibility gate on this single ledger so a
 * newly accepted adapter cannot be observed in one path but rejected later.
 */
internal object GmmpPlaylistAdapterPolicy {
    val verifiedClassNames: Set<String> =
        setOf("zn3", "ao3")

    fun isVerified(className: String?): Boolean =
        className != null && className in verifiedClassNames
}
