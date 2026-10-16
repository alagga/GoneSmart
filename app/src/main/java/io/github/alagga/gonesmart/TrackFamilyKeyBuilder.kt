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

        @Suppress("UNUSED_VARIABLE")
        val duplicateRawTitle =
            rawTitle

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

        /*
         * "Prevent Version Duplicates" means one normalized song family,
         * regardless of Original/Radio/Extended/Remix/Edit/Bootleg/Live.
         * Version information still matters for matching/ranking elsewhere;
         * it must not create a loophole in duplicate prevention.
         */
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

        if (
            artistKeys.isEmpty()
        ) {

            return setOf(
                "*|$titleKey"
            )
        }

        return artistKeys
            .map { artistKey ->
                "$artistKey|$titleKey"
            }
            .toSet()
    }

}