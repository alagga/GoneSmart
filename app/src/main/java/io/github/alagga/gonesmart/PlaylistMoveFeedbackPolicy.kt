package io.github.alagga.gonesmart

/** One result policy for BOTH single and multi playlist moves. */
internal object PlaylistMoveFeedbackPolicy {
    /**
     * A verified successful move is already visibly reflected in GMMP's
     * original native playlist index/list. Bare "Move" and "Playlist saved"
     * Toasts are both unwanted; failures must remain actionable.
     */
    fun shouldShowToast(success: Boolean): Boolean = !success
}
