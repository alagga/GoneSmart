from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


bridge_path = Path('app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt')
bridge = bridge_path.read_text()
bridge_marker = '''    /** R57 generic Track Mix seed isolation. This is the only queue
     * mutation owned by Track Mix before GMMP's native AUTO_DJ takes over. */
'''
bridge_insert = '''    /**
     * R71 natural next-source isolation. R70 proves MusicService.I2 only as
     * an event and independently proves the selected Current at its absolute
     * queue_position. At that boundary the original I2 caller is about to ask
     * for Current+1, so rebasing Current to 1 would require an unnecessary
     * playback-state write and would invalidate the coordinate just proved.
     *
     * Delete source history/followers only. The pinned Current row, its
     * queue_position and the verified native state coordinate remain exactly
     * unchanged. Native qr.z then owns the follower identities; the existing
     * anchored compactor may repair only their positions before I2 resumes.
     */
    fun isolateTrackMixZeroPoolSeedAnchored(
        selectedTrackId: Long,
        expectedCurrentPosition: Int
    ): Boolean {
        require(expectedCurrentPosition > 0) {
            "Anchored Track Mix Current position must be positive"
        }
        val resolved = resolve(requireDelete = true, requireStatePosition = true)
        val state = resolved.statePosition
            ?: error("Verified GMMP current-position writer unavailable")
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("Track Mix pinned seed queue entry unavailable")
        currentQueueEntryIdHint?.let { expected ->
            require(current.queueEntryId == expected) {
                "Track Mix pinned seed queue entry changed"
            }
        }
        require(current.track.id == selectedTrackId) {
            "Track Mix pinned seed Track changed before anchored isolation"
        }

        val oldState = state.read()
        require(
            current.queuePosition == expectedCurrentPosition &&
                oldState == expectedCurrentPosition
        ) {
            "Track Mix anchored Current queue/state mismatch: " +
                "row=${current.queuePosition} state=$oldState " +
                "expected=$expectedCurrentPosition"
        }
        val plan = TrackMixZeroPoolPolicy.seedPlan(
            entries = resolved.context.items.map { item ->
                TrackMixPlan.NativeQueueEntry(
                    queueId = item.queueEntryId,
                    trackId = item.track.id,
                    position = item.queuePosition
                )
            },
            currentEntryId = current.queueEntryId,
            selectedTrackId = selectedTrackId
        )
        require(plan.currentPosition == expectedCurrentPosition) {
            "Track Mix anchored seed plan changed Current coordinate"
        }

        val rowsById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        rowsById[current.queueEntryId]
            ?: error("Verified anchored Track Mix Current entity disappeared")
        val staleRows = (plan.pastEntryIds + plan.futureEntryIds).map { queueId ->
            rowsById[queueId]
                ?: error("Verified anchored Track Mix source row disappeared")
        }
        if (staleRows.isNotEmpty()) {
            val delete = resolved.delete
                ?: error("Verified GMMP Queue DAO delete unavailable")
            delete.invoke(
                resolved.dao,
                typedEntityArray(resolved.rows.first().javaClass, staleRows)
            )
        }

        val stateAfter = state.read()
        check(stateAfter == expectedCurrentPosition) {
            "Anchored Track Mix isolation changed native Current state"
        }
        val verified = GmmpQueueReader().read(
            autoDj,
            stateAfter,
            current.queueEntryId
        ) ?: error("Anchored Track Mix seed isolation verification unavailable")
        val only = verified.items.singleOrNull()
            ?: error("Anchored Track Mix seed isolation left extra native rows")
        check(
            only.queueEntryId == current.queueEntryId &&
                only.track.id == selectedTrackId &&
                only.queuePosition == expectedCurrentPosition &&
                only.state == QueueItemState.CURRENT &&
                verified.currentQueuePosition == expectedCurrentPosition
        ) {
            "Anchored Track Mix seed isolation changed Current identity/coordinate"
        }
        Log.i(
            TAG,
            "TRACK MIX SEED ISOLATION ANCHORED | verified=true" +
                " | currentEntry=${current.queueEntryId}" +
                " | currentPosition=$expectedCurrentPosition" +
                " | stateWrite=false | rebased=false" +
                " | deleted=${staleRows.size}"
        )
        return true
    }

'''
bridge = replace_once(bridge, bridge_marker, bridge_insert + bridge_marker, 'bridge insertion')
bridge_path.write_text(bridge)

controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
controller = controller_path.read_text()
old_isolation = '''            val isolated = GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = currentWriter,
                currentQueueEntryIdHint = current.queueEntryId
            ).isolateTrackMixZeroPoolSeed(target.id)
            check(isolated) {
                "Could not isolate selected Track Auto-DJ seed at Current boundary"
            }
'''
new_isolation = '''            val isolated = GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = currentWriter,
                currentQueueEntryIdHint = current.queueEntryId
            ).isolateTrackMixZeroPoolSeedAnchored(
                selectedTrackId = target.id,
                expectedCurrentPosition = position
            )
            check(isolated) {
                "Could not isolate selected Track Auto-DJ seed at anchored Current boundary"
            }
'''
controller = replace_once(controller, old_isolation, new_isolation, 'natural isolation call')
old_refill = '''            request.stage = "FILLING"
            request.transitionalRefillSuppressed = false
            check(requestHeldNativeRefill(refillCount)) {
                "GMMP rejected zero-pool native Initial Size refill"
            }

            val verified = queueReader.read(
'''
new_refill = '''            request.stage = "FILLING"
            request.transitionalRefillSuppressed = false
            check(requestHeldNativeRefill(refillCount)) {
                "GMMP rejected zero-pool native Initial Size refill"
            }
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    currentQueueEntryIdHint = current.queueEntryId
                ).compactTrackMixZeroPoolRefillAnchored(
                    selectedTrackId = target.id,
                    expectedSize = initial
                )
            ) {
                "Could not compact zero-pool native refill around anchored Current"
            }

            val verified = queueReader.read(
'''
controller = replace_once(controller, old_refill, new_refill, 'anchored refill compaction')
controller_path.write_text(controller)

test_path = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt')
test = test_path.read_text()
test_marker = '''    @Test
    fun `initial native refill can be compacted to one through Initial Size`() {
'''
test_insert = '''    @Test
    fun `r71 natural next source seed plan preserves absolute current anchor`() {
        val entries = (1..5).map { position ->
            TrackMixPlan.NativeQueueEntry(
                queueId = 100L + position,
                trackId = if (position == 2) 18165L else 20_000L + position,
                position = position
            )
        }
        val seed = TrackMixZeroPoolPolicy.seedPlan(
            entries = entries,
            currentEntryId = 102L,
            selectedTrackId = 18165L
        )
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = seed.currentPosition,
            currentQueuePosition = seed.currentPosition,
            writerReadback = seed.currentPosition
        )

        assertEquals(2, seed.currentPosition)
        assertEquals(listOf(101L), seed.pastEntryIds)
        assertEquals(listOf(103L, 104L, 105L), seed.futureEntryIds)
        assertNotNull(boundary)
        assertEquals(2, boundary!!.currentQueuePosition)
        assertEquals(3, boundary.nextQueuePosition)
    }

'''
test = replace_once(test, test_marker, test_insert + test_marker, 'regression test insertion')
test_path.write_text(test)

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
agents_marker = '\n## 6. Playlist / Smart-Playlist / UI rules\n'
agents_note = '''- R71 evidence fixes an implementation mismatch inside R70 rather than changing the accepted I2 proof. When the verified natural next-source boundary owns Current at an absolute position other than 1, zero-provider isolation must delete only the old source rows and preserve that exact Current queue_position/native state coordinate. Never call the position-1 seed rebase from this I2 path. After native qr.z supplies the followers, compact follower positions only around the unchanged anchor before the original I2 resumes and asks for Current+1. The native-refill/Queue-tail position-1 path remains unchanged. A Current-state writer dispatch is therefore neither required nor permitted merely to prepare the verified I2 boundary. Device re-acceptance is required.\n'''
if 'R71 evidence fixes an implementation mismatch inside R70' in agents:
    raise SystemExit('AGENTS already contains R71 note')
agents = replace_once(agents, agents_marker, '\n' + agents_note + agents_marker, 'AGENTS R71 insertion')
agents_path.write_text(agents)

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text().rstrip() + '\n'
if '### 2026-10-17 R71 anchored I2 zero-pool isolation' in playbook:
    raise SystemExit('playbook already contains R71 section')
playbook += '''\n### 2026-10-17 R71 anchored I2 zero-pool isolation\n\nThe first R70 device pass proves its new coordinate detection was correct but exposes an implementation mismatch downstream. For Track `18165`, GoneSmart proved the selected row, native Current and verified writer/readback all at absolute `queue_position=2`, then correctly derived `nextPosition=3`. The natural-boundary path nevertheless reused the older generic seed-isolation helper, which rewrites Current to position 1 and therefore attempted a controlled `dx3.c2(1)` state write. That dispatch was queued behind GMMP's executor (`started=false`) and timed out; the original `I2` then resumed, queried position 3 and received `next audio source is null`. A later file-browser invocation succeeded through the independent native-refill path specifically because its seed was already position 1 and no state write was required.\n\nR71 restores the R70 ownership contract instead of weakening writer dispatch. At the accepted natural `MusicService.I2(int)` event, after exact Current/target/writer agreement, GoneSmart removes only the old source history/followers and preserves the selected Current row at its proven absolute queue position. It never rewrites that row or native Current state. Native `qr.z` supplies the `Initial Size - 1` follower identities; before the original I2 resumes, the existing anchored position-only compactor normalizes those follower rows to `Current+1..Current+N` while preserving queue IDs, Track IDs and Current state. The existing native-refill and Queue-tail fallback keep the accepted position-1 invariant and are unchanged. No MusicService replay, decoder mutation or executor-inline writer exception is introduced. Device re-acceptance should show `TRACK MIX SEED ISOLATION ANCHORED ... currentPosition=2 | stateWrite=false | rebased=false`, anchored native refill compaction, `MIX ZERO-POOL NEXT SOURCE PREPARE ... currentPosition=2 | nextPosition=3`, no `QUEUE POSITION DISPATCH TIMEOUT`, and final `MIX VERIFIED`.\n'''
playbook_path.write_text(playbook)
