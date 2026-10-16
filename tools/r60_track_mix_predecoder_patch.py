from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


# 1) Keep routing explicit and testable: non-queue GMMP 4.2.1 starts use
# the proven predecoder compatibility port; queue-context starts retain R59.
policy_path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt"
policy = read(policy_path)
policy_anchor = '''    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)
'''
policy_insert = '''    fun usePredecoderCompatibility(
        source: String,
        legacyQueue: Boolean
    ): Boolean =
        !legacyQueue && source != "menu_gm_context_queue"

''' + policy_anchor
policy = replace_once(policy, policy_anchor, policy_insert, "policy route")
write(policy_path, policy)

policy_test_path = "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt"
policy_test = read(policy_test_path)
policy_test_anchor = '''    @Test fun slotRefillRequestsOnlyRealShortfall() {
'''
policy_test_insert = '''    @Test fun nonQueue421StartsUseThePredecoderCompatibilityPort() {
        assertTrue(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_playlist_details",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_queue",
                legacyQueue = false
            )
        )
        assertFalse(
            TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = "menu_gm_context_playlist_details",
                legacyQueue = true
            )
        )
    }

''' + policy_test_anchor
policy_test = replace_once(policy_test, policy_test_anchor, policy_test_insert, "policy test")
write(policy_test_path, policy_test)


# 2) Re-enable the already-proven predecoder Track-Mix path only for non-queue
# starts. The queue-context path keeps the current R59 native-ownership flow.
track_path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
track = read(track_path)

# Restore seed-specific pre-Play recommendation preparation. This does not
# change selection semantics: it prepares the same normal GoneSmart pool early
# enough for GMMP 4.2.1's eager current+1 materialization.
old_preplay = '''                // R57 restores the accepted 4.2.0 ownership model. Pre-Play
                // work resolves only the exact clicked Track identity. Track Mix
                // never prepares or injects follower IDs itself; GMMP's normal
                // AUTO_DJ refill and GoneSmart's general selection hook own them.
                val initial = settings.read().initialQueueSize.coerceAtLeast(1)
                request.prePlayInitialSize = initial
                request.prePlayPrepared = TrackMixPreparedInitialPool.EMPTY
                request.stage = "PREPARED"
                Log.i(
                    TAG,
                    "MIX PREPLAY | target=${resolution.track.id} | initial=$initial | " +
                        "strategy=native-auto-dj"
                )
'''
new_preplay = '''                val initial = settings.read().initialQueueSize.coerceAtLeast(1)
                request.prePlayInitialSize = initial
                val usePredecoderCompatibility =
                    TrackMixInitialFillPolicy.usePredecoderCompatibility(
                        source = source,
                        legacyQueue = false
                    ) && initial > 1
                if (usePredecoderCompatibility) {
                    val requiredUpcoming = (initial - 1).coerceAtLeast(0)
                    request.stage = "PREPLAY_PREPARE"
                    request.prePlayPrepared = preparePrePlaySmartPool(
                        autoDj,
                        resolution.track,
                        requiredUpcoming
                    )
                    Log.i(
                        TAG,
                        "MIX PREPLAY SMART POOL | target=${resolution.track.id} | " +
                            "requested=$requiredUpcoming | prepared=${request.prePlayPrepared.trackIds.size} | " +
                            "route=predecoder-4.2.0-semantics"
                    )
                    if (request.prePlayPrepared.trackIds.isEmpty()) {
                        Log.i(
                            TAG,
                            "MIX PREPLAY FALLBACK | no GoneSmart matches; " +
                                "awaiting native qr.z predecoder boundary"
                        )
                    }
                } else {
                    request.prePlayPrepared = TrackMixPreparedInitialPool.EMPTY
                    request.stage = "PREPARED"
                    Log.i(
                        TAG,
                        "MIX PREPLAY | target=${resolution.track.id} | initial=$initial | " +
                            "strategy=native-auto-dj | route=r59"
                    )
                }
'''
track = replace_once(track, old_preplay, new_preplay, "preplay preparation")

