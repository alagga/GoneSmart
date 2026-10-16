from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# --- Settings model / policy -------------------------------------------------
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartSettingsKeys.kt",
    '    const val KEY_PLAYLIST_SIZE_WEIGHTING = "playlist_size_weighting"\n',
    '    const val KEY_PLAYLIST_SIZE_WEIGHTING = "playlist_size_weighting"\n'
    '    const val KEY_RECOMMENDATION_VARIETY = "recommendation_variety"\n'
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationProviderPolicy.kt",
    '''enum class PlaylistSizeWeighting(val preferenceValue: String) {
    OFF("off"),
    BALANCED("balanced"),
    STRONG("strong");

    companion object {
        fun fromPreference(value: String?): PlaylistSizeWeighting =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}
''',
    '''enum class PlaylistSizeWeighting(val preferenceValue: String) {
    OFF("off"),
    BALANCED("balanced"),
    STRONG("strong");

    companion object {
        fun fromPreference(value: String?): PlaylistSizeWeighting =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}

enum class RecommendationVariety(val preferenceValue: String) {
    OFF("off"),
    BALANCED("balanced"),
    HIGH("high");

    companion object {
        fun fromPreference(value: String?): RecommendationVariety =
            entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}
'''
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationProviderPolicy.kt",
    '''    val providerWeights: Map<RecommendationProvider, Double>,
    val playlistSizeWeighting: PlaylistSizeWeighting
) {''',
    '''    val providerWeights: Map<RecommendationProvider, Double>,
    val playlistSizeWeighting: PlaylistSizeWeighting,
    val variety: RecommendationVariety
) {'''
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationProviderPolicy.kt",
    '''                    ),
                    playlistSizeWeighting = PlaylistSizeWeighting.BALANCED
                )''',
    '''                    ),
                    playlistSizeWeighting = PlaylistSizeWeighting.BALANCED,
                    variety = RecommendationVariety.BALANCED
                )'''
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/RecommendationProviderPolicy.kt",
    '''                ),
                playlistSizeWeighting = options.playlistSizeWeighting
            )''',
    '''                ),
                playlistSizeWeighting = options.playlistSizeWeighting,
                variety = options.recommendationVariety
            )'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartOptions.kt",
    '''    val localPlaylistProviderWeight: Double = RecommendationProviderPolicy.SMART_LOCAL_PLAYLIST_WEIGHT,
    val playlistSizeWeighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED,
    val preferHigherRatedMatches: Boolean = true,''',
    '''    val localPlaylistProviderWeight: Double = RecommendationProviderPolicy.SMART_LOCAL_PLAYLIST_WEIGHT,
    val playlistSizeWeighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED,
    val recommendationVariety: RecommendationVariety = RecommendationVariety.BALANCED,
    val preferHigherRatedMatches: Boolean = true,'''
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartOptions.kt",
    '''                playlistSizeWeighting = PlaylistSizeWeighting.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING,
                        PlaylistSizeWeighting.BALANCED.preferenceValue
                    )
                ),
                preferHigherRatedMatches = preferences.getBoolean(''',
    '''                playlistSizeWeighting = PlaylistSizeWeighting.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING,
                        PlaylistSizeWeighting.BALANCED.preferenceValue
                    )
                ),
                recommendationVariety = RecommendationVariety.fromPreference(
                    preferences.getString(
                        GoneSmartSettingsKeys.KEY_RECOMMENDATION_VARIETY,
                        RecommendationVariety.BALANCED.preferenceValue
                    )
                ),
                preferHigherRatedMatches = preferences.getBoolean('''
)

settings_repo = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartSettingsRepository.kt"
replace_once(
    settings_repo,
    '''            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, local.playlistSizeWeighting.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, local.preferHigherRatedMatches)''',
    '''            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, local.playlistSizeWeighting.preferenceValue)
            .putString(GoneSmartSettingsKeys.KEY_RECOMMENDATION_VARIETY, local.recommendationVariety.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, local.preferHigherRatedMatches)'''
)
replace_once(
    settings_repo,
    '''            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, options.playlistSizeWeighting.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)''',
    '''            .putString(GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING, options.playlistSizeWeighting.preferenceValue)
            .putString(GoneSmartSettingsKeys.KEY_RECOMMENDATION_VARIETY, options.recommendationVariety.preferenceValue)
            .putBoolean(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)'''
)

# --- Controlled variety ------------------------------------------------------
controlled_variety = '''package io.github.alagga.gonesmart

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

internal data class ControlledVarietyResult<T>(
    val items: List<T>,
    val bucketCount: Int,
    val shuffledBuckets: Int,
    val largestBucket: Int
)

