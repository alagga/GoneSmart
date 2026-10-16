from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:100]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"non-unique patch anchor in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


def insert_before(path: str, marker: str, addition: str) -> None:
    replace_once(path, marker, addition + marker)


controller = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
bridge = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
module = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
policy = "app/src/main/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicy.kt"
test = "app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt"

insert_before(
    controller,
    "    private data class ObservedNativePositionCommand(\n",
    """    internal data class ZeroPoolNativeRefillBoundary(\n        val token: Long,\n        val selectedTrackId: Long,\n        val selectedEntryId: Long,\n        val currentPosition: Int,\n        val initialSize: Int,\n        val originalRequested: Int,\n        val topUpCount: Int\n    )\n\n"""
)

replace_once(
    controller,
    """    fun nativeRefillAction(): TrackMixInitialFillPolicy.NativeRefillAction {\n        val request = pending\n        val explicitAllowance = (nativeRefillAllowance.get() ?: 0) > 0\n""",
    """    fun nativeRefillAction(): TrackMixInitialFillPolicy.NativeRefillAction {\n        val request = pending\n        if (request?.stage == \"ZERO_POOL_NATIVE_FILL\") {\n            return TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE\n        }\n        val explicitAllowance = (nativeRefillAllowance.get() ?: 0) > 0\n"""
)

replace_once(
    controller,
    """                if (\n                    request.prePlayTarget != null &&\n                    request.prePlayPrepared.trackIds.isEmpty() &&\n                    request.prePlayInitialSize > 1\n                ) {\n                    activePendingController = WeakReference(this@TrackMixController)\n                }\n\n""",
    """                // R56: zero-provider startup is anchored at GMMP's natural\n                // qr.z refill (or the exact accepted Queue-row target), not at\n                // an inferred dx3/current-state writer.\n\n"""
)

insert_before(
    controller,
    "    fun bootstrapSelectionForNativeRefill(\n",
    r'''    fun prepareZeroPoolAtNativeRefill(
        autoDj: Any,
        requestedTracks: Int
    ): ZeroPoolNativeRefillBoundary? {
        val request = pending ?: return null
        if (
            request.stage != "WAIT_PLAY" ||
            request.prePlayTarget == null ||
            request.prePlayPrepared.trackIds.isNotEmpty() ||
            request.prePlayInitialSize <= 1 ||
            request.zeroPoolBoundaryPrepared ||
            request.zeroPoolBoundaryAttempted ||
            nativeAutoDj?.get() !== autoDj
        ) return null

        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(
            initialSize = request.prePlayInitialSize,
            originalRequested = requestedTracks
        ) ?: return null
        val target = request.prePlayTarget ?: return null
        val located = queueReader.locateUniqueTrackRow(autoDj, target.id) ?: return null

        request.zeroPoolBoundaryAttempted = true
        request.verifiedCurrentQueueEntryId = located.queueEntryId
        request.provisionalCurrentQueuePosition = located.queuePosition
        request.stage = "ZERO_POOL_NATIVE_FILL"
        return try {
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    currentQueueEntryIdHint = located.queueEntryId
                ).isolateTrackMixZeroPoolSeed(target.id)
            ) {
                "Could not isolate selected Track Auto-DJ seed before native refill"
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | isolated=true | beforeOriginal=true" +
                    " | seed=${target.id} | entry=${located.queueEntryId}" +
                    " | currentPosition=${located.queuePosition}" +
                    " | originalRequested=$requestedTracks | topUp=${plan.topUpCount}"
            )
            ZeroPoolNativeRefillBoundary(
                token = request.token,
                selectedTrackId = target.id,
                selectedEntryId = located.queueEntryId,
                currentPosition = located.queuePosition,
                initialSize = request.prePlayInitialSize,
                originalRequested = requestedTracks,
                topUpCount = plan.topUpCount
            )
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            request.zeroPoolBoundaryAttempted = false
            Log.e(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | seed isolation failed; natural refill continues",
                failure
            )
            null
        }
    }

    fun completeZeroPoolAtNativeRefill(
        autoDj: Any,
        boundary: ZeroPoolNativeRefillBoundary
    ): Boolean {
        val request = pending ?: return false
        if (request.token != boundary.token || nativeAutoDj?.get() !== autoDj) return false
        if (request.stage != "ZERO_POOL_NATIVE_FILL") return false
        return try {
            if (boundary.topUpCount > 0) {
                check(requestHeldNativeRefill(boundary.topUpCount)) {
                    "GMMP rejected zero-pool Initial Size top-up"
                }
            }
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    currentQueueEntryIdHint = boundary.selectedEntryId
                ).compactTrackMixZeroPoolRefillAnchored(
                    selectedTrackId = boundary.selectedTrackId,
                    expectedSize = boundary.initialSize
                )
            ) {
                "Could not compact native zero-pool refill around selected seed"
            }

            val verified = queueReader.read(
                autoDj,
                currentQueueEntryIdHint = boundary.selectedEntryId
            ) ?: error("Zero-pool native refill verification unavailable")
            val ordered = verified.items.sortedBy { it.queuePosition }
            check(
                ordered.size == boundary.initialSize &&
                    ordered.firstOrNull()?.queueEntryId == boundary.selectedEntryId &&
                    ordered.firstOrNull()?.track?.id == boundary.selectedTrackId &&
                    ordered.firstOrNull()?.queuePosition == boundary.currentPosition &&
                    ordered.map { it.queuePosition } ==
                        (boundary.currentPosition until
                            boundary.currentPosition + boundary.initialSize).toList()
            ) {
                "Zero-pool native refill did not produce contiguous Initial Size"
            }

            val nativeFallbackTrackIds = ordered.drop(1).map { it.track.id }
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = boundary.selectedTrackId,
                nativeFallbackTrackIds = nativeFallbackTrackIds,
                expectedSize = boundary.initialSize
            )
            request.zeroPoolNativeFallbackTrackIds = nativeFallbackTrackIds
            check(ensureZeroPoolSourceBatchHooks(autoDj)) {
                "Verified native Queue DAO source-batch hooks are unavailable"
            }
            request.provisionalCurrentQueuePosition = boundary.currentPosition
            request.zeroPoolBoundaryPrepared = true
            request.stage = "WAIT_PLAY"
            Log.i(
                TAG,
                "MIX ZERO-POOL BOUNDARY | verified=true | beforeDecoder=true" +
                    " | source=natural-native-refill | seed=${boundary.selectedTrackId}" +
                    " | initial=${boundary.initialSize}" +
                    " | nativeGenerated=${nativeFallbackTrackIds.size}" +
                    " | currentPosition=${boundary.currentPosition}" +
                    " | originalRequested=${boundary.originalRequested}" +
                    " | topUp=${boundary.topUpCount}"
            )
            true
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            Log.e(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | native fill verification failed; fail closed",
                failure
            )
            false
        }
    }

'''
)

