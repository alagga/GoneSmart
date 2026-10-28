package io.github.alagga.gonesmart

import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal data class TrackMixPrePlayTargetResolution(
    val track: TrackInfo,
    val score: Int,
    val depth: Int,
    val evidence: String,
    val inspectedObjects: Int
)

/**
 * Resolves the row represented by GMMP's context-menu callback graph without
 * depending on R8 field names. Only direct scalar evidence from each bounded
 * object is scored. Large framework graphs are not traversed.
 */
internal class TrackMixPrePlayTargetResolver(
    private val maxDepth: Int = 5,
    private val maxObjects: Int = 240,
    private val maxCollectionItems: Int = 48
) {
    private data class Node(val value: Any, val depth: Int)
    private data class Candidate(
        val track: TrackInfo,
        val score: Int,
        val depth: Int,
        val evidence: String
    )

    fun resolve(
        roots: List<Any?>,
        library: List<TrackInfo>
    ): TrackMixPrePlayTargetResolution? {
        if (roots.isEmpty() || library.isEmpty()) return null
        // Build the lookup only for this explicit Track Mix start. Matching a context-menu
        // graph must not rescan and normalize the whole GMMP library for every node,
        // especially when Android schedules the worker in the background.
        val byPath = hashMapOf<String, MutableList<Int>>()
        val byTitle = hashMapOf<String, MutableList<Int>>()
        val byId = hashMapOf<Long, MutableList<Int>>()
        library.forEachIndexed { index, track ->
            normalize(track.path).takeIf { it.isNotEmpty() }?.let { path ->
                byPath.getOrPut(path) { arrayListOf() }.add(index)
            }
            normalize(track.title).takeIf { it.isNotEmpty() }?.let { title ->
                byTitle.getOrPut(title) { arrayListOf() }.add(index)
            }
            if (track.id > 0L) byId.getOrPut(track.id) { arrayListOf() }.add(index)
        }
        val queue = ArrayDeque<Node>()
        roots.filterNotNull().forEach { queue.add(Node(it, 0)) }
        val visited = IdentityHashMap<Any, Boolean>()
        val bestByTrack = linkedMapOf<Long, Candidate>()
        var inspected = 0

        while (queue.isNotEmpty() && inspected < maxObjects) {
            val node = queue.removeFirst()
            if (visited.put(node.value, true) != null) continue
            inspected++

            val scalars = directScalars(node.value)
            if (scalars.strings.isNotEmpty() || scalars.numbers.isNotEmpty()) {
                // Every strong match must contain an exact path, an ID or a title.
                // Artist/filename alone and a bare ID still fail in score().
                val candidates = linkedSetOf<Int>()
                scalars.strings.forEach { value ->
                    byPath[value]?.let(candidates::addAll)
                    byTitle[value]?.let(candidates::addAll)
                }
                scalars.numbers.forEach { value ->
                    byId[value]?.let(candidates::addAll)
                }
                candidates.forEach { index ->
                    val track = library[index]
                    score(track, scalars, node.depth)?.let { candidate ->
                        val previous = bestByTrack[track.id]
                        if (previous == null || candidate.score > previous.score ||
                            (candidate.score == previous.score && candidate.depth < previous.depth)
                        ) {
                            bestByTrack[track.id] = candidate
                        }
                    }
                }
            }

            if (node.depth >= maxDepth) continue
            directChildren(node.value).take(maxCollectionItems).forEach { child ->
                if (visited[child] == null && shouldTraverse(child)) {
                    queue.add(Node(child, node.depth + 1))
                }
            }
        }

        val ranked = bestByTrack.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.depth }
        )
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (best.score < 80) return null
        if (second != null && second.score == best.score && second.track.id != best.track.id) {
            return null
        }
        return TrackMixPrePlayTargetResolution(
            track = best.track,
            score = best.score,
            depth = best.depth,
            evidence = best.evidence,
            inspectedObjects = inspected
        )
    }

    private data class Scalars(
        val strings: Set<String>,
        val numbers: Set<Long>
    )

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
                    .takeIf { it.isNotEmpty() }?.let(strings::add)
                is Byte -> numbers += child.toLong()
                is Short -> numbers += child.toLong()
                is Int -> numbers += child.toLong()
                is Long -> numbers += child
            }
        }
        return Scalars(strings, numbers)
    }

    private fun directChildren(value: Any): Sequence<Any> = sequence {
        when (value) {
            is Iterable<*> -> {
                value.take(maxCollectionItems).filterNotNull().forEach { yield(it) }
                return@sequence
            }
            is Map<*, *> -> {
                value.entries.take(maxCollectionItems).forEach { entry ->
                    entry.key?.let { yield(it) }
                    entry.value?.let { yield(it) }
                }
                return@sequence
            }
        }
        if (value.javaClass.isArray) {
            val length = java.lang.reflect.Array.getLength(value).coerceAtMost(maxCollectionItems)
            repeat(length) { index ->
                java.lang.reflect.Array.get(value, index)?.let { yield(it) }
            }
            return@sequence
        }
        fields(value.javaClass).forEach { field ->
            val child = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: return@forEach
            if (!isScalar(child)) yield(child)
        }
    }

    private fun score(track: TrackInfo, scalars: Scalars, depth: Int): Candidate? {
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

        val baseScore = when {
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
        return Candidate(
            track = track,
            score = baseScore + bonus - depth * 6,
            depth = depth,
            evidence = evidence
        )
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

    private fun shouldTraverse(value: Any): Boolean {
        if (isScalar(value)) return false
        val name = value.javaClass.name
        if (value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) return true
        return !(
            name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("com.google.android.") ||
                name.startsWith("java.lang.") ||
                name.startsWith("java.util.concurrent.") ||
                name.startsWith("kotlin.")
        )
    }

    private fun isScalar(value: Any): Boolean =
        value is CharSequence || value is Number || value is Boolean ||
            value is Char || value.javaClass.isEnum

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase().orEmpty()
}
