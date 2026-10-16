from pathlib import Path

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected 1 occurrence, found {count}: {old[:100]!r}')
    write(path, text.replace(old, new, 1))


# Settings key.
replace_once(
    'app/src/main/java/io/github/alagga/gonesmart/GoneSmartSettingsKeys.kt',
    '    const val KEY_PREVENT_BASE_VERSION_DUPLICATES = "prevent_base_version_duplicates"\n',
    '    const val KEY_PREVENT_BASE_VERSION_DUPLICATES = "prevent_base_version_duplicates"\n'
    '    const val KEY_PREVENT_REMIX_DUPLICATES = "prevent_remix_duplicates"\n'
)

# Options: remixes remain independent by default.
replace_once(
    'app/src/main/java/io/github/alagga/gonesmart/GoneSmartOptions.kt',
    '    val preventBaseVersionDuplicates: Boolean = true,\n'
    '    val fallbackToNativeAutoDjWhenNoSuitableTracks: Boolean = true,',
    '    val preventBaseVersionDuplicates: Boolean = true,\n'
    '    val preventRemixDuplicates: Boolean = false,\n'
    '    val fallbackToNativeAutoDjWhenNoSuitableTracks: Boolean = true,'
)
replace_once(
    'app/src/main/java/io/github/alagga/gonesmart/GoneSmartOptions.kt',
    '                preventBaseVersionDuplicates = preferences.getBoolean(\n'
    '                    GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,\n'
    '                    true\n'
    '                ),\n'
    '                fallbackToNativeAutoDjWhenNoSuitableTracks = preferences.getBoolean(',
    '                preventBaseVersionDuplicates = preferences.getBoolean(\n'
    '                    GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES,\n'
    '                    true\n'
    '                ),\n'
    '                preventRemixDuplicates = preferences.getBoolean(\n'
    '                    GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES,\n'
    '                    false\n'
    '                ),\n'
    '                fallbackToNativeAutoDjWhenNoSuitableTracks = preferences.getBoolean('
)

# Settings synchronization both directions.
repo = 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartSettingsRepository.kt'
text = read(repo)
needle = '            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, local.preventBaseVersionDuplicates)\n'
if text.count(needle) != 1:
    raise RuntimeError('settings repository local->remote anchor mismatch')
text = text.replace(
    needle,
    needle + '            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES, local.preventRemixDuplicates)\n',
    1
)
needle2 = '            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, options.preventBaseVersionDuplicates)\n'
if text.count(needle2) != 1:
    raise RuntimeError('settings repository remote->local anchor mismatch')
text = text.replace(
    needle2,
    needle2 + '            .putBoolean(GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES, options.preventRemixDuplicates)\n',
    1
)
write(repo, text)

# Smart DJ UI and live refresh.
main = 'app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt'
replace_once(
    main,
    '            SettingSpec(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, "≠", "Prevent version duplicates", "Block Original, Radio, Extended and Club Mix variants of the same song family.", COLOR_ACCENT)\n',
    '            SettingSpec(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, "≠", "Prevent version duplicates", "Block Original, Radio, Extended and Club Mix variants of the same song family. Remixes stay independent.", COLOR_ACCENT),\n'
    '            SettingSpec(GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES, "R", "Prevent remix duplicates", "Also block a remix when another version of the same song family is already in the current/recent queue. Off by default.", COLOR_AMBER)\n'
)
replace_once(
    main,
    '        setSwitch(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, options.preventBaseVersionDuplicates)\n'
    '        setSwitch(GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES, options.fallbackToNativeAutoDjWhenNoSuitableTracks)\n',
    '        setSwitch(GoneSmartSettingsKeys.KEY_PREVENT_BASE_VERSION_DUPLICATES, options.preventBaseVersionDuplicates)\n'
    '        setSwitch(GoneSmartSettingsKeys.KEY_PREVENT_REMIX_DUPLICATES, options.preventRemixDuplicates)\n'
    '        setSwitch(GoneSmartSettingsKeys.KEY_FALLBACK_WHEN_NO_MATCHES, options.fallbackToNativeAutoDjWhenNoSuitableTracks)\n'
)

# Version-aware identity builder. Base versions share one family; remixes are
# separate unless the independent remix policy uses songIdentityKeys.
write('app/src/main/java/io/github/alagga/gonesmart/TrackFamilyKeyBuilder.kt', r'''package io.github.alagga.gonesmart

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
''')

