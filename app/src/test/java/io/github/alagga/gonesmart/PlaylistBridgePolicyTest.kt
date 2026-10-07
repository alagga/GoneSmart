package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBridgePolicyTest {
    @Test
    fun recognizesOnlyNativeSmartPlaylistReferences() {
        assertTrue(
            PlaylistBridgePolicy.isNativeSmartPlaylistReference(
                "/storage/emulated/0/gmmp/smart/Source.spl|Source"
            )
        )
        assertFalse(
            PlaylistBridgePolicy.isNativeSmartPlaylistReference(
                "/storage/emulated/0/gmmp/playlists/Source.m3u|Source"
            )
        )
        assertFalse(
            PlaylistBridgePolicy.isNativeSmartPlaylistReference(
                "/storage/emulated/0/gmmp/smart/Source.spl"
            )
        )
    }

    @Test
    fun logTextDoesNotLeakPathOrDisplayName() {
        val raw =
            "/storage/emulated/0/gmmp/smart/Very Private Source.spl|Private Mix"
        val safe = PlaylistBridgePolicy.safeReference(raw)

        assertTrue(safe.contains("ext=spl"))
        assertTrue(safe.contains("pathHash="))
        assertTrue(safe.contains("displayLen=11"))
        assertFalse(safe.contains("/storage/"))
        assertFalse(safe.contains("Very Private Source"))
        assertFalse(safe.contains("Private Mix"))
    }

    @Test
    fun normalPlaylistPathIsRedactedToo() {
        val safe = PlaylistBridgePolicy.safePath(
            "/storage/emulated/0/gmmp/playlists/Test Folder/Bridge.m3u"
        )
        assertTrue(safe.contains("ext=m3u"))
        assertFalse(safe.contains("Test Folder"))
        assertFalse(safe.contains("Bridge.m3u"))
    }
}
