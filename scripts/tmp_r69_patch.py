from pathlib import Path

controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
controller = controller_path.read_text()
controller = controller.replace(
    '    private val fileBrowserMenuResolver = TrackMixFileBrowserMenuResolver()\n',
    '',
    1,
)
old_gate = '''        if (name == FILE_BROWSER_MENU) {
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
'''
new_gate = '''        if (existing != null) {
            if (name == FILE_BROWSER_MENU) {
                scheduleFileBrowserMenuEligibility(menu, existing)
            } else {
                existing.isVisible = true
            }
            return
        }
'''
if old_gate not in controller:
    raise SystemExit('R68 file-browser gate not found')
controller = controller.replace(old_gate, new_gate, 1)
old_item = '''        item.setOnMenuItemClickListener {
            onMixClicked(context, name, menu, nativePlay, confirmation, label)
            true
        }

        val inserted = insertAfter(menu, item, nativeNext)
'''
new_item = '''        item.setOnMenuItemClickListener {
            onMixClicked(context, name, menu, nativePlay, confirmation, label)
            true
        }
        if (name == FILE_BROWSER_MENU) {
            scheduleFileBrowserMenuEligibility(menu, item)
        }

        val inserted = insertAfter(menu, item, nativeNext)
'''
if old_item not in controller:
    raise SystemExit('Track Mix menu item insertion anchor not found')
controller = controller.replace(old_item, new_item, 1)
helper_anchor = '''    private fun insertAfter(
        menu: Menu,
        item: MenuItem,
        anchor: MenuItem
    ): Boolean {
'''
helper = '''    /**
     * MenuInflater.inflate returns before GMMP's file-browser caller applies
     * the row-specific visibility state. Keep GoneSmart hidden during that
     * call stack, then classify the final native menu shape on the next main
     * loop turn. The exact 4.2.1 shapes are device-proven: directory rows have
     * eight visible native items, playable files have ten. This controls UI
     * exposure only; click-time pre-Play Track resolution remains mandatory.
     */
    private fun scheduleFileBrowserMenuEligibility(
        menu: Menu,
        item: MenuItem
    ) {
        item.isVisible = false
        main.post {
            val nativeVisibleItems = (0 until menu.size()).count { index ->
                val candidate = menu.getItem(index)
                candidate.itemId != MIX_ITEM_ID && candidate.isVisible
            }
            val selection = TrackMixFileBrowserMenuShapePolicy.resolve(
                nativeVisibleItems
            )
            val eligible =
                selection.kind == TrackMixFileBrowserSelectionKind.REGULAR_FILE
            item.isVisible = eligible
            Log.i(
                TAG,
                (if (eligible) "MIX MENU FILE BROWSER" else "MIX MENU SKIP") +
                    " | menu=$FILE_BROWSER_MENU" +
                    " | browserContext=${selection.kind.name.lowercase()}" +
                    " | evidence=${selection.evidence}" +
                    " | nativeVisible=$nativeVisibleItems" +
                    " | deferred=true"
            )
        }
    }

'''
if helper_anchor not in controller:
    raise SystemExit('insertAfter helper anchor not found')
controller = controller.replace(helper_anchor, helper + helper_anchor, 1)
controller_path.write_text(controller.rstrip('\n') + '\n')

resolver_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolver.kt')
resolver_path.write_text('''package io.github.alagga.gonesmart

internal enum class TrackMixFileBrowserSelectionKind {
    REGULAR_FILE,
    DIRECTORY,
    UNKNOWN
}

internal data class TrackMixFileBrowserSelection(
    val kind: TrackMixFileBrowserSelectionKind,
    val evidence: String
)

/**
 * GMMP 4.2.1 reuses menu_gm_context_file_browser for directories and playable
 * files, but its final native menu visibility differs deterministically after
 * the caller finishes configuring the row. Directory rows expose eight native
 * items; playable files expose ten. Any other cardinality fails closed.
 *
 * This is deliberately only a UI eligibility signal. Track Auto-DJ still has
 * to resolve one exact Track through the existing strong pre-Play resolver
 * before native Play can be dispatched.
 */
internal object TrackMixFileBrowserMenuShapePolicy {
    private const val GMMP_421_DIRECTORY_VISIBLE_ITEMS = 8
    private const val GMMP_421_SONG_VISIBLE_ITEMS = 10

    fun resolve(nativeVisibleItems: Int): TrackMixFileBrowserSelection =
        when (nativeVisibleItems) {
            GMMP_421_SONG_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.REGULAR_FILE,
                evidence = "gmmp421-native-song-menu-shape"
            )
            GMMP_421_DIRECTORY_VISIBLE_ITEMS -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.DIRECTORY,
                evidence = "gmmp421-native-directory-menu-shape"
            )
            else -> TrackMixFileBrowserSelection(
                kind = TrackMixFileBrowserSelectionKind.UNKNOWN,
                evidence = "unexpected-native-menu-shape"
            )
        }
}
''')

