package io.github.alagga.gonesmart

import java.text.Normalizer
import java.util.Locale

enum class MetadataConfidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class TrackVersionType {
    ORIGINAL_MIX,
    EXTENDED_MIX,
    RADIO_EDIT,
    CLUB_MIX,
    ACOUSTIC_VERSION,
    REMIX,
    EDIT,
    BOOTLEG,
    MASHUP
}

data class NormalizedTrackMetadata(
    val rawArtist: String?,
    val rawTitle: String?,

    /*
     * Artists aus dem eigentlichen Artist-Tag.
     */
    val artists: List<String>,

    /*
     * Featured Artists, die zusätzlich aus
     * dem Titel extrahiert wurden.
     */
    val featuredArtists: List<String>,

    /*
     * Falls vorhanden:
     *
     * "Track (DubVision Remix)"
     *
     * =>
     *
     * remixArtists = ["DubVision"]
     */
    val remixArtists: List<String>,

    /*
     * Titel ohne Feature- und Versionszusätze.
     */
    val baseTitle: String,

    /*
     * Originale erkannte Versionsbezeichnung.
     *
     * Beispiele:
     *
     * "DubVision Remix"
     * "Extended Mix"
     * "3LAUs Acoustic Version"
     */
    val versionLabel: String?,

    val versionType: TrackVersionType?,

    /*
     * Artist- und Title-String, den wir bevorzugt
     * an externe Recommendation-Dienste schicken.
     */
    val searchArtist: String,
    val searchTitle: String,

    val confidence: MetadataConfidence
) {

    val allArtists: List<String>
        get() =
            (
                    artists +
                            featuredArtists
                    )
                .distinctBy {
                    it.lowercase(
                        Locale.ROOT
                    )
                }
}

