package io.github.alagga.gonesmart

import java.io.File
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal enum class TrackMixFileBrowserSelectionKind {
    REGULAR_FILE,
    DIRECTORY,
    UNKNOWN
}

internal data class TrackMixFileBrowserSelection(
    val kind: TrackMixFileBrowserSelectionKind,
    val depth: Int? = null,
    val evidence: String = "none",
    val inspectedObjects: Int = 0
)

/**
 * GMMP reuses menu_gm_context_file_browser for both playable files and
 * directories. The selected file-browser row is nested behind a small native
 * collection on 4.2.1, so bounded collection traversal is required. Only the
 * unique shallowest filesystem target is authoritative: deeper child rows can
 * never promote a selected directory, and an adapter-like set of multiple
 * paths fails closed as ambiguous.
 */
internal class TrackMixFileBrowserMenuResolver(
    private val maxDepth: Int = 5,
    private val maxObjects: Int = 240,
    private val maxCollectionItems: Int = 48,
    private val pathKind: (String) -> TrackMixFileBrowserSelectionKind = ::probePath
) {
    private data class Node(val value: Any, val depth: Int)
    private data class Candidate(
        val kind: TrackMixFileBrowserSelectionKind,
        val depth: Int,
        val path: String
    )

    fun resolve(roots: List<Any?>): TrackMixFileBrowserSelection {
        if (roots.isEmpty()) return TrackMixFileBrowserSelection(
            TrackMixFileBrowserSelectionKind.UNKNOWN
        )
        val queue = ArrayDeque<Node>()
        roots.filterNotNull().forEach { queue.add(Node(it, 0)) }
        val visited = IdentityHashMap<Any, Boolean>()
        val candidates = arrayListOf<Candidate>()
        var inspected = 0

        while (queue.isNotEmpty() && inspected < maxObjects) {
            val node = queue.removeFirst()
            if (visited.put(node.value, true) != null) continue
            inspected++

            directPaths(node.value).forEach { path ->
                val kind = pathKind(path)
                if (kind != TrackMixFileBrowserSelectionKind.UNKNOWN) {
                    candidates += Candidate(kind, node.depth, path)
                }
            }

            if (node.depth >= maxDepth) continue
            directChildren(node.value).take(maxCollectionItems).forEach { child ->
                if (visited[child] == null && shouldInspect(child)) {
                    queue.add(Node(child, node.depth + 1))
                }
            }
        }

        val shallowest = candidates.minOfOrNull { it.depth }
            ?: return TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                inspectedObjects = inspected
            )
        val atDepth = candidates.filter { it.depth == shallowest }
        val distinctTargets = atDepth
            .groupBy { it.path }
        if (distinctTargets.size != 1) {
            return TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                depth = shallowest,
                evidence = "ambiguous-shallow-paths",
                inspectedObjects = inspected
            )
        }
        val sameTarget = distinctTargets.values.single()
        val kinds = sameTarget.map { it.kind }.distinct()
        if (kinds.size != 1) {
            return TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                depth = shallowest,
                evidence = "ambiguous-path-kind",
                inspectedObjects = inspected
            )
        }
        val winner = sameTarget.first()
        return TrackMixFileBrowserSelection(
            kind = winner.kind,
            depth = winner.depth,
            evidence = if (winner.kind == TrackMixFileBrowserSelectionKind.REGULAR_FILE) {
                "existing-file"
            } else {
                "existing-directory"
            },
            inspectedObjects = inspected
        )
    }

    private fun directPaths(value: Any): Sequence<String> = sequence {
        when (value) {
            is File -> normalizePath(value.path)?.let { yield(it) }
            is CharSequence -> normalizePath(value.toString())?.let { yield(it) }
        }
        if (value is File || value is CharSequence) return@sequence
        fields(value.javaClass).forEach { field ->
            val child = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull() ?: return@forEach
            when (child) {
                is File -> normalizePath(child.path)?.let { yield(it) }
                is CharSequence -> normalizePath(child.toString())?.let { yield(it) }
            }
        }
    }

    private fun directChildren(value: Any): Sequence<Any> = sequence {
        when (value) {
            is File, is CharSequence -> return@sequence
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
            if (child !is File && child !is CharSequence && !isScalar(child)) {
                yield(child)
            }
        }
    }

    private fun shouldInspect(value: Any): Boolean {
        if (value is File || value is CharSequence) return true
        if (value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) return true
        if (isScalar(value)) return false
        val name = value.javaClass.name
        return !(
            name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("com.google.android.") ||
                name.startsWith("java.lang.") ||
                name.startsWith("java.util.concurrent.") ||
                name.startsWith("kotlin.")
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

    private fun isScalar(value: Any): Boolean =
        value is Number || value is Boolean || value is Char || value.javaClass.isEnum

    private fun normalizePath(raw: String): String? {
        val trimmed = raw.trim()
        val path = when {
            trimmed.startsWith("file://") -> trimmed.removePrefix("file://")
            trimmed.startsWith("/") -> trimmed
            else -> return null
        }
        return path.takeIf { it.length > 1 && '\u0000' !in it }
    }

    companion object {
        private fun probePath(path: String): TrackMixFileBrowserSelectionKind {
            val file = File(path)
            return when {
                file.isFile -> TrackMixFileBrowserSelectionKind.REGULAR_FILE
                file.isDirectory -> TrackMixFileBrowserSelectionKind.DIRECTORY
                else -> TrackMixFileBrowserSelectionKind.UNKNOWN
            }
        }
    }
}
