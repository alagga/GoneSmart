package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzArtistIdentityPolicyTest {
    private val key: (String) -> String = { value ->
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    @Test
    fun exactArtistCreditCanSeedFallbackWithoutTrackAcceptance() {
        val metadata = metadata(searchArtist = "BUNT. & Røam", artists = listOf("BUNT.", "Røam"))
        val identity = ListenBrainzArtistIdentityPolicy.select(
            metadata = metadata,
            matches = listOf(
                ListenBrainzRecordingMatch(
                    recordingMbid = "recording",
                    recordingName = "Some other title",
                    artistName = "BUNT. & Røam",
                    releaseName = null,
                    artistMbids = listOf("bunt-mbid", "roam-mbid")
                )
            ),
            comparisonKey = key
        )

        assertEquals("bunt-mbid", identity?.artistMbid)
        assertEquals(0.90, identity?.confidence ?: 0.0, 0.0001)
    }

    @Test
    fun primaryArtistCreditCanSeedConservativeFallback() {
        val metadata = metadata(searchArtist = "BUNT., Røam", artists = listOf("BUNT.", "Røam"))
        val identity = ListenBrainzArtistIdentityPolicy.select(
            metadata = metadata,
            matches = listOf(
                ListenBrainzRecordingMatch(
                    recordingMbid = "recording",
                    recordingName = "Unrelated recording title",
                    artistName = "BUNT.",
                    releaseName = null,
                    artistMbids = listOf("bunt-mbid")
                )
            ),
            comparisonKey = key
        )

        assertEquals("bunt-mbid", identity?.artistMbid)
        assertTrue((identity?.confidence ?: 0.0) < 0.90)
    }

    @Test
    fun unrelatedArtistIsRejected() {
        val identity = ListenBrainzArtistIdentityPolicy.select(
            metadata = metadata(searchArtist = "Axwell", artists = listOf("Axwell")),
            matches = listOf(
                ListenBrainzRecordingMatch(
                    recordingMbid = "recording",
                    recordingName = "Belong",
                    artistName = "Completely Different Artist",
                    releaseName = null,
                    artistMbids = listOf("wrong-mbid")
                )
            ),
            comparisonKey = key
        )
        assertNull(identity)
    }

    private fun metadata(
        searchArtist: String,
        artists: List<String>
    ) = NormalizedTrackMetadata(
        rawArtist = searchArtist,
        rawTitle = "Seed",
        artists = artists,
        featuredArtists = emptyList(),
        remixArtists = emptyList(),
        baseTitle = "Seed",
        versionLabel = null,
        versionType = null,
        searchArtist = searchArtist,
        searchTitle = "Seed",
        confidence = MetadataConfidence.HIGH
    )
}
