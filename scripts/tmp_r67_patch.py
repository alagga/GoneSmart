from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}\nANCHOR:\n{old}")
    p.write_text(text.replace(old, new, 1))

# Runtime resolver: distinguish a selected real file from a selected folder
# without traversing adapter collections or guessing R8 names.
Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolver.kt").write_text(r'''package io.github.alagga.gonesmart

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
 * directories. Resolve only filesystem identity from the bounded selected-row
 * callback graph. Collections/arrays are deliberately not traversed so a
 * folder's child rows cannot make the folder itself look playable.
 */
internal class TrackMixFileBrowserMenuResolver(
    private val maxDepth: Int = 4,
    private val maxObjects: Int = 64,
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
            directChildren(node.value).forEach { child ->
                if (visited[child] == null && shouldTraverse(child)) {
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
        val winner = atDepth.firstOrNull {
            it.kind == TrackMixFileBrowserSelectionKind.REGULAR_FILE
        } ?: atDepth.firstOrNull {
            it.kind == TrackMixFileBrowserSelectionKind.DIRECTORY
        } ?: return TrackMixFileBrowserSelection(
            kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
            inspectedObjects = inspected
        )
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
        if (value is File) {
            normalizePath(value.path)?.let { yield(it) }
        }
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
        // Never walk adapter/list contents: only the selected callback/model
        // chain is eligible to decide whether the menu represents a file.
        if (value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) {
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

    private fun shouldTraverse(value: Any): Boolean {
        if (isScalar(value)) return false
        val name = value.javaClass.name
        return !(
            name.startsWith("android.") ||
                name.startsWith("androidx.") ||
                name.startsWith("com.google.android.") ||
                name.startsWith("java.") ||
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
''')

Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolverTest.kt").write_text(r'''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackMixFileBrowserMenuResolverTest {
    private data class Selected(val path: String)
    private data class Listener(val selected: Any, val adapterRows: List<Any> = emptyList())

    @Test fun regularFileIsEligible() {
        val root = Files.createTempDirectory("gs-track-mix-file").toFile()
        val file = root.resolve("song.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(Listener(Selected(file.absolutePath)))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.REGULAR_FILE, result.kind)
        assertEquals("existing-file", result.evidence)
        root.deleteRecursively()
    }

    @Test fun directoryIsNotEligibleEvenWhenItsAdapterRowsContainFiles() {
        val root = Files.createTempDirectory("gs-track-mix-dir").toFile()
        val child = root.resolve("child.mp3").apply { writeText("x") }
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(
                Listener(
                    selected = Selected(root.absolutePath),
                    adapterRows = listOf(Selected(child.absolutePath))
                )
            )
        )
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertTrue(result.inspectedObjects <= 64)
        root.deleteRecursively()
    }

    @Test fun unknownPathFailsClosed() {
        val missing = "/definitely/not/a/real/gonesmart/path/song.mp3"
        val result = TrackMixFileBrowserMenuResolver().resolve(
            listOf(Listener(Selected(missing)))
        )
        assertEquals(TrackMixFileBrowserSelectionKind.UNKNOWN, result.kind)
    }
}
''')

path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
replace_once(
    path,
    '''        private const val MIX_ITEM_ID = 0x47534D01
        // These GMMP 4.2.0 menu XMLs all have native Play and Play next.
''',
    '''        private const val MIX_ITEM_ID = 0x47534D01
        private const val FILE_BROWSER_MENU = "menu_gm_context_file_browser"
        // These GMMP 4.2.0 menu XMLs all have native Play and Play next.
'''
)
replace_once(
    path,
    '''            "menu_gm_context_playlist_details",
            "menu_gm_context_file_browser",
            "menu_gm_context_shared"
''',
    '''            "menu_gm_context_playlist_details",
            FILE_BROWSER_MENU,
            "menu_gm_context_shared"
'''
)
replace_once(
    path,
    '''    private data class LoadedPlay(
        val snapshot: Snapshot,
        val selectedTrackId: Long
    )
''',
    '''    private data class LoadedPlay(
        val snapshot: Snapshot,
        val selectedTrackId: Long
    )

    private data class MenuSelectionContext(
        val callback: Any?,
        val popup: Any?,
        val listener: Any?,
        val roots: List<Any>
    )
'''
)
replace_once(
    path,
    '''    private val queueReader = GmmpQueueReader()
    private val prePlayAutoDjResolver = TrackMixAutoDjResolver()
''',
    '''    private val queueReader = GmmpQueueReader()
    private val fileBrowserMenuResolver = TrackMixFileBrowserMenuResolver()
    private val prePlayAutoDjResolver = TrackMixAutoDjResolver()
'''
)
replace_once(
    path,
    '''        if (name !in SONG_MENUS) return
        menu.findItem(MIX_ITEM_ID)?.let {
            it.isVisible = enabled
            return
        }
        if (!enabled) return

        val playId = context.resources.getIdentifier(
''',
    '''        if (name !in SONG_MENUS) return
        val existing = menu.findItem(MIX_ITEM_ID)
        if (!enabled) {
            existing?.isVisible = false
            return
        }

        val playId = context.resources.getIdentifier(
'''
)
replace_once(
    path,
    '''        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        val language = context.resources.configuration.locales[0].language
''',
    '''        val nativePlay = menu.findItem(playId) ?: return
        val nativeNext = menu.findItem(nextId) ?: return
        if (name == FILE_BROWSER_MENU) {
            val selectionContext = menuSelectionContext(menu, nativePlay)
            val browserSelection = fileBrowserMenuResolver.resolve(selectionContext.roots)
            if (browserSelection.kind != TrackMixFileBrowserSelectionKind.REGULAR_FILE) {
                existing?.isVisible = false
                Log.i(
                    TAG,
                    "MIX MENU SKIP | menu=$name" +
                        " | browserContext=${browserSelection.kind.name.lowercase()}" +
                        " | evidence=${browserSelection.evidence}" +
                        " | depth=${browserSelection.depth ?: -1}" +
                        " | roots=${selectionContext.roots.size}" +
                        " | inspected=${browserSelection.inspectedObjects}"
                )
                return
            }
        }
        if (existing != null) {
            existing.isVisible = true
            return
        }
        val language = context.resources.configuration.locales[0].language
'''
)
replace_once(
    path,
    '''        val callback = runCatching { field(menu, "mCallback") }.getOrNull()
        val popup = callback?.let { runCatching { field(it, "this$0") }.getOrNull() }
        val listener = popup?.let {
            runCatching {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }.getOrNull()
        }
        val prePlayRoots = listOfNotNull(
            listener, popup, callback, menu, nativePlay
        ).distinctBy { System.identityHashCode(it) }
''',
    '''        val selectionContext = menuSelectionContext(menu, nativePlay)
        val listener = selectionContext.listener
        val prePlayRoots = selectionContext.roots
'''
)
replace_once(
    path,
    '''    private fun menuContext(menu: Menu, inflater: Any?): Context? {
''',
    '''    private fun menuSelectionContext(
        menu: Menu,
        nativePlay: MenuItem
    ): MenuSelectionContext {
        val callback = runCatching { field(menu, "mCallback") }.getOrNull()
        val popup = callback?.let {
            runCatching { field(it, "this$0") }.getOrNull()
        }
        val listener = popup?.let {
            runCatching {
                field(it, "mMenuItemClickListener")
                    ?: field(it, "mOnMenuItemClickListener")
            }.getOrNull()
        }
        return MenuSelectionContext(
            callback = callback,
            popup = popup,
            listener = listener,
            roots = listOfNotNull(
                listener, popup, callback, menu, nativePlay
            ).distinctBy { System.identityHashCode(it) }
        )
    }

    private fun menuContext(menu: Menu, inflater: Any?): Context? {
'''
)

# Standing invariant: shared file-browser XML is not proof of a single track.
path = "AGENTS.md"
replace_once(
    path,
    '''The prepared-match 4.2.1 flow is accepted from normal playlists and large Smart Playlists. The zero-provider/native-fallback startup remains fail-closed and requires device re-acceptance after boundary changes.
''',
    '''The GMMP 4.2.1 prepared-match and zero-provider/native-fallback Track Auto-DJ flows are device-accepted across normal playlists, Queue, file-browser files and large Smart Playlists. Any playback/refill boundary change requires device re-acceptance.
'''
)
replace_once(
    path,
    '''- R66 device evidence: a previously verified 4.2.1 Current-position writer can be queued behind GMMP worker contention for more than the old 1.5 s dispatch budget. Keep semantic readback verification strict, but allow a separate bounded 4 s native-dispatch wait and cancel work that has not started when that budget expires. A dispatch timeout is scheduling evidence, not permission to guess or invoke another writer. Track-Mix `no-matches-native-fallback` status is owned only after preplay preparation finishes; defer it while the pool is unresolved and suppress it when prepared Track IDs exist.
''',
    '''- R66 device evidence: a previously verified 4.2.1 Current-position writer can be queued behind GMMP worker contention for more than the old 1.5 s dispatch budget. Keep semantic readback verification strict, but allow a separate bounded 4 s native-dispatch wait and cancel work that has not started when that budget expires. A dispatch timeout is scheduling evidence, not permission to guess or invoke another writer. Track-Mix `no-matches-native-fallback` status is owned only after preplay preparation finishes; defer it while the pool is unresolved and suppress it when prepared Track IDs exist.
- GMMP reuses `menu_gm_context_file_browser` for both playable file rows and directory rows. Track Auto-DJ may be exposed there only when the bounded native menu callback graph proves the selected filesystem target is an existing regular file. An existing directory or unknown/ambiguous filesystem identity hides the action. Do not blanket-disable the file-browser menu because real file rows are device-accepted, and never traverse adapter collections/child rows to promote a folder.
'''
)

# Chronological device evidence belongs in the compatibility playbook.
path = "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
p = Path(path)
text = p.read_text().rstrip()
text += r'''

### 2026-10-17 R67 file-browser folder menu scope

Post-R66 device testing accepted Track Auto-DJ as robust across repeated starts from multiple playlists/queue contexts and file-browser files. The remaining UI defect was isolated to GMMP's Folder tab: `menu_gm_context_file_browser` is reused for both a real audio-file row and a directory row. The real-file path still resolves and starts Track Auto-DJ, while a directory click reaches the same GoneSmart action but has no unique single-track target and correctly fails closed at `MIX PREPLAY TARGET | resolved=false`.

R67 keeps the accepted real-file action but adds a pre-menu semantic gate. Only a bounded selected-row callback graph proving an existing regular filesystem file may expose Track Auto-DJ. A directory or unknown identity hides the action; adapter collections/child rows are deliberately not traversed so files inside a folder cannot authorize Track Auto-DJ on the folder itself. All playback, recommendation, refill and queue-mutation semantics remain unchanged.
'''
p.write_text(text + "\n")