/**
 * Adds bounded variety only among candidates whose final ranking scores are
 * already close. This never adds random noise to the score itself, so a clear
 * quality gap remains a hard ordering boundary.
 */
internal object ControlledVarietyPolicy {
    fun <T> reorder(
        items: List<T>,
        mode: RecommendationVariety,
        scoreOf: (T) -> Double,
        random: Random = Random.Default
    ): ControlledVarietyResult<T> {
        if (items.size < 2 || mode == RecommendationVariety.OFF) {
            return ControlledVarietyResult(
                items = items,
                bucketCount = if (items.isEmpty()) 0 else items.size,
                shuffledBuckets = 0,
                largestBucket = if (items.isEmpty()) 0 else 1
            )
        }

        val (absoluteWindow, relativeWindow) = when (mode) {
            RecommendationVariety.OFF -> 0.0 to 0.0
            RecommendationVariety.BALANCED -> 0.020 to 0.030
            RecommendationVariety.HIGH -> 0.040 to 0.060
        }

        val output = ArrayList<T>(items.size)
        var index = 0
        var bucketCount = 0
        var shuffledBuckets = 0
        var largestBucket = 1

        while (index < items.size) {
            val leaderScore = scoreOf(items[index])
            val tolerance = max(absoluteWindow, abs(leaderScore) * relativeWindow)
            var endExclusive = index + 1
            while (endExclusive < items.size) {
                val candidateScore = scoreOf(items[endExclusive])
                if (leaderScore - candidateScore > tolerance + 0.0000001) break
                endExclusive += 1
            }

            val bucket = items.subList(index, endExclusive)
            bucketCount += 1
            largestBucket = max(largestBucket, bucket.size)
            if (bucket.size > 1) {
                output += bucket.shuffled(random)
                shuffledBuckets += 1
            } else {
                output += bucket
            }
            index = endExclusive
        }

        return ControlledVarietyResult(
            items = output,
            bucketCount = bucketCount,
            shuffledBuckets = shuffledBuckets,
            largestBucket = largestBucket
        )
    }
}
'''
write("app/src/main/java/io/github/alagga/gonesmart/ControlledVarietyPolicy.kt", controlled_variety)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/LocalPreferenceRanker.kt",
    '''        return result
            .take(
                MAX_RESULTS
            )
    }
''',
    '''        val limited =
            result
                .take(
                    MAX_RESULTS
                )

        val variety =
            if (options.recommendationMode == RecommendationMode.SMART) {
                RecommendationVariety.BALANCED
            } else {
                options.recommendationVariety
            }

        val varietyResult =
            ControlledVarietyPolicy.reorder(
                items = limited,
                mode = variety,
                scoreOf = { it.rankingScore }
            )

        Log.i(
            TAG,
            "CONTROLLED VARIETY | " +
                "mode=$variety | " +
                "candidates=${limited.size} | " +
                "buckets=${varietyResult.bucketCount} | " +
                "shuffledBuckets=${varietyResult.shuffledBuckets} | " +
                "largestBucket=${varietyResult.largestBucket}"
        )

        return varietyResult.items
    }
