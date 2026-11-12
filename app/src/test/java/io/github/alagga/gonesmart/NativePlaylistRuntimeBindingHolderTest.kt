package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The real GMMP ao3 adapter is shared by picker and main-list holders. */
class NativePlaylistRuntimeBindingHolderTest {
    private class PlaylistModel(
        val id: Long,
        val title: String,
        val path: String
    )

    private class PlaylistAdapter(private val items: List<PlaylistModel>) {
        fun getItem(position: Int): PlaylistModel = items[position]
    }

    private class PickerHolder(val pickerModel: PlaylistModel)
    private class MainHolder(val mainModel: PlaylistModel)
    private class StaleHolder(val staleModel: PlaylistModel)

    @Test fun pickerBindingDoesNotHideMainListHolderModel() {
        val summer = PlaylistModel(
            410L, "Summer 2027", "/playlists/Summer/Summer 2027.m3u"
        )
        val festival = PlaylistModel(
            420L, "Festival 2027", "/playlists/Festival/Festival 2027.m3u"
        )
        val picker = PlaylistAdapter(listOf(summer, festival))
        assertNotNull(
            NativePlaylistRuntimeBinding.observeBoundRow(
                picker, PickerHolder(summer), 0, "Summer 2027"
            )
        )
        val main = PlaylistAdapter(listOf(festival, summer))
        val mainHolder = MainHolder(festival)
        assertNull(NativePlaylistRuntimeBinding.boundModel(mainHolder))

        // Previously isReady(ao3) made GoneSmart skip the main holder
        // completely, despite the correct adapter position being visible.
        assertSame(
            festival,
            NativePlaylistRuntimeBinding.boundModelAt(main, mainHolder, 0)
        )
        assertSame(festival, NativePlaylistRuntimeBinding.boundModel(mainHolder))
        assertNotNull(
            NativePlaylistRuntimeBinding.observeBoundRow(
                main, mainHolder, 0, "Festival 2027"
            )
        )
        assertEquals(
            listOf(festival.path, summer.path),
            NativePlaylistRuntimeBinding.readAll(main, 2)?.map { it.path }
        )
    }

    @Test fun staleHolderIsNeverAcceptedForAnotherAdapterPosition() {
        val correct = PlaylistModel(71, "All", "/playlists/All.m3u")
        val wrong = PlaylistModel(72, "Other", "/playlists/Other.m3u")
        val adapter = PlaylistAdapter(listOf(correct, wrong))
        assertNotNull(
            NativePlaylistRuntimeBinding.observeBoundRow(
                adapter, PickerHolder(correct), 0, "All"
            )
        )
        assertNull(
            NativePlaylistRuntimeBinding.boundModelAt(
                adapter, StaleHolder(wrong), 0
            )
        )
        assertNull(NativePlaylistRuntimeBinding.boundModel(StaleHolder(wrong)))
    }
}
