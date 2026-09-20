package io.github.alagga.gonesmart

data class ListenBrainzMatchEvaluation(
    val match: ListenBrainzRecordingMatch,
    val artistScore: Double,
    val titleScore: Double,
    val versionScore: Double,
    val totalScore: Double,
    val accepted: Boolean
)

class ListenBrainzMatchSelector(
    private val metadataNormalizer:
    TrackMetadataNormalizer
) {

    companion object {

        private const val MIN_ARTIST_SCORE =
            0.80

        private const val MIN_TITLE_SCORE =
            0.90

        private const val MIN_TOTAL_SCORE =
            0.85

        /*
         * Bei Remix / Bootleg / Mashup verlangen wir
         * zusätzlich einen brauchbaren Versionsmatch.
         *
         * Dadurch wird beispielsweise
         *
         * Track (DubVision Remix)
         *
         * nicht einfach als Originaltrack akzeptiert.
         */
        private const val MIN_STRONG_VERSION_SCORE =
            0.75
    }

    fun evaluateAll(
        localMetadata: NormalizedTrackMetadata,
        matches: List<ListenBrainzRecordingMatch>
    ): List<ListenBrainzMatchEvaluation> {

        return matches
            .map { match ->

                evaluate(
                    localMetadata =
                        localMetadata,

                    match =
                        match
                )
            }
            .sortedByDescending {
                it.totalScore
            }
    }

    /*
     * Kompatibilitäts-Overload.
     *
     * Falls wir an anderer Stelle noch direkt
     * Artist + Titel übergeben, funktioniert
     * der Selector weiterhin.
     */
    fun evaluateAll(
        localArtist: String,
        localTitle: String,
        matches: List<ListenBrainzRecordingMatch>
    ): List<ListenBrainzMatchEvaluation> {

        val localMetadata =
            metadataNormalizer.normalize(
                artist = localArtist,
                title = localTitle
            )

        return evaluateAll(
            localMetadata =
                localMetadata,

            matches =
                matches
        )
    }

    fun selectBest(
        localMetadata: NormalizedTrackMetadata,
        matches: List<ListenBrainzRecordingMatch>
    ): ListenBrainzMatchEvaluation? {

        return evaluateAll(
            localMetadata =
                localMetadata,

            matches =
                matches
        )
            .firstOrNull {
                it.accepted
            }
    }

    private fun evaluate(
        localMetadata: NormalizedTrackMetadata,
        match: ListenBrainzRecordingMatch
    ): ListenBrainzMatchEvaluation {

        val remoteMetadata =
            metadataNormalizer.normalize(
                artist =
                    match.artistName,

                title =
                    match.recordingName
            )

        val artistScore =
            calculateArtistScore(
                local =
                    localMetadata,

                remote =
                    remoteMetadata
            )

        val titleScore =
            calculateTitleScore(
                local =
                    localMetadata,

                remote =
                    remoteMetadata
            )

        val versionScore =
            calculateVersionScore(
                local =
                    localMetadata,

                remote =
                    remoteMetadata
            )

        /*
         * Artist bleibt das stärkste Signal.
         *
         * Titel ist fast genauso wichtig.
         *
         * Version wird zusätzlich berücksichtigt,
         * ohne Radio/Extended-Unterschiede sofort
         * alles kaputtzumachen.
         */
        val totalScore =
            artistScore * 0.50 +
                    titleScore * 0.35 +
                    versionScore * 0.15

        val requiresStrongVersionMatch =
            localMetadata.versionType ==
                    TrackVersionType.REMIX ||
                    localMetadata.versionType ==
                    TrackVersionType.BOOTLEG ||
                    localMetadata.versionType ==
                    TrackVersionType.MASHUP

        val accepted =
            artistScore >=
                    MIN_ARTIST_SCORE &&
                    titleScore >=
                    MIN_TITLE_SCORE &&
                    totalScore >=
                    MIN_TOTAL_SCORE &&
                    (
                            !requiresStrongVersionMatch ||
                                    versionScore >=
                                    MIN_STRONG_VERSION_SCORE
                            )

        return ListenBrainzMatchEvaluation(
            match =
                match,

            artistScore =
                artistScore,

            titleScore =
                titleScore,

            versionScore =
                versionScore,

            totalScore =
                totalScore,

            accepted =
                accepted
        )
    }

    private fun calculateArtistScore(
        local: NormalizedTrackMetadata,
        remote: NormalizedTrackMetadata
    ): Double {

        /*
         * Vollständig gleiche normalisierte
         * Artist-Credits.
         */
        val localArtistKey =
            metadataNormalizer
                .comparisonKey(
                    local.searchArtist
                )

        val remoteArtistKey =
            metadataNormalizer
                .comparisonKey(
                    remote.searchArtist
                )

        if (
            localArtistKey.isNotBlank() &&
            localArtistKey ==
            remoteArtistKey
        ) {
            return 1.0
        }

        val localArtists =
            local
                .allArtists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .filter {
                    it.isNotBlank()
                }
                .toSet()

        val remoteArtists =
            remote
                .allArtists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .filter {
                    it.isNotBlank()
                }
                .toSet()

        if (
            localArtists.isEmpty() ||
            remoteArtists.isEmpty()
        ) {
            return 0.0
        }

        if (
            localArtists ==
            remoteArtists
        ) {
            return 1.0
        }

        val intersection =
            localArtists
                .intersect(
                    remoteArtists
                )

        val localCoverage =
            intersection.size.toDouble() /
                    localArtists.size.toDouble()

        val remoteCoverage =
            intersection.size.toDouble() /
                    remoteArtists.size.toDouble()

        /*
         * Lokaler Artist ist vollständig in einem
         * ausführlicheren externen Artist-Credit
         * enthalten.
         *
         * Beispiel:
         *
         * Markus Schulz
         *
         * vs.
         *
         * Markus Schulz feat. Sarah Howells
         */
        if (
            localCoverage >= 1.0
        ) {
            return 0.95
        }

        /*
         * Umgekehrter Fall:
         *
         * Unsere lokalen Tags enthalten mehr
         * Artistinformationen als der Provider.
         */
        if (
            remoteCoverage >= 1.0
        ) {
            return 0.90
        }

        return maxOf(
            localCoverage,
            remoteCoverage
        ) * 0.70
    }

    private fun calculateTitleScore(
        local: NormalizedTrackMetadata,
        remote: NormalizedTrackMetadata
    ): Double {

        val localTitle =
            metadataNormalizer
                .comparisonKey(
                    local.baseTitle
                )

        val remoteTitle =
            metadataNormalizer
                .comparisonKey(
                    remote.baseTitle
                )

        if (
            localTitle.isBlank() ||
            remoteTitle.isBlank()
        ) {
            return 0.0
        }

        /*
         * Durch comparisonKey werden beispielsweise
         * unterschiedliche Apostrophe bereits
         * vereinheitlicht:
         *
         * Don't
         * Don’t
         */
        if (
            localTitle ==
            remoteTitle
        ) {
            return 1.0
        }

        return 0.0
    }

    private fun calculateVersionScore(
        local: NormalizedTrackMetadata,
        remote: NormalizedTrackMetadata
    ): Double {

        val localType =
            local.versionType

        val remoteType =
            remote.versionType

        if (
            localType == null &&
            remoteType == null
        ) {
            return 1.0
        }

        /*
         * Eine Seite kennt keine Versionsangabe.
         *
         * Das ist beispielsweise bei Datenbanken
         * möglich, die "Radio Edit" nicht sauber
         * im Recording-Namen tragen.
         *
         * Deshalb nicht komplett verwerfen.
         */
        if (
            localType == null ||
            remoteType == null
        ) {
            return 0.65
        }

        if (
            localType !=
            remoteType
        ) {
            return 0.20
        }

        /*
         * Bei normalen Mix-Typen reicht derselbe Typ.
         */
        if (
            localType !=
            TrackVersionType.REMIX &&
            localType !=
            TrackVersionType.BOOTLEG &&
            localType !=
            TrackVersionType.MASHUP
        ) {
            return 1.0
        }

        /*
         * Remix / Bootleg / Mashup:
         *
         * Falls wir konkrete Artists aus der
         * Versionsbezeichnung kennen, berücksichtigen
         * wir auch diese.
         */
        val localRemixArtists =
            local.remixArtists

        val remoteRemixArtists =
            remote.remixArtists

        if (
            localRemixArtists.isEmpty() &&
            remoteRemixArtists.isEmpty()
        ) {
            return 0.90
        }

        if (
            localRemixArtists.isEmpty() ||
            remoteRemixArtists.isEmpty()
        ) {
            return 0.75
        }

        val localKeys =
            localRemixArtists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .toSet()

        val remoteKeys =
            remoteRemixArtists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .toSet()

        val overlap =
            localKeys
                .intersect(
                    remoteKeys
                )

        if (
            overlap.isNotEmpty()
        ) {
            return 1.0
        }

        /*
         * Gleicher Remix-Typ, aber offenbar
         * unterschiedliche Remixer.
         */
        return 0.35
    }
}