package io.github.alagga.gonesmart

/**
 * Main Playlist RecyclerView compatibility gates verified from real GMMP
 * surfaces.
 *
 * Surface identity and model-source compatibility are deliberately separate.
 * A model source is promoted here only after a read-only runtime resolver has
 * identified the bound native row model, its title/path fields and a unique
 * adapter position getter, and has reproduced the complete adapter row count.
 *
 * 4.2.0: zn3 surface + model source accepted
 * 4.2.1: ao3 surface + paged model source runtime-verified
 */
internal object GmmpPlaylistAdapterPolicy {
    val verifiedClassNames: Set<String> =
        setOf("zn3", "ao3")

    private val verifiedModelSourceClassNames: Set<String> =
        setOf("zn3", "ao3")

    private val runtimeBoundModelSourceClassNames: Set<String> =
        setOf("ao3")

    fun isVerified(className: String?): Boolean =
        className != null && className in verifiedClassNames

    fun hasVerifiedModelSource(className: String?): Boolean =
        className != null && className in verifiedModelSourceClassNames

    fun requiresRuntimeModelBinding(className: String?): Boolean =
        className != null && className in runtimeBoundModelSourceClassNames
}
