from pathlib import Path
import re

ROOT = Path(".")

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected 1 occurrence, found {count}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))

def regex_once(path, pattern, repl, flags=re.S):
    text = read(path)
    new, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{path}: regex expected 1 occurrence, found {count}: {pattern[:100]!r}")
    write(path, new)

settings = "app/src/main/java/io/github/alagga/gonesmart/GmmpAutoDjSettingsReader.kt"
replace_once(settings,
'''data class SmartPoolSizing(
    val targetSize: Int,
    val lowWaterMark: Int,
    val minimumConsumedBeforeRefill: Int
)''',
'''data class SmartPoolSizing(
    val targetSize: Int,
    val lowWaterMark: Int,
    val minimumConsumedBeforeRefill: Int,
    val rescueCandidateTarget: Int
)''')

replace_once(settings,
'''        val lowWater =
            max(
                settings.upcomingTrackCount * 2,''',
'''        /*
         * Artist-level provider expansion is a rescue stage, not a default
         * tax on every seed. Scale its local-candidate gate from GMMP's
         * visible Auto-DJ demand. Initial Size includes the current seed.
         * Keep roughly two turns of useful local choices before paying for
         * broader artist calls, bounded by the internal pool target.
         */
        val visibleDemand =
            max(
                (settings.initialQueueSize - 1)
                    .coerceAtLeast(1),
                settings.upcomingTrackCount
            )

        val rescueCandidateTarget =
            (visibleDemand * 2)
                .coerceIn(
                    4,
                    target
                )

        val lowWater =
            max(
                settings.upcomingTrackCount * 2,''')

replace_once(settings,
'''        return SmartPoolSizing(
            targetSize = target,
            lowWaterMark = lowWater,
            minimumConsumedBeforeRefill =
                minimumConsumedBeforeRefill
        )''',
'''        return SmartPoolSizing(
            targetSize = target,
            lowWaterMark = lowWater,
            minimumConsumedBeforeRefill =
                minimumConsumedBeforeRefill,
            rescueCandidateTarget =
                rescueCandidateTarget
        )''')

family = "app/src/main/java/io/github/alagga/gonesmart/TrackFamilyKeyBuilder.kt"
family_text = read(family)
start = family_text.index("    private fun familyKeys(\n")
end_start = family_text.index("\n    private fun versionLabelKey(", start)
class_close = family_text.rfind("\n}")
if class_close <= end_start:
    raise RuntimeError("TrackFamilyKeyBuilder class close not found")
new_family = '''    private fun familyKeys(
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
'''
family_text = family_text[:start] + new_family + family_text[class_close:]
write(family, family_text)

module = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"

replace_once(module,
'''                    "poolTarget=${poolSizing.targetSize} | " +
                    "lowWater=${poolSizing.lowWaterMark}"''',
'''                    "poolTarget=${poolSizing.targetSize} | " +
                    "lowWater=${poolSizing.lowWaterMark} | " +
                    "rescueTarget=${poolSizing.rescueCandidateTarget}"''')

text = read(module)
needle = '''count = requestedTracks,
                        excludedTrackIds = queueTrackIds'''
count = text.count(needle)
if count != 3:
    raise RuntimeError(f"{module}: expected 3 requestedTracks readiness checks, found {count}")
text = text.replace(needle, '''count = 1,
                        excludedTrackIds = queueTrackIds''')
write(module, text)

replace_once(module,
'''                                generation = generation,
                                targetPoolSize = sizing.targetSize
                            )''',
'''                                generation = generation,
                                targetPoolSize = sizing.targetSize,
                                rescueCandidateTarget =
                                    sizing.rescueCandidateTarget
                            )''')

replace_once(module,
'''        generation: Long,
        targetPoolSize: Int
    ): List<Long> {''',
'''        generation: Long,
        targetPoolSize: Int,
        rescueCandidateTarget: Int
    ): List<Long> {''')