replace_once(
    controller,
    """                if (request.prePlayPrepared.trackIds.isEmpty()) {\n                    filled = fillZeroPoolNativeFallback(\n""",
    """                if (request.prePlayPrepared.trackIds.isEmpty()) {\n                    if (!request.zeroPoolBoundaryPrepared &&\n                        request.source == \"menu_gm_context_queue\"\n                    ) {\n                        val autoDj = nativeAutoDj?.get() ?: run {\n                            fail(\"Could not access Auto-DJ queue.\", request.context)\n                            return\n                        }\n                        val boundary = prepareZeroPoolAtNativeRefill(\n                            autoDj = autoDj,\n                            requestedTracks = 0\n                        )\n                        if (boundary == null ||\n                            !completeZeroPoolAtNativeRefill(autoDj, boundary)\n                        ) {\n                            fail(\n                                \"Could not prepare the selected queue song for native Auto-DJ.\",\n                                request.context\n                            )\n                            return\n                        }\n                    }\n                    filled = fillZeroPoolNativeFallback(\n"""
)

replace_once(
    controller,
    """            val currentEntryHint = targetIdentity?.queueEntryId\n                ?: if (transitionRebindActive) transitionIdentity?.queueEntryId else null\n            val current = queueSnapshot(\n""",
    r'''            val currentEntryHint = targetIdentity?.queueEntryId
                ?: if (transitionRebindActive) transitionIdentity?.queueEntryId else null

            if (
                targetIdentity == null &&
                request.source == "menu_gm_context_queue" &&
                request.nativePlayAccepted
            ) {
                val autoDj = nativeAutoDj?.get()
                val target = request.prePlayTarget
                if (autoDj != null && target != null) {
                    val located = queueReader.locateUniqueTrackRow(autoDj, target.id)
                    if (located != null) {
                        val pinned = queueReader.read(
                            autoDj,
                            currentQueueEntryIdHint = located.queueEntryId
                        )
                        val pinnedIndex = pinned?.items?.indexOfFirst {
                            it.queueEntryId == located.queueEntryId &&
                                it.track.id == target.id
                        } ?: -1
                        if (pinned != null && pinnedIndex >= 0) {
                            val snapshot = Snapshot(
                                ids = pinned.items.map { it.track.id },
                                currentIndex = pinnedIndex,
                                entryIds = pinned.items.map { it.queueEntryId },
                                currentResolutionSource = "queue-context-target",
                                currentQueuePosition = located.queuePosition
                            )
                            request.provisionalCurrentQueuePosition = located.queuePosition
                            Log.i(
                                TAG,
                                "MIX PLAY VERIFIED | source=queue-context-target" +
                                    " | targetEntry=${located.queueEntryId}" +
                                    " | targetTrack=${target.id}" +
                                    " | queueSize=${snapshot.ids.size}" +
                                    " | currentPosition=${located.queuePosition}" +
                                    " | evidence=exact-native-menu-play"
                            )
                            return LoadedPlay(snapshot, target.id)
                        }
                    }
                }
            }

            val current = queueSnapshot(
'''
)

