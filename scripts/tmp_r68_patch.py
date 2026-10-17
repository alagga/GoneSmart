from pathlib import Path

resolver = r'''package io.github.alagga.gonesmart

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
'''
Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolver.kt').write_text(resolver)

test = r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackMixFileBrowserMenuResolverTest {
    private data class Selected(val path: String)
    private data class Listener(val selected: Any, val adapterRows: List<Any> = emptyList())
    private data class CollectionBridge(val rows: List<Any>)

    @Test fun regularFileBehindBoundedCollectionIsEligible() {
        val root = Files.createTempDirectory("gs-track-mix-file").toFile()
        val file = root.resolve("song.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(CollectionBridge(listOf(Listener(Selected(file.absolutePath)))))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.REGULAR_FILE, result.kind)
        assertEquals("existing-file", result.evidence)
        root.deleteRecursively()
    }

    @Test fun shallowDirectoryWinsOverDeeperChildFile() {
        val root = Files.createTempDirectory("gs-track-mix-dir").toFile()
        val child = root.resolve("child.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                CollectionBridge(
                    listOf(
                        Listener(
                            selected = Selected(root.absolutePath),
                            adapterRows = listOf(Selected(child.absolutePath))
                        )
                    )
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertTrue(result.inspectedObjects <= 240)
        root.deleteRecursively()
    }

    @Test fun adapterLikeCollectionWithMultipleFileTargetsFailsClosed() {
        val root = Files.createTempDirectory("gs-track-mix-many").toFile()
        val first = root.resolve("one.mp3").apply { writeText("1") }
        val second = root.resolve("two.mp3").apply { writeText("2") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                CollectionBridge(
                    listOf(
                        Selected(first.absolutePath),
                        Selected(second.absolutePath)
                    )
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
        assertEquals("ambiguous-shallow-paths", result.evidence)
        root.deleteRecursively()
    }

    @Test fun unknownPathFailsClosed() {
        val missing = "/definitely/not/a/real/gonesmart/path/song.mp3"
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(CollectionBridge(listOf(Selected(missing))))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
    }
}
'''
Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolverTest.kt').write_text(test)

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
anchor = '- GMMP reuses `menu_gm_context_file_browser` for both playable file rows and directory rows. Track Auto-DJ may be exposed there only when the bounded native menu callback graph proves the selected filesystem target is an existing regular file. An existing directory or unknown/ambiguous filesystem identity hides the action. Do not blanket-disable the file-browser menu because real file rows are device-accepted, and never traverse adapter collections/child rows to promote a folder.\n'
replacement = anchor + '- R68 device evidence corrects the R67 traversal restriction: on GMMP 4.2.1 the selected playable file model is itself nested behind a small native collection, so direct-object-only traversal reports `unknown` for both songs and folders. File-browser menu eligibility may traverse containers only with strict depth/object/item bounds and may accept only one unique filesystem target at the shallowest proven depth. A shallower directory blocks deeper child files; multiple distinct shallow paths are ambiguous and fail closed. This changes menu eligibility only, not Track Auto-DJ playback/refill semantics.\n'
if anchor not in agents:
    raise SystemExit('AGENTS R67 anchor missing')
agents_path.write_text(agents.replace(anchor, replacement, 1))

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text().rstrip()
section = '''

### 2026-10-17 R68 file-browser selected-row collection gate

R67 correctly removed Track Auto-DJ from directory rows, but the next device pass showed that it also hid the action from real songs. All tested `menu_gm_context_file_browser` openings reported `browserContext=unknown`, because GMMP 4.2.1 keeps the selected playable row behind a small native collection while R67 intentionally refused to traverse containers. Historical accepted song starts independently prove that the pre-Play resolver reaches exact path/ID/title/artist evidence through that bounded collection, while a directory row still resolves no unique Track.

R68 keeps the cheap filesystem-only menu gate and does not move the expensive library resolver onto the UI thread. The gate now traverses collections/maps/arrays only under the same bounded selected-context philosophy (depth 5, 240 objects, 48 items per container). Eligibility is decided only by the unique filesystem target at the shallowest proven depth: one existing regular file exposes Track Auto-DJ; one existing directory hides it; multiple distinct shallow targets or no target fail closed. A selected directory therefore outranks any deeper child file, and a broad adapter-style collection of multiple songs cannot authorize the action. Playback, recommendation, queue mutation and refill behavior remain unchanged.
'''
if '### 2026-10-17 R68 file-browser selected-row collection gate' not in playbook:
    playbook += section
playbook_path.write_text(playbook + '\n')