regex_once(module,
r'''            val smartTrackIds =\n.*?            nativeResult\n        }\n\n        Log\.i\(\n            TAG,\n            "Auto-DJ selection hook installed successfully"\n        \)''',
'''            val smartTrackIds =
                recommendationPool
                    .peek(
                        expectedSessionId = selectionContext.sessionId,
                        count = desiredCount,
                        excludedTrackIds = selectionContext.excludedTrackIds
                    )

            if (
                smartTrackIds.isEmpty()
            ) {

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | " +
                        "needed=$desiredCount | poolAvailable=0"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                return@intercept ArrayList<Any>()
            }

            val appliedRows =
                ArrayList<Any>()

            val appliedTrackIds =
                mutableListOf<Long>()

            smartTrackIds
                .forEachIndexed { index, trackId ->

                    val row =
                        rows.getOrNull(index)
                            ?: return@forEachIndexed

                    try {

                        setAutoDjTrackId(
                            row = row,
                            trackId = trackId
                        )

                        appliedRows +=
                            row

                        appliedTrackIds +=
                            trackId

                        Log.i(
                            TAG,
                            "SMART DJ SELECT | " +
                                "slot=${index + 1} | " +
                                "trackId=$trackId"
                        )

                    } catch (t: Throwable) {

                        Log.e(
                            TAG,
                            "Failed to replace Auto-DJ row " +
                                "at slot ${index + 1}",
                            t
                        )
                    }
                }

            if (
                appliedRows.isEmpty()
            ) {

                Log.w(
                    TAG,
                    "SMART DJ SUPPRESSED | no native row could be replaced"
                )

                playerBadgeController
                    .setMode(
                        PlayerAutoDjBadgeController.Mode.FALLBACK
                    )

                return@intercept ArrayList<Any>()
            }

            recommendationPool
                .commitSelected(
                    expectedSessionId = selectionContext.sessionId,
                    selectedTrackIds = appliedTrackIds
                )

            if (
                appliedRows.size <
                desiredCount
            ) {

                Log.i(
                    TAG,
                    "SMART DJ PARTIAL | " +
                        "requested=$desiredCount | " +
                        "applied=${appliedRows.size} | " +
                        "deferred=${desiredCount - appliedRows.size} | " +
                        "nativePadding=false"
                )
            }

            Log.i(
                TAG,
                "SMART DJ APPLIED | " +
                    "${appliedRows.size}/$desiredCount track(s) replaced | " +
                    recommendationPool.describe(
                        selectionContext.sessionId
                    )
            )

            playerBadgeController
                .setMode(
                    PlayerAutoDjBadgeController.Mode.SMART
                )

            runtimeReporter.report(
                mode = GoneSmartRuntimeContract.MODE_SMART,
                message = "GoneSmart selected ${appliedRows.size} track(s) from the current session pool.",
                appendEvent = true
            )

            appliedRows
        }

        Log.i(
            TAG,
            "Auto-DJ selection hook installed successfully"
        )''')

anchor = '''            var directLocalMatches =
                localLibraryMatcher
                    .match(
                        recommendations = activeRecommendations,
                        library = library,
                        excludedTrackIds = excludedTrackIds
                    )

            /*
             * External services recommend tracks globally, but the user can'''
