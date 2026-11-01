package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalGenrePolicyTest {
    @Test fun `remixer hardstyle tags outrank non genre community labels`() {
        val tags = listOf(
            LastFmGenreTag("seen live", 900),
            LastFmGenreTag("favorites", 400),
            LastFmGenreTag("Hardstyle", 80),
            LastFmGenreTag("Electronic", 120),
            LastFmGenreTag("Hard Style", 40)
        )
        assertEquals(listOf("hardstyle"), ExternalGenrePolicy.select(tags))
    }

    @Test fun `style aliases are normalized but user flags are not genres`() {
        assertEquals("drum and bass", ExternalGenrePolicy.normalizedGenre("D&B"))
        assertEquals("psytrance", ExternalGenrePolicy.normalizedGenre("Psy Trance"))
        assertNull(ExternalGenrePolicy.normalizedGenre("2010s"))
        assertNull(ExternalGenrePolicy.normalizedGenre("my favorites"))
        assertNull(ExternalGenrePolicy.normalizedGenre("Electronic"))
    }

    @Test fun `genre evidence remains below direct track similarity`() {
        assertEquals(0.50, ExternalGenrePolicy.genreRankScore(0, 40), 0.0001)
        assertTrue(ExternalGenrePolicy.genreRankScore(39, 40) < 0.5)
        assertEquals(0.78, ExternalGenrePolicy.genreArtistRankScore(0, 50), 0.0001)
        assertTrue(ExternalGenrePolicy.genreArtistRankScore(49, 50) < 0.5)
        assertEquals(emptyList<String>(), ExternalGenrePolicy.select(
            listOf(LastFmGenreTag("pop", 60)), limit = 0))
    }
}