# Independent duplicate policy makes remix filtering orthogonal to base-version filtering.
write('app/src/main/java/io/github/alagga/gonesmart/DuplicateFamilyPolicy.kt', r'''package io.github.alagga.gonesmart

enum class DuplicateConflictReason {
    VERSION,
    REMIX
}

data class DuplicateFamilyState(
    val versionFamilyKeys: MutableSet<String> = mutableSetOf(),
    val songIdentityKeys: MutableSet<String> = mutableSetOf(),
    val remixSongIdentityKeys: MutableSet<String> = mutableSetOf()
)

object DuplicateFamilyPolicy {

    fun conflictReason(
        candidate: TrackDuplicateIdentity,
        state: DuplicateFamilyState,
        preventBaseVersionDuplicates: Boolean,
        preventRemixDuplicates: Boolean
    ): DuplicateConflictReason? {

        if (
            preventBaseVersionDuplicates &&
            candidate.versionFamilyKeys.any {
                it in state.versionFamilyKeys
            }
        ) {

            return DuplicateConflictReason.VERSION
        }

        if (
            preventRemixDuplicates
        ) {

            val remixConflict =
                if (
                    candidate.isRemix
                ) {

                    candidate.songIdentityKeys.any {
                        it in state.songIdentityKeys
                    }

                } else {

                    candidate.songIdentityKeys.any {
                        it in state.remixSongIdentityKeys
                    }
                }

            if (
                remixConflict
            ) {

                return DuplicateConflictReason.REMIX
            }
        }

        return null
    }

    fun add(
        identity: TrackDuplicateIdentity,
        state: DuplicateFamilyState
    ) {

        state.versionFamilyKeys +=
            identity.versionFamilyKeys

        state.songIdentityKeys +=
            identity.songIdentityKeys

        if (
            identity.isRemix
        ) {

            state.remixSongIdentityKeys +=
                identity.songIdentityKeys
        }
    }
}
''')

# Ranker: build one duplicate state for recent/current queue and one for the new batch.
ranker = 'app/src/main/java/io/github/alagga/gonesmart/LocalPreferenceRanker.kt'
replace_once(
    ranker,
'''        val duplicateContextFamilyKeys =
            if (
                options.preventBaseVersionDuplicates
            ) {

                duplicateContextTracks
                    .flatMap {
                        trackFamilyKeyBuilder
                            .familyKeys(
                                it
                            )
                    }
                    .toSet()

            } else {

                emptySet()
            }
''',
'''        val duplicatePolicyEnabled =
            options.preventBaseVersionDuplicates ||
                options.preventRemixDuplicates

        val duplicateContextState =
            DuplicateFamilyState()

        if (
            duplicatePolicyEnabled
        ) {

            duplicateContextTracks
                .forEach { track ->
                    DuplicateFamilyPolicy.add(
                        identity =
                            trackFamilyKeyBuilder
                                .duplicateIdentity(
                                    track
                                ),
                        state = duplicateContextState
                    )
                }
        }
''')
replace_once(
    ranker,
'''                    if (
                        options.preventBaseVersionDuplicates
                    ) {

                        val candidateFamilyKeys =
                            trackFamilyKeyBuilder
                                .familyKeys(
                                    match.track
                                )

                        if (
                            candidateFamilyKeys.any {
                                it in duplicateContextFamilyKeys
                            }
                        ) {

                            queueDuplicatesRejected +=
                                1

                            Log.i(
                                TAG,
                                "DUPLICATE FAMILY REJECT | " +
                                    "trackId=${match.track.id} | " +
                                    "artist=${match.track.artist} | " +
                                    "title=${match.track.title}"
                            )

                            return@mapNotNull null
                        }
                    }
''',
'''                    if (
                        duplicatePolicyEnabled
                    ) {

                        val candidateIdentity =
                            trackFamilyKeyBuilder
                                .duplicateIdentity(
                                    match.track
                                )

                        val duplicateReason =
                            DuplicateFamilyPolicy.conflictReason(
                                candidate = candidateIdentity,
                                state = duplicateContextState,
                                preventBaseVersionDuplicates =
                                    options.preventBaseVersionDuplicates,
                                preventRemixDuplicates =
                                    options.preventRemixDuplicates
                            )

                        if (
                            duplicateReason != null
                        ) {

                            queueDuplicatesRejected +=
                                1

                            Log.i(
                                TAG,
                                "DUPLICATE FAMILY REJECT | " +
                                    "reason=$duplicateReason | " +
                                    "trackId=${match.track.id} | " +
                                    "artist=${match.track.artist} | " +
                                    "title=${match.track.title}"
                            )

                            return@mapNotNull null
                        }
                    }
''')
replace_once(
    ranker,
'''        val occupiedFamilyKeys =
            mutableSetOf<String>()
''',
'''        val batchDuplicateState =
            DuplicateFamilyState()
''')
replace_once(
    ranker,
'''            val familyKeys =
                if (
                    options.preventBaseVersionDuplicates
                ) {

                    trackFamilyKeyBuilder
                        .familyKeys(
                            match.track
                        )

                } else {

                    emptySet()
                }

            if (
                familyKeys.isNotEmpty() &&
                familyKeys.any {
                    it in occupiedFamilyKeys
                }
            ) {

                batchFamilyDuplicatesCollapsed +=
                    1

                continue
            }

            result +=
                match

            occupiedTrackIds +=
                match.track.id

            occupiedFamilyKeys +=
                familyKeys
''',
'''            val duplicateIdentity =
                if (
                    duplicatePolicyEnabled
                ) {

                    trackFamilyKeyBuilder
                        .duplicateIdentity(
                            match.track
                        )

                } else {

                    TrackDuplicateIdentity.EMPTY
                }

            val duplicateReason =
                if (
                    duplicatePolicyEnabled
                ) {

                    DuplicateFamilyPolicy.conflictReason(
                        candidate = duplicateIdentity,
                        state = batchDuplicateState,
                        preventBaseVersionDuplicates =
                            options.preventBaseVersionDuplicates,
                        preventRemixDuplicates =
                            options.preventRemixDuplicates
                    )

                } else {

                    null
                }

            if (
                duplicateReason != null
            ) {

                batchFamilyDuplicatesCollapsed +=
                    1

                continue
            }

            result +=
                match

            occupiedTrackIds +=
                match.track.id

            if (
                duplicatePolicyEnabled
            ) {

                DuplicateFamilyPolicy.add(
                    identity = duplicateIdentity,
                    state = batchDuplicateState
                )
            }
''')