# Replace R54 seed isolation with pinned-entry isolation and add predecoder
# follower compaction that never writes playback state.
p = Path(bridge)
text = p.read_text()
start = text.index("    fun isolateTrackMixZeroPoolSeed(selectedTrackId: Long): Boolean {\n")
end = text.index("    /**\n     * Settled zero-pool cleanup only.", start)
new = r'''    fun isolateTrackMixZeroPoolSeed(selectedTrackId: Long): Boolean {
        val resolved = resolve(requireDelete = true, requireStatePosition = false)
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("Track Mix pinned seed queue entry unavailable")
        currentQueueEntryIdHint?.let { expected ->
            require(current.queueEntryId == expected) {
                "Track Mix pinned seed queue entry changed"
            }
        }
        require(current.track.id == selectedTrackId) {
            "Track Mix pinned seed Track changed before isolation"
        }
        val rowsById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val staleRows = resolved.context.items
            .filter { it.queueEntryId != current.queueEntryId }
            .map { item ->
                rowsById[item.queueEntryId]
                    ?: error("Verified Track Mix source row disappeared")
            }
        if (staleRows.isNotEmpty()) {
            val delete = resolved.delete
                ?: error("Verified GMMP Queue DAO delete unavailable")
            delete.invoke(
                resolved.dao,
                typedEntityArray(resolved.rows.first().javaClass, staleRows)
            )
        }
        val verified = GmmpQueueReader().read(
            autoDj,
            currentQueueEntryIdHint = current.queueEntryId
        ) ?: error("Zero-pool seed isolation verification unavailable")
        val only = verified.items.singleOrNull()
            ?: error("Zero-pool seed isolation left extra native rows")
        check(
            only.queueEntryId == current.queueEntryId &&
                only.track.id == selectedTrackId &&
                only.queuePosition == current.queuePosition
        ) {
            "Zero-pool seed isolation did not preserve exact pinned seed"
        }
        Log.i(
            TAG,
            "TRACK MIX ZERO-POOL SEED | verified=true" +
                " | currentEntry=${current.queueEntryId}" +
                " | currentPosition=${current.queuePosition}" +
                " | anchored=true | stateWrite=false" +
                " | deleted=${staleRows.size}"
        )
        return true
    }

    /** R56: compact only native follower positions before the qr.z caller
     * resumes and asks for CURRENT+1. The selected absolute playback position
     * and all Track IDs remain native-owned. */
    fun compactTrackMixZeroPoolRefillAnchored(
        selectedTrackId: Long,
        expectedSize: Int
    ): Boolean {
        val resolved = resolve(requireDelete = false, requireStatePosition = false)
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("Track Mix pinned seed unavailable after native refill")
        currentQueueEntryIdHint?.let { expected ->
            require(current.queueEntryId == expected)
        }
        require(current.track.id == selectedTrackId)
        require(resolved.context.items.size == expectedSize) {
            "Native zero-pool refill size mismatch: ${resolved.context.items.size}/$expectedSize"
        }
        require(current.queuePosition > 0 &&
            current.queuePosition <= Int.MAX_VALUE - (expectedSize - 1))

        val ordered = resolved.context.items.sortedBy { it.queuePosition }
        val desiredOrder = listOf(current) + ordered.filter {
            it.queueEntryId != current.queueEntryId
        }
        val rowsById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val changed = arrayListOf<Any>()
        desiredOrder.forEachIndexed { index, item ->
            val desired = current.queuePosition + index
            val row = rowsById[item.queueEntryId]
                ?: error("Native refill row disappeared before compaction")
            if (number(resolved.position, row).toInt() != desired) {
                setInt(resolved.position, row, desired)
                changed += row
            }
        }
        if (changed.isNotEmpty()) {
            resolved.update.invoke(resolved.dao, ArrayList(changed))
        }
        val verified = GmmpQueueReader().read(
            autoDj,
            currentQueueEntryIdHint = current.queueEntryId
        ) ?: error("Anchored native refill verification unavailable")
        val verifyOrdered = verified.items.sortedBy { it.queuePosition }
        check(
            verifyOrdered.size == expectedSize &&
                verifyOrdered.first().queueEntryId == current.queueEntryId &&
                verifyOrdered.first().track.id == selectedTrackId &&
                verifyOrdered.map { it.queuePosition } ==
                    (current.queuePosition until current.queuePosition + expectedSize).toList()
        ) {
            "Anchored native refill positions failed verification"
        }
        Log.i(
            TAG,
            "TRACK MIX ZERO-POOL NATIVE REFILL | verified=true" +
                " | size=$expectedSize | changed=${changed.size}" +
                " | currentPosition=${current.queuePosition} | stateWrite=false"
        )
        return true
    }

'''
p.write_text(text[:start] + new + text[end:])

