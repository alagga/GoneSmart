from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    assert count == 1, f"{label}: anchor count={count}"
    p.write_text(text.replace(old, new, 1))


controller = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
replace_once(
    controller,
    '''        var currentSnapshot = loaded
        if (
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = request.source,
                legacyQueue = false
            )
        ) {
''',
    '''        var currentSnapshot = loaded
        val preparedSeedIsolationRequired =
            TrackMixInitialFillPolicy.requiresPreparedSeedIsolation(
                source = request.source,
                legacyQueue = false
            )
        if (preparedSeedIsolationRequired) {
''',
    "prepared seed isolation flag",
)

replace_once(
    controller,
    '''        request.stage = "PRUNE"
        val autoDj = nativeAutoDj?.get() ?: run {
            fail("Could not access Auto-DJ queue.", request.context)
            return null
        }
        val pruned = runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj),
                currentQueueEntryIdHint = selectedEntryId
            ).retargetTrackMixUpcoming(
''',
    '''        val autoDj = nativeAutoDj?.get() ?: run {
            fail("Could not access Auto-DJ queue.", request.context)
            return null
        }
        val verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ?: if (preparedSeedIsolationRequired) {
                nativeTrackMixPositionWriterProvider(autoDj, 1)
            } else {
                null
            }

        // R65: R63 correctly isolates/rebases a Queue-context prepared seed to
        // position 1, but GMMP's hidden append allocator may still materialize
        // the four prepared followers at old high coordinates (for example
        // 7216..7219). The Track IDs/order are already correct at this point.
        // Compact only queue_position through the accepted native O0 writer,
        // preserve queue IDs/Track IDs/Current state, verify 1..Initial Size,
        // and only then run the unchanged prune/final verification.
        if (preparedSeedIsolationRequired) {
            request.stage = "NORMALIZE_PREPARED"
            val normalized = runCatching {
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedPositionWriter,
                    currentQueueEntryIdHint = selectedEntryId
                ).compactTrackMixPreparedRefillAnchored(
                    selectedTrackId = selectedId,
                    expectedSize = initial
                )
            }.onFailure {
                Log.e(
                    TAG,
                    "MIX PREPLAY PREPARED POSITIONS | native coordinate normalization failed",
                    it
                )
            }.getOrDefault(false)
            if (!normalized) {
                fail("Could not normalize the prepared Track Auto-DJ queue.", request.context)
                return null
            }
            currentSnapshot = queueSnapshot(selectedEntryId)?.takeIf {
                it.currentId == selectedId &&
                    it.currentEntryId == selectedEntryId &&
                    it.currentQueuePosition == 1 &&
                    it.ids.size == initial
            } ?: run {
                fail("Could not verify the normalized Track Auto-DJ queue.", request.context)
                return null
            }
            Log.i(
                TAG,
                "MIX PREPLAY PREPARED POSITIONS | verified=true | " +
                    "seed=$selectedId | entry=$selectedEntryId | " +
                    "initial=$initial | currentPosition=1 | positions=1..$initial"
            )
        }

        request.stage = "PRUNE"
        val pruned = runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = verifiedPositionWriter,
                currentQueueEntryIdHint = selectedEntryId
            ).retargetTrackMixUpcoming(
''',
    "prepared queue coordinate normalization",
)

bridge = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
replace_once(
    bridge,
    '''    /**
     * Settled zero-pool cleanup only. Native qr.z owns the fallback Track IDs;
''',
    '''    /**
     * R65 prepared Queue-context bootstrap uses the same predecoder-safe,
     * position-only compaction as R56. The historical zero-pool helper already
     * preserves queue IDs, Track IDs and Current state and only rewrites the
     * verified native queue_position fields relative to the pinned Current.
     */
    fun compactTrackMixPreparedRefillAnchored(
        selectedTrackId: Long,
        expectedSize: Int
    ): Boolean = compactTrackMixZeroPoolRefillAnchored(
        selectedTrackId = selectedTrackId,
        expectedSize = expectedSize
    )

    /**
     * Settled zero-pool cleanup only. Native qr.z owns the fallback Track IDs;
''',
    "prepared refill compaction alias",
)

