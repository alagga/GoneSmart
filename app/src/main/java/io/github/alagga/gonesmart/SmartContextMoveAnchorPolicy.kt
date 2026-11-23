package io.github.alagga.gonesmart

/** Exact native row context-button evidence, bounded to the current surface. */
internal object SmartContextMoveAnchorPolicy {
    fun isValid(
        sameBrowser: Boolean,
        rowAttached: Boolean,
        pathStillBound: Boolean,
        pathInProjection: Boolean,
        elapsedMs: Long
    ): Boolean = sameBrowser && rowAttached && pathStillBound &&
        pathInProjection && elapsedMs in 0..3_000
}
