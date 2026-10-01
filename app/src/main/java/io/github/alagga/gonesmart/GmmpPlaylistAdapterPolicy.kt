package io.github.alagga.gonesmart

/**
 * Main Playlist RecyclerView compatibility gates verified from real GMMP
 * surfaces.
 *
 * Surface identity and model-source compatibility are deliberately separate:
 * 4.2.1's ao3 is proven to own playlistListRecyclerView, but its paged model
 * source is not yet semantically resolved. Treating those as the same state
 * caused repeated full adapter scans and main-thread stalls.
 *
 * 4.2.0: zn3 surface + model source accepted
 * 4.2.1: ao3 surface verified; model source unresolved
 */
internal object GmmpPlaylistAdapterPolicy {
    val verifiedClassNames: Set<String> =
        setOf("zn3", "ao3")

    private val verifiedModelSourceClassNames: Set<String> =
        setOf("zn3")

    fun isVerified(className: String?): Boolean =
        className != null && className in verifiedClassNames

    fun hasVerifiedModelSource(className: String?): Boolean =
        className != null && className in verifiedModelSourceClassNames
}