rescue = '''            var directLocalMatches =
                localLibraryMatcher
                    .match(
                        recommendations = activeRecommendations,
                        library = library,
                        excludedTrackIds = excludedTrackIds
                    )

            var directLocalTrackCount =
                directLocalMatches
                    .map { it.track.id }
                    .distinct()
                    .size

            val shouldRunArtistRescue =
                !broadSearchPerformed &&
                    networkStateReader.getState() ==
                        GoneSmartNetworkState.ONLINE &&
                    directLocalTrackCount <
                        rescueCandidateTarget

            Log.i(
                TAG,
                "SMART DJ ARTIST RESCUE GATE | " +
                    "localDirect=$directLocalTrackCount | " +
                    "target=$rescueCandidateTarget | " +
                    "action=${if (shouldRunArtistRescue) "run" else "skip"}"
            )

            if (
                shouldRunArtistRescue
            ) {

                val rescueContributions =
                    collectArtistRescueContributions(
                        seeds = seeds,
                        library = library,
                        providerPolicy = providerPolicy
                    )

                if (
                    generation !=
                    pipelineGeneration.get()
                ) {

                    return emptyList()
                }

                if (
                    rescueContributions.isNotEmpty()
                ) {

                    rawContributions +=
                        rescueContributions

                    val rescueMerged =
                        recommendationAggregator
                            .aggregate(
                                rawContributions
                            )

                    if (
                        rescueMerged.isNotEmpty()
                    ) {

                        activeRecommendations =
                            rescueMerged

                        directLocalMatches =
                            localLibraryMatcher
                                .match(
                                    recommendations = activeRecommendations,
                                    library = library,
                                    excludedTrackIds = excludedTrackIds
                                )

                        directLocalTrackCount =
                            directLocalMatches
                                .map { it.track.id }
                                .distinct()
                                .size
                    }
                }

                Log.i(
                    TAG,
                    "SMART DJ ARTIST RESCUE END | " +
                        "contributions=${rescueContributions.size} | " +
                        "localDirect=$directLocalTrackCount | " +
                        "target=$rescueCandidateTarget"
                )
            }

            /*
             * External services recommend tracks globally, but the user can'''
replace_once(module, anchor, rescue)

replace_once(module,
'''        library: List<GmmpLibraryTrack>,
        providerPolicy: RecommendationProviderPolicy,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {''',
'''        library: List<GmmpLibraryTrack>,
        providerPolicy: RecommendationProviderPolicy,
        broadSearch: Boolean = false,
        allowArtistFallback: Boolean = false,
        artistFallbackOnly: Boolean = false
    ): List<RawRecommendationContribution> {''')

replace_once(module,
'''                        library = library,
                        broadSearch = broadSearch
                    )''',
'''                        library = library,
                        broadSearch = broadSearch,
                        allowArtistFallback = allowArtistFallback,
                        artistFallbackOnly = artistFallbackOnly
                    )''')

replace_once(module,
'''                        metadata = metadata,
                        broadSearch = broadSearch
                    )''',
'''                        metadata = metadata,
                        broadSearch = broadSearch,
                        allowArtistFallback = allowArtistFallback,
                        artistFallbackOnly = artistFallbackOnly
                    )''')

replace_once(module,
'''            "SMART DJ PROVIDER ROUND | seed=$seedNumber | broad=$broadSearch | " +
                "enabled=${tasks.map { it.first }} | contributions=${result.size} | " +''',
'''            "SMART DJ PROVIDER ROUND | seed=$seedNumber | " +
                "stage=${when {
                    artistFallbackOnly -> "artist-rescue"
                    broadSearch -> "broad"
                    else -> "direct"
                }} | " +
                "enabled=${tasks.map { it.first }} | contributions=${result.size} | " +''')

text = read(module)
broad_call = '''                providerPolicy = providerPolicy,
                broadSearch = true
            )'''
if text.count(broad_call) != 2:
    raise RuntimeError(f"expected 2 broad provider calls, found {text.count(broad_call)}")
text = text.replace(
    broad_call,
'''                providerPolicy = providerPolicy,
                broadSearch = true,
                allowArtistFallback = true
            )''')
write(module, text)

