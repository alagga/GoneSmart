package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBridgeReferenceTest {
    @Test
    fun roundTripsLegacyUnicodePathWithoutExposingItAsNativeSmartLinkPath() {
        val path =
            "/storage/emulated/0/gmmp/playlists/GoneSmart Tests/ÄÖ Festival.m3u"
        val encoded = PlaylistBridgeReference.encode(path, "Festival")
        val decoded = PlaylistBridgeReference.decode(encoded)

        assertTrue(encoded.startsWith("gonesmart-playlist:"))
        assertFalse(encoded.substringBefore('|').contains("/storage/"))
        assertEquals(path, decoded?.path)
        assertEquals("Festival", decoded?.displayName)
        assertEquals(false, decoded?.portable)
    }

    @Test
    fun portableReferenceLooksLikeAValidNativeSmartLink() {
        val source = "/music/folder/A.m3u"
        val compatibility =
            "/data/user/0/gonemad.gmmp/files/gonesmart/playlist-bridge/bridge-true.spl"
        val encoded = PlaylistBridgeReference.encodePortable(
            source,
            "A",
            compatibility
        )
        val decoded = PlaylistBridgeReference.decode(encoded)

        assertEquals(compatibility, encoded.substringBefore('|'))
        assertEquals("A", encoded.split('|')[1])
        assertFalse(encoded.substringBefore('|').contains(source))
        assertEquals(source, decoded?.path)
        assertEquals("A", decoded?.displayName)
        assertEquals(true, decoded?.portable)
    }

    @Test
    fun displayNameMayContainAdditionalSeparatorsForGoneSmartDecoder() {
        val legacy = PlaylistBridgeReference.encode(
            "/music/a.m3u",
            "A | B"
        )
        val portable = PlaylistBridgeReference.encodePortable(
            "/music/a.m3u",
            "A | B",
            "/private/bridge-true.spl"
        )
        assertEquals(
            "A | B",
            PlaylistBridgeReference.decode(legacy)?.displayName
        )
        assertEquals(
            "A | B",
            PlaylistBridgeReference.decode(portable)?.displayName
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
        assertNull(
            PlaylistBridgeReference.decode(
                "/private/bridge.spl|Name|gonesmart-playlist-v2:zz"
            )
        )
    }
}
