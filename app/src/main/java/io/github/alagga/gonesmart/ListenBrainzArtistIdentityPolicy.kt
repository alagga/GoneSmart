package io.github.alagga.gonesmart

internal data class ListenBrainzArtistIdentity(
    val artistMbid: String,
    val artistName: String,
    val confidence: Double
)

/**
 * Recover an artist identity from ListenBrainz recording-search rows without
 * pretending that the returned recording itself is a valid track match.
 *
 * This is intentionally conservative: exact normalized artist credit wins;
 * otherwise only a row containing the normalized primary seed artist is
 * accepted. The first artist-credit MBID is used because ListenBrainz returns
 * artist credit MBIDs in credit order and GoneSmart's provider fallback is
 * seeded from the primary artist.
 */
internal object ListenBrainzArtistIdentityPolicy {
    fun select(
        metadata: NormalizedTrackMetadata,
        matches: List<ListenBrainzRecordingMatch>,
        comparisonKey: (String) -> String,
        artistMbidsForRecording: (ListenBrainzRecordingMatch) -> List<String> = { it.artistMbids }
    ): ListenBrainzArtistIdentity? {
        val fullArtistKey = comparisonKey(metadata.searchArtist)
        val primaryArtistKey = metadata.artists
            .firstOrNull()
            ?.let(comparisonKey)
            .orEmpty()

        val candidates = matches
            .mapNotNull { match ->
                val matchArtistKey = comparisonKey(match.artistName)
                val confidence = when {
                    fullArtistKey.isNotBlank() && matchArtistKey == fullArtistKey -> 0.90
                    primaryArtistKey.isNotBlank() &&
                        (matchArtistKey == primaryArtistKey ||
                            matchArtistKey.contains(primaryArtistKey) ||
                            primaryArtistKey.contains(matchArtistKey)) -> 0.82
                    else -> 0.0
                }
                if (confidence <= 0.0) null else match to confidence
            }
            .sortedByDescending { it.second }
            .take(3)

        for ((match, confidence) in candidates) {
            val mbid = artistMbidsForRecording(match)
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (mbid.isBlank()) continue
            return ListenBrainzArtistIdentity(
                artistMbid = mbid,
                artistName = match.artistName,
                confidence = confidence
            )
        }
        return null
    }
}