class TrackMetadataNormalizer(
    private val knownAmpersandArtistsProvider:
        () -> Set<String> = {
        emptySet()
    }
) {

    companion object {

        private const val AMPERSAND_TOKEN =
            "§GONESMART_AMPERSAND§"

        private val AUDIO_EXTENSION_REGEX =
            Regex(
                "(?i)\\.(mp3|flac|m4a|aac|ogg|wav|opus)$"
            )

        /*
         * Typischer YouTube-/Download-Müll.
         */
        private val PROMO_NOISE_REGEX =
            Regex(
                "(?i)\\b(" +
                        "official\\s+music\\s+video|" +
                        "official\\s+lyric\\s+video|" +
                        "official\\s+video|" +
                        "offizielles\\s+video|" +
                        "official\\s+audio|" +
                        "hq\\s+audio|" +
                        "full\\s+song|" +
                        "lyrics?\\s+video" +
                        ")\\b"
            )

        private val BRACKETED_GENERIC_NOISE_REGEX =
            Regex(
                "(?i)" +
                        "[\\(\\[]" +
                        "\\s*(" +
                        "official|" +
                        "audio|" +
                        "free|" +
                        "free\\s+dl|" +
                        "free\\s+download|" +
                        "out\\s+now!?" +
                        ")" +
                        "\\s*" +
                        "[\\)\\]]"
            )

        private val FREE_DOWNLOAD_REGEX =
            Regex(
                "(?i)" +
                        "[\\(\\[]" +
                        "\\s*free\\s*(?:dl|download)?" +
                        "\\s*" +
                        "[\\)\\]]"
            )

        private val OUT_NOW_REGEX =
            Regex(
                "(?i)" +
                        "(" +
                        "[\\(\\[]\\s*out\\s+now!?\\s*[\\)\\]]" +
                        "|" +
                        "\\s*-\\s*out\\s+now!?" +
                        ")"
            )

        private val EMPTY_BRACKETS_REGEX =
            Regex(
                "\\(\\s*\\)|\\[\\s*\\]"
            )

        private val BRACKETED_FEATURE_REGEX =
            Regex(
                "(?i)" +
                        "[\\(\\[]" +
                        "\\s*" +
                        "(?:feat(?:uring)?\\.?|ft\\.?)" +
                        "\\s+" +
                        "([^\\)\\]]+)" +
                        "[\\)\\]]"
            )

        private val BARE_FEATURE_REGEX =
            Regex(
                "(?i)" +
                        "\\s+" +
                        "(?:feat(?:uring)?\\.?|ft\\.?)" +
                        "\\s+" +
                        "(.+)$"
            )

        private val TRAILING_BRACKET_REGEX =
            Regex(
                "\\s*[\\(\\[]([^\\)\\]]+)[\\)\\]]\\s*$"
            )

        private val LEGACY_ARTIST_TITLE_REGEX =
            Regex(
                "^(.+?)\\s+[\\-–—]\\s+(.+)$"
            )

        private val LEGACY_BY_SUFFIX_REGEX =
            Regex(
                "(?i)\\s+by\\s+[^\\(\\[]+$"
            )

        private val GENERIC_ARTISTS =
            setOf(
                "",
                "various artists",
                "various",
                "unknown",
                "unbekannt",
                "none",
                "<unknown>",
                "va"
            )

        private val GENERIC_TITLES =
            setOf(
                "",
                "unknown",
                "unbekannt",
                "none",
                "<unknown>"
            )
    }

    fun normalize(
        track: TrackInfo
    ): NormalizedTrackMetadata {

        return normalize(
            artist = track.artist,
            title = track.title,
            path = track.path
        )
    }

    fun normalize(
        artist: String?,
        title: String?,
        path: String? = null
    ): NormalizedTrackMetadata {

        val rawArtist =
            artist

        val rawTitle =
            title

        var workingArtist =
            cleanArtistText(
                artist.orEmpty()
            )

        val titleCandidate =
            chooseTitle(
                title = title,
                path = path
            )

        var workingTitle =
            cleanTitleNoise(
                titleCandidate
            )

        var reconstructedFromLegacyTitle =
            false

        var titleTakenFromPath =
            false

        if (
            title.isNullOrBlank() ||
            isGenericTitle(
                title
            )
        ) {

            titleTakenFromPath =
                !path.isNullOrBlank()
        }

        /*
         * Altbestand-Fallback:
         *
         * Artist:
         * Various Artists
         *
         * Title:
         * Dropgun - Nobody by HEXAGON.mp3
         */
        if (
            isGenericArtist(
                workingArtist
            )
        ) {

            val reconstructed =
                splitLegacyArtistAndTitle(
                    workingTitle
                )

            if (
                reconstructed != null
            ) {

                workingArtist =
                    cleanArtistText(
                        reconstructed.first
                    )

                workingTitle =
                    cleanTitleNoise(
                        removeLegacyChannelSuffix(
                            reconstructed.second
                        )
                    )

                reconstructedFromLegacyTitle =
                    true
            }
        }

        /*
         * Featured Artists zuerst aus dem Titel holen.
         */
        val featureExtraction =
            extractFeaturedArtists(
                workingTitle
            )

        workingTitle =
            cleanupWhitespace(
                featureExtraction.cleanedTitle
            )

        /*
         * Danach Versionsangabe analysieren.
         */
        val versionExtraction =
            extractVersion(
                workingTitle
            )

        val baseTitle =
            cleanupWhitespace(
                versionExtraction.baseTitle
            )

        val artists =
            parseArtists(
                workingArtist
            )

        val featuredArtists =
            featureExtraction
                .artistStrings
                .flatMap {
                    parseArtists(
                        it
                    )
                }
                .distinctBy {
                    comparisonKey(
                        it
                    )
                }

        val remixArtists =
            extractRemixArtists(
                versionLabel =
                    versionExtraction.versionLabel,

                versionType =
                    versionExtraction.versionType
            )

        val allArtists =
            (
                    artists +
                            featuredArtists
                    )
                .filter {
                    it.isNotBlank()
                }
                .distinctBy {
                    comparisonKey(
                        it
                    )
                }

        val searchArtist =
            allArtists
                .joinToString(
                    ", "
                )

        /*
         * Versionsinformation für externe Suche
         * bewusst erhalten.
         */
        val searchTitle =
            if (
                versionExtraction.versionLabel
                    .isNullOrBlank()
            ) {

                baseTitle

            } else {

                "$baseTitle " +
                        "(${versionExtraction.versionLabel})"
            }

        val confidence =
            when {

                artists.isEmpty() ||
                        isGenericArtist(
                            workingArtist
                        ) ||
                        baseTitle.isBlank() -> {

                    MetadataConfidence.LOW
                }

                reconstructedFromLegacyTitle ||
                        titleTakenFromPath -> {

                    MetadataConfidence.MEDIUM
                }

                else -> {

                    MetadataConfidence.HIGH
                }
            }

        return NormalizedTrackMetadata(
            rawArtist =
                rawArtist,

            rawTitle =
                rawTitle,

            artists =
                artists,

            featuredArtists =
                featuredArtists,

            remixArtists =
                remixArtists,

            baseTitle =
                baseTitle,

            versionLabel =
                versionExtraction.versionLabel,

            versionType =
                versionExtraction.versionType,

            searchArtist =
                searchArtist,

            searchTitle =
                searchTitle,

            confidence =
                confidence
        )
    }

    fun comparisonKey(
        value: String
    ): String {

        val decomposed =
            Normalizer.normalize(
                value,
                Normalizer.Form.NFD
            )

        return decomposed
            .replace(
                Regex(
                    "\\p{Mn}+"
                ),
                ""
            )
            .lowercase(
                Locale.ROOT
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}]+"
                ),
                " "
            )
            .trim()
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
    }

    private fun chooseTitle(
        title: String?,
        path: String?
    ): String {

        if (
            !title.isNullOrBlank() &&
            !isGenericTitle(
                title
            )
        ) {
            return title
        }

        if (
            !path.isNullOrBlank()
        ) {

            return path
                .substringAfterLast(
                    "/"
                )
        }

        return title.orEmpty()
    }

    private fun cleanArtistText(
        value: String
    ): String {

        return value
            .replace(
                "&amp;",
                "&",
                ignoreCase = true
            )
            .replace(
                "\n",
                " "
            )
            .replace(
                "_",
                " "
            )
            .let {
                cleanupWhitespace(
                    it
                )
            }
    }

    private fun cleanTitleNoise(
        value: String
    ): String {

        var result =
            value
                .replace(
                    "_",
                    " "
                )
                .replace(
                    AUDIO_EXTENSION_REGEX,
                    ""
                )
                .replace(
                    PROMO_NOISE_REGEX,
                    ""
                )
                .replace(
                    BRACKETED_GENERIC_NOISE_REGEX,
                    ""
                )
                .replace(
                    FREE_DOWNLOAD_REGEX,
                    ""
                )
                .replace(
                    OUT_NOW_REGEX,
                    ""
                )

        while (
            EMPTY_BRACKETS_REGEX.containsMatchIn(
                result
            )
        ) {

            result =
                result.replace(
                    EMPTY_BRACKETS_REGEX,
                    " "
                )
        }

        return cleanupWhitespace(
            result
        )
    }

    private fun extractFeaturedArtists(
        title: String
    ): FeatureExtraction {

        var workingTitle =
            title

        val artistStrings =
            mutableListOf<String>()

        while (true) {

            val match =
                BRACKETED_FEATURE_REGEX
                    .find(
                        workingTitle
                    )
                    ?: break

            val artistText =
                match.groupValues
                    .getOrNull(
                        1
                    )
                    ?.trim()

            if (
                !artistText.isNullOrBlank()
            ) {

                artistStrings +=
                    artistText
            }

            workingTitle =
                workingTitle
                    .removeRange(
                        match.range
                    )
        }

        val bareMatch =
            BARE_FEATURE_REGEX
                .find(
                    workingTitle
                )

        if (
            bareMatch != null
        ) {

            val artistText =
                bareMatch.groupValues
                    .getOrNull(
                        1
                    )
                    ?.trim()

            if (
                !artistText.isNullOrBlank()
            ) {

                artistStrings +=
                    artistText
            }

            workingTitle =
                workingTitle
                    .removeRange(
                        bareMatch.range
                    )
        }

        return FeatureExtraction(
            cleanedTitle =
                cleanupWhitespace(
                    workingTitle
                ),

            artistStrings =
                artistStrings
        )
    }

    private fun extractVersion(
        title: String
    ): VersionExtraction {

        val match =
            TRAILING_BRACKET_REGEX
                .find(
                    title
                )

        if (
            match == null
        ) {

            return VersionExtraction(
                baseTitle =
                    title,

                versionLabel =
                    null,

                versionType =
                    null
            )
        }

        val content =
            match.groupValues
                .getOrNull(
                    1
                )
                ?.trim()
                .orEmpty()

        val versionType =
            classifyVersion(
                content
            )

        /*
         * Unbekannte Klammerinhalte bleiben Teil
         * des Titels.
         */
        if (
            versionType == null
        ) {

            return VersionExtraction(
                baseTitle =
                    title,

                versionLabel =
                    null,

                versionType =
                    null
            )
        }

        val baseTitle =
            title
                .removeRange(
                    match.range
                )
                .let {
                    cleanupWhitespace(
                        it
                    )
                }

        return VersionExtraction(
            baseTitle =
                baseTitle,

            versionLabel =
                content,

            versionType =
                versionType
        )
    }

    private fun classifyVersion(
        value: String
    ): TrackVersionType? {

        val normalized =
            comparisonKey(
                value
            )

        return when {

            /*
             * Remix zuerst prüfen.
             */
            Regex(
                "\\bremix\\b"
            ).containsMatchIn(
                normalized
            ) -> {

                TrackVersionType.REMIX
            }

            Regex(
                "\\bbootleg\\b"
            ).containsMatchIn(
                normalized
            ) -> {

                TrackVersionType.BOOTLEG
            }

            Regex(
                "\\bmashup\\b"
            ).containsMatchIn(
                normalized
            ) -> {

                TrackVersionType.MASHUP
            }

            /*
             * Beispiele:
             *
             * Extended Mix
             * Extended Version
             * Extended
             */
            normalized.contains(
                "extended"
            ) -> {

                TrackVersionType.EXTENDED_MIX
            }

            /*
             * Beispiele:
             *
             * Radio Edit
             * Radio Mix
             * Radio Version
             */
            normalized.contains(
                "radio edit"
            ) ||
                    normalized.contains(
                        "radio mix"
                    ) ||
                    normalized.contains(
                        "radio version"
                    ) -> {

                TrackVersionType.RADIO_EDIT
            }

            normalized.contains(
                "club mix"
            ) ||
                    normalized.contains(
                        "club version"
                    ) -> {

                TrackVersionType.CLUB_MIX
            }

            /*
             * NEU BUILD 22:
             *
             * Acoustic Version
             * Acoustic Mix
             *
             * sowie z.B.
             *
             * 3LAUs Acoustic Version
             */
            Regex(
                "\\bacoustic\\b"
            ).containsMatchIn(
                normalized
            ) -> {

                TrackVersionType.ACOUSTIC_VERSION
            }

            normalized.contains(
                "original mix"
            ) ||
                    normalized.contains(
                        "original version"
                    ) -> {

                TrackVersionType.ORIGINAL_MIX
            }

            Regex(
                "\\bedit\\b"
            ).containsMatchIn(
                normalized
            ) -> {

                TrackVersionType.EDIT
            }

            else -> {

                null
            }
        }
    }

    private fun extractRemixArtists(
        versionLabel: String?,
        versionType: TrackVersionType?
    ): List<String> {

        if (
            versionLabel.isNullOrBlank()
        ) {
            return emptyList()
        }

        if (
            versionType !=
            TrackVersionType.REMIX &&
            versionType !=
            TrackVersionType.BOOTLEG &&
            versionType !=
            TrackVersionType.MASHUP
        ) {
            return emptyList()
        }

        val artistPart =
            versionLabel
                .replace(
                    Regex(
                        "(?i)\\s+" +
                                "(remix|bootleg|mashup)" +
                                "\\s*$"
                    ),
                    ""
                )
                .trim()

        if (
            artistPart.isBlank()
        ) {
            return emptyList()
        }

        return parseArtists(
            artistPart
        )
    }

    private fun parseArtists(
        artistString: String
    ): List<String> {

        val cleaned =
            cleanArtistText(
                artistString
            )

        if (
            cleaned.isBlank()
        ) {
            return emptyList()
        }

        /*
         * Erst echte GMMP-Artistnamen mit &
         * schützen.
         */
        var protected =
            cleaned

        val knownAmpersandArtists =
            knownAmpersandArtistsProvider()
                .sortedByDescending {
                    it.length
                }

        knownAmpersandArtists
            .forEach { knownArtist ->

                if (
                    knownArtist.isBlank()
                ) {
                    return@forEach
                }

                val regex =
                    Regex(
                        Regex.escape(
                            knownArtist
                        ),
                        RegexOption.IGNORE_CASE
                    )

                protected =
                    regex.replace(
                        protected
                    ) { matchResult ->

                        matchResult
                            .value
                            .replace(
                                "&",
                                AMPERSAND_TOKEN
                            )
                    }
            }

        protected =
            protected
                .replace(
                    Regex(
                        "(?i)" +
                                "\\s+" +
                                "(" +
                                "feat(?:uring)?\\.?" +
                                "|" +
                                "ft\\.?" +
                                "|" +
                                "versus\\.?" +
                                "|" +
                                "vs\\.?" +
                                "|" +
                                "with" +
                                "|" +
                                "presents?\\.?" +
                                "|" +
                                "pres\\.?" +
                                "|" +
                                "x" +
                                ")" +
                                "\\s+"
                    ),
                    ";"
                )
                .replace(
                    ",",
                    ";"
                )

        /*
         * Nicht geschützte & gelten als Separator.
         */
        protected =
            protected.replace(
                Regex(
                    "\\s*&\\s*"
                ),
                ";"
            )

        return protected
            .split(
                ";"
            )
            .map {
                it
                    .replace(
                        AMPERSAND_TOKEN,
                        "&"
                    )
                    .trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinctBy {
                comparisonKey(
                    it
                )
            }
    }

    private fun splitLegacyArtistAndTitle(
        value: String
    ): Pair<String, String>? {

        val match =
            LEGACY_ARTIST_TITLE_REGEX
                .find(
                    value
                )
                ?: return null

        val artist =
            match.groupValues
                .getOrNull(
                    1
                )
                ?.trim()
                .orEmpty()

        val title =
            match.groupValues
                .getOrNull(
                    2
                )
                ?.trim()
                .orEmpty()

        if (
            artist.isBlank() ||
            title.isBlank()
        ) {
            return null
        }

        return artist to title
    }

    private fun removeLegacyChannelSuffix(
        value: String
    ): String {

        return value
            .replace(
                LEGACY_BY_SUFFIX_REGEX,
                ""
            )
            .trim()
    }

    private fun isGenericArtist(
        artist: String
    ): Boolean {

        return comparisonKey(
            artist
        ) in GENERIC_ARTISTS
    }

    private fun isGenericTitle(
        title: String
    ): Boolean {

        return comparisonKey(
            title
        ) in GENERIC_TITLES
    }

    private fun cleanupWhitespace(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    private data class FeatureExtraction(
        val cleanedTitle: String,
        val artistStrings: List<String>
    )

    private data class VersionExtraction(
        val baseTitle: String,
        val versionLabel: String?,
        val versionType: TrackVersionType?
    )
}