test = "app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt"
replace_once(
    test,
    '''    @Test
    fun `late source rebuild restores exactly the predecode native fallback identities`() {
''',
    '''    @Test
    fun `prepared queue append gaps compact before native retarget prune`() {
        val gapped = listOf(
            TrackMixPlan.NativeQueueEntry(50, 6354, 1),
            TrackMixPlan.NativeQueueEntry(51, 7022, 7216),
            TrackMixPlan.NativeQueueEntry(52, 14339, 7217),
            TrackMixPlan.NativeQueueEntry(53, 6270, 7218),
            TrackMixPlan.NativeQueueEntry(54, 14338, 7219)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.planNativeRetarget(
                entries = gapped,
                currentPosition = 1,
                selectedTrackId = 6354,
                targetQueueSize = 5,
                smartTrackIds = emptyList()
            )
        }

        val rebase = TrackMixZeroPoolPolicy.rebasePlan(gapped, 50, 5)
        val compact = gapped.map { entry ->
            entry.copy(position = rebase.positionsByEntryId.getValue(entry.queueId))
        }
        val plan = TrackMixPlan.planNativeRetarget(
            entries = compact,
            currentPosition = 1,
            selectedTrackId = 6354,
            targetQueueSize = 5,
            smartTrackIds = emptyList()
        )
        assertEquals(listOf(6354L, 7022L, 14339L, 6270L, 14338L), plan.expectedTrackIds)
        assertEquals(emptyList<Long>(), plan.removeEntryIds)
    }

    @Test
    fun `late source rebuild restores exactly the predecode native fallback identities`() {
''',
    "prepared queue gap regression",
)

agents = "AGENTS.md"
replace_once(
    agents,
    '''- Do not implement this late-source protection as polling or an AudioPlayer/decoder mutation. The native queue batch remains GMMP-owned; GoneSmart only preserves already-selected identities before materialization, and the later five-row cleanup may only restore the same IDs.
''',
    '''- Do not implement this late-source protection as polling or an AudioPlayer/decoder mutation. The native queue batch remains GMMP-owned; GoneSmart only preserves already-selected identities before materialization, and the later five-row cleanup may only restore the same IDs.
- R65 device evidence narrows the prepared Queue-context failure after R63 to coordinates, not recommendation identity. Both tested Queue starts correctly resolved the selected native row, isolated/rebased it to Current position 1, materialized four prepared GoneSmart Track IDs and produced a five-row queue; finalization then failed only because GMMP's hidden append allocator assigned the new follower rows old/high `queue_position` values, so `planNativeRetarget` rejected them as non-contiguous. For an R63 prepared Queue seed only, compact those already-materialized native rows to `1..Initial Size` through the accepted position-only `O0(List)` writer before the unchanged prune/final verification. Preserve queue IDs, Track IDs and Current state; do not reselect recommendations, replay playback or infer an append allocator. Device re-acceptance remains required.
''',
    "agents r65 evidence",
)

playbook = Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md")
text = playbook.read_text().rstrip()
text += '''\n\n### 2026-10-17 R65 prepared Queue coordinate normalization\n\nThe first R64 combined device pass accepted the BUNT. zero-provider path end-to-end, including a real non-null CURRENT+1 AudioSource and final five-row `MIX VERIFIED`. Two subsequent `menu_gm_context_queue` starts also proved that R63 now selects and materializes the correct recommendation identities: the selected Queue row was pinned, isolated/rebased to Current position 1, and four prepared Smart Track IDs were inserted. Both runs failed later and identically at `planNativeRetarget` with `Native upcoming queue positions are not contiguous`. The native queue already had the correct five Track IDs/order; only GMMP's hidden append coordinates remained high/stale (the second run exposed Current at 7215 before isolation). R65 therefore reuses the accepted position-only anchored compaction immediately after the prepared bootstrap and before prune, normalizing the already-correct five native rows to `1..Initial Size` without changing queue IDs, Track IDs, Current identity/state or playback. No recommendation or decoder behavior changes. One Queue-context device re-acceptance is still required.\n'''
playbook.write_text(text)
