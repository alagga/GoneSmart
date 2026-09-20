package io.github.alagga.gonesmart

class RecommendationAggregator(
    private val metadataNormalizer: TrackMetadataNormalizer
) {

    fun aggregate(
        contributions: List<RawRecommendationContribution>
    ): List<RecommendationCandidate> {

        val grouped =
            contributions
                .groupBy {
                    candidateKey(
                        artist =
                            it.artist,

                        title =
                            it.title
                    )
                }

        return grouped
            .values
            .mapNotNull { group ->

                if (
                    group.isEmpty()
                ) {
                    return@mapNotNull null
                }

                /*
                 * Ein Provider darf denselben
                 * logischen Song innerhalb desselben
                 * Seeds mehrfach zurückgeben.
                 *
                 * Beispiel:
                 *
                 * ListenBrainz liefert mehrere
                 * Recordings desselben Tracks.
                 *
                 * Diese dürfen den GoneSmart-Score
                 * nicht künstlich vervielfachen.
                 *
                 * Deshalb zählt pro:
                 *
                 * Provider + Seed
                 *
                 * nur der beste Beitrag.
                 */
                val bestPerProviderAndSeed =
                    group
                        .groupBy {
                            ProviderSeedKey(
                                provider =
                                    it.provider,

                                seedTrackId =
                                    it.seed.track.id
                            )
                        }
                        .values
                        .mapNotNull { sameSource ->

                            sameSource
                                .maxByOrNull {
                                    it.weightedScore
                                }
                        }

                if (
                    bestPerProviderAndSeed.isEmpty()
                ) {
                    return@mapNotNull null
                }

                /*
                 * Für die sichtbare Artist-/Titel-
                 * Darstellung nehmen wir den
                 * stärksten einzelnen Beitrag.
                 *
                 * Die Gruppierung selbst erfolgt
                 * aber über den canonical key.
                 */
                val representative =
                    bestPerProviderAndSeed
                        .maxByOrNull {
                            it.weightedScore
                        }
                        ?: return@mapNotNull null

                val mappedContributions =
                    bestPerProviderAndSeed
                        .map {
                            RecommendationContribution(
                                provider =
                                    it.provider,

                                seed =
                                    it.seed,

                                providerSimilarity =
                                    it.providerSimilarity,

                                seedWeight =
                                    it.seedWeight,

                                identityConfidence =
                                    it.identityConfidence,

                                weightedScore =
                                    it.weightedScore
                            )
                        }

                RecommendationCandidate(
                    artist =
                        representative.artist,

                    title =
                        representative.title,

                    mbids =
                        group
                            .mapNotNull {
                                it.mbid
                                    ?.trim()
                                    ?.takeIf { mbid ->
                                        mbid.isNotBlank()
                                    }
                            }
                            .toSet(),

                    contributions =
                        mappedContributions,

                    totalScore =
                        mappedContributions
                            .sumOf {
                                it.weightedScore
                            }
                )
            }
            .sortedByDescending {
                it.totalScore
            }
    }

    /**
     * Erzeugt eine musikalisch normalisierte
     * Identität für externe Recommendations.
     *
     * Dadurch sollen beispielsweise diese
     * Schreibweisen zusammenfallen:
     *
     * Swedish House Mafia
     * Don't You Worry Child
     * (Radio Edit) (feat. John Martin)
     *
     * und:
     *
     * Swedish House Mafia ft. John Martin
     * Don't You Worry Child (radio edit)
     *
     * Gleichzeitig bleiben unterschiedliche
     * Versionen getrennt:
     *
     * Levels
     * Levels (Radio Edit)
     * Levels (Extended Mix)
     */
    private fun candidateKey(
        artist: String,
        title: String
    ): String {

        val normalized =
            try {

                metadataNormalizer
                    .normalize(
                        TrackInfo(
                            id =
                                -1L,

                            title =
                                title,

                            artist =
                                artist,

                            albumArtist =
                                null,

                            path =
                                null
                        )
                    )

            } catch (
                _: Throwable
            ) {

                null
            }

        if (
            normalized == null
        ) {

            return fallbackCandidateKey(
                artist =
                    artist,

                title =
                    title
            )
        }

        val artistKey =
            canonicalArtistKey(
                rawArtist =
                    artist,

                metadata =
                    normalized
            )

        val titleKey =
            metadataNormalizer
                .comparisonKey(
                    normalized
                        .baseTitle
                        .ifBlank {
                            title
                        }
                )

        val versionKey =
            canonicalVersionKey(
                normalized
            )

        return "$artistKey|$titleKey|$versionKey"
    }

    /**
     * Featured Artists werden absichtlich nicht
     * Teil der primären Track-Artist-Identität.
     *
     * Spotify/Last.fm/ListenBrainz schreiben
     * beispielsweise:
     *
     * Swedish House Mafia feat. John Martin
     *
     * teilweise als Artist und teilweise als:
     *
     * Artist = Swedish House Mafia
     * Title  = ... feat. John Martin
     *
     * Für GoneSmart ist das derselbe Song.
     */
    private fun canonicalArtistKey(
        rawArtist: String,
        metadata: NormalizedTrackMetadata
    ): String {

        val withoutFeatureCredit =
            stripFeatureCredit(
                rawArtist
            )

        if (
            withoutFeatureCredit.isNotBlank()
        ) {

            return metadataNormalizer
                .comparisonKey(
                    withoutFeatureCredit
                )
        }

        val fallbackArtist =
            metadata
                .artists
                .firstOrNull()
                .orEmpty()

        return metadataNormalizer
            .comparisonKey(
                fallbackArtist
            )
    }

    /**
     * Versionen werden nicht pauschal
     * zusammengeführt.
     *
     * Base/Original Mix gelten als dieselbe
     * Grundversion.
     *
     * Radio Edit, Extended Mix, Acoustic usw.
     * bleiben eigenständig.
     *
     * Bei Remixen/Bootlegs/Edits ist außerdem
     * die konkrete Versionsbezeichnung wichtig.
     */
    private fun canonicalVersionKey(
        metadata: NormalizedTrackMetadata
    ): String {

        return when (
            metadata.versionType
        ) {

            null -> {

                "BASE"
            }

            TrackVersionType.ORIGINAL_MIX -> {

                "BASE"
            }

            TrackVersionType.EXTENDED_MIX -> {

                "EXTENDED"
            }

            TrackVersionType.RADIO_EDIT -> {

                "RADIO"
            }

            TrackVersionType.CLUB_MIX -> {

                "CLUB"
            }

            TrackVersionType.ACOUSTIC_VERSION -> {

                "ACOUSTIC"
            }

            TrackVersionType.REMIX -> {

                val remixArtistKey =
                    metadata
                        .remixArtists
                        .map {
                            metadataNormalizer
                                .comparisonKey(
                                    it
                                )
                        }
                        .filter {
                            it.isNotBlank()
                        }
                        .sorted()
                        .joinToString(
                            "+"
                        )

                if (
                    remixArtistKey.isNotBlank()
                ) {

                    "REMIX:$remixArtistKey"

                } else {

                    "REMIX:" +
                            canonicalVersionLabel(
                                metadata
                            )
                }
            }

            TrackVersionType.EDIT -> {

                "EDIT:" +
                        canonicalVersionLabel(
                            metadata
                        )
            }

            TrackVersionType.BOOTLEG -> {

                "BOOTLEG:" +
                        canonicalVersionLabel(
                            metadata
                        )
            }

            TrackVersionType.MASHUP -> {

                "MASHUP:" +
                        canonicalVersionLabel(
                            metadata
                        )
            }
        }
    }

    private fun canonicalVersionLabel(
        metadata: NormalizedTrackMetadata
    ): String {

        return metadataNormalizer
            .comparisonKey(
                metadata
                    .versionLabel
                    .orEmpty()
            )
            .ifBlank {
                "UNKNOWN"
            }
    }

    private fun stripFeatureCredit(
        artist: String
    ): String {

        val featureRegex =
            Regex(
                pattern =
                    """(?i)\s+(?:feat\.?|ft\.?|featuring)\s+"""
            )

        val match =
            featureRegex.find(
                artist
            )

        if (
            match == null
        ) {

            return artist.trim()
        }

        return artist
            .substring(
                0,
                match.range.first
            )
            .trim()
    }

    private fun fallbackCandidateKey(
        artist: String,
        title: String
    ): String {

        return metadataNormalizer
            .comparisonKey(
                stripFeatureCredit(
                    artist
                )
            ) +
                "|" +
                metadataNormalizer
                    .comparisonKey(
                        title
                    ) +
                "|FALLBACK"
    }

    private data class ProviderSeedKey(
        val provider: RecommendationProvider,
        val seedTrackId: Long
    )
}

data class RawRecommendationContribution(
    val provider: RecommendationProvider,
    val seed: RecommendationSeed,
    val artist: String,
    val title: String,
    val mbid: String?,
    val providerSimilarity: Double,
    val seedWeight: Double,
    val identityConfidence: Double
) {

    val weightedScore: Double
        get() =
            providerSimilarity *
                    seedWeight *
                    identityConfidence
}