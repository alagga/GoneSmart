package io.github.alagga.gonesmart

data class TrackDuplicateIdentity(
    val versionFamilyKeys: Set<String>,
    val songIdentityKeys: Set<String>,
    val isRemix: Boolean
) {
    companion object {
        val EMPTY = TrackDuplicateIdentity(
            versionFamilyKeys = emptySet(),
            songIdentityKeys = emptySet(),
            isRemix = false
        )
    }
}

class TrackFamilyKeyBuilder(
    private val metadataNormalizer:
    TrackMetadataNormalizer,

    private val trackInputResolver:
    TrackInputResolver
) {

    companion object {

        private val BRACKETED_LIVE_REGEX =
            Regex(
                pattern =
                    """(?i)[\(\[][^\)\]]*\blive\b[^\)\]]*[\)\]]"""
            )

        private val DASH_LIVE_REGEX =
            Regex(
                pattern =
                    """(?i)\s[-–—]\s*live(?:\s+(?:version|at|from|in)\b.*)?\s*$"""
            )

        private val EXPLICIT_LIVE_REGEX =
            Regex(
                pattern =
                    """(?i)\blive\s+(?:version|at|from)\b"""
            )
    }

    fun familyKeys(
        track: TrackInfo
    ): Set<String> =
        duplicateIdentity(track)
            .versionFamilyKeys

    fun duplicateIdentity(
        track: TrackInfo
    ): TrackDuplicateIdentity {

        return try {

            val resolved =
                trackInputResolver
                    .resolve(
                        track
                    )

            val metadata =
                metadataNormalizer
                    .normalize(
                        resolved.track
                    )

            duplicateIdentity(
                metadata = metadata,
                rawTitle = resolved.track.title
            )

        } catch (
            _: Throwable
        ) {

            TrackDuplicateIdentity.EMPTY
        }
    }

    fun isLiveTrack(
        track: TrackInfo
    ): Boolean {

        return isLiveTitle(
            track.title
        )
    }

    fun isLiveTitle(
        title: String?
    ): Boolean {

        val value =
            title
                ?.trim()
                .orEmpty()

        if (
            value.isBlank()
        ) {

            return false
        }

        return BRACKETED_LIVE_REGEX
            .containsMatchIn(
                value
            ) ||
                DASH_LIVE_REGEX
                    .containsMatchIn(
                        value
                    ) ||
                EXPLICIT_LIVE_REGEX
                    .containsMatchIn(
                        value
                    )
    }

    private fun duplicateIdentity(
        metadata: NormalizedTrackMetadata,
        rawTitle: String?
    ): TrackDuplicateIdentity {

        val titleKey =
            metadataNormalizer
                .comparisonKey(
                    metadata.baseTitle
                )

        if (
            titleKey.isBlank()
        ) {

            return TrackDuplicateIdentity.EMPTY
        }

        val artistKeys =
            metadata
                .artists
                .map {
                    metadataNormalizer
                        .comparisonKey(
                            it
                        )
                }
                .filter {
                    it.isNotBlank() &&
                        it != "unknown" &&
                        it != "unknownartist"
                }
                .toSet()

        val normalizedArtists =
            if (
                artistKeys.isEmpty()
            ) {

                setOf("*")

            } else {

                artistKeys
            }

        val songIdentityKeys =
            normalizedArtists
                .map { artistKey ->
                    "$artistKey|$titleKey"
                }
                .toSet()

        val versionFamily =
            versionFamilyKey(
                metadata = metadata,
                rawTitle = rawTitle
            )

        val versionFamilyKeys =
            songIdentityKeys
                .map { songKey ->
                    "$songKey|$versionFamily"
                }
                .toSet()

        return TrackDuplicateIdentity(
            versionFamilyKeys = versionFamilyKeys,
            songIdentityKeys = songIdentityKeys,
            isRemix = metadata.versionType == TrackVersionType.REMIX
        )
    }

    private fun versionFamilyKey(
        metadata: NormalizedTrackMetadata,
        rawTitle: String?
    ): String {

        if (
            isLiveTitle(
                rawTitle
            )
        ) {

            return "LIVE"
        }

        return when (
            metadata.versionType
        ) {

            null,
            TrackVersionType.ORIGINAL_MIX,
            TrackVersionType.RADIO_EDIT,
            TrackVersionType.EXTENDED_MIX,
            TrackVersionType.CLUB_MIX -> {

                "BASE"
            }

            TrackVersionType.REMIX -> {

                "REMIX:" +
                    versionLabelKey(
                        metadata
                    )
            }

            TrackVersionType.EDIT -> {

                "EDIT:" +
                    versionLabelKey(
                        metadata
                    )
            }

            TrackVersionType.BOOTLEG -> {

                "BOOTLEG:" +
                    versionLabelKey(
                        metadata
                    )
            }

            TrackVersionType.MASHUP -> {

                "MASHUP:" +
                    versionLabelKey(
                        metadata
                    )
            }

            TrackVersionType.ACOUSTIC_VERSION -> {

                "ACOUSTIC:" +
                    versionLabelKey(
                        metadata
                    )
            }
        }
    }

    private fun versionLabelKey(
        metadata: NormalizedTrackMetadata
    ): String {

        val labelKey =
            metadataNormalizer
                .comparisonKey(
                    metadata
                        .versionLabel
                        .orEmpty()
                )

        return if (
            labelKey.isBlank()
        ) {

            "UNKNOWN"

        } else {

            labelKey
        }
    }
}