replace_once(
    module,
    """                val trackMixBootstrap =\n                    trackMixController.bootstrapSelectionForNativeRefill(\n                        autoDj = autoDjInstance,\n                        requested = requestedTracks\n                    )\n""",
    """                val zeroPoolBoundary =\n                    trackMixController.prepareZeroPoolAtNativeRefill(\n                        autoDj = autoDjInstance,\n                        requestedTracks = requestedTracks\n                    )\n                if (zeroPoolBoundary != null) {\n                    val zeroPoolResult = chain.proceed()\n                    val completed = trackMixController.completeZeroPoolAtNativeRefill(\n                        autoDj = autoDjInstance,\n                        boundary = zeroPoolBoundary\n                    )\n                    if (!completed) {\n                        Log.e(\n                            \"GoneSmartTrackMix\",\n                            \"MIX ZERO-POOL REFILL BOUNDARY | completion failed after native qr.z\"\n                        )\n                    }\n                    return@intercept zeroPoolResult\n                }\n\n                val trackMixBootstrap =\n                    trackMixController.bootstrapSelectionForNativeRefill(\n                        autoDj = autoDjInstance,\n                        requested = requestedTracks\n                    )\n"""
)

insert_before(
    policy,
    "    data class NaturalNextSourceGuardPlan(\n",
    r'''    data class NativeRefillBoundaryPlan(
        val topUpCount: Int
    )

    fun nativeRefillBoundaryPlan(
        initialSize: Int,
        originalRequested: Int
    ): NativeRefillBoundaryPlan? {
        if (initialSize <= 1 || originalRequested < 0) return null
        val requiredFollowers = initialSize - 1
        if (originalRequested > requiredFollowers) return null
        return NativeRefillBoundaryPlan(
            topUpCount = requiredFollowers - originalRequested
        )
    }

'''
)

p = Path(test)
text = p.read_text()
idx = text.rfind("\n}")
if idx < 0:
    raise SystemExit("test class closing brace not found")
addition = r'''

    @Test
    fun `native refill boundary tops device observed three followers to initial five`() {
        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 3)
        assertNotNull(plan)
        assertEquals(1, plan?.topUpCount)
    }

    @Test
    fun `queue boundary requests all followers when no natural refill happened`() {
        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 0)
        assertNotNull(plan)
        assertEquals(4, plan?.topUpCount)
    }

    @Test
    fun `native refill boundary rejects overfill`() {
        assertNull(TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 5))
    }
'''
p.write_text(text[:idx] + addition + text[idx:])

with Path("AGENTS.md").open("a") as f:
    f.write("""

## GMMP 4.2.1 Track Auto-DJ natural-refill invariant (R56)
- R54 device evidence retires the inferred `dx3.c2(int)` CURRENT boundary as a zero-provider prerequisite: the state call arrived before the selected BUNT. row existed in `queue_table`.
- Restore the 4.2.0 ownership shape: native Play -> exact selected seed isolation -> native GMMP Auto-DJ fill -> Initial Size verification.
- The 4.2.1 predecoder port point is the natural `qr.z(count)` refill. Before its original body, isolate the uniquely pre-resolved selected row without changing its absolute playback coordinate. Let GMMP select follower Track IDs, boundedly top up to Initial Size, compact only follower queue positions before `qr.z` returns, then let GMMP's natural CURRENT+1 lookup create the decoder.
- Queue-context Play may have no early natural refill. Once its exact native Play is accepted, the uniquely pre-resolved clicked row may use the same isolation/native-fill sequence before the later gapless preload. Ambiguous targets fail closed.
- R50/R51 CURRENT+1/source-batch guards remain downstream protection against the late Smart-source rebuild; they are no longer seed-boundary discovery mechanisms.
""")

with Path("docs/GMMP_421_COMPLETION.md").open("a") as f:
    f.write("""

### R56 Track Auto-DJ native-refill architecture correction
R54 device evidence showed BUNT. Track 19118 was absent when `dx3.c2(1)` fired, then present when GMMP naturally called `qr.z(3)`, and only afterwards did `MusicService.I2(2)` materialize the wrong Smart-Playlist follower. R56 therefore returns to the 4.2.0 seed-first model but uses the natural 4.2.1 refill as the predecoder boundary. GoneSmart isolates the exact selected row before the original refill, lets GMMP choose all follower identities, tops up to Initial Size, and compacts only follower positions before the refill caller resumes. Queue-context Play uses the same sequence from the exact accepted clicked row when no natural refill arrives first. Device re-acceptance is pending.
""")
