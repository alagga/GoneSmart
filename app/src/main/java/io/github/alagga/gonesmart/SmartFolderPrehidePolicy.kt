package io.github.alagga.gonesmart

/**
 * A Smart view's resource name alone must never hide arbitrary GMMP rows.
 * The native RecyclerView adapter and differ shape must both be proven and
 * a 2.5-second independent native visibility restore remains mandatory.
 * A detached, parentless fragment may still be a legitimate incoming tab:
 * the resource name/adapter/differ triple replaces parent-based proof.
 */
internal object SmartFolderPrehidePolicy {
    fun mayPrehide(
        enabled: Boolean,
        namedSmartSurface: Boolean,
        nativeRecyclerAdapter: Boolean,
        differShapeVerified: Boolean,
        front: Boolean,
        attachedAndShown: Boolean,
        preparedForAttach: Boolean = false
    ): Boolean = enabled && namedSmartSurface && nativeRecyclerAdapter &&
        differShapeVerified &&
        ((front && attachedAndShown) || preparedForAttach)
}
