package io.github.alagga.gonesmart

/**
 * Native GMMP's Initial Size includes the selected seed song.
 * Never request an extra recommendation when the requested total is
 * already met, and never treat a negative/empty queue as complete.
 */
internal object TrackMixPlan {
    fun additionalTracksNeeded(initialSize: Int, actualQueueSize: Int): Int {
        require(initialSize > 0) { "Initial queue size must be positive" }
        require(actualQueueSize >= 0) { "Queue size must not be negative" }
        return (initialSize - actualQueueSize).coerceAtLeast(0)
    }

    fun hasEnoughTracks(initialSize: Int, actualQueueSize: Int): Boolean =
        additionalTracksNeeded(initialSize, actualQueueSize) == 0

    /**
     * GMMP translates both "track" and "auto_dj" in its own language.
     * Preserve the familiar short menu label for German and English.
     * For other languages use BOTH native GMMP words rather than an
     * unlocalized English "Mix" suffix.
     */
    fun localizedMenuLabel(
        language: String,
        nativeTrack: String?,
        nativeAutoDj: String?
    ): String {
        val track = nativeTrack?.takeIf { it.isNotBlank() }
        val dj = nativeAutoDj?.takeIf { it.isNotBlank() }
        return when (language.lowercase(java.util.Locale.ROOT)) {
            "de" -> "${track ?: "Titel"}-Mix"
            "en" -> "${track ?: "Track"} Mix"
            else -> if (track != null && dj != null) {
                "$track · $dj"
            } else {
                dj ?: "Track Mix"
            }
        }
    }

    /**
     * Track Mix is a GoneSmart feature name, not a GMMP string, so there
     * cannot be a native "Mix" translation in every player language.
     * Use GMMP's own track/Auto-DJ nouns for the menu label; a native
     * "started" resource if available, otherwise a language-neutral
     * confirmation mark. German and English preserve the familiar name.
     */
    fun localizedStartedMessage(
        language: String,
        menuLabel: String,
        gmmpStarted: String?
    ): String = when (language.lowercase(java.util.Locale.ROOT)) {
        "de" -> "$menuLabel gestartet"
        "en" -> "$menuLabel started"
        else -> gmmpStarted?.takeIf { it.isNotBlank() }
            ?.let { "$menuLabel · $it" }
            ?: "$menuLabel ✓"
    }

    /**
     * If GMMP has already generated new entries while a native Clear
     * command is being processed, its queue may never be observed at
     * exactly one track. Accept only a first-position seed and no
     * remaining PRE-CLEAR song IDs; a different newly generated queue
     * must not be mistaken for leftover history.
     */
    fun isSeedIsolated(
        selectedId: Long,
        oldTracks: List<Long>,
        oldCurrentIndex: Int,
        newTracks: List<Long>,
        newCurrentIndex: Int
    ): Boolean {
        if (newCurrentIndex != 0 || newTracks.firstOrNull() != selectedId) return false
        if (newTracks.size == 1) return true
        if (oldCurrentIndex !in oldTracks.indices) return false
        val stale = oldTracks.filterIndexed { i, _ -> i != oldCurrentIndex }.toSet()
        return newTracks.drop(1).none { it in stale }
    }

    /**
     * A new native track menu must always put the action immediately
     * after "Play next" without reordering any other native items.
     */
    fun insertionIndex(playNextIndex: Int, itemCount: Int): Int {
        require(playNextIndex in 0 until itemCount)
        return playNextIndex + 1
    }
}
