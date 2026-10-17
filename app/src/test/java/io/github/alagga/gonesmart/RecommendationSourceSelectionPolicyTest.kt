package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationSourceSelectionPolicyTest {

    @Test
    fun lastEnabledSourceCannotBeDisabled() {
        RecommendationProvider.entries.forEach { onlyEnabled ->
            val options = optionsWithOnly(onlyEnabled)
            assertFalse(
                RecommendationSourceSelectionPolicy.canDisable(
                    provider = onlyEnabled,
                    options = options
                )
            )
        }
    }

    @Test
    fun sourceCanBeDisabledWhenAnotherSourceRemains() {
        val options = GoneSmartOptions(
            recommendationMode = RecommendationMode.CUSTOM,
            localPlaylistProviderEnabled = true,
            listenBrainzProviderEnabled = true,
            lastFmProviderEnabled = false
        )

        assertTrue(
            RecommendationSourceSelectionPolicy.canDisable(
                RecommendationProvider.LOCAL_PLAYLIST,
                options
            )
        )
        assertTrue(
            RecommendationSourceSelectionPolicy.canDisable(
                RecommendationProvider.LISTENBRAINZ,
                options
            )
        )
    }

    @Test
    fun everySourceCanBeDisabledWhileAllThreeAreEnabled() {
        val options = GoneSmartOptions(
            recommendationMode = RecommendationMode.CUSTOM,
            localPlaylistProviderEnabled = true,
            listenBrainzProviderEnabled = true,
            lastFmProviderEnabled = true
        )

        RecommendationProvider.entries.forEach { provider ->
            assertTrue(
                RecommendationSourceSelectionPolicy.canDisable(
                    provider = provider,
                    options = options
                )
            )
        }
    }

    @Test
    fun alreadyDisabledSourceDoesNotBlockUiRefresh() {
        val options = optionsWithOnly(RecommendationProvider.LOCAL_PLAYLIST)

        assertTrue(
            RecommendationSourceSelectionPolicy.canDisable(
                RecommendationProvider.LISTENBRAINZ,
                options
            )
        )
    }

    private fun optionsWithOnly(
        provider: RecommendationProvider
    ) = GoneSmartOptions(
        recommendationMode = RecommendationMode.CUSTOM,
        localPlaylistProviderEnabled = provider == RecommendationProvider.LOCAL_PLAYLIST,
        listenBrainzProviderEnabled = provider == RecommendationProvider.LISTENBRAINZ,
        lastFmProviderEnabled = provider == RecommendationProvider.LASTFM
    )
}
