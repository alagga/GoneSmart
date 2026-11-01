package io.github.alagga.gonesmart

import java.text.Normalizer
import java.util.Locale

/**
 * Last.fm community tags are not all genres ("seen live", "favorites", etc.).
 * Only established musical style tags can seed a genre fallback. The list is
 * deliberately conservative: a missing/ambiguous tag must not invent a genre.
 */
internal object ExternalGenrePolicy {
    private val recognizedGenres = setOf(
        "hardstyle", "rawstyle", "euphoric hardstyle", "hardcore", "happy hardcore",
        "frenchcore", "uptempo hardcore", "hard dance", "gabber", "trance",
        "progressive trance", "uplifting trance", "vocal trance", "psytrance",
        "tech trance", "house", "deep house", "progressive house", "future house",
        "electro house", "tech house", "tropical house", "bass house",
        "melodic house", "melodic techno", "techno", "hard techno", "minimal techno",
        "dubstep", "drum and bass", "liquid drum and bass", "breakbeat",
        "garage", "uk garage", "ambient", "downtempo", "chillout",
        "synthwave", "disco", "funk", "soul", "jazz", "blues", "rock",
        "alternative rock", "indie rock", "metal", "punk", "hip hop",
        "trip hop", "rnb", "pop", "electropop", "indie pop", "folk",
        "classical", "reggae", "dancehall", "afrobeat", "country"
    )

    fun normalizedGenre(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[-_]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val canonical = when (normalized) {
            "dnb", "d&b", "drum n bass", "drum & bass" -> "drum and bass"
            "hard style" -> "hardstyle"
            "psy trance" -> "psytrance"
            "r&b", "r and b", "rhythm and blues" -> "rnb"
            else -> normalized
        }
        return canonical.takeIf { it in recognizedGenres }
    }

    fun select(tags: List<LastFmGenreTag>, limit: Int = 1): List<String> {
        if (limit <= 0) return emptyList()
        return tags.asSequence()
            .mapNotNull { tag ->
                normalizedGenre(tag.name)?.let { genre ->
                    // More specific genres beat their generic parents for the
                    // same seed, subject to a bounded community-vote signal.
                    genre to (tag.count.coerceAtLeast(0) +
                        if (genre.contains(' ')) 18 else 0)
                }
            }
            .groupBy({ it.first }, { it.second })
            .map { (genre, scores) -> genre to scores.max() }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun genreRankScore(index: Int, count: Int): Double {
        val progress = index.coerceAtLeast(0).toDouble() /
            (count - 1).coerceAtLeast(1).toDouble()
        return (0.50 - 0.18 * progress).coerceIn(0.0, 0.50)
    }

    // Genre-level artist identity is less specific than a recognized recording,
    // but more informative than generic original-artist fallback for a bootleg.
    fun genreArtistRankScore(index: Int, count: Int): Double {
        val progress = index.coerceAtLeast(0).toDouble() /
            (count - 1).coerceAtLeast(1).toDouble()
        return (0.78 - 0.30 * progress).coerceIn(0.0, 0.78)
    }
}
