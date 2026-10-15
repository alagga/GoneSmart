package io.github.alagga.gonesmart

import java.lang.reflect.Modifier

internal data class TrackMixNativePlayListResolution(
    val item: Any,
    val index: Int,
    val matchCount: Int,
    val score: Int,
    val evidence: String
)

/**
 * Resolves the selected Track Mix row inside GMMP's native MusicService.w1
 * playback list without relying on R8 field names. Only direct scalar fields
 * are inspected; exact path evidence wins, while weak ambiguous matches fail
 * open and leave GMMP's native list untouched.
 */
internal class TrackMixNativePlayListResolver {
    private data class Scalars(
        val strings: Set<String>,
        val numbers: Set<Long>
    )

    private data class Candidate(
        val item: Any,
        val index: Int,
        val score: Int,
        val evidence: String
    )

    fun resolve(
        items: List<*>,
        target: TrackInfo
    ): TrackMixNativePlayListResolution? {
        if (items.isEmpty()) return null
        val candidates = items.mapIndexedNotNull { index, raw ->
            val item = raw ?: return@mapIndexedNotNull null
            score(target, directScalars(item))?.let { (score, evidence) ->
                Candidate(item, index, score, evidence)
            }
        }
        if (candidates.isEmpty()) return null

        val bestScore = candidates.maxOf { it.score }
        val best = candidates.filter { it.score == bestScore }
        val strongIdentity = best.first().evidence.split('+').any {
            it == "path" || it == "id"
        }
        // Duplicate occurrences of the exact same library track are
        // semantically equivalent for Track Mix. Title+artist-only ties are
        // not strong enough to choose an arbitrary native row.
        if (best.size > 1 && !strongIdentity) return null

        val chosen = best.first()
        return TrackMixNativePlayListResolution(
            item = chosen.item,
            index = chosen.index,
            matchCount = best.size,
            score = chosen.score,
            evidence = chosen.evidence
        )
    }

    private fun score(
        track: TrackInfo,
        scalars: Scalars
    ): Pair<Int, String>? {
        val path = normalize(track.path)
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val albumArtist = normalize(track.albumArtist)
        val filename = path.substringAfterLast('/').takeIf { '/' in path }.orEmpty()

        val pathMatch = path.isNotEmpty() && path in scalars.strings
        val idMatch = track.id > 0L && track.id in scalars.numbers
        val titleMatch = title.isNotEmpty() && title in scalars.strings
        val artistMatch = (artist.isNotEmpty() && artist in scalars.strings) ||
            (albumArtist.isNotEmpty() && albumArtist in scalars.strings)
        val filenameMatch = filename.isNotEmpty() && filename in scalars.strings

        val base = when {
            pathMatch -> 120
            idMatch && (titleMatch || artistMatch || filenameMatch) -> 105
            titleMatch && artistMatch -> 92
            else -> return null
        }
        val bonus = (if (idMatch) 5 else 0) +
            (if (titleMatch) 3 else 0) +
            (if (artistMatch) 3 else 0) +
            (if (filenameMatch) 2 else 0)
        val evidence = buildList {
            if (pathMatch) add("path")
            if (idMatch) add("id")
            if (titleMatch) add("title")
            if (artistMatch) add("artist")
            if (filenameMatch) add("filename")
        }.joinToString("+")
        return (base + bonus) to evidence
    }

    private fun directScalars(value: Any): Scalars {
        val strings = linkedSetOf<String>()
        val numbers = linkedSetOf<Long>()
        fields(value.javaClass).forEach { field ->
            val child = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: return@forEach
            when (child) {
                is CharSequence -> normalize(child.toString())
                    .takeIf { it.isNotEmpty() }
                    ?.let(strings::add)
                is Byte -> numbers += child.toLong()
                is Short -> numbers += child.toLong()
                is Int -> numbers += child.toLong()
                is Long -> numbers += child
            }
        }
        return Scalars(strings, numbers)
    }

    private fun fields(type: Class<*>): List<java.lang.reflect.Field> {
        val result = arrayListOf<java.lang.reflect.Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach(result::add)
            current = current.superclass
        }
        return result
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase().orEmpty()
}
