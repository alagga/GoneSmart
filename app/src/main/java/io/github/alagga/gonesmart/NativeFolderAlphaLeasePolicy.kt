package io.github.alagga.gonesmart

/**
 * The module temporarily hides verified native RecyclerViews during folder
 * projection. Never save our OWN alpha=0 mask as their original opacity.
 *
 * View.alpha defaults to 1f. A positive, independently observed native alpha
 * is preserved across fragment/adapter re-creations; a zero captured after
 * a preceding module mask is not authoritative and must be recovered from
 * the last positive original (or the framework's View default).
 */
internal object NativeFolderAlphaLeasePolicy {
    fun original(observed: Float, lastPositiveNative: Float?): Float =
        when {
            observed.isFinite() && observed > 0f && observed <= 1f ->
                observed
            lastPositiveNative != null &&
                lastPositiveNative.isFinite() &&
                lastPositiveNative > 0f && lastPositiveNative <= 1f ->
                lastPositiveNative
            else -> 1f
        }
}