'''
)

variety_test = '''package io.github.alagga.gonesmart

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledVarietyPolicyTest {
    private data class Candidate(val id: Int, val score: Double)

    @Test
    fun offPreservesDeterministicOrder() {
        val input = listOf(Candidate(1, 0.8), Candidate(2, 0.8), Candidate(3, 0.7))
        val result = ControlledVarietyPolicy.reorder(
            items = input,
            mode = RecommendationVariety.OFF,
            scoreOf = Candidate::score,
            random = Random(1)
        )
        assertEquals(input, result.items)
        assertEquals(0, result.shuffledBuckets)
    }

    @Test
    fun balancedNeverCrossesClearScoreCliff() {
        val input = listOf(
            Candidate(1, 0.80),
            Candidate(2, 0.79),
            Candidate(3, 0.78),
            Candidate(4, 0.60),
            Candidate(5, 0.59)
        )
        val result = ControlledVarietyPolicy.reorder(
            items = input,
            mode = RecommendationVariety.BALANCED,
            scoreOf = Candidate::score,
            random = Random(3)
        )
        assertEquals(setOf(1, 2, 3), result.items.take(3).map { it.id }.toSet())
        assertEquals(setOf(4, 5), result.items.drop(3).map { it.id }.toSet())
        assertTrue(result.shuffledBuckets >= 1)
    }

    @Test
    fun highUsesWiderNearTieBucketsThanBalanced() {
        val input = listOf(
            Candidate(1, 0.68),
            Candidate(2, 0.66),
            Candidate(3, 0.64),
            Candidate(4, 0.50)
        )
        val balanced = ControlledVarietyPolicy.reorder(
            input,
            RecommendationVariety.BALANCED,
            Candidate::score,
            Random(7)
        )
        val high = ControlledVarietyPolicy.reorder(
            input,
            RecommendationVariety.HIGH,
            Candidate::score,
            Random(7)
        )
        assertTrue(high.largestBucket > balanced.largestBucket)
        assertEquals(input.map { it.id }.toSet(), high.items.map { it.id }.toSet())
    }
}
'''
write("app/src/test/java/io/github/alagga/gonesmart/ControlledVarietyPolicyTest.kt", variety_test)

# --- Custom-mode UI: disable irrelevant weights + variety selector -----------
main = "app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt"
replace_once(
    main,
    '''    private val switches = linkedMapOf<String, SwitchMaterial>()
    private val settingRows = linkedMapOf<String, View>()
    private val tabButtons = linkedMapOf<Tab, LinearLayout>()''',
    '''    private val switches = linkedMapOf<String, SwitchMaterial>()
    private val settingRows = linkedMapOf<String, View>()
    private val providerWeightCards = linkedMapOf<String, View>()
    private val providerWeightSliders = linkedMapOf<String, Slider>()
    private val tabButtons = linkedMapOf<Tab, LinearLayout>()'''
)
replace_once(
    main,
    '''        switches.clear()
        settingRows.clear()
        minimumRatingSlider = null''',
    '''        switches.clear()
        settingRows.clear()
        providerWeightCards.clear()
        providerWeightSliders.clear()
        minimumRatingSlider = null'''
)
replace_once(
    main,
    '''                body = "GoneSmart uses all stable sources automatically: Local Playlists ×1.00, ListenBrainz ×1.00 and Last.fm ×0.95, with balanced down-weighting for very large playlists. Network sources run in parallel; GoneSmart can refine these defaults in future updates."''',
    '''                body = "GoneSmart uses all stable sources automatically: Local Playlists ×1.00, ListenBrainz ×1.00 and Last.fm ×0.95, with balanced down-weighting for very large playlists and balanced controlled variety among near-equal final matches. Network sources run in parallel; GoneSmart can refine these defaults in future updates."'''
)
replace_once(
    main,
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT,
                value = recommendationOptions.localPlaylistProviderWeight
            ))''',
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT,
                value = recommendationOptions.localPlaylistProviderWeight,
                enabled = recommendationOptions.localPlaylistProviderEnabled
            ))'''
)
replace_once(
    main,
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT,
                value = recommendationOptions.listenBrainzProviderWeight
            ))''',
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT,
                value = recommendationOptions.listenBrainzProviderWeight,
                enabled = recommendationOptions.listenBrainzProviderEnabled
            ))'''
)
replace_once(
    main,
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT,
                value = recommendationOptions.lastFmProviderWeight
            ))''',
    '''                key = GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT,
                value = recommendationOptions.lastFmProviderWeight,
                enabled = recommendationOptions.lastFmProviderEnabled
            ))'''
)
replace_once(
    main,
    '''            container.addView(playlistSizeWeightingCard(recommendationOptions))
            container.addView(verticalGap(10))
            container.addView(infoCard(''',
    '''            container.addView(playlistSizeWeightingCard(recommendationOptions))
            container.addView(verticalGap(10))
            container.addView(recommendationVarietyCard(recommendationOptions))
            container.addView(verticalGap(10))
            container.addView(infoCard('''
)
replace_once(
    main,
    '''        key: String,
        value: Double
    ): View {
        val card = card().apply { setContentPadding(dp(20), dp(18), dp(20), dp(14)) }''',
    '''        key: String,
        value: Double,
        enabled: Boolean
    ): View {
        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(14))
            alpha = if (enabled) 1f else 0.45f
        }'''
)
replace_once(
    main,
    '''            setLabelFormatter { providerWeightLabel(it.toDouble()) }
            addOnChangeListener { slider, sliderValue, fromUser ->''',
    '''            setLabelFormatter { providerWeightLabel(it.toDouble()) }
            isEnabled = enabled
            addOnChangeListener { slider, sliderValue, fromUser ->'''
)
replace_once(
    main,
    '''        content.addView(slider)
        card.addView(content)
        return card
    }

    private fun providerWeightLabel''',
    '''        providerWeightCards[key] = card
        providerWeightSliders[key] = slider
        content.addView(slider)
        card.addView(content)
        return card
    }

    private fun providerWeightLabel'''
)