marker = '''    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>,'''
artist_helper = '''    private fun collectArtistRescueContributions(
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        providerPolicy: RecommendationProviderPolicy
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result =
            mutableListOf<RawRecommendationContribution>()

        seeds.forEachIndexed { seedIndex, seed ->
            val seedNumber = seedIndex + 1
            val resolvedInput = trackInputResolver.resolve(seed.track)
            val metadata = metadataNormalizer.normalize(resolvedInput.track)
            if (metadata.searchArtist.isBlank() || metadata.searchTitle.isBlank()) {
                return@forEachIndexed
            }

            var contributions =
                collectSeedContributions(
                    seedNumber = seedNumber,
                    seed = seed,
                    metadata = metadata,
                    library = library,
                    providerPolicy = providerPolicy,
                    allowArtistFallback = true,
                    artistFallbackOnly = true
                )

            if (
                contributions.isEmpty() &&
                resolvedInput.source != TrackInputSource.TAGS
            ) {
                val fallbackTrack =
                    buildProviderFallbackTrack(
                        resolvedInput.track
                    )
                if (fallbackTrack != null) {
                    val fallbackMetadata =
                        metadataNormalizer
                            .normalize(
                                fallbackTrack
                            )
                    if (
                        fallbackMetadata.searchArtist.isNotBlank() &&
                        fallbackMetadata.searchTitle.isNotBlank()
                    ) {
                        contributions =
                            collectSeedContributions(
                                seedNumber = seedNumber,
                                seed = seed,
                                metadata = fallbackMetadata,
                                library = library,
                                providerPolicy = providerPolicy,
                                allowArtistFallback = true,
                                artistFallbackOnly = true
                            )
                    }
                }
            }

            result +=
                contributions
        }

        return result
    }

    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>,'''
replace_once(module, marker, artist_helper)

replace_once(module,
'''        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {''',
'''        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        broadSearch: Boolean = false,
        allowArtistFallback: Boolean = false,
        artistFallbackOnly: Boolean = false
    ): List<RawRecommendationContribution> {''')

replace_once(module,
'''            for (evaluation in evaluations) {
                val similar = try {''',
'''            if (!artistFallbackOnly) {
                for (evaluation in evaluations) {
                    val similar = try {''')

replace_once(module,
'''                if (identitiesUsed >= BROAD_LISTENBRAINZ_IDENTITIES_TO_USE) break
            }

            if (broadContributions.isNotEmpty()) {
                return broadContributions.distinctBy {
                    Triple(it.artist, it.title, it.mbid)
                }
            }

            // Recording graph exhausted: use ListenBrainz's official''',
'''                    if (identitiesUsed >= BROAD_LISTENBRAINZ_IDENTITIES_TO_USE) break
                }

                if (broadContributions.isNotEmpty()) {
                    return broadContributions.distinctBy {
                        Triple(it.artist, it.title, it.mbid)
                    }
                }
            }

            if (!allowArtistFallback) {
                return emptyList()
            }

            // Recording graph exhausted: use ListenBrainz's official''')

replace_once(module,
'''        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {''',
'''        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        broadSearch: Boolean = false,
        allowArtistFallback: Boolean = false,
        artistFallbackOnly: Boolean = false
    ): List<RawRecommendationContribution> {''')

replace_once(module,
'''            if (identityConfidence > fallbackConfidence) {
                fallbackIdentity = identity
                fallbackConfidence = identityConfidence
            }

            val similar = try {''',
'''            if (identityConfidence > fallbackConfidence) {
                fallbackIdentity = identity
                fallbackConfidence = identityConfidence
            }

            if (artistFallbackOnly) {
                continue
            }

            val similar = try {''')

replace_once(module,
'''            return mapLastFmContributions(similar, seed, identityConfidence)
        }

        val identity = fallbackIdentity ?: return emptyList()''',
'''            return mapLastFmContributions(similar, seed, identityConfidence)
        }

        if (!allowArtistFallback) {
            return emptyList()
        }

        val identity = fallbackIdentity ?: return emptyList()''')

write("app/src/test/java/io/github/alagga/gonesmart/GmmpAutoDjSettingsReaderSizingTest.kt", '''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class GmmpAutoDjSettingsReaderSizingTest {
    private val reader = GmmpAutoDjSettingsReader()

    @Test
    fun currentFiveTrackInitialSizeUsesEightCandidateRescueGate() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 5,
                upcomingTrackCount = 1,
                source = "test"
            )
        )

        assertEquals(20, sizing.targetSize)
        assertEquals(8, sizing.rescueCandidateTarget)
    }

    @Test
    fun largerInitialQueueScalesRescueGate() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 10,
                upcomingTrackCount = 1,
                source = "test"
            )
        )

        assertEquals(30, sizing.targetSize)
        assertEquals(18, sizing.rescueCandidateTarget)
    }

    @Test
    fun rescueGateNeverExceedsInternalPoolTarget() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 5,
                upcomingTrackCount = 20,
                source = "test"
            )
        )

        assertEquals(30, sizing.targetSize)
        assertEquals(30, sizing.rescueCandidateTarget)
    }
}
''')

