package io.github.alagga.gonesmart

/** A recognized remix/bootleg producer owns style discovery before the original artist. */
internal object RemixSourcePriorityPolicy {
    fun remixArtists(
        metadata: NormalizedTrackMetadata,
        comparisonKey: (String) -> String
    ): List<String> = metadata.remixArtists
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(comparisonKey)
        .take(2)

    fun originalIdentityConfidence(baseConfidence: Double, hasRemixer: Boolean): Double =
        (baseConfidence * if (hasRemixer) 0.38 else 1.0).coerceIn(0.0, 1.0)

    fun allowUnverifiedOriginalArtistFallback(hasRemixer: Boolean): Boolean = !hasRemixer

    /** Artist-only rescue must use the recording's producer, not its original credit. */
    fun localFallbackSeed(
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        comparisonKey: (String) -> String
    ): RecommendationSeed {
        val remixers = remixArtists(metadata, comparisonKey)
        return if (remixers.isEmpty()) seed else seed.copy(
            track = seed.track.copy(artist = remixers.joinToString(", "))
        )
    }
}