variety_ui = '''
    private fun recommendationVarietyCard(options: GoneSmartOptions): View {
        val label = when (options.recommendationVariety) {
            RecommendationVariety.OFF -> "Off"
            RecommendationVariety.BALANCED -> "Balanced"
            RecommendationVariety.HIGH -> "High"
        }
        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { showRecommendationVarietyDialog(options.recommendationVariety) }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            textView("Recommendation variety", 16f, COLOR_TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(textView(label, 15f, COLOR_ACCENT, bold = true))
        content.addView(row)
        content.addView(textView(
            "Rotates only among near-equal final matches. Clear score gaps remain deterministic; High uses a wider near-tie window.",
            13f,
            COLOR_TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        card.addView(content)
        return card
    }

    private fun showRecommendationVarietyDialog(current: RecommendationVariety) {
        val values = RecommendationVariety.entries
        val labels = arrayOf("Off", "Balanced (recommended)", "High")
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Recommendation variety")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = values[which]
                settingsRepository.setString(
                    GoneSmartSettingsKeys.KEY_RECOMMENDATION_VARIETY,
                    selected.preferenceValue
                )
                dialog.dismiss()
                showTab(Tab.SMART)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

'''
replace_once(
    main,
    '''    private fun playlistSizeWeightingCard(options: GoneSmartOptions): View {''',
    variety_ui + '''    private fun playlistSizeWeightingCard(options: GoneSmartOptions): View {'''
)
replace_once(
    main,
    '''        setSwitch(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, options.localPlaylistProviderEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)''',
    '''        setSwitch(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, options.localPlaylistProviderEnabled)
        refreshProviderWeightAvailability(options)
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)'''
)
provider_availability = '''
    private fun refreshProviderWeightAvailability(options: GoneSmartOptions = settingsRepository.read()) {
        val availability = mapOf(
            GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT to options.localPlaylistProviderEnabled,
            GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT to options.listenBrainzProviderEnabled,
            GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT to options.lastFmProviderEnabled
        )
        availability.forEach { (key, enabled) ->
            providerWeightSliders[key]?.isEnabled = enabled
            providerWeightCards[key]?.alpha = if (enabled) 1f else 0.45f
        }
    }

'''
replace_once(
    main,
    '''    private fun refreshPlaylistFolderAvailability(options: GoneSmartOptions = settingsRepository.read()) {''',
    provider_availability + '''    private fun refreshPlaylistFolderAvailability(options: GoneSmartOptions = settingsRepository.read()) {'''
)
replace_once(
    main,
    '''        if (key == GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS) refreshPlaylistFolderAvailability()
        if (key == GoneSmartSettingsKeys.KEY_SMART_PLAYLIST_FOLDERS) refreshSmartPlaylistFolderAvailability()
        if (key == GoneSmartSettingsKeys.KEY_SMART_RATING) refreshRatingFallbackAvailability()''',
    '''        if (key == GoneSmartSettingsKeys.KEY_PLAYLIST_FOLDERS) refreshPlaylistFolderAvailability()
        if (key == GoneSmartSettingsKeys.KEY_SMART_PLAYLIST_FOLDERS) refreshSmartPlaylistFolderAvailability()
        if (
            key == GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED ||
            key == GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED ||
            key == GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED
        ) refreshProviderWeightAvailability()
        if (key == GoneSmartSettingsKeys.KEY_SMART_RATING) refreshRatingFallbackAvailability()'''
)