# Restore the natural qr.z predecoder boundary, adapted to the later R59
# position-1 invariant. The exact selected row is rebased through the verified
# writer before qr.z's original body, then only GMMP-created follower positions
# are compacted by completeZeroPoolAtNativeRefill before the caller can ask for
# current+1. Queue-context starts are intentionally excluded.
old_zero_boundary = '''    fun prepareZeroPoolAtNativeRefill(
        autoDj: Any,
        requestedTracks: Int
    ): ZeroPoolNativeRefillBoundary? = null
'''
new_zero_boundary = '''    fun prepareZeroPoolAtNativeRefill(
        autoDj: Any,
        requestedTracks: Int
    ): ZeroPoolNativeRefillBoundary? {
        val request = pending ?: return null
        if (!TrackMixInitialFillPolicy.usePredecoderCompatibility(
                source = request.source,
                legacyQueue = false
            )
        ) return null
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
            val verifiedWriter = nativePositionWriterProvider(autoDj)
                ?: nativeTrackMixPositionWriterProvider(autoDj, located.queuePosition)
                ?: error("Verified GMMP Current-position writer unavailable at native refill boundary")
            check(
                GmmpQueueMutationBridge(
                    autoDj = autoDj,
                    verifiedPositionWriter = verifiedWriter,
                    currentQueueEntryIdHint = located.queueEntryId
                ).isolateTrackMixSeed(target.id)
            ) {
                "Could not isolate selected Track Auto-DJ seed before native refill"
            }
            val isolated = queueReader.read(
                autoDj,
                verifiedWriter.read(),
                located.queueEntryId
            ) ?: error("Predecoder seed verification unavailable")
            check(
                isolated.currentQueuePosition == 1 &&
                    isolated.currentItem?.queueEntryId == located.queueEntryId &&
                    isolated.currentItem?.track?.id == target.id &&
                    isolated.items.size == 1
            ) {
                "Predecoder seed did not restore the 4.2.0 position-1 invariant"
            }
            request.provisionalCurrentQueuePosition = 1
            Log.i(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | isolated=true | beforeOriginal=true" +
                    " | seed=${target.id} | entry=${located.queueEntryId}" +
                    " | oldCurrentPosition=${located.queuePosition} | currentPosition=1" +
                    " | originalRequested=$requestedTracks | topUp=${plan.topUpCount}" +
                    " | route=predecoder-4.2.0-semantics"
            )
            ZeroPoolNativeRefillBoundary(
                token = request.token,
                selectedTrackId = target.id,
                selectedEntryId = located.queueEntryId,
                currentPosition = 1,
                initialSize = request.prePlayInitialSize,
                originalRequested = requestedTracks,
                topUpCount = plan.topUpCount
            )
        } catch (failure: Throwable) {
            request.stage = "WAIT_PLAY"
            request.zeroPoolBoundaryAttempted = false
            Log.e(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | seed isolation failed; fail closed",
                failure
            )
            null
        }
    }
'''
track = replace_once(track, old_zero_boundary, new_zero_boundary, "zero-pool qr.z boundary")

# Add a bounded helper that is almost the accepted R51/R56 non-queue path.
# It consumes only data already prepared/materialized before current+1 and never
# calls a playback method. The later R59 queue-context path remains below it.
run_anchor = '''    private fun runTrackMix(request: Pending) {
'''
predecoder_helper = '''    private fun preparePredecoderInitialQueue(
        request: Pending,
        loaded: Snapshot,
        selectedId: Long,
        selectedEntryId: Long,
        initial: Int,
        upcoming: Int
    ): Snapshot? {
        val selectedQueuePosition = loaded.currentQueuePosition ?: run {
            Log.e(TAG, "MIX PREDECODER | selected queue_position unavailable")
            fail("Could not identify the selected queue position safely.", request.context)
            return null
        }
        val requiredUpcoming = (initial - 1).coerceAtLeast(0)

        if (request.prePlayPrepared.trackIds.isEmpty()) {
            return fillZeroPoolNativeFallback(
                request = request,
                selectedId = selectedId,
                selectedEntryId = selectedEntryId,
                selectedQueuePosition = selectedQueuePosition,
                initial = initial,
                upcomingSetting = upcoming
            ) ?: run {
                fail(
                    "Could not safely materialize the native Auto-DJ queue before playback preload.",
                    request.context
                )
                null
            }
        }

        var currentSnapshot = loaded
        var availableUpcoming =
            (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
        if (availableUpcoming < requiredUpcoming) {
            val deficit = requiredUpcoming - availableUpcoming
            if (!requestHeldNativeRefill(deficit)) {
                fail("Could not prepare Auto-DJ queue slots before playback preload.", request.context)
                return null
            }
            currentSnapshot = awaitQueue(
                request,
                8_000L,
                selectedEntryId
            ) { snapshot ->
                snapshot.currentId == selectedId &&
                    (snapshot.ids.size - snapshot.currentIndex - 1) >= requiredUpcoming
            } ?: run {
                fail("Could not prepare Auto-DJ queue slots before playback preload.", request.context)
                return null
            }
            availableUpcoming =
                (currentSnapshot.ids.size - currentSnapshot.currentIndex - 1).coerceAtLeast(0)
        }

        val expectedSmart = request.prePlayPrepared.trackIds
            .take(request.bootstrapAppliedTrackIds.size)
        if (expectedSmart.isEmpty()) {
            Log.e(
                TAG,
                "MIX PREDECODER VERIFY | prepared Smart pool never reached native materialization"
            )
            fail("Could not apply the prepared Track Auto-DJ selection safely.", request.context)
            return null
        }
        expectedSmart.forEachIndexed { index, trackId ->
            val actual = currentSnapshot.ids.getOrNull(currentSnapshot.currentIndex + 1 + index)
            if (actual != trackId) {
                Log.e(
                    TAG,
                    "MIX PREDECODER VERIFY | slot=${index + 1} | expected=$trackId | actual=$actual"
                )
                fail("Could not verify the prepared Track Auto-DJ selection.", request.context)
                return null
            }
        }

        request.stage = "PRUNE"
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
                selectedTrackId = selectedId,
                smartTrackIds = emptyList(),
                targetQueueSize = initial
            )
        }.onFailure {
            Log.e(TAG, "MIX PREDECODER PRUNE | native 4.2.1 queue prune failed", it)
        }.getOrDefault(false)
        if (!pruned) {
            fail("Could not finalize the Track Auto-DJ queue.", request.context)
            return null
        }
        val filled = queueSnapshot(selectedEntryId)?.takeIf {
            it.currentId == selectedId && it.ids.size == initial
        } ?: run {
            fail("Could not verify the Track Auto-DJ queue.", request.context)
            return null
        }

        Log.i(
            TAG,
            "MIX PREDECODER QUEUE | verified=true | initial=$initial | " +
                "smart=${request.bootstrapAppliedTrackIds.size} | " +
                "nativeRemainder=${requiredUpcoming - request.bootstrapAppliedTrackIds.size} | " +
                "current=$selectedId | upcoming=$availableUpcoming"
        )

        armManagedAutoDjSession()
        request.stage = "FILLING"
        request.transitionalRefillSuppressed = false
        sendCommand(request.context, COMMAND_AUTO_DJ)
        nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 7_000L
        awaitAutoDjCommandRefillBoundary(request)
        Log.i(
            TAG,
            "MIX AUTO-DJ | command=sent-after-predecoder-bootstrap | " +
                "initialSize=$initial | upcoming=$upcoming | seed=$selectedId"
        )
        return filled
    }

''' + run_anchor
track = replace_once(track, run_anchor, predecoder_helper, "predecoder helper")

