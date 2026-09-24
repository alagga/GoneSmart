package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistSourceInspectorTest {
    private class xn3(val q: String)
    private class NativeGroup(val rows: List<Any>)
    private open class AdapterBase(private val groups: List<NativeGroup>)
    private class Adapter(
        private val rows: List<xn3>,
        groups: List<NativeGroup>
    ) : AdapterBase(groups)

    @Test fun nativeModelListIsReadEvenIfGroupOnlyContainsAHeader() {
        val adapter = Adapter(
            listOf(xn3("/sdcard/a.m3u"), xn3("/card/b.m3u")),
            listOf(NativeGroup(listOf(arrayOf("Header"))))
        )
        val result = NativePlaylistSourceInspector.inspect(adapter, 2)
        assertEquals(setOf("/sdcard/a.m3u", "/card/b.m3u"), result.paths.toSet())
        assertFalse(result.truncated)
    }

    @Test fun repeatedModelsAreDeduplicatedByPath() {
        val track = xn3("/storage/Playlist.m3u")
        val adapter = Adapter(
            listOf(track, track, xn3("/storage/Playlist.m3u")),
            listOf(NativeGroup(listOf(track)))
        )
        assertEquals(
            listOf("/storage/Playlist.m3u"),
            NativePlaylistSourceInspector.inspect(adapter).paths
        )
    }

    @Test fun headerStringsAreNotMistakenForNativePlaylists() {
        val adapter = Adapter(
            emptyList(),
            listOf(NativeGroup(listOf(arrayOf("/fake/NotAPlaylist.m3u"))))
        )
        assertTrue(NativePlaylistSourceInspector.inspect(adapter).paths.isEmpty())
    }

    private class GetterAdapter(private val source: Map<Int, xn3>) {
        fun getItem(position: Int): xn3? = source[position]
    }

    @Test fun safeGetItemReadsNativeModelsWithoutFileSystem() {
        val adapter = GetterAdapter(
            mapOf(0 to xn3("/root/A.m3u"), 1 to xn3("/external/B.m3u"))
        )
        assertEquals(
            setOf("/root/A.m3u", "/external/B.m3u"),
            NativePlaylistSourceInspector.inspect(adapter, 2).paths.toSet()
        )
    }
}