# --- ListenBrainz fallback repair --------------------------------------------
lb_client = "app/src/main/java/io/github/alagga/gonesmart/ListenBrainzClient.kt"
replace_once(
    lb_client,
    '''import org.json.JSONArray
import org.json.JSONObject''',
    '''import android.util.Log
import org.json.JSONArray
import org.json.JSONObject'''
)
replace_once(
    lb_client,
    '''    companion object {
        private const val RECORDING_SEARCH_URL =
            "https://labs.api.listenbrainz.org/recording-search/json"
        private const val SIMILAR_RECORDINGS_URL =''',
    '''    companion object {
        private const val TAG = "GoneSmart"
        private const val RECORDING_SEARCH_URL =
            "https://labs.api.listenbrainz.org/recording-search/json"
        private const val RECORDING_LOOKUP_URL =
            "https://labs.api.listenbrainz.org/recording-mbid-lookup/json"
        private const val SIMILAR_RECORDINGS_URL ='''
)
replace_once(
    lb_client,
    '''        val response = postJson(RECORDING_SEARCH_URL, request)
        requireSuccess("Recording search", response)
        return parseRecordingSearch(response.body)
    }

    fun getSimilarRecordings''',
    '''        val response = postJson(RECORDING_SEARCH_URL, request)
        requireSuccess("Recording search", response)
        return parseRecordingSearch(response.body).also { matches ->
            if (matches.isEmpty()) Log.i(TAG, "LB EMPTY | stage=recording-search")
        }
    }

    fun getArtistMbidsForRecording(recordingMbid: String): List<String> {
        if (recordingMbid.isBlank()) return emptyList()
        val request = JSONArray().put(
            JSONObject().put("recording_mbid", recordingMbid)
        )
        val response = postJson(RECORDING_LOOKUP_URL, request)
        if (response.statusCode == 404) return emptyList()
        requireSuccess("Recording MBID lookup", response)
        val json = JSONArray(response.body)
        val mbids = buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                addAll(parseStringList(item.opt("artist_credit_mbids")))
            }
        }.distinct()
        if (mbids.isEmpty()) Log.i(TAG, "LB EMPTY | stage=recording-artist-lookup")
        return mbids
    }

    fun getSimilarRecordings'''
)
replace_once(
    lb_client,
    '''        val response = postJson(SIMILAR_RECORDINGS_URL, request)
        requireSuccess("Similar-recordings request", response)
        return parseSimilarRecordings(response.body, recordingMbid)
    }''',
    '''        val response = postJson(SIMILAR_RECORDINGS_URL, request)
        requireSuccess("Similar-recordings request", response)
        return parseSimilarRecordings(response.body, recordingMbid).also { tracks ->
            if (tracks.isEmpty()) Log.i(TAG, "LB EMPTY | stage=similar-recordings")
        }
    }'''
)
replace_once(
    lb_client,
    '''        return parsed.map { it.copy(score = (it.score / highest).coerceIn(0.0, 1.0)) }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
    }''',
    '''        return parsed.map { it.copy(score = (it.score / highest).coerceIn(0.0, 1.0)) }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
            .also { artists ->
                if (artists.isEmpty()) Log.i(TAG, "LB EMPTY | stage=similar-artists")
            }
    }'''
)
replace_once(
    lb_client,
    '''                        artistMbids = parseStringList(item.opt("artist_credit_mbids"))''',
    '''                        // recording-search currently does not expose artist MBIDs.
                        // Resolve them lazily through recording-mbid-lookup only if
                        // the recording-similarity path cannot produce results.
                        artistMbids = emptyList()'''
)
replace_once(
    lb_client,
    '''    private fun postJson(url: String, body: JSONArray): ListenBrainzHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {''',
    '''    private fun postJson(url: String, body: JSONArray): ListenBrainzHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {'''
)
# Wrap transport failures without changing the success path.
replace_once(
    lb_client,
    '''            return ListenBrainzHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}''',
    '''            return ListenBrainzHttpResponse(status, responseBody)
        } catch (t: Throwable) {
            val endpoint = url.substringAfter("listenbrainz.org/").substringBefore('/').ifBlank { "labs" }
            Log.w(TAG, "LB HTTP FAILURE | endpoint=$endpoint | ${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            connection.disconnect()
        }
    }
}'''
)

lb_policy = "app/src/main/java/io/github/alagga/gonesmart/ListenBrainzArtistIdentityPolicy.kt"
replace_once(
    lb_policy,
    '''     * accepted. The first artist-credit MBID is used because ListenBrainz returns
 * artist credit MBIDs in credit order and GoneSmart's provider fallback is
 * seeded from the primary artist.
 */''',
    '''     * accepted. recording-search does not currently expose artist MBIDs, so the
 * caller may lazily resolve them from the selected recording MBID. The first
 * artist-credit MBID is used because ListenBrainz returns artist credits in
 * credit order and GoneSmart's fallback is seeded from the primary artist.
 */'''
)
replace_once(
    lb_policy,
    '''        metadata: NormalizedTrackMetadata,
        matches: List<ListenBrainzRecordingMatch>,
        comparisonKey: (String) -> String
    ): ListenBrainzArtistIdentity? {''',
    '''        metadata: NormalizedTrackMetadata,
        matches: List<ListenBrainzRecordingMatch>,
        comparisonKey: (String) -> String,
        artistMbidsForRecording: (ListenBrainzRecordingMatch) -> List<String> = { it.artistMbids }
    ): ListenBrainzArtistIdentity? {'''
)
replace_once(
    lb_policy,
    '''        return matches
            .mapNotNull { match ->
                val mbid = match.artistMbids.firstOrNull()?.trim().orEmpty()
                if (mbid.isBlank()) return@mapNotNull null

                val matchArtistKey = comparisonKey(match.artistName)
                val confidence = when {
                    fullArtistKey.isNotBlank() && matchArtistKey == fullArtistKey -> 0.90
                    primaryArtistKey.isNotBlank() &&
                        (matchArtistKey == primaryArtistKey ||
                            matchArtistKey.contains(primaryArtistKey) ||
                            primaryArtistKey.contains(matchArtistKey)) -> 0.82
                    else -> 0.0
                }
                if (confidence <= 0.0) return@mapNotNull null

                ListenBrainzArtistIdentity(
                    artistMbid = mbid,
                    artistName = match.artistName,
                    confidence = confidence
                )
            }
            .maxByOrNull { it.confidence }''',
    '''        val candidates = matches
            .mapNotNull { match ->
                val matchArtistKey = comparisonKey(match.artistName)
                val confidence = when {
                    fullArtistKey.isNotBlank() && matchArtistKey == fullArtistKey -> 0.90
                    primaryArtistKey.isNotBlank() &&
                        (matchArtistKey == primaryArtistKey ||
                            matchArtistKey.contains(primaryArtistKey) ||
                            primaryArtistKey.contains(matchArtistKey)) -> 0.82
                    else -> 0.0
                }
                if (confidence <= 0.0) null else match to confidence
            }
            .sortedByDescending { it.second }
            .take(3)

        for ((match, confidence) in candidates) {
            val mbid = artistMbidsForRecording(match)
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (mbid.isBlank()) continue
            return ListenBrainzArtistIdentity(
                artistMbid = mbid,
                artistName = match.artistName,
                confidence = confidence
            )
        }
        return null'''
)