# Tests: version option allows a remix, remix option can collapse the song identity.
write('app/src/test/java/io/github/alagga/gonesmart/TrackFamilyKeyBuilderTest.kt', r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFamilyKeyBuilderTest {
    private val builder = TrackFamilyKeyBuilder(
        metadataNormalizer = TrackMetadataNormalizer(),
        trackInputResolver = TrackInputResolver()
    )

    @Test
    fun remixAndRadioEditAreDifferentVersionFamiliesButSameSongIdentity() {
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

        val radioIdentity = builder.duplicateIdentity(radio)
        val remixIdentity = builder.duplicateIdentity(remix)

        assertFalse(
            radioIdentity.versionFamilyKeys
                .intersect(remixIdentity.versionFamilyKeys)
                .isNotEmpty()
        )
        assertTrue(
            radioIdentity.songIdentityKeys
                .intersect(remixIdentity.songIdentityKeys)
                .isNotEmpty()
        )
        assertFalse(radioIdentity.isRemix)
        assertTrue(remixIdentity.isRemix)
    }

    @Test
    fun radioAndExtendedShareBaseVersionFamily() {
        val radio = track(1L, "Avicii", "Levels (Radio Edit)")
        val extended = track(2L, "Avicii", "Levels (Extended Mix)")

        assertTrue(
            builder.familyKeys(radio)
                .intersect(builder.familyKeys(extended))
                .isNotEmpty()
        )
    }

    @Test
    fun sameTitleByDifferentArtistsRemainsDistinct() {
        val avicii = track(1L, "Avicii", "Levels")
        val other = track(2L, "Other Artist", "Levels")

        assertFalse(
            builder.duplicateIdentity(avicii).songIdentityKeys
                .intersect(builder.duplicateIdentity(other).songIdentityKeys)
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

write('app/src/test/java/io/github/alagga/gonesmart/DuplicateFamilyPolicyTest.kt', r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicateFamilyPolicyTest {

    private val base = TrackDuplicateIdentity(
        versionFamilyKeys = setOf("avicii|levels|BASE"),
        songIdentityKeys = setOf("avicii|levels"),
        isRemix = false
    )

    private val remix = TrackDuplicateIdentity(
        versionFamilyKeys = setOf("avicii|levels|REMIX:skrillex"),
        songIdentityKeys = setOf("avicii|levels"),
        isRemix = true
    )

    @Test
    fun versionDuplicatesDoNotBlockIndependentRemix() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(base, state)

        assertNull(
            DuplicateFamilyPolicy.conflictReason(
                candidate = remix,
                state = state,
                preventBaseVersionDuplicates = true,
                preventRemixDuplicates = false
            )
        )
    }

    @Test
    fun remixOptionBlocksRemixAgainstExistingBase() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(base, state)

        assertEquals(
            DuplicateConflictReason.REMIX,
            DuplicateFamilyPolicy.conflictReason(
                candidate = remix,
                state = state,
                preventBaseVersionDuplicates = false,
                preventRemixDuplicates = true
            )
        )
    }

    @Test
    fun remixOptionAlsoBlocksBaseAgainstExistingRemix() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(remix, state)

        assertEquals(
            DuplicateConflictReason.REMIX,
            DuplicateFamilyPolicy.conflictReason(
                candidate = base,
                state = state,
                preventBaseVersionDuplicates = false,
                preventRemixDuplicates = true
            )
        )
    }
}
''')

# Default contract test.
replace_once(
    'app/src/test/java/io/github/alagga/gonesmart/GoneSmartOptionsTest.kt',
    'import org.junit.Assert.assertTrue\n',
    'import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\n'
)
replace_once(
    'app/src/test/java/io/github/alagga/gonesmart/GoneSmartOptionsTest.kt',
'''    fun playlistBridgeDefaultsEnabledForUpgradeContinuity() {
        assertTrue(GoneSmartOptions().playlistBridgeEnabled)
    }
''',
'''    fun playlistBridgeDefaultsEnabledForUpgradeContinuity() {
        assertTrue(GoneSmartOptions().playlistBridgeEnabled)
    }

    @Test
    fun remixDuplicatePreventionDefaultsOff() {
        assertFalse(GoneSmartOptions().preventRemixDuplicates)
    }
''')

print('Remix duplicate option patch applied.')
