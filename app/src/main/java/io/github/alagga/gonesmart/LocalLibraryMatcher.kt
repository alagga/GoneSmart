package io.github.alagga.gonesmart

import android.util.Log
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class LocalRecommendationMatch(
    val recommendation: RecommendationCandidate,
    val libraryTrack: GmmpLibraryTrack,
    val matchScore: Double,
    val titleScore: Double,
    val artistScore: Double,
    val versionScore: Double,
    val finalScore: Double,
    val rankingScore: Double = finalScore,
    val preferenceMultiplier: Double = 1.0,
    val ratingMultiplier: Double = 1.0,
    val eraMultiplier: Double = 1.0,
    val recentAddedMultiplier: Double = 1.0,
    val liveMultiplier: Double = 1.0
) {
    val track: TrackInfo
        get() = libraryTrack.track

    val ratingStars: Double?
        get() = libraryTrack.ratingStars
}

enum class LibraryIndexPreparationResult {
    BUILT,
    CACHE_HIT
}

class LocalLibraryMatcher(
    private val metadataNormalizer:
    TrackMetadataNormalizer,

    private val trackInputResolver:
    TrackInputResolver
) {

    companion object {

        private const val TAG =
            "GoneSmart"

        private const val MIN_MATCH_SCORE =
            0.82

        private const val MIN_TITLE_SCORE =
            0.90

        private const val MAX_EXTERNAL_CANDIDATES =
            120

        private const val MAX_RAW_RESULTS =
            120

        private const val TARGET_RESULTS_BEFORE_FUZZY =
            12

        private const val MAX_FUZZY_RECOMMENDATIONS =
            24

        private const val MAX_FUZZY_CANDIDATES =
            16

        private const val MAX_EXACT_CANDIDATES_PER_RECOMMENDATION =
            12

        private const val MIN_FUZZY_TITLE_SCORE =
            0.90

        private val TRAILING_BRACKET_GROUP_REGEX =
            Regex(
                pattern =
                    """\s*[\(\[][^\(\)\[\]]*[\)\]]\s*$"""
            )

        private val TRAILING_FEATURE_REGEX =
            Regex(
                pattern =
                    """(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.+$"""
            )

        private val TRAILING_DASH_VERSION_REGEX =
            Regex(
                pattern =
                    """(?i)\s*[-–—]\s*(?:radio\s+edit|extended\s+mix|original\s+mix|club\s+mix|acoustic(?:\s+version)?|remix|edit|bootleg|mashup)\s*$"""
            )
    }

    @Volatile
    private var cachedFingerprint:
            LibraryFingerprint? =
        null

    @Volatile
    private var cachedLibraryIndex:
            LibraryIndex? =
        null

    private val normalizedTrackCache =
        mutableMapOf<
                Long,
                NormalizedLocalTrack
                >()

    fun prepareLibrary(
        library: List<GmmpLibraryTrack>
    ): LibraryIndexPreparationResult {

        return getOrBuildIndex(
            library
        ).preparationResult
    }

    fun match(
        recommendations:
        List<RecommendationCandidate>,

        library:
        List<GmmpLibraryTrack>,

        excludedTrackIds:
        Set<Long>
    ): List<LocalRecommendationMatch> {

        if (
            recommendations.isEmpty() ||
            library.isEmpty()
        ) {

            return emptyList()
        }

        val indexLookup =
            getOrBuildIndex(
                library
            )

        val index =
            indexLookup.index

        val preparedRecommendations =
            recommendations
                .take(
                    MAX_EXTERNAL_CANDIDATES
                )
                .mapNotNull { recommendation ->

                    val metadata =
                        normalizeRecommendation(
                            recommendation
                        )
                            ?: return@mapNotNull null

                    val titleKeys =
                        recommendationTitleKeys(
                            recommendation = recommendation,
                            metadata = metadata
                        )

                    if (
                        titleKeys.isEmpty()
                    ) {

                        return@mapNotNull null
                    }

                    PreparedRecommendation(
                        recommendation = recommendation,
                        metadata = metadata,
                        titleKeys = titleKeys
                    )
                }

        Log.i(
            TAG,
            "LOCAL MATCH START | " +
                    "recommendations=${recommendations.size} | " +
                    "prepared=${preparedRecommendations.size} | " +
                    "indexedTracks=${index.trackCount}"
        )

        val startTime =
            System.nanoTime()

        val collectedMatches =
            mutableListOf<LocalRecommendationMatch>()

        var exactRecommendationCount =
            0

        preparedRecommendations
            .forEach { prepared ->

                val exactTracks =
                    findExactCandidates(
                        prepared = prepared,
                        index = index,
                        excludedTrackIds = excludedTrackIds
                    )

                val scored =
                    scoreCandidateTracks(
                        prepared = prepared,
                        tracks = exactTracks
                    )

                if (
                    scored.isNotEmpty()
                ) {

                    exactRecommendationCount +=
                        1

                    collectedMatches +=
                        scored
                }
            }

        var fuzzyRecommendationsUsed =
            0

        if (
            exactRecommendationCount <
            TARGET_RESULTS_BEFORE_FUZZY
        ) {

            val exactlyMatchedRecommendations =
                collectedMatches
                    .map {
                        it.recommendation
                    }
                    .toSet()

            preparedRecommendations
                .asSequence()
                .filter {
                    it.recommendation !in
                            exactlyMatchedRecommendations
                }
                .take(
                    MAX_FUZZY_RECOMMENDATIONS
                )
                .forEach { prepared ->

                    val primaryTitleKey =
                        cheapKey(
                            prepared.metadata.baseTitle
                        )

                    if (
                        primaryTitleKey.isBlank()
                    ) {

                        return@forEach
                    }

                    fuzzyRecommendationsUsed +=
                        1

                    val fuzzyTracks =
                        findFuzzyCandidates(
                            recommendationTitleKey = primaryTitleKey,
                            prepared = prepared,
                            index = index,
                            excludedTrackIds = excludedTrackIds
                        )

                    collectedMatches +=
                        scoreCandidateTracks(
                            prepared = prepared,
                            tracks = fuzzyTracks
                        )
                }
        }

        val result =
            collectedMatches
                .sortedByDescending {
                    it.finalScore
                }
                .take(
                    MAX_RAW_RESULTS
                )

        val elapsedMs =
            (
                    System.nanoTime() -
                            startTime
                    ) /
                    1_000_000L

        Log.i(
            TAG,
            "LOCAL MATCH END | " +
                    "exactRecommendations=$exactRecommendationCount | " +
                    "fuzzyRecommendations=$fuzzyRecommendationsUsed | " +
                    "rawMatches=${result.size} | " +
                    "normalizedLocalCache=" +
                    normalizedTrackCache.size +
                    " | durationMs=$elapsedMs"
        )

        return result
    }

    private fun findExactCandidates(
        prepared: PreparedRecommendation,
        index: LibraryIndex,
        excludedTrackIds: Set<Long>
    ): List<GmmpLibraryTrack> {

        val allCandidates =
            linkedMapOf<
                    Long,
                    GmmpLibraryTrack
                    >()

        prepared
            .titleKeys
            .forEach { key ->

                index
                    .exactTitleIndex[
                    key
                ]
                    .orEmpty()
                    .forEach { libraryTrack ->

                        if (
                            libraryTrack.track.id !in
                            excludedTrackIds
                        ) {

                            allCandidates[
                                libraryTrack.track.id
                            ] =
                                libraryTrack
                        }
                    }
            }

        if (
            allCandidates.isEmpty()
        ) {

            return emptyList()
        }

        val artistFiltered =
            allCandidates
                .values
                .filter { libraryTrack ->

                    cheapArtistCompatible(
                        metadata = prepared.metadata,
                        localArtist = libraryTrack.track.artist
                    )
                }

        val preferredPool =
            if (
                artistFiltered.isNotEmpty()
            ) {

                artistFiltered

            } else {

                allCandidates.values.toList()
            }

        return preferredPool
            .sortedWith(
                compareByDescending<GmmpLibraryTrack> {
                    it.ratingRaw
                }
                    .thenBy {
                        it.track.id
                    }
            )
            .take(
                MAX_EXACT_CANDIDATES_PER_RECOMMENDATION
            )
    }

    private fun scoreCandidateTracks(
        prepared: PreparedRecommendation,
        tracks: List<GmmpLibraryTrack>
    ): List<LocalRecommendationMatch> {

        if (
            tracks.isEmpty()
        ) {

            return emptyList()
        }

        val result =
            mutableListOf<LocalRecommendationMatch>()

        tracks
            .forEach { libraryTrack ->

                val local =
                    normalizeLocalTrackCached(
                        libraryTrack.track
                    )
                        ?: return@forEach

                val localMatch =
                    scoreMatch(
                        recommendation = prepared.recommendation,
                        recommendationMetadata = prepared.metadata,
                        libraryTrack = libraryTrack,
                        local = local
                    )

                if (
                    localMatch.titleScore >=
                    MIN_TITLE_SCORE &&
                    localMatch.matchScore >=
                    MIN_MATCH_SCORE
                ) {

                    result +=
                        localMatch
                }
            }

        return result
    }

    private fun getOrBuildIndex(
        library: List<GmmpLibraryTrack>
    ): IndexLookupResult {

        val fingerprint =
            calculateFingerprint(
                library
            )

        val currentFingerprint =
            cachedFingerprint

        val currentIndex =
            cachedLibraryIndex

        if (
            currentFingerprint ==
            fingerprint &&
            currentIndex != null
        ) {

            Log.i(
                TAG,
                "LOCAL LIBRARY INDEX CACHE HIT | " +
                        "tracks=${fingerprint.trackCount}"
            )

            return IndexLookupResult(
                index = currentIndex,
                preparationResult =
                    LibraryIndexPreparationResult.CACHE_HIT
            )
        }

        synchronized(
            this
        ) {

            val synchronizedFingerprint =
                cachedFingerprint

            val synchronizedIndex =
                cachedLibraryIndex

            if (
                synchronizedFingerprint ==
                fingerprint &&
                synchronizedIndex != null
            ) {

                return IndexLookupResult(
                    index = synchronizedIndex,
                    preparationResult =
                        LibraryIndexPreparationResult.CACHE_HIT
                )
            }

            Log.i(
                TAG,
                "LOCAL LIBRARY CHANGE DETECTED | " +
                        "old=$synchronizedFingerprint | " +
                        "new=$fingerprint"
            )

            synchronized(
                normalizedTrackCache
            ) {

                normalizedTrackCache.clear()
            }

            val built =
                buildIndex(
                    library = library,
                    fingerprint = fingerprint
                )

            cachedFingerprint =
                fingerprint

            cachedLibraryIndex =
                built

            return IndexLookupResult(
                index = built,
                preparationResult =
                    LibraryIndexPreparationResult.BUILT
            )
        }
    }

    private fun buildIndex(
        library: List<GmmpLibraryTrack>,
        fingerprint: LibraryFingerprint
    ): LibraryIndex {

        Log.i(
            TAG,
            "LOCAL LIBRARY INDEX BUILD START | " +
                    "tracks=${library.size}"
        )

        val startTime =
            System.nanoTime()

        val exactTitleIndex =
            mutableMapOf<
                    String,
                    MutableList<GmmpLibraryTrack>
                    >()

        val firstCharacterIndex =
            mutableMapOf<
                    Char,
                    MutableList<TitleIndexEntry>
                    >()

        var indexedTracks =
            0

        var indexedKeys =
            0

        library
            .forEach { libraryTrack ->

                val keys =
                    cheapTitleKeys(
                        libraryTrack.track
                    )

                if (
                    keys.isEmpty()
                ) {

                    return@forEach
                }

                indexedTracks +=
                    1

                keys
                    .forEach { key ->

                        indexedKeys +=
                            1

                        exactTitleIndex
                            .getOrPut(
                                key
                            ) {
                                mutableListOf()
                            }
                            .add(
                                libraryTrack
                            )

                        val firstCharacter =
                            key.firstOrNull()

                        if (
                            firstCharacter != null
                        ) {

                            firstCharacterIndex
                                .getOrPut(
                                    firstCharacter
                                ) {
                                    mutableListOf()
                                }
                                .add(
                                    TitleIndexEntry(
                                        libraryTrack = libraryTrack,
                                        titleKey = key
                                    )
                                )
                        }
                    }
            }

        val elapsedMs =
            (
                    System.nanoTime() -
                            startTime
                    ) /
                    1_000_000L

        Log.i(
            TAG,
            "LOCAL LIBRARY INDEX BUILD END | " +
                    "indexedTracks=$indexedTracks | " +
                    "keys=$indexedKeys | " +
                    "exactTitles=${exactTitleIndex.size} | " +
                    "durationMs=$elapsedMs"
        )

        return LibraryIndex(
            fingerprint = fingerprint,
            trackCount = indexedTracks,
            exactTitleIndex = exactTitleIndex,
            firstCharacterIndex = firstCharacterIndex
        )
    }

    private fun cheapTitleKeys(
        track: TrackInfo
    ): Set<String> {

        val resolved =
            trackInputResolver
                .resolve(
                    track
                )

        val rawTitle =
            resolved
                .track
                .title
                ?.trim()
                .orEmpty()

        if (
            rawTitle.isBlank()
        ) {

            return emptySet()
        }

        val titleCandidates =
            linkedSetOf<String>()

        addCheapTitleVariants(
            value = rawTitle,
            target = titleCandidates
        )

        if (
            trackInputResolver
                .isUnknownArtist(
                    resolved.track.artist
                )
        ) {

            val separatorIndex =
                rawTitle.indexOf(
                    " - "
                )

            if (
                separatorIndex > 0 &&
                separatorIndex + 3 <
                rawTitle.length
            ) {

                val titlePart =
                    rawTitle
                        .substring(
                            separatorIndex + 3
                        )
                        .trim()

                addCheapTitleVariants(
                    value = titlePart,
                    target = titleCandidates
                )
            }
        }

        return titleCandidates
            .mapNotNull {
                cheapKey(
                    it
                )
                    .takeIf { key ->
                        key.isNotBlank()
                    }
            }
            .toSet()
    }

    private fun addCheapTitleVariants(
        value: String,
        target: MutableSet<String>
    ) {

        var current =
            value.trim()

        if (
            current.isBlank()
        ) {

            return
        }

        target +=
            current

        repeat(
            3
        ) {

            val withoutBracket =
                TRAILING_BRACKET_GROUP_REGEX
                    .replace(
                        current,
                        ""
                    )
                    .trim()

            if (
                withoutBracket.isBlank() ||
                withoutBracket ==
                current
            ) {

                return@repeat
            }

            target +=
                withoutBracket

            current =
                withoutBracket
        }

        val withoutFeature =
            TRAILING_FEATURE_REGEX
                .replace(
                    value,
                    ""
                )
                .trim()

        if (
            withoutFeature.isNotBlank() &&
            withoutFeature !=
            value
        ) {

            target +=
                withoutFeature
        }

        val withoutDashVersion =
            TRAILING_DASH_VERSION_REGEX
                .replace(
                    value,
                    ""
                )
                .trim()

        if (
            withoutDashVersion.isNotBlank() &&
            withoutDashVersion !=
            value
        ) {

            target +=
                withoutDashVersion
        }
    }

    private fun recommendationTitleKeys(
        recommendation:
        RecommendationCandidate,

        metadata:
        NormalizedTrackMetadata
    ): Set<String> {

        val result =
            linkedSetOf<String>()

        val baseKey =
            cheapKey(
                metadata.baseTitle
            )

        if (
            baseKey.isNotBlank()
        ) {

            result +=
                baseKey
        }

        val fullKey =
            cheapKey(
                recommendation.title
            )

        if (
            fullKey.isNotBlank()
        ) {

            result +=
                fullKey
        }

        return result
    }

    private fun findFuzzyCandidates(
        recommendationTitleKey: String,
        prepared: PreparedRecommendation,
        index: LibraryIndex,
        excludedTrackIds: Set<Long>
    ): List<GmmpLibraryTrack> {

        val firstCharacter =
            recommendationTitleKey
                .firstOrNull()
                ?: return emptyList()

        val bucket =
            index
                .firstCharacterIndex[
                firstCharacter
            ]
                .orEmpty()

        val wantedLength =
            recommendationTitleKey.length

        val maximumLengthDifference =
            max(
                3,
                (
                        wantedLength *
                                0.25
                        ).toInt()
            )

        val titleMatches =
            bucket
                .asSequence()
                .filter {
                    it.libraryTrack.track.id !in
                            excludedTrackIds
                }
                .filter {

                    abs(
                        it.titleKey.length -
                                wantedLength
                    ) <=
                            maximumLengthDifference
                }
                .map { entry ->

                    FuzzyTrackScore(
                        libraryTrack = entry.libraryTrack,
                        score =
                            stringSimilarity(
                                recommendationTitleKey,
                                entry.titleKey
                            )
                    )
                }
                .filter {
                    it.score >=
                            MIN_FUZZY_TITLE_SCORE
                }
                .groupBy {
                    it.libraryTrack.track.id
                }
                .values
                .mapNotNull {
                    it.maxByOrNull { score ->
                        score.score
                    }
                }

        val artistFiltered =
            titleMatches
                .filter { fuzzy ->

                    cheapArtistCompatible(
                        metadata = prepared.metadata,
                        localArtist = fuzzy.libraryTrack.track.artist
                    )
                }

        val preferredPool =
            if (
                artistFiltered.isNotEmpty()
            ) {

                artistFiltered

            } else {

                titleMatches
            }

        return preferredPool
            .sortedWith(
                compareByDescending<FuzzyTrackScore> {
                    it.score
                }
                    .thenByDescending {
                        it.libraryTrack.ratingRaw
                    }
            )
            .take(
                MAX_FUZZY_CANDIDATES
            )
            .map {
                it.libraryTrack
            }
    }

    private fun cheapArtistCompatible(
        metadata: NormalizedTrackMetadata,
        localArtist: String?
    ): Boolean {

        val localKey =
            cheapKey(
                localArtist.orEmpty()
            )

        if (
            localKey.isBlank() ||
            localKey ==
            "unknown" ||
            localKey ==
            "unknownartist"
        ) {

            return true
        }

        val externalKeys =
            metadata
                .artists
                .map {
                    cheapKey(
                        it
                    )
                }
                .filter {
                    it.isNotBlank()
                }

        if (
            externalKeys.isEmpty()
        ) {

            return true
        }

        return externalKeys
            .any { externalKey ->

                localKey.contains(
                    externalKey
                ) ||
                        externalKey.contains(
                            localKey
                        )
            }
    }

    private fun normalizeLocalTrackCached(
        track: TrackInfo
    ): NormalizedLocalTrack? {

        synchronized(
            normalizedTrackCache
        ) {

            normalizedTrackCache[
                track.id
            ]
                ?.let {

                    return it
                }
        }

        val normalized =
            normalizeLocalTrack(
                track
            )
                ?: return null

        synchronized(
            normalizedTrackCache
        ) {

            normalizedTrackCache[
                track.id
            ] =
                normalized
        }

        return normalized
    }

    private fun normalizeLocalTrack(
        track: TrackInfo
    ): NormalizedLocalTrack? {

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

            val titleKey =
                titleKey(
                    metadata
                )

            if (
                titleKey.isBlank()
            ) {

                null

            } else {

                NormalizedLocalTrack(
                    original = track,
                    metadata = metadata,
                    titleKey = titleKey
                )
            }

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun normalizeRecommendation(
        recommendation:
        RecommendationCandidate
    ): NormalizedTrackMetadata? {

        return try {

            metadataNormalizer
                .normalize(
                    TrackInfo(
                        id = -1L,
                        title = recommendation.title,
                        artist = recommendation.artist,
                        albumArtist = null,
                        path = null
                    )
                )

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    private fun scoreMatch(
        recommendation:
        RecommendationCandidate,

        recommendationMetadata:
        NormalizedTrackMetadata,

        libraryTrack:
        GmmpLibraryTrack,

        local:
        NormalizedLocalTrack
    ): LocalRecommendationMatch {

        val titleScore =
            titleSimilarity(
                recommendationMetadata,
                local.metadata
            )

        val artistScore =
            artistSimilarity(
                recommendationMetadata,
                local.metadata
            )

        val versionScore =
            versionSimilarity(
                recommendationMetadata,
                local.metadata
            )

        val matchScore =
            (
                    titleScore *
                            0.55 +
                            artistScore *
                            0.35 +
                            versionScore *
                            0.10
                    )
                .coerceIn(
                    0.0,
                    1.0
                )

        val finalScore =
            recommendation
                .totalScore *
                    matchScore

        return LocalRecommendationMatch(
            recommendation = recommendation,
            libraryTrack = libraryTrack,
            matchScore = matchScore,
            titleScore = titleScore,
            artistScore = artistScore,
            versionScore = versionScore,
            finalScore = finalScore
        )
    }

    private fun titleSimilarity(
        external:
        NormalizedTrackMetadata,

        local:
        NormalizedTrackMetadata
    ): Double {

        val externalKey =
            titleKey(
                external
            )

        val localKey =
            titleKey(
                local
            )

        if (
            externalKey.isBlank() ||
            localKey.isBlank()
        ) {

            return 0.0
        }

        if (
            externalKey ==
            localKey
        ) {

            return 1.0
        }

        return stringSimilarity(
            externalKey,
            localKey
        )
    }

    private fun artistSimilarity(
        external:
        NormalizedTrackMetadata,

        local:
        NormalizedTrackMetadata
    ): Double {

        val externalArtists =
            artistKeys(
                external
            )

        val localArtists =
            artistKeys(
                local
            )

        if (
            externalArtists.isEmpty() ||
            localArtists.isEmpty()
        ) {

            return 0.0
        }

        if (
            externalArtists.any {
                it in localArtists
            }
        ) {

            return 1.0
        }

        var best =
            0.0

        externalArtists
            .forEach { externalArtist ->

                localArtists
                    .forEach { localArtist ->

                        best =
                            max(
                                best,
                                stringSimilarity(
                                    externalArtist,
                                    localArtist
                                )
                            )
                    }
            }

        return best
    }

    private fun versionSimilarity(
        external:
        NormalizedTrackMetadata,

        local:
        NormalizedTrackMetadata
    ): Double {

        val externalType =
            external.versionType

        val localType =
            local.versionType

        if (
            externalType ==
            localType
        ) {

            if (
                externalType ==
                TrackVersionType.REMIX
            ) {

                return remixSimilarity(
                    external,
                    local
                )
            }

            if (
                externalType ==
                TrackVersionType.EDIT ||
                externalType ==
                TrackVersionType.BOOTLEG ||
                externalType ==
                TrackVersionType.MASHUP
            ) {

                return labelledVersionSimilarity(
                    external,
                    local
                )
            }

            return 1.0
        }

        val externalBase =
            isBaseVersion(
                externalType
            )

        val localBase =
            isBaseVersion(
                localType
            )

        if (
            externalBase &&
            localBase
        ) {

            return 1.0
        }

        if (
            externalBase xor
            localBase
        ) {

            val specificType =
                if (
                    externalBase
                ) {

                    localType

                } else {

                    externalType
                }

            return when (
                specificType
            ) {

                TrackVersionType.RADIO_EDIT,
                TrackVersionType.EXTENDED_MIX,
                TrackVersionType.CLUB_MIX -> {

                    0.85
                }

                TrackVersionType.ACOUSTIC_VERSION,
                TrackVersionType.REMIX,
                TrackVersionType.EDIT,
                TrackVersionType.BOOTLEG,
                TrackVersionType.MASHUP -> {

                    0.55
                }

                else -> {

                    0.70
                }
            }
        }

        return 0.30
    }

    private fun remixSimilarity(
        external:
        NormalizedTrackMetadata,

        local:
        NormalizedTrackMetadata
    ): Double {

        val externalRemixers =
            external
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
                .toSet()

        val localRemixers =
            local
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
                .toSet()

        if (
            externalRemixers.isNotEmpty() &&
            localRemixers.isNotEmpty()
        ) {

            return if (
                externalRemixers.any {
                    it in localRemixers
                }
            ) {

                1.0

            } else {

                0.25
            }
        }

        return labelledVersionSimilarity(
            external,
            local
        )
    }

    private fun labelledVersionSimilarity(
        external:
        NormalizedTrackMetadata,

        local:
        NormalizedTrackMetadata
    ): Double {

        val externalLabel =
            metadataNormalizer
                .comparisonKey(
                    external
                        .versionLabel
                        .orEmpty()
                )

        val localLabel =
            metadataNormalizer
                .comparisonKey(
                    local
                        .versionLabel
                        .orEmpty()
                )

        if (
            externalLabel.isBlank() ||
            localLabel.isBlank()
        ) {

            return 0.65
        }

        if (
            externalLabel ==
            localLabel
        ) {

            return 1.0
        }

        val similarity =
            stringSimilarity(
                externalLabel,
                localLabel
            )

        return if (
            similarity >=
            0.90
        ) {

            similarity

        } else {

            0.40
        }
    }

    private fun isBaseVersion(
        type: TrackVersionType?
    ): Boolean {

        return type == null ||
                type ==
                TrackVersionType.ORIGINAL_MIX
    }

    private fun titleKey(
        metadata:
        NormalizedTrackMetadata
    ): String {

        return metadataNormalizer
            .comparisonKey(
                metadata.baseTitle
            )
    }

    private fun artistKeys(
        metadata:
        NormalizedTrackMetadata
    ): Set<String> {

        return metadata
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
    }

    private fun calculateFingerprint(
        library:
        List<GmmpLibraryTrack>
    ): LibraryFingerprint {

        var hash =
            1125899906842597L

        library
            .forEach { libraryTrack ->

                val track =
                    libraryTrack.track

                hash =
                    hash * 31L +
                            track.id

                hash =
                    hash * 31L +
                            (
                                    track.title
                                        ?.hashCode()
                                        ?.toLong()
                                        ?: 0L
                                    )

                hash =
                    hash * 31L +
                            (
                                    track.artist
                                        ?.hashCode()
                                        ?.toLong()
                                        ?: 0L
                                    )

                hash =
                    hash * 31L +
                            (
                                    track.albumArtist
                                        ?.hashCode()
                                        ?.toLong()
                                        ?: 0L
                                    )

                hash =
                    hash * 31L +
                            (
                                    track.path
                                        ?.hashCode()
                                        ?.toLong()
                                        ?: 0L
                                    )

                hash =
                    hash * 31L +
                            libraryTrack.year

                hash =
                    hash * 31L +
                            libraryTrack.ratingRaw

                hash =
                    hash * 31L +
                            (
                                    libraryTrack.dateAddedEpochMs
                                        ?: 0L
                                    )

                hash =
                    hash * 31L +
                            (
                                    libraryTrack.dateUpdatedEpochMs
                                        ?: 0L
                                    )
            }

        return LibraryFingerprint(
            trackCount = library.size,
            contentHash = hash
        )
    }

    private fun cheapKey(
        value: String
    ): String {

        if (
            value.isBlank()
        ) {

            return ""
        }

        val normalized =
            Normalizer.normalize(
                value,
                Normalizer.Form.NFKD
            )
                .lowercase(
                    Locale.ROOT
                )

        val result =
            StringBuilder(
                normalized.length
            )

        normalized
            .forEach { character ->

                if (
                    character.isLetterOrDigit()
                ) {

                    result.append(
                        character
                    )
                }
            }

        return result.toString()
    }

    private fun stringSimilarity(
        left: String,
        right: String
    ): Double {

        if (
            left ==
            right
        ) {

            return 1.0
        }

        if (
            left.isEmpty() ||
            right.isEmpty()
        ) {

            return 0.0
        }

        val distance =
            levenshteinDistance(
                left,
                right
            )

        val maximumLength =
            max(
                left.length,
                right.length
            )

        return (
                1.0 -
                        distance.toDouble() /
                        maximumLength.toDouble()
                )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun levenshteinDistance(
        left: String,
        right: String
    ): Int {

        if (
            left ==
            right
        ) {

            return 0
        }

        if (
            left.isEmpty()
        ) {

            return right.length
        }

        if (
            right.isEmpty()
        ) {

            return left.length
        }

        var previous =
            IntArray(
                right.length + 1
            ) {
                it
            }

        var current =
            IntArray(
                right.length + 1
            )

        for (
        leftIndex in
        left.indices
        ) {

            current[0] =
                leftIndex + 1

            for (
            rightIndex in
            right.indices
            ) {

                val substitutionCost =
                    if (
                        left[leftIndex] ==
                        right[rightIndex]
                    ) {

                        0

                    } else {

                        1
                    }

                current[
                    rightIndex + 1
                ] =
                    minOf(
                        current[
                            rightIndex
                        ] + 1,
                        previous[
                            rightIndex + 1
                        ] + 1,
                        previous[
                            rightIndex
                        ] +
                                substitutionCost
                    )
            }

            val temporary =
                previous

            previous =
                current

            current =
                temporary
        }

        return previous[
            right.length
        ]
    }

    private data class PreparedRecommendation(
        val recommendation:
        RecommendationCandidate,

        val metadata:
        NormalizedTrackMetadata,

        val titleKeys:
        Set<String>
    )

    private data class NormalizedLocalTrack(
        val original: TrackInfo,
        val metadata: NormalizedTrackMetadata,
        val titleKey: String
    )

    private data class LibraryFingerprint(
        val trackCount: Int,
        val contentHash: Long
    )

    private data class LibraryIndex(
        val fingerprint: LibraryFingerprint,
        val trackCount: Int,

        val exactTitleIndex:
        Map<
                String,
                List<GmmpLibraryTrack>
                >,

        val firstCharacterIndex:
        Map<
                Char,
                List<TitleIndexEntry>
                >
    )

    private data class IndexLookupResult(
        val index: LibraryIndex,
        val preparationResult: LibraryIndexPreparationResult
    )

    private data class TitleIndexEntry(
        val libraryTrack: GmmpLibraryTrack,
        val titleKey: String
    )

    private data class FuzzyTrackScore(
        val libraryTrack: GmmpLibraryTrack,
        val score: Double
    )
}