# Lazily enrich only the fallback artist identity. Direct recording similarity
# therefore keeps the exact same request count and latency as before.
module = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
replace_once(
    module,
    '''                    ListenBrainzArtistIdentityPolicy.select(
                        metadata = metadata,
                        matches = matches,
                        comparisonKey = metadataNormalizer::comparisonKey
                    )''',
    '''                    ListenBrainzArtistIdentityPolicy.select(
                        metadata = metadata,
                        matches = matches,
                        comparisonKey = metadataNormalizer::comparisonKey,
                        artistMbidsForRecording = { match ->
                            if (match.artistMbids.isNotEmpty()) {
                                match.artistMbids
                            } else {
                                try {
                                    listenBrainzClient.getArtistMbidsForRecording(
                                        match.recordingMbid
                                    )
                                } catch (t: Throwable) {
                                    Log.w(
                                        TAG,
                                        "LB ARTIST ID LOOKUP FAILED seed=$seedNumber | " +
                                            "recording=${match.recordingMbid} | " +
                                            "${t.javaClass.simpleName}: ${t.message}"
                                    )
                                    emptyList()
                                }
                            }
                        }
                    )'''
)
replace_once(
    module,
    '''            if (recoveredArtistIdentity != null) {
                Log.i(
                    TAG,
                    "LB ARTIST IDENTITY FALLBACK seed=$seedNumber | " +
                        "artist=${recoveredArtistIdentity.artistName} | " +
                        "confidence=${formatScore(recoveredArtistIdentity.confidence)}"
                )
            }
''',
    '''            if (recoveredArtistIdentity != null) {
                Log.i(
                    TAG,
                    "LB ARTIST IDENTITY FALLBACK seed=$seedNumber | " +
                        "artist=${recoveredArtistIdentity.artistName} | " +
                        "confidence=${formatScore(recoveredArtistIdentity.confidence)}"
                )
            } else if (acceptedArtistMbid.isNullOrBlank()) {
                Log.i(
                    TAG,
                    "LB ARTIST FALLBACK UNAVAILABLE seed=$seedNumber | " +
                        "recordingMatches=${matches.size}"
                )
            }
'''
)

# Extend the existing identity-policy tests with the actual Labs contract:
# recording-search rows can lack artist MBIDs, and only the selected compatible
# row should trigger the lazy lookup.
lb_test = "app/src/test/java/io/github/alagga/gonesmart/ListenBrainzArtistIdentityPolicyTest.kt"
replace_once(
    lb_test,
    '''    @Test
    fun unrelatedArtistIsRejected() {''',
    '''    @Test
    fun missingSearchArtistMbidCanBeResolvedLazilyFromRecording() {
        val requested = mutableListOf<String>()
        val identity = ListenBrainzArtistIdentityPolicy.select(
            metadata = metadata(searchArtist = "BUNT. & Røam", artists = listOf("BUNT.", "Røam")),
            matches = listOf(
                ListenBrainzRecordingMatch(
                    recordingMbid = "good-recording",
                    recordingName = "Different title",
                    artistName = "BUNT. & Røam",
                    releaseName = null,
                    artistMbids = emptyList()
                ),
                ListenBrainzRecordingMatch(
                    recordingMbid = "wrong-recording",
                    recordingName = "Different title",
                    artistName = "Other Artist",
                    releaseName = null,
                    artistMbids = emptyList()
                )
            ),
            comparisonKey = key,
            artistMbidsForRecording = { match ->
                requested += match.recordingMbid
                if (match.recordingMbid == "good-recording") listOf("resolved-bunt-mbid") else emptyList()
            }
        )

        assertEquals("resolved-bunt-mbid", identity?.artistMbid)
        assertEquals(listOf("good-recording"), requested)
    }

    @Test
    fun unrelatedArtistIsRejected() {'''
)

