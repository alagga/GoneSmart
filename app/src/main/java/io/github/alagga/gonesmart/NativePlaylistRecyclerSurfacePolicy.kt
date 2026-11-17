package io.github.alagga.gonesmart

/**
 * Surface identity is independent from a nearby FAB. A confirmed native
 * playlist adapter (ao3/zn3) or the real playlist RecyclerView resource
 * is required; never mistake an ordinary track list for a Playlist page.
 */
internal object NativePlaylistRecyclerSurfacePolicy {
    fun isPlaylistCandidate(
        resourceName: String,
        verifiedPlaylistAdapter: Boolean
    ): Boolean {
        if (resourceName == "trackListRecyclerView") return false
        return resourceName == "playlistListRecyclerView" ||
            verifiedPlaylistAdapter
    }
}