# Route only non-queue 4.2.1 starts through the predecoder helper. Success uses
# the same final R59 1..InitialSize verification; failure is fail-closed and
# never falls through to a late post-decode queue repair.
legacy_anchor = '''            val legacyQueue =
                nativeAutoDj?.get()?.let { autoDj ->
                    field(autoDj, "q")?.javaClass?.name == "ex3"
                } == true || nativeQueue?.get()?.javaClass?.name == "ex3"

            // This is the one queue mutation Track Mix owns: reduce the queue to
'''
legacy_insert = '''            val legacyQueue =
                nativeAutoDj?.get()?.let { autoDj ->
                    field(autoDj, "q")?.javaClass?.name == "ex3"
                } == true || nativeQueue?.get()?.javaClass?.name == "ex3"

            val usePredecoderCompatibility =
                TrackMixInitialFillPolicy.usePredecoderCompatibility(
                    source = request.source,
                    legacyQueue = legacyQueue
                ) && initial > 1
            if (usePredecoderCompatibility) {
                val filled = preparePredecoderInitialQueue(
                    request = request,
                    loaded = loaded,
                    selectedId = selectedId,
                    selectedEntryId = selectedEntryId,
                    initial = initial,
                    upcoming = upcoming
                )
                refillHold.set(false)
                if (filled == null) return

                val exactContext = nativeAutoDj?.get()?.let { autoDj ->
                    queueReader.read(
                        autoDj,
                        currentQueueEntryIdHint = selectedEntryId
                    )
                }
                val orderedPositions = exactContext?.items
                    ?.sortedBy { it.queuePosition }
                    ?.map { it.queuePosition }
                    ?: emptyList()
                if (exactContext?.currentItem?.track?.id != selectedId ||
                    !TrackMixInitialFillPolicy.isExactInitialQueue(
                        initialSize = initial,
                        currentQueuePosition = exactContext?.currentQueuePosition,
                        queuePositions = orderedPositions
                    )
                ) {
                    Log.e(
                        TAG,
                        "MIX PREDECODER COORDINATES | initial=$initial | actual=${filled.ids.size} | " +
                            "currentPosition=${exactContext?.currentQueuePosition} | " +
                            "positions=${orderedPositions.joinToString(",")} | verified=false"
                    )
                    fail("GMMP's predecoder Initial Size was not normalized correctly.", request.context)
                    return
                }

                if (managedAutoDj?.get() == null) {
                    armManagedAutoDjSession()
                }
                managedSessionReady = managedAutoDj?.get() === nativeAutoDj?.get()
                if (!managedSessionReady) {
                    fail("Could not retain the Track Auto-DJ continuation session.", request.context)
                    return
                }

                Log.i(
                    TAG,
                    "MIX VERIFIED | initial=$initial | actual=${filled.ids.size} | " +
                        "currentPreserved=true | tracksAfterSeed=${filled.ids.size - 1} | " +
                        "owner=predecoder-4.2.0-semantics"
                )
                events.reportEvent(
                    GoneSmartRuntimeContract.CATEGORY_TRACK_MIX,
                    "${request.menuLabel}: ${filled.ids.size - 1} " +
                        "tracks queued after the selected song."
                )
                toast(request.context, request.confirmation)
                nativeToastSuppressionUntilMs = SystemClock.elapsedRealtime() + 3_000L
                return
            }

            // This is the one queue mutation Track Mix owns: reduce the queue to
'''
track = replace_once(track, legacy_anchor, legacy_insert, "run route")

