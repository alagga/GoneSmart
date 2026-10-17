package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMixFileBrowserMenuResolverTest {
    @Test fun gmmp421ObservedSongMenuShapesAreEligible() {
        listOf(9, 10).forEach { count ->
            val result = TrackMixFileBrowserMenuShapePolicy.resolve(count)
            assertEquals(
                "count=$count",
                TrackMixFileBrowserSelectionKind.REGULAR_FILE,
                result.kind
            )
            assertEquals("gmmp421-native-song-menu-shape", result.evidence)
        }
    }

    @Test fun gmmp421DirectoryMenuShapeIsNotEligible() {
        val result = TrackMixFileBrowserMenuShapePolicy.resolve(8)
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertEquals("gmmp421-native-directory-menu-shape", result.evidence)
    }

    @Test fun unknownNativeMenuShapesFailClosed() {
        listOf(0, 7, 11, 12).forEach { count ->
            val result = TrackMixFileBrowserMenuShapePolicy.resolve(count)
            assertEquals(
                "count=$count",
                TrackMixFileBrowserSelectionKind.UNKNOWN,
                result.kind
            )
            assertEquals("unexpected-native-menu-shape", result.evidence)
        }
    }
}