write("app/src/test/java/io/github/alagga/gonesmart/TrackFamilyKeyBuilderTest.kt", '''package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFamilyKeyBuilderTest {
    private val builder = TrackFamilyKeyBuilder(
        metadataNormalizer = TrackMetadataNormalizer(),
        trackInputResolver = TrackInputResolver()
    )

    @Test
    fun remixAndRadioEditShareDuplicateFamily() {
        val radio = track(
            id = 1L,
            artist = "Avicii",
            title = "Levels (Radio Edit)"
        )
        val remix = track(
            id = 2L,
            artist = "Avicii",
            title = "Levels (Skrillex Remix)"
        )

        assertTrue(
            builder.familyKeys(radio)
                .intersect(builder.familyKeys(remix))
                .isNotEmpty()
        )
    }

    @Test
    fun sameTitleByDifferentArtistsRemainsDistinct() {
        val avicii = track(1L, "Avicii", "Levels")
        val other = track(2L, "Other Artist", "Levels")

        assertFalse(
            builder.familyKeys(avicii)
                .intersect(builder.familyKeys(other))
                .isNotEmpty()
        )
    }

    private fun track(
        id: Long,
        artist: String,
        title: String
    ) = TrackInfo(
        id = id,
        title = title,
        artist = artist,
        albumArtist = null,
        path = "/music/$id.mp3"
    )
}
''')

agents = read("AGENTS.md")
section = '''
- Provider artist fallbacks are a rescue stage, not a default per-provider tax: first evaluate Playlist Affinity plus direct track/recording recommendations locally, then run artist-level provider expansion only when unique local coverage is below a target scaled from GMMP Initial Size / Upcoming Tracks.
- A partially populated GoneSmart pool is valid. If GMMP requests more tracks than GoneSmart currently has suitable candidates, return only the suitable GoneSmart rows and never pad the remainder with untouched/native random rows; GMMP can request again as the shorter queue approaches its end. Native fallback for recommendation scarcity is reserved for the zero-suitable-track case according to the fallback policy.
- `Prevent Version Duplicates` treats Original/Radio/Extended/Remix/Edit/Bootleg/Acoustic/Live variants of the same normalized artist + base title as one duplicate family. Version metadata still influences matching/ranking, but never creates a duplicate-prevention loophole.
'''
if "Provider artist fallbacks are a rescue stage" not in agents:
    agents = agents.rstrip() + "\n" + section
    write("AGENTS.md", agents)

providers_doc = "docs/AUTO_DJ_PROVIDERS.md"
doc = read(providers_doc)
doc_add = '''
## Staged artist rescue and partial fills

GoneSmart treats artist-level provider expansion as a rescue stage. Playlist Affinity and direct track/recording recommendations are collected and matched against the local GMMP library first. Artist fallback is requested only when the number of unique local direct candidates is below a threshold derived from GMMP's configured Auto-DJ demand: roughly two times the larger of `Initial Size - 1` and `Upcoming Tracks`, capped by the internal recommendation-pool target.

If fewer suitable GoneSmart tracks exist than GMMP requests in a refill, GoneSmart returns only those suitable tracks. It does not preserve untouched native/random rows to pad the request. GMMP can ask for another refill when the shorter queue approaches its end. The configured native fallback remains relevant when there are zero suitable GoneSmart tracks.

`Prevent Version Duplicates` groups versions by normalized artist plus base title, so Original, Radio, Extended, Remix, Edit, Bootleg, Acoustic and Live variants cannot bypass duplicate prevention merely because their version labels differ.
'''
if "## Staged artist rescue and partial fills" not in doc:
    write(providers_doc, doc.rstrip() + "\n\n" + doc_add.strip() + "\n")

print("Auto-DJ rescue patch applied.")
