package io.github.alagga.gonesmart

/**
 * The bounded native Smart-list fallback is valid only for the active,
 * attached browser and the same refresh generation that scheduled it.
 *
 * A timer started for the root must never reveal the native root dataset
 * while a child-folder projection (or the return to root) is still settling.
 */
internal object SmartFolderRevealTimeoutPolicy {
    fun mayFailOpen(
        scheduledGeneration: Long,
        currentGeneration: Long,
        browserActive: Boolean,
        listAttached: Boolean,
        nativeContentReady: Boolean
    ): Boolean =
        scheduledGeneration == currentGeneration &&
            browserActive && listAttached && !nativeContentReady
}