write(track_path, track)


# 3) Persist the reason for the split path. This documents why low-level 4.2.0
# code cannot be copied literally while keeping its behavior as the spec.
agents_path = "AGENTS.md"
agents = read(agents_path)
agents_append = '''

## GMMP 4.2.1 Track Auto-DJ 4.2.0 semantic port (R60)
- The accepted GMMP 4.2.0 implementation remains the behavioral specification: native Play must resolve the exact selected seed, the queue/current coordinate must be coherent before Auto-DJ followers are exposed, GMMP/GoneSmart's normal Auto-DJ selection owns follower identity, and no playback method is replayed after decoder materialization.
- A literal low-level port is impossible on 4.2.1 because the old `ex3` owner coupled queue DAO mutation, append allocator `p`, and playback pointer `b2(int)` inside one native transaction. 4.2.1 splits those responsibilities across the verified Queue DAO/current writer and `qr.z(int)` and may materialize `current+1` while native Play is still rebuilding its source.
- For non-Queue song-menu Track Auto-DJ, restore the proven predecoder compatibility path: prepare the selected-seed GoneSmart pool before Play; when the pool is empty, acquire the natural `qr.z(int)` boundary, isolate/rebase only the exact selected seed through verified writers, let GMMP choose native fallback identities, and compact the resulting Initial Size before the original caller can materialize `current+1`. A non-empty prepared pool continues through the existing bootstrap selection window. R50/R51 guards remain downstream identity protection if GMMP later rebuilds the original Smart source.
- `menu_gm_context_queue` deliberately stays on the later R59 native-ownership path. Earlier predecoder state manipulation was device-problematic for Queue-context starts, while the latest Queue test already built the correct queue; the post-decode replay that caused its second-track freeze has been removed.
- Never reintroduce a post-decode `MusicService(Int)->void` replay as a next-source refresh. R49 and the 2026-10-16 trace both disprove that model.
'''
if "## GMMP 4.2.1 Track Auto-DJ 4.2.0 semantic port (R60)" in agents:
    raise SystemExit("AGENTS R60 section already present")
agents += agents_append
write(agents_path, agents)

playbook_path = "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
playbook = read(playbook_path)
playbook_append = '''

### 2026-10-16 Track Auto-DJ predecoder semantic port

The final post-decode `MusicService(Int)->void` replay experiment was retired. Device evidence showed that the visible five-row queue could be fully correct and the replay could return successfully while playback still froze on/after the second-track transition. Historical R49 evidence had already shown the same boundary is not a player-cache refresh.

GMMP 4.2.0 remains the semantic baseline, but its low-level implementation cannot be copied literally: `ex3.c(...)`, append allocator `ex3.p`, queue DAO mutation and `b2(int)` current-state movement formed one coupled owner. GMMP 4.2.1 splits those responsibilities and can ask for `current+1` during native Play before a later Track-Mix worker can isolate/refill the seed.

For non-Queue song-menu starts, the compatibility port therefore restores the already-proven predecoder ordering rather than repairing playback afterwards. Selected-seed GoneSmart preparation happens before Play. The natural `qr.z(int)` hook is allowed to establish a zero-match native Initial Size synchronously after rebasing the exact selected seed to the accepted position-1 invariant, or to consume the prepared Smart bootstrap. The original GMMP caller then owns its normal next-source/decoder creation. Existing R50/R51 guards may only preserve those already-selected identities across a later source rebuild. Queue-context starts remain on the R59 native-ownership path because that path already produces the correct queue and earlier predecoder state writes were not accepted there.
'''
if "### 2026-10-16 Track Auto-DJ predecoder semantic port" in playbook:
    raise SystemExit("playbook R60 section already present")
playbook += playbook_append
write(playbook_path, playbook)
