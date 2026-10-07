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
     * Once the desired GoneSmart projection is already committed and visible,
     * do not let GMMP replace it with its transient physical-root submit just
     * to hide/rebuild the same folder view again. The controller refreshes the
     * current folder directly instead.
     */
    fun shouldSuppressNativeRootRefresh(
        projectionPrepared: Boolean,
        nativeContentReady: Boolean,
        currentIsRoot: Boolean,
        otherLocations: Boolean,
        groupRootPlaylists: Boolean
    ): Boolean =
        projectionPrepared &&
            nativeContentReady &&
            shouldMaskNativeRootRefresh(
                currentIsRoot,
                otherLocations,
                groupRootPlaylists
            )

    /**
     * Direct AsyncListDiffer interception happens after a GoneSmart projection
     * is already committed. At that point a mismatching external GMMP submit
     * is exactly the transient root replacement that causes a one-frame flash.
     * Unlike first-attach masking this may also protect an attached background
     * Smart list because no alpha/lifecycle state is changed: the committed
     * projection stays visible and the controller refreshes that same folder.
     */
    fun shouldSuppressExternalDifferSubmit(
        projectionPrepared: Boolean,
        nativeContentReady: Boolean,
        currentIsRoot: Boolean,
        otherLocations: Boolean,
        groupRootPlaylists: Boolean,
        incomingMatchesProjection: Boolean
    ): Boolean =
        !incomingMatchesProjection &&
            shouldSuppressNativeRootRefresh(
                projectionPrepared = projectionPrepared,
                nativeContentReady = nativeContentReady,
                currentIsRoot = currentIsRoot,
                otherLocations = otherLocations,
                groupRootPlaylists = groupRootPlaylists
            )

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