test_path = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixFileBrowserMenuResolverTest.kt')
test_path.write_text('''package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMixFileBrowserMenuResolverTest {
    @Test fun gmmp421SongMenuShapeIsEligible() {
        val result = TrackMixFileBrowserMenuShapePolicy.resolve(10)
        assertEquals(TrackMixFileBrowserSelectionKind.REGULAR_FILE, result.kind)
        assertEquals("gmmp421-native-song-menu-shape", result.evidence)
    }

    @Test fun gmmp421DirectoryMenuShapeIsNotEligible() {
        val result = TrackMixFileBrowserMenuShapePolicy.resolve(8)
        assertEquals(TrackMixFileBrowserSelectionKind.DIRECTORY, result.kind)
        assertEquals("gmmp421-native-directory-menu-shape", result.evidence)
    }

    @Test fun unknownNativeMenuShapesFailClosed() {
        listOf(0, 7, 9, 11, 12).forEach { count ->
            val result = TrackMixFileBrowserMenuShapePolicy.resolve(count)
            assertEquals(
                "count=$count",
                TrackMixFileBrowserSelectionKind.UNKNOWN,
                result.kind
            )
            assertEquals("unexpected-native-menu-shape", result.evidence)
        }
    }
}
''')

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
anchor = '- R68 device evidence corrects the R67 traversal restriction: on GMMP 4.2.1 the selected playable file model is itself nested behind a small native collection, so direct-object-only traversal reports `unknown` for both songs and folders. File-browser menu eligibility may traverse containers only with strict depth/object/item bounds and may accept only one unique filesystem target at the shallowest proven depth. A shallower directory blocks deeper child files; multiple distinct shallow paths are ambiguous and fail closed. This changes menu eligibility only, not Track Auto-DJ playback/refill semantics.\n'
addition = anchor + '- R69 evidence supersedes R68 as the accepted file-browser menu gate. Even with R68 expanded to 240 inspected objects, both song and directory menus still produced zero filesystem candidates, so that reflective scan is retired from the UI hot path. GMMP 4.2.1 itself exposes a stable final native menu shape after its caller configures the row: directory rows have exactly 8 visible native actions and playable-file rows have exactly 10. Because the MenuInflater hook runs before that caller-side visibility configuration, GoneSmart inserts Track Auto-DJ hidden and re-evaluates on the next main-loop turn; only the exact 10-item native shape may expose it, exact 8 stays hidden, and every other cardinality fails closed. The count is UI evidence only: click-time Track Auto-DJ still requires the existing strong pre-Play Track resolution before native Play, so menu shape never supplies Track identity or mutation authority.\n'
if anchor not in agents:
    raise SystemExit('AGENTS R68 anchor missing')
agents_path.write_text(agents.replace(anchor, addition, 1).rstrip('\n') + '\n')

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text().rstrip('\n')
section = '''

### 2026-10-17 R69 deferred native file-browser menu shape

The R68 device pass disproved the remaining filesystem-graph assumption: every tested file-browser row still ended as `browserContext=unknown | evidence=none | inspected=240`, including a real song. The same log gives a stronger host-owned discriminator. With GoneSmart hidden, the real song popup is 1440 px high while directory popups are 1152 px; the accepted pre-R67 trace shows the same two native shapes becoming 1584 px and 1296 px respectively when GoneSmart's single 144 px row was inserted. That establishes 10 visible native actions for a playable file and 8 for a directory on the accepted GMMP 4.2.1 build.

R69 therefore retires the unsuccessful reflective filesystem scan from the menu hot path. The MenuInflater hook first inserts Track Auto-DJ hidden, because GMMP applies row-specific native-item visibility only after `inflate(...)` returns. On the next main-loop turn GoneSmart counts only GMMP's visible native items, excluding its own row: exactly 10 exposes Track Auto-DJ, exactly 8 keeps it hidden, and every other cardinality fails closed. This signal grants UI exposure only. A click still runs the existing worker-side semantic resolver and must uniquely prove the selected Track from strong path/ID/title/artist evidence before native Play can be dispatched; playback, recommendation, queue and refill semantics are unchanged.
'''
if '### 2026-10-17 R69 deferred native file-browser menu shape' not in playbook:
    playbook += section
playbook_path.write_text(playbook.rstrip('\n') + '\n')
