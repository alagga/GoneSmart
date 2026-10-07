package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistTitleResolverTest {
    private val p1 = "/storage/root/UnrelatedFile1.m3u"
    private val p2 = "/storage/SD/UnrelatedFile2.m3u"
    private val p3 = "/storage/SD/UnrelatedFile3.m3u"

    @Test fun findsNativeNameFieldAndAppliesItToUnseenRows() {
        val models = listOf(
            NativePlaylistTitleResolver.Model(
                p1, mapOf("q" to p1, "r" to "Progressive House",
                    "s" to "Other value")
            ),
            NativePlaylistTitleResolver.Model(
                p2, mapOf("q" to p2, "r" to "My Favorites",
                    "s" to "Not the playlist name")
            ),
            NativePlaylistTitleResolver.Model(
                p3, mapOf("q" to p3, "r" to "Deep Cuts",
                    "s" to "Not a title")
            )
        )
        val result = NativePlaylistTitleResolver.resolve(
            models, mapOf(p1 to "Progressive House", p2 to "My Favorites")
        )
        assertEquals("r", result.chosenField)
        assertEquals("Deep Cuts", result.names[p3])
        assertEquals(3, result.nativeTitles)
        assertEquals(0, result.filenameFallbacks)
    }

    @Test fun conflictingFieldsDoNotOverrideVisibleNativeText() {
        val models = listOf(
            NativePlaylistTitleResolver.Model(p1,
                mapOf("r" to "Bad name", "s" to "Unrelated text")),
            NativePlaylistTitleResolver.Model(p2,
                mapOf("r" to "Also bad", "s" to "Unknown"))
        )
        val result = NativePlaylistTitleResolver.resolve(
            models, mapOf(p1 to "Rendered A", p2 to "Rendered B")
        )
        assertNull(result.chosenField)
        assertEquals("Rendered A", result.names[p1])
        assertEquals(0, result.filenameFallbacks)
    }

    @Test fun neverUsesPathAsDisplayName() {
        val result = NativePlaylistTitleResolver.resolve(
            listOf(
                NativePlaylistTitleResolver.Model(p1,
                    mapOf("q" to p1, "path" to p1))
            ), emptyMap()
        )
        assertNull(result.chosenField)
        assertTrue(result.names.isEmpty())
        assertEquals(1, result.filenameFallbacks)
    }

    @Test fun explicitSemanticNameFieldWorksWithoutVisibleRows() {
        val result = NativePlaylistTitleResolver.resolve(
            listOf(
                NativePlaylistTitleResolver.Model(p1,
                    mapOf("displayName" to "Playlist One")),
                NativePlaylistTitleResolver.Model(p2,
                    mapOf("displayName" to "Playlist Two"))
            ), emptyMap()
        )
        assertEquals("displayName", result.chosenField)
        assertEquals("Playlist Two", result.names[p2])
    }

    @Test fun keepsSameNamedPlaylistsDistinctByPath() {
        val result = NativePlaylistTitleResolver.resolve(
            listOf(
                NativePlaylistTitleResolver.Model(p1,
                    mapOf("r" to "Favorites")),
                NativePlaylistTitleResolver.Model(p2,
                    mapOf("r" to "Favorites"))
            ), mapOf(p1 to "Favorites", p2 to "Favorites")
        )
        assertEquals(2, result.names.size)
        assertEquals("Favorites", result.names[p2])
    }
}
