package io.github.alagga.gonesmart

enum class TrackInputSource {
    TAGS,
    TITLE_FILENAME_FALLBACK,
    PATH_FILENAME_FALLBACK
}

data class ResolvedTrackInput(
    val track: TrackInfo,
    val source: TrackInputSource
)

class TrackInputResolver {

    companion object {

        private val AUDIO_EXTENSION_REGEX =
            Regex(
                pattern =
                    """(?i)\.(?:mp3|flac|m4a|aac|ogg|opus|wav|wma|alac|aiff|aif|ape)$"""
            )

        /*
         * Beispiele:
         *
         * 6. Artist - Title
         * 06. Artist - Title
         * 6) Artist - Title
         * 06 - Artist - Title
         * 06_ Artist - Title
         *
         * Wichtig:
         *
         * "50 Cent - In Da Club"
         *
         * darf NICHT verändert werden.
         */
        private val LEADING_TRACK_NUMBER_REGEX =
            Regex(
                pattern =
                    """^\s*\d{1,3}(?:\s*[.)]\s+|\s*[-_]\s+)"""
            )

        private val UNKNOWN_ARTISTS =
            setOf(
                "",
                "unknown",
                "unknown artist",
                "<unknown>",
                "<unknown artist>",
                "?"
            )

        private val UNKNOWN_TITLES =
            setOf(
                "",
                "unknown",
                "unknown title",
                "<unknown>",
                "<unknown title>"
            )
    }

    fun resolve(
        track: TrackInfo
    ): ResolvedTrackInput {

        val rawArtist =
            track.artist
                ?.trim()
                .orEmpty()

        val rawTitle =
            track.title
                ?.trim()
                .orEmpty()

        val artistMissing =
            isUnknownArtist(
                rawArtist
            )

        val titleMissing =
            isUnknownTitle(
                rawTitle
            )

        /*
         * Normale, brauchbar getaggte Datei.
         */
        if (
            !artistMissing &&
            !titleMissing
        ) {

            return ResolvedTrackInput(
                track =
                    track,

                source =
                    TrackInputSource.TAGS
            )
        }

        /*
         * GMMP verwendet bei vollständig
         * fehlendem Titel offenbar häufig den
         * Dateinamen als track_name.
         *
         * Beispiel:
         *
         * Artist = Unknown
         * Title  =
         * 6. Fred again.. - Trippie (Edit3).mp3
         */
        if (
            !titleMissing
        ) {

            val cleanedTitle =
                cleanFilenameLikeText(
                    rawTitle
                )

            if (
                cleanedTitle != rawTitle
            ) {

                return ResolvedTrackInput(
                    track =
                        track.copy(
                            title =
                                cleanedTitle
                        ),

                    source =
                        TrackInputSource
                            .TITLE_FILENAME_FALLBACK
                )
            }

            /*
             * Auch ohne Dateiendung kann ein
             * Scanner bereits den Dateinamen
             * bereinigt haben.
             *
             * Die führende Tracknummer entfernen
             * wir bei unbekanntem Artist trotzdem.
             */
            if (
                artistMissing
            ) {

                val withoutTrackNumber =
                    removeLeadingTrackNumber(
                        rawTitle
                    )

                if (
                    withoutTrackNumber !=
                    rawTitle
                ) {

                    return ResolvedTrackInput(
                        track =
                            track.copy(
                                title =
                                    withoutTrackNumber
                            ),

                        source =
                            TrackInputSource
                                .TITLE_FILENAME_FALLBACK
                    )
                }
            }
        }

        /*
         * Wenn der Titel wirklich leer ist,
         * verwenden wir track_uri / path.
         */
        if (
            titleMissing &&
            !track.path.isNullOrBlank()
        ) {

            val filename =
                filenameFromPath(
                    track.path
                )

            val cleanedFilename =
                cleanFilenameLikeText(
                    filename
                )

            if (
                cleanedFilename.isNotBlank()
            ) {

                /*
                 * Wenn der Artist vorhanden ist
                 * und der Dateiname ebenfalls
                 *
                 * Artist - Title
                 *
                 * enthält, entfernen wir den
                 * Artist-Anteil aus dem Titel.
                 */
                val resolvedTitle =
                    if (
                        !artistMissing
                    ) {

                        titleFromFilenameWithKnownArtist(
                            filenameText =
                                cleanedFilename,

                            knownArtist =
                                rawArtist
                        )

                    } else {

                        cleanedFilename
                    }

                return ResolvedTrackInput(
                    track =
                        track.copy(
                            title =
                                resolvedTitle
                        ),

                    source =
                        TrackInputSource
                            .PATH_FILENAME_FALLBACK
                )
            }
        }

        return ResolvedTrackInput(
            track =
                track,

            source =
                TrackInputSource.TAGS
        )
    }

    fun isUnknownArtist(
        artist: String?
    ): Boolean {

        val key =
            artist
                ?.trim()
                ?.lowercase()
                .orEmpty()

        return key in
                UNKNOWN_ARTISTS
    }

    private fun isUnknownTitle(
        title: String?
    ): Boolean {

        val key =
            title
                ?.trim()
                ?.lowercase()
                .orEmpty()

        return key in
                UNKNOWN_TITLES
    }

    private fun cleanFilenameLikeText(
        value: String
    ): String {

        var result =
            value.trim()

        result =
            AUDIO_EXTENSION_REGEX
                .replace(
                    result,
                    ""
                )
                .trim()

        result =
            removeLeadingTrackNumber(
                result
            )

        return result
            .trim()
    }

    private fun removeLeadingTrackNumber(
        value: String
    ): String {

        return LEADING_TRACK_NUMBER_REGEX
            .replace(
                value,
                ""
            )
            .trim()
    }

    private fun filenameFromPath(
        path: String
    ): String {

        val normalized =
            path.replace(
                '\\',
                '/'
            )

        return normalized
            .substringAfterLast(
                '/'
            )
            .trim()
    }

    private fun titleFromFilenameWithKnownArtist(
        filenameText: String,
        knownArtist: String
    ): String {

        val separatorIndex =
            filenameText.indexOf(
                " - "
            )

        if (
            separatorIndex <= 0
        ) {

            return filenameText
        }

        val filenameArtist =
            filenameText
                .substring(
                    0,
                    separatorIndex
                )
                .trim()

        val filenameTitle =
            filenameText
                .substring(
                    separatorIndex + 3
                )
                .trim()

        if (
            filenameTitle.isBlank()
        ) {

            return filenameText
        }

        if (
            simpleKey(
                filenameArtist
            ) ==
            simpleKey(
                knownArtist
            )
        ) {

            return filenameTitle
        }

        return filenameText
    }

    private fun simpleKey(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex(
                    """[^\p{L}\p{N}]+"""
                ),
                ""
            )
    }
}