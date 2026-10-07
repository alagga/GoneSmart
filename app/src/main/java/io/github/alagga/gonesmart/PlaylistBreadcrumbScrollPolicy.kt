package io.github.alagga.gonesmart

/** Deterministic scroll behavior shared by both playlist-folder surfaces. */
internal object PlaylistBreadcrumbScrollPolicy {
    fun targetX(
        revealNewest: Boolean,
        previousX: Int,
        contentWidth: Int,
        viewportWidth: Int
    ): Int {
        val limit = (contentWidth - viewportWidth).coerceAtLeast(0)
        return if (revealNewest) limit else previousX.coerceIn(0, limit)
    }
}
