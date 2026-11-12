package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistSourceInspectorTest {
    private class Details(val title: String)
    private class xn3(
        val q: String,
        val r: String? = null,
        val info: Details? = null
    )
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

    @Test fun readsNativeModelTitlesWithoutGuessingFromFilenames() {
        val path = "/external/file-does-not-match.m3u"
        val adapter = Adapter(
            listOf(xn3(path, "Real playlist name", Details("Nested title"))),
            emptyList()
        )
        val found = NativePlaylistSourceInspector.inspect(adapter)
            .models.single()
        assertEquals(path, found.path)
        assertEquals("Real playlist name", found.textFields["r"])
        assertEquals("Nested title", found.textFields["info.title"])
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

    private class OrderedNativeModel(
        val id: Long,
        // Deliberately obfuscated, just like GMMP 4.2.1's native title
        // field: the picker must not depend on a literal "title" name.
        val n: String,
        val path: String
    )

    private class OrderedAdapter(
        private val discoveredFirst: List<OrderedNativeModel>,
        private val sortedRows: List<OrderedNativeModel>
    ) {
        fun getItem(position: Int): OrderedNativeModel = sortedRows[position]
    }

    private class OrderedHolder(val model: OrderedNativeModel)

    @Test fun authoritativeAdapterPositionsOverrideCompleteButUnorderedNativeCollections() {
        val summer = OrderedNativeModel(1, "Summer 2027", "/playlists/Summer/Summer 2027.m3u")
        val festival = OrderedNativeModel(2, "Festival 2027", "/playlists/Festival/Festival 2027.m3u")
        val adapter = OrderedAdapter(
            discoveredFirst = listOf(summer, festival),
            sortedRows = listOf(festival, summer)
        )
        assertNotNull(
            NativePlaylistRuntimeBinding.observeBoundRow(
                adapter, OrderedHolder(festival), 0, "Festival 2027"
            )
        )

        val result = NativePlaylistSourceInspector.inspect(adapter, 2)
        assertEquals(
            listOf(festival.path, summer.path), result.paths
        )
        assertEquals(festival, result.nativeObjects[festival.path])
        assertTrue(result.traces.single().contains("orderedRows=2"))

        // The picker can have zero bound native labels even though readAll
        // verified every model and its actual displayed title earlier.
        // It must still produce the complete folder/title index.
        val noVisibleTitles = NativePlaylistTitleResolver.resolve(
            result.models, emptyMap()
        )
        assertEquals(2, noVisibleTitles.nativeTitles)
        assertEquals("Festival 2027", noVisibleTitles.names[festival.path])
        assertEquals("Summer 2027", noVisibleTitles.names[summer.path])
    }
}