# --- Library cache: cheap revision revalidation instead of full reload/2 min -
reader = "app/src/main/java/io/github/alagga/gonesmart/GmmpLibraryReader.kt"
replace_once(
    reader,
    '''        /*
         * The in-memory library cache is refreshed
         * periodically so newly scanned, deleted or
         * retagged files become visible without a GMMP
         * restart.
         *
         * LocalLibraryMatcher uses a content fingerprint
         * afterwards and only rebuilds its index if the
         * relevant library data actually changed.
         */
        private const val CACHE_MAX_AGE_MS =
            2L * 60L * 1000L

        private const val LIBRARY_QUERY =''',
    '''        /*
         * Full-library reads are expensive on large libraries. Revalidate the
         * cache cheaply every two minutes from aggregate track-table revision
         * signals and rebuild only when those signals changed. A hard refresh
         * remains as a bounded safety net for metadata changes not reflected in
         * the revision columns.
         */
        private const val CACHE_REVALIDATE_AGE_MS =
            2L * 60L * 1000L

        private const val CACHE_HARD_MAX_AGE_MS =
            30L * 60L * 1000L

        private const val LIBRARY_REVISION_QUERY =
            """
            SELECT
                COUNT(*) AS track_count,
                COALESCE(MAX(song_id), 0) AS max_song_id,
                COALESCE(MAX(track_date_added), 0) AS max_date_added,
                COALESCE(MAX(track_date_updated), 0) AS max_date_updated,
                COALESCE(SUM(song_rating), 0) AS rating_sum
            FROM tracks
            WHERE track_placeholder = 0
            """

        private const val LIBRARY_QUERY ='''
)
replace_once(
    reader,
    '''    @Volatile
    private var cacheCreatedAtElapsedMs:
            Long =
        0L

    @Volatile
    private var deterministicMappingFailure =''',
    '''    @Volatile
    private var cacheCreatedAtElapsedMs:
            Long =
        0L

    @Volatile
    private var cacheValidatedAtElapsedMs:
            Long =
        0L

    @Volatile
    private var cachedRevision:
            LibraryRevision? =
        null

    @Volatile
    private var deterministicMappingFailure ='''
)
replace_once(
    reader,
    '''            now -
            cacheCreatedAtElapsedMs <
            CACHE_MAX_AGE_MS
        ) {''',
    '''            now -
            cacheCreatedAtElapsedMs <
            CACHE_HARD_MAX_AGE_MS &&
            now -
            cacheValidatedAtElapsedMs <
            CACHE_REVALIDATE_AGE_MS
        ) {'''
)
replace_once(
    reader,
    '''                synchronizedNow -
                cacheCreatedAtElapsedMs <
                CACHE_MAX_AGE_MS
            ) {

                return synchronizedCache
            }

            val loaded =''',
    '''                synchronizedNow -
                cacheCreatedAtElapsedMs <
                CACHE_HARD_MAX_AGE_MS &&
                synchronizedNow -
                cacheValidatedAtElapsedMs <
                CACHE_REVALIDATE_AGE_MS
            ) {

                return synchronizedCache
            }

            if (
                !forceRefresh &&
                synchronizedCache != null &&
                synchronizedNow - cacheCreatedAtElapsedMs < CACHE_HARD_MAX_AGE_MS
            ) {
                val revision = readLibraryRevision(autoDjInstance)
                if (revision != null && revision == cachedRevision) {
                    cacheValidatedAtElapsedMs = synchronizedNow
                    Log.i(
                        TAG,
                        "GMMP library cache revalidated | tracks=${synchronizedCache.size}"
                    )
                    return synchronizedCache
                }
            }

            val loaded ='''
)
replace_once(
    reader,
    '''                cacheCreatedAtElapsedMs =
                    SystemClock.elapsedRealtime()
            }

            return loaded''',
    '''                val cachedAt = SystemClock.elapsedRealtime()
                cacheCreatedAtElapsedMs = cachedAt
                cacheValidatedAtElapsedMs = cachedAt
                cachedRevision =
                    readLibraryRevision(autoDjInstance)
                        ?: revisionFromTracks(loaded)
            }

            return loaded'''
)
replace_once(
    reader,
    '''        return SystemClock.elapsedRealtime() -
                cacheCreatedAtElapsedMs <
                CACHE_MAX_AGE_MS
    }''',
    '''        val now = SystemClock.elapsedRealtime()
        return now - cacheCreatedAtElapsedMs < CACHE_HARD_MAX_AGE_MS &&
            now - cacheValidatedAtElapsedMs < CACHE_REVALIDATE_AGE_MS
    }'''
)
replace_once(
    reader,
    '''            cacheCreatedAtElapsedMs =
                0L
        }
    }

    private fun loadLibrary(''',
    '''            cacheCreatedAtElapsedMs =
                0L

            cacheValidatedAtElapsedMs =
                0L

            cachedRevision =
                null
        }
    }

    private fun readLibraryRevision(autoDjInstance: Any): LibraryRevision? {
        return runCatching {
            GmmpReadOnlySql.query(
                autoDjInstance = autoDjInstance,
                sql = LIBRARY_REVISION_QUERY.trimIndent()
            ) { cursor ->
                if (!cursor.moveToFirst()) return@query null
                LibraryRevision(
                    trackCount = cursor.getLong(cursor.getColumnIndexOrThrow("track_count")),
                    maxSongId = cursor.getLong(cursor.getColumnIndexOrThrow("max_song_id")),
                    maxDateAdded = cursor.getLong(cursor.getColumnIndexOrThrow("max_date_added")),
                    maxDateUpdated = cursor.getLong(cursor.getColumnIndexOrThrow("max_date_updated")),
                    ratingSum = cursor.getLong(cursor.getColumnIndexOrThrow("rating_sum"))
                )
            }
        }.getOrNull()
    }

    private fun revisionFromTracks(tracks: List<GmmpLibraryTrack>): LibraryRevision =
        LibraryRevision(
            trackCount = tracks.size.toLong(),
            maxSongId = tracks.maxOfOrNull { it.track.id } ?: 0L,
            maxDateAdded = tracks.maxOfOrNull { it.dateAddedEpochMs ?: 0L } ?: 0L,
            maxDateUpdated = tracks.maxOfOrNull { it.dateUpdatedEpochMs ?: 0L } ?: 0L,
            ratingSum = tracks.sumOf { it.ratingRaw.toLong() }
        )

    private fun loadLibrary('''
)
replace_once(
    reader,
    '''data class GmmpLibraryTrack(
''',
    '''private data class LibraryRevision(
    val trackCount: Long,
    val maxSongId: Long,
    val maxDateAdded: Long,
    val maxDateUpdated: Long,
    val ratingSum: Long
)

data class GmmpLibraryTrack(
'''
)

