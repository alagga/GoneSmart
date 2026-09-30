package io.github.alagga.gonesmart

/**
 * Avoid resubmitting an unchanged Smart-Playlist snapshot to GMMP's original
 * AsyncListDiffer. Repeated identical submissions can rebind/re-animate native
 * rows while the user is scrolling.
 */
internal object SmartNativeSubmissionPolicy {
    fun shouldSubmit(
        hasSubmitted: Boolean,
        previousSignature: List<String>,
        nextSignature: List<String>
    ): Boolean = !hasSubmitted || previousSignature != nextSignature

    /**
     * GMMP refreshes the Smart list from its physical root before GoneSmart
     * reapplies a nested/virtual folder projection. Hide that transient root
     * dataset whenever it cannot be the dataset the user should see.
     */
    fun shouldMaskNativeRootRefresh(
        currentIsRoot: Boolean,
        otherLocations: Boolean,
        groupRootPlaylists: Boolean
    ): Boolean =
        !currentIsRoot || otherLocations || groupRootPlaylists

    /**
     * The native RecyclerView may be revealed only after AsyncListDiffer has
     * committed the expected item count and no currently bound native holder
     * belongs to a stale dataset.
     */
    fun projectionReady(
        expectedCount: Int,
        adapterCount: Int?,
        visiblePaths: List<String>,
        expectedPaths: Set<String>
    ): Boolean {
        if (adapterCount == null || adapterCount != expectedCount) return false
        return visiblePaths.all(expectedPaths::contains)
    }
}
