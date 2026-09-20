package io.github.alagga.gonesmart

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
    ): Set<String> {

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

            familyKeys(
                metadata = metadata,
                rawTitle = resolved.track.title
            )

        } catch (
            _: Throwable
        ) {

            emptySet()
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

    private fun familyKeys(
        metadata: NormalizedTrackMetadata,
        rawTitle: String?
    ): Set<String> {

        val titleKey =
            metadataNormalizer
                .comparisonKey(
                    metadata.baseTitle
                )

        if (
            titleKey.isBlank()
        ) {

            return emptySet()
        }

        val versionFamily =
            versionFamilyKey(
                metadata = metadata,
                rawTitle = rawTitle
            )

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
                            it !=
                            "unknown" &&
                            it !=
                            "unknownartist"
                }
                .toSet()

        if (
            artistKeys.isEmpty()
        ) {

            return setOf(
                "*|$titleKey|$versionFamily"
            )
        }

        return artistKeys
            .map { artistKey ->
                "$artistKey|$titleKey|$versionFamily"
            }
            .toSet()
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