# --- Persistent architecture note -------------------------------------------
replace_once(
    "AGENTS.md",
    '''- Provider corroboration is evidence: after provider/seed deduplication, independent providers confirming the same logical track may receive a bounded bonus. A local playlist signal must never bypass the normal local-library quality/rating/duplicate gates.
- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up, including local-only prewarm when the network is unavailable.''',
    '''- Provider corroboration is evidence: after provider/seed deduplication, independent providers confirming the same logical track may receive a bounded bonus. A local playlist signal must never bypass the normal local-library quality/rating/duplicate gates.
- Final selection may add controlled variety only inside bounded near-equal score buckets. Do not add global random score noise that can promote a clearly weaker candidate over a stronger one; Smart mode uses the balanced policy and Custom may expose Off/Balanced/High.
- Revalidate large-library caches with cheap read-only revision signals before repeating an expensive full-library scan; retain a bounded hard refresh for metadata changes the revision signal may miss.
- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up, including local-only prewarm when the network is unavailable.'''
)

providers_doc = "docs/AUTO_DJ_PROVIDERS.md"
text = read(providers_doc)
append = '''\n\n### Controlled variety and cache/runtime follow-up\n\n- Final local matches keep their real ranking scores. GoneSmart only randomizes order inside bounded near-equal score buckets, so obvious score gaps stay deterministic. Smart uses Balanced; Custom exposes Off, Balanced and High.\n- In Custom mode a disabled provider also disables and visually mutes its ranking-weight slider because that weight is inactive.\n- ListenBrainz recording search currently does not return artist-credit MBIDs. The artist fallback therefore resolves the MBID lazily from the selected recording through the official recording-MBID lookup endpoint; direct recording similarity does not pay this extra request.\n- The GMMP library cache is cheaply revalidated before a repeated full-library read, with a bounded hard refresh retained as a safety net.\n'''
if "### Controlled variety and cache/runtime follow-up" not in text:
    write(providers_doc, text.rstrip() + append)

# Remove the one-shot infrastructure from the implementation commit itself.
for transient in [
    ROOT / ".github/workflows/one-shot-auto-dj-variety.yml",
    ROOT / "scripts/one_shot_auto_dj_variety.py",
]:
    if transient.exists():
        transient.unlink()

print("one-shot Auto-DJ provider improvements applied")
