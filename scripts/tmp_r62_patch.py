from pathlib import Path

controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
text = controller_path.read_text()

old_log = 'awaiting native qr.z predecoder boundary'
new_log = 'awaiting native qr.z or current+1 predecoder boundary'
assert text.count(old_log) == 1, f'preplay log anchor count={text.count(old_log)}'
text = text.replace(old_log, new_log, 1)

guard_start = text.index('    private fun guardZeroPoolNaturalNextSource(')
guard_end = text.index('\n    /**', guard_start)
guard = text[guard_start:guard_end]
old_early = '        if (!request.zeroPoolBoundaryPrepared) return\n'
assert guard.count(old_early) == 1, f'guard early anchor count={guard.count(old_early)}'
guard = guard.replace(old_early, '', 1)
auto_marker = '        val autoDj = nativeAutoDj?.get() ?: return\n        val currentPosition = request.provisionalCurrentQueuePosition ?: return'
assert guard.count(auto_marker) == 1, f'guard autoDj anchor count={guard.count(auto_marker)}'
fallback = '''        val autoDj = nativeAutoDj?.get() ?: return

        // R62: GMMP does not deterministically issue a natural qr.z refill
        // before its first current+1 lookup. If that unique, already-proven
        // predecoder lookup arrives first, reuse the exact R60 seed-isolation
        // and native-fill sequence with a zero original refill count. This is
        // still before the original MusicService lookup/decoder creation; no
        // playback method is replayed after materialization.
        if (!request.zeroPoolBoundaryPrepared) {
            val fallbackBoundary = prepareZeroPoolAtNativeRefill(
                autoDj = autoDj,
                requestedTracks = 0
            ) ?: run {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL NEXT SOURCE PREPARE | " +
                        "predecoder Initial Size boundary unavailable; fail closed"
                )
                return
            }
            if (!completeZeroPoolAtNativeRefill(autoDj, fallbackBoundary)) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL NEXT SOURCE PREPARE | " +
                        "native Initial Size preparation failed; fail closed"
                )
                return
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL NEXT SOURCE PREPARE | verified=true | " +
                    "beforeOriginal=true | source=natural-current-plus-one | " +
                    "originalRequested=0 | nativeRequested=${fallbackBoundary.topUpCount}"
            )
        }

        val currentPosition = request.provisionalCurrentQueuePosition ?: return'''
guard = guard.replace(auto_marker, fallback, 1)
text = text[:guard_start] + guard + text[guard_end:]

complete_start = text.index('    fun completeZeroPoolAtNativeRefill(')
complete_end = text.index('\n    fun bootstrapSelectionForNativeRefill(', complete_start)
complete = text[complete_start:complete_end]
source_literal = '                    " | source=natural-native-refill | seed=${boundary.selectedTrackId}" +'
assert complete.count(source_literal) == 1, f'source anchor count={complete.count(source_literal)}'
log_marker = '            Log.i(\n                TAG,\n                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true" +'
assert complete.count(log_marker) == 1, f'log anchor count={complete.count(log_marker)}'
source_setup = '''            val boundarySource = if (boundary.originalRequested == 0) {
                "natural-current-plus-one-fallback"
            } else {
                "natural-native-refill"
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true" +'''
complete = complete.replace(log_marker, source_setup, 1)
complete = complete.replace(source_literal, '                    " | source=$boundarySource | seed=${boundary.selectedTrackId}" +', 1)
text = text[:complete_start] + complete + text[complete_end:]
controller_path.write_text(text)

test_path = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt')
test = test_path.read_text()
test_marker = '    @Test\n    fun `native refill boundary tops device observed three followers to initial five`() {'
assert test.count(test_marker) == 1, f'test anchor count={test.count(test_marker)}'
new_test = '''    @Test
    fun `natural current plus one can bootstrap initial size when natural refill was absent`() {
        val boundary = TrackMixZeroPoolPolicy.selectNextSourceBoundary(
            currentQueuePosition = 1,
            candidates = listOf(
                TrackMixZeroPoolPolicy.PositionCommandCandidate("next", 2, 100)
            )
        )
        assertEquals("next", boundary.key)
        assertEquals(1, boundary.matchCount)

        val refill = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 0)
        assertNotNull(refill)
        assertEquals(4, refill?.topUpCount)
    }

'''
test_path.write_text(test.replace(test_marker, new_test + test_marker, 1))

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
phrase = 'when the pool is empty, acquire the natural `qr.z(int)` boundary, isolate/rebase only the exact selected seed'
replacement = 'when the pool is empty, prefer the natural `qr.z(int)` boundary; if it does not occur before the first uniquely proven natural `current+1` lookup, use that lookup as the fallback predecoder trigger and then isolate/rebase only the exact selected seed'
assert agents.count(phrase) == 1, f'AGENTS phrase count={agents.count(phrase)}'
agents = agents.replace(phrase, replacement, 1)
agent_anchor = '- Never reintroduce a post-decode `MusicService(Int)->void` replay as a next-source refresh. R49 and the 2026-10-16 trace both disprove that model.'
assert agents.count(agent_anchor) == 1, f'AGENTS anchor count={agents.count(agent_anchor)}'
r62 = '- R62 device evidence proves the natural `qr.z(int)` refill is not guaranteed to occur before GMMP\'s first `current+1` lookup. A BUNT. run reached the unique natural `MusicService(Int)->void` current+1 boundary with the selected seed already Current at position 1 but no preceding `qr.z`; GMMP therefore materialized the stale Smart-Playlist follower and the later worker failed closed. When this exact one-shot current+1 boundary arrives first, synchronously reuse the same R60 seed-isolation/native-fill sequence with `originalRequested=0` before the original MusicService call proceeds. The nested fill still uses verified native `qr.z`, must produce and verify the dynamic Initial Size before decoder creation, and fails closed on ambiguous target/boundary/writer/fill state.\n'
agents_path.write_text(agents.replace(agent_anchor, r62 + agent_anchor, 1))

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text()
heading = '### 2026-10-17 R62 current+1 predecoder fallback'
assert heading not in playbook, 'playbook R62 already present'
append = '''

### 2026-10-17 R62 current+1 predecoder fallback

Device evidence shows that GMMP 4.2.1 does not deterministically issue a natural `qr.z(int)` refill before the first next-source lookup. In the failing BUNT. run the selected seed was already Current at logical position 1 and the unique natural `MusicService.I2(2)` current+1 boundary was observed, but no earlier `qr.z` occurred; GMMP therefore materialized the stale Smart-Playlist follower before Track Mix could prepare Initial Size. When that exact one-shot current+1 boundary arrives first, GoneSmart now reuses the same R60 seed-isolation/native-fill sequence synchronously with `originalRequested=0`. The full dynamic follower count is still requested through the verified native `qr.z` path and verified before returning to GMMP's original current+1 lookup. This is a predecoder fallback trigger, not a playback refresh: no MusicService/decoder method is replayed after materialization, and ambiguity still fails closed.
'''
playbook_path.write_text(playbook.rstrip() + append + '\n')
