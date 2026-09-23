package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class GoneSmartLogSummaryTest {
    @Test fun summarizesEveryActiveFeatureAndUncategorizedEvents() {
        val log = """
            22:00:01  [System] GoneSmart ready.
            22:00:02  [Smart DJ] Selected tracks from current pool.
            22:00:03  [Playlists] Added 3 songs to 2 playlists.
            22:00:04  [Flip] Reversed 31-track queue.
            22:00:05  [Flip] Playing Smart Playlist in reverse.
            22:00:06  [UI] Flip enabled.
            22:00:07  Legacy entry before categories existed.
        """.trimIndent()

        assertEquals(
            GoneSmartLogSummary.Summary(
                total = 7,
                smartDj = 1,
                playlists = 1,
                flip = 2,
                other = 3
            ),
            GoneSmartLogSummary.fromText(log)
        )
    }

    @Test fun emptyLogProducesZeroCounters() {
        assertEquals(
            GoneSmartLogSummary.Summary(0, 0, 0, 0, 0),
            GoneSmartLogSummary.fromText("  \n\n")
        )
    }

    @Test fun repeatedEventsRetainAllHistoryAndAccurateCounts() {
        val log = listOf(
            "09:00:01  [Flip] Reversed 17-track playlist.",
            "09:00:02  [Flip] Reversed 140-track Smart Playlist.",
            "09:00:03  [Playlists] Added 4 songs to 3 playlists.",
            "09:00:04  [Smart DJ] Fallback active.",
            "09:00:05  [Smart DJ] Recommendations resumed."
        ).joinToString("\n")
        assertEquals(
            GoneSmartLogSummary.Summary(5, 2, 1, 2, 0),
            GoneSmartLogSummary.fromText(log)
        )
    }
}
