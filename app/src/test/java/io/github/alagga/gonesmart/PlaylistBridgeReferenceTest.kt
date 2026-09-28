package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBridgeReferenceTest {
    @Test
    fun roundTripsUnicodePathWithoutExposingItAsNativeSmartLinkPath() {
        val path =
            "/storage/emulated/0/gmmp/playlists/GoneSmart Tests/ÄÖ Festival.m3u"
        val encoded = PlaylistBridgeReference.encode(path, "Festival")
        val decoded = PlaylistBridgeReference.decode(encoded)

        assertTrue(encoded.startsWith("gonesmart-playlist:"))
        assertFalse(encoded.substringBefore('|').contains("/storage/"))
        assertEquals(path, decoded?.path)
        assertEquals("Festival", decoded?.displayName)
    }

    @Test
    fun displayNameMayContainAdditionalSeparatorsForGoneSmartDecoder() {
        val encoded = PlaylistBridgeReference.encode(
            "/music/a.m3u",
            "A | B"
        )
        assertEquals(
            "A | B",
            PlaylistBridgeReference.decode(encoded)?.displayName
        )
    }

    @Test
    fun rejectsNativeSmartLinksAndMalformedPayloads() {
        assertNull(
            PlaylistBridgeReference.decode("/storage/x.spl|Smart")
        )
        assertNull(
            PlaylistBridgeReference.decode("gonesmart-playlist:zz|Broken")
        )
        assertNull(
            PlaylistBridgeReference.decode("gonesmart-playlist:00|")
        )
    }
}
