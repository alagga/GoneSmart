package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrackMixNativePlayListResolverTest {
    private data class NativeTrack(
        val a: Long,
        val b: String,
        val c: String,
        val d: String
    )

    private val target = TrackInfo(
        19118L,
        "Nothing Lasts Forever",
        "Bunt., Røam",
        "Bunt., Røam",
        "/music/Tomorrow House/01. BUNT., Røam - Nothing Lasts Forever.mp3"
    )

    @Test fun exactPathSelectsOnlyTheTargetNativeRow() {
        val other = NativeTrack(1L, "Other", "Artist", "/music/other.mp3")
        val selected = NativeTrack(
            19118L,
            "Nothing Lasts Forever",
            "Bunt., Røam",
            target.path!!
        )
        val result = TrackMixNativePlayListResolver().resolve(
            listOf(other, selected),
            target
        )
        assertSame(selected, result?.item)
        assertEquals(1, result?.index)
        assertEquals(true, result?.evidence?.contains("path"))
        assertEquals(true, result?.evidence?.contains("id"))
    }

    @Test fun idPlusMetadataResolvesWhenPathIsNotExposed() {
        data class Compact(val id: Long, val title: String, val artist: String)
        val selected = Compact(19118L, "Nothing Lasts Forever", "Bunt., Røam")
        val result = TrackMixNativePlayListResolver().resolve(
            listOf(selected),
            target
        )
        assertSame(selected, result?.item)
        assertEquals(true, result?.evidence?.contains("id"))
    }

    @Test fun ambiguousTitleArtistOnlyRowsFailOpen() {
        data class MetadataOnly(val title: String, val artist: String)
        assertNull(
            TrackMixNativePlayListResolver().resolve(
                listOf(
                    MetadataOnly("Nothing Lasts Forever", "Bunt., Røam"),
                    MetadataOnly("Nothing Lasts Forever", "Bunt., Røam")
                ),
                target
            )
        )
    }

    @Test fun unrelatedRowsNeverResolveFromBareNumericCoincidence() {
        data class Bare(val id: Long)
        assertNull(
            TrackMixNativePlayListResolver().resolve(
                listOf(Bare(19118L)),
                target
            )
        )
    }
}
