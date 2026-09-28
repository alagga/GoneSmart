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
}
