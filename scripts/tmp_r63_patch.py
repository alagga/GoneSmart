from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    assert count == 1, f"{label} anchor count={count}"
    return text.replace(old, new, 1)


controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
controller = controller_path.read_text()
controller_anchor = '''        var currentSnapshot = loaded
        var availableUpcoming =
            (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
'''
controller_replacement = '''        var currentSnapshot = loaded
        if (
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = request.source,
                legacyQueue = false
            )
        ) {
            request.stage = "ISOLATING_PREPARED"
            val autoDj = nativeAutoDj?.get() ?: run {
                fail("Could not access Auto-DJ queue.", request.context)
                return null
            }
            val verifiedWriter = nativePositionWriterProvider(autoDj)
                ?: nativeTrackMixPositionWriterProvider(autoDj, selectedQueuePosition)
                ?: run {
                    Log.e(
                        TAG,
                        "MIX PREPLAY PREPARED SEED | verified Current-position writer unavailable"
                    )
                    fail("Could not isolate the selected queue song safely.", request.context)
                    return null
                }
            val isolated = runCatching {
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedWriter,
                    currentQueueEntryIdHint = selectedEntryId
                ).isolateTrackMixSeed(selectedId)
            }.onFailure {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED SEED | queue-context seed isolation failed",
                    it
                )
            }.getOrDefault(false)
            if (!isolated) {
                fail("Could not isolate the selected queue song safely.", request.context)
                return null
            }
            currentSnapshot = queueSnapshot(selectedEntryId)?.takeIf {
                it.currentId == selectedId &&
                    it.currentEntryId == selectedEntryId &&
                    it.currentQueuePosition == 1 &&
                    it.ids.size == 1
            } ?: run {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED SEED | isolated seed postcondition failed"
                )
                fail("Could not verify the isolated queue song.", request.context)
                return null
            }
            request.provisionalCurrentQueuePosition = 1
            request.stage = "WAIT_PLAY"
            Log.i(
                TAG,
                "MIX PREPLAY PREPARED SEED | isolated=true | source=queue-context | " +
                    "seed=$selectedId | entry=$selectedEntryId | " +
                    "oldCurrentPosition=$selectedQueuePosition | currentPosition=1 | " +
                    "removed=${(loaded.ids.size - 1).coerceAtLeast(0)}"
            )
        }
        var availableUpcoming =
            (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
'''
controller = replace_once(
    controller,
    controller_anchor,
    controller_replacement,
    'controller prepared queue isolation'
)
controller_path.write_text(controller)

policy_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt')
policy = policy_path.read_text()
policy_anchor = '''    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)
'''
policy_replacement = '''    fun requiresPreparedSeedIsolation(
        source: String,
        legacyQueue: Boolean
    ): Boolean {
        // R63: a Queue-context Play moves Current inside the existing queue.
        // Prepared Smart bootstrap must first restore the 4.2.0 one-seed
        // position-1 invariant or old followers survive ahead of the new pool.
        return !legacyQueue && source == "menu_gm_context_queue"
    }

    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)
'''
policy = replace_once(policy, policy_anchor, policy_replacement, 'policy isolation gate')
policy_path.write_text(policy)

test_path = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt')
test = test_path.read_text()
test_anchor = '''    @Test fun slotRefillRequestsOnlyRealShortfall() {
'''
new_test = '''    @Test fun preparedQueueContextRestoresOneSeedBeforeBootstrap() {
        assertTrue(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_queue",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_playlist_details",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = "menu_gm_context_queue",
                legacyQueue = true
            )
        )
    }

    @Test fun slotRefillRequestsOnlyRealShortfall() {
'''
test = replace_once(test, test_anchor, new_test, 'test insertion')
test_path.write_text(test)

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
agents_anchor = '- Never reintroduce a post-decode `MusicService(Int)->void` replay as a next-source refresh. R49 and the 2026-10-16 trace both disprove that model.'
r63 = '- R63 device evidence closes the prepared-pool Queue-context gap. When native Queue Play selected Green Day from an existing five-row queue, the target was independently verified as Current at absolute position 4, but the prepared Smart path kept the old row at position 5 and requested only the three missing followers; the three prepared tracks were therefore appended while the stale follower survived, and verification failed (`expected=1470`, `actual=12819`). For `menu_gm_context_queue` with a non-empty prepared pool, first isolate/rebase the exact verified selected queue entry to the one-row position-1 invariant through the already-proven native queue/current writers, then request the full dynamic `Initial Size - 1` through the ordinary `qr.z` bootstrap. Keep non-Queue prepared starts unchanged.\n'
agents = replace_once(agents, agents_anchor, r63 + agents_anchor, 'AGENTS R63')
agents_path.write_text(agents)

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text()
heading = '### 2026-10-17 R63 prepared Queue seed isolation'
assert heading not in playbook, 'playbook R63 already present'
append = '''\n\n### 2026-10-17 R63 prepared Queue seed isolation\n\nThe first post-R62 Queue-context test exposed a prepared-pool-specific semantic gap rather than another decoder timing failure. Native Queue Play correctly selected Green Day and GoneSmart independently pinned the exact queue entry, but Current remained at absolute position 4 of the existing five-row queue. The prepared bootstrap path treated the old position-5 row as an available upcoming slot, requested only three additional rows, and appended three prepared Smart tracks. Verification then correctly failed because the first post-seed slot still contained the stale prior-queue track (`expected=1470`, `actual=12819`). Queue-context prepared starts now restore the accepted 4.2.0 invariant before bootstrap: isolate the exact pinned selected entry, rebase it to logical position 1, verify a one-row queue, then request the full dynamic `Initial Size - 1` through the existing native `qr.z` selection hook. Non-Queue prepared starts are unchanged, and no playback/decoder replay is introduced.\n'''
playbook_path.write_text(playbook.rstrip() + append + '\n')
