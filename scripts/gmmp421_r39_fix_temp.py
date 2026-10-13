from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing expected block in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1))


# Pure initial-target classification. A foreign CURRENT change after native Play
# is transition evidence only when a unique pre-Play target already exists.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixPlaybackIdentityPolicy.kt",
    '''    data class Identity(\n        val queueEntryId: Long?,\n        val trackId: Long,\n        val currentIndex: Int\n    )\n\n''',
    '''    data class Identity(\n        val queueEntryId: Long?,\n        val trackId: Long,\n        val currentIndex: Int\n    )\n\n    enum class InitialCandidateRole {\n        TARGET,\n        TRANSITION_ONLY,\n        IGNORE\n    }\n\n    fun classifyInitialCandidate(\n        expectedTrackId: Long?,\n        hasPlaybackEvidence: Boolean,\n        current: Identity\n    ): InitialCandidateRole {\n        if (!hasPlaybackEvidence) return InitialCandidateRole.IGNORE\n        if (expectedTrackId == null || current.trackId == expectedTrackId) {\n            return InitialCandidateRole.TARGET\n        }\n        return InitialCandidateRole.TRANSITION_ONLY\n    }\n\n'''
)

# Controller: hold a mismatching first CURRENT only as a bounded transition
# anchor. If that temporary queue entry disappears during the Smart source
# rebuild, the already-resolved exact target path can recover the selected row.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''        var stableSnapshot: Snapshot? = null\n        var stableAt = 0L\n        var pathRebindApplied = false\n        val deadline = SystemClock.elapsedRealtime() +\n''',
    '''        var stableSnapshot: Snapshot? = null\n        var stableAt = 0L\n        var pathRebindApplied = false\n        var transitionIdentity: TrackMixPlaybackIdentityPolicy.Identity? = null\n        var transitionDetectedAt = 0L\n        val deadline = SystemClock.elapsedRealtime() +\n'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''            val rebindPathHint = if (\n                targetIdentity != null &&\n                !pathRebindApplied &&\n                detectedAt > 0L &&\n                completionGuard > 0L &&\n                sampleAt - detectedAt <= completionGuard\n            ) {\n                request.prePlayTarget?.path\n            } else {\n                null\n            }\n            val current = queueSnapshot(\n                currentQueueEntryIdHint = targetIdentity?.queueEntryId,\n                currentTrackPathHint = rebindPathHint\n            ) ?: continue\n''',
    '''            val targetRebindActive =\n                targetIdentity != null &&\n                    !pathRebindApplied &&\n                    detectedAt > 0L &&\n                    completionGuard > 0L &&\n                    sampleAt - detectedAt <= completionGuard\n            val transitionRebindActive =\n                targetIdentity == null &&\n                    transitionIdentity != null &&\n                    transitionDetectedAt > 0L &&\n                    completionGuard > 0L &&\n                    sampleAt - transitionDetectedAt <= completionGuard\n            val rebindPathHint = if (targetRebindActive || transitionRebindActive) {\n                request.prePlayTarget?.path\n            } else {\n                null\n            }\n            val currentEntryHint = targetIdentity?.queueEntryId\n                ?: if (transitionRebindActive) transitionIdentity?.queueEntryId else null\n            val current = queueSnapshot(\n                currentQueueEntryIdHint = currentEntryHint,\n                currentTrackPathHint = rebindPathHint\n            ) ?: continue\n'''
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''            if (targetIdentity == null && playbackChanged) {\n                targetIdentity = identity\n                detectedAt = now\n                detectedQueueSize = current.ids.size\n                stableSnapshot = current\n                stableAt = now\n                request.provisionalCurrentQueuePosition = current.currentQueuePosition\n                tryPromoteNextSourceBoundary(request)\n                Log.i(\n                    TAG,\n                    "MIX PLAY DETECTED | source=queue-current-change" +\n                        " | entry=" + (identity.queueEntryId ?: -1L) +\n                        " | track=" + identity.trackId +\n                        " | queueSize=" + current.ids.size +\n                        " | completionGuardMs=" + completionGuard\n                )\n                continue\n            }\n''',
    '''            if (targetIdentity == null && playbackChanged) {\n                when (\n                    TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(\n                        expectedTrackId = request.prePlayTarget?.id,\n                        hasPlaybackEvidence = true,\n                        current = identity\n                    )\n                ) {\n                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TRANSITION_ONLY -> {\n                        if (transitionIdentity == null &&\n                            completionGuard > 0L &&\n                            identity.queueEntryId != null\n                        ) {\n                            transitionIdentity = identity\n                            transitionDetectedAt = now\n                            Log.i(\n                                TAG,\n                                "MIX PLAY TRANSITION | candidateEntry=" +\n                                    identity.queueEntryId +\n                                    " | candidateTrack=" + identity.trackId +\n                                    " | expectedTrack=" +\n                                    (request.prePlayTarget?.id ?: -1L) +\n                                    " | completionGuardMs=" + completionGuard +\n                                    " | targetDeferred=true"\n                            )\n                        }\n                        continue\n                    }\n                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET -> {\n                        val recoveredAfterTransition =\n                            transitionIdentity != null &&\n                                current.currentResolutionSource ==\n                                    "track-mix-path-rebind"\n                        targetIdentity = identity\n                        detectedAt = now\n                        detectedQueueSize = current.ids.size\n                        stableSnapshot = current\n                        stableAt = now\n                        transitionIdentity = null\n                        transitionDetectedAt = 0L\n                        request.provisionalCurrentQueuePosition =\n                            current.currentQueuePosition\n                        tryPromoteNextSourceBoundary(request)\n                        Log.i(\n                            TAG,\n                            "MIX PLAY DETECTED | source=" +\n                                if (recoveredAfterTransition)\n                                    "preplay-exact-path-after-transition"\n                                else "queue-current-change" +\n                                " | entry=" + (identity.queueEntryId ?: -1L) +\n                                " | track=" + identity.trackId +\n                                " | queueSize=" + current.ids.size +\n                                " | completionGuardMs=" + completionGuard\n                        )\n                        continue\n                    }\n                    TrackMixPlaybackIdentityPolicy.InitialCandidateRole.IGNORE -> Unit\n                }\n            }\n'''
)

# Fix operator precedence in the source label expression by making the entire
# source choice explicit before the suffix concatenation.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''                            "MIX PLAY DETECTED | source=" +\n                                if (recoveredAfterTransition)\n                                    "preplay-exact-path-after-transition"\n                                else "queue-current-change" +\n                                " | entry=" + (identity.queueEntryId ?: -1L) +\n''',
    '''                            "MIX PLAY DETECTED | source=" +\n                                (if (recoveredAfterTransition)\n                                    "preplay-exact-path-after-transition"\n                                else "queue-current-change") +\n                                " | entry=" + (identity.queueEntryId ?: -1L) +\n'''
)

# Same-current queue replay is only acceptable when it also matches a unique
# pre-Play target (if one was resolved). It is not a transition anchor.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt",
    '''            if (targetIdentity == null &&\n                TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(\n                    source = request.source,\n                    nativePlayAccepted = request.nativePlayAccepted,\n                    nativePlaySignal = request.nativePlaySignal,\n                    before = before,\n                    current = identity,\n                    stableMs = now - request.createdAt,\n                    actionAgeMs = now - request.createdAt\n                )\n            ) {\n''',
    '''            if (targetIdentity == null &&\n                TrackMixPlaybackIdentityPolicy.acceptSameCurrentQueuePlay(\n                    source = request.source,\n                    nativePlayAccepted = request.nativePlayAccepted,\n                    nativePlaySignal = request.nativePlaySignal,\n                    before = before,\n                    current = identity,\n                    stableMs = now - request.createdAt,\n                    actionAgeMs = now - request.createdAt\n                ) &&\n                (request.prePlayTarget == null ||\n                    identity.trackId == request.prePlayTarget?.id)\n            ) {\n'''
)

# Host tests for the target-vs-transition distinction.
p = Path("app/src/test/java/io/github/alagga/gonesmart/TrackMixPlaybackIdentityPolicyTest.kt")
text = p.read_text()
if "import org.junit.Assert.assertEquals" not in text:
    text = text.replace(
        "import org.junit.Assert.assertFalse\n",
        "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\n",
        1
    )
needle = "\n    @Test fun legacyPathFallsBackToTrackIdentityWithoutQueueEntryId() {"
if needle not in text:
    raise SystemExit("identity policy test insertion anchor missing")
new_tests = '''\n    @Test fun mismatchingCurrentChangeIsTransitionOnlyWhenPrePlayTargetIsKnown() {\n        val current = TrackMixPlaybackIdentityPolicy.Identity(\n            queueEntryId = 700L,\n            trackId = 19133L,\n            currentIndex = 5\n        )\n\n        assertEquals(\n            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TRANSITION_ONLY,\n            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(\n                expectedTrackId = 19118L,\n                hasPlaybackEvidence = true,\n                current = current\n            )\n        )\n    }\n\n    @Test fun exactPrePlayTargetMayBecomeInitialTargetAfterTransition() {\n        val current = TrackMixPlaybackIdentityPolicy.Identity(\n            queueEntryId = 701L,\n            trackId = 19118L,\n            currentIndex = 0\n        )\n\n        assertEquals(\n            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET,\n            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(\n                expectedTrackId = 19118L,\n                hasPlaybackEvidence = true,\n                current = current\n            )\n        )\n    }\n\n    @Test fun initialCandidateWithoutPlaybackEvidenceIsIgnored() {\n        val current = TrackMixPlaybackIdentityPolicy.Identity(\n            queueEntryId = 701L,\n            trackId = 19118L,\n            currentIndex = 0\n        )\n\n        assertEquals(\n            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.IGNORE,\n            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(\n                expectedTrackId = 19118L,\n                hasPlaybackEvidence = false,\n                current = current\n            )\n        )\n    }\n\n    @Test fun legacyFlowWithoutPrePlayTargetKeepsPlaybackChangeBehavior() {\n        val current = TrackMixPlaybackIdentityPolicy.Identity(\n            queueEntryId = 701L,\n            trackId = 42L,\n            currentIndex = 0\n        )\n\n        assertEquals(\n            TrackMixPlaybackIdentityPolicy.InitialCandidateRole.TARGET,\n            TrackMixPlaybackIdentityPolicy.classifyInitialCandidate(\n                expectedTrackId = null,\n                hasPlaybackEvidence = true,\n                current = current\n            )\n        )\n    }\n'''
if "mismatchingCurrentChangeIsTransitionOnlyWhenPrePlayTargetIsKnown" not in text:
    text = text.replace(needle, new_tests + needle, 1)
p.write_text(text)

# Startup marker for the next bundled device candidate.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r38"',
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r39"'
)

# Standing architecture rule; chronology remains in compatibility docs.
replace_once(
    "AGENTS.md",
    '''- Treat the first CURRENT from the generic track menu as provisional during the bounded Smart-Playlist completion guard.\n''',
    '''- Treat the first CURRENT from the generic track menu as provisional during the bounded Smart-Playlist completion guard. If a unique pre-Play target is already resolved, a different CURRENT track may prove only that native Play entered a transition; it must not become Track Mix's selected identity. During the same bounded guard, retain its queue entry only as a rebind anchor. If that temporary entry disappears, the exact pre-Play target path may recover the target only when the recovered Track ID also equals the pre-Play target. After that promotion, the normal one-shot exact-path rebind remains available for one further native source rebuild.\n'''
)

# Durable evidence notes.
for path, paragraph in {
    "docs/GMMP_COMPATIBILITY_PLAYBOOK.md": '''\n### GMMP 4.2.1 Track Mix pre-Play target gate\n\nr38 device evidence exposed a Smart-Playlist race before zero-pool isolation: native Play first changed CURRENT to a transient non-target row even though the clicked track had already been uniquely resolved before Play. A foreign first CURRENT is now transition evidence only. Its native queue entry may be retained as a bounded rebind anchor; if that entry disappears inside the Smart completion guard, the exact pre-Play target path may recover the materialized row only when the recovered Track ID equals the pre-Play target. This initial recovery does not consume the existing one-shot post-target exact-path rebind, because GMMP may replace the first materialized target row once more while completing the source rebuild.\n''',
    "docs/GMMP_421_COMPLETION.md": '''\n### r39 Track Mix transient-CURRENT gate\n\nThe r38 BUNT. pass failed before zero-pool mutation because `queue-current-change` promoted transient Track 19133 as the selected identity while the already resolved clicked target was Track 19118. The subsequent exact-path lookup found BUNT. correctly but was rejected against the wrong provisional Track ID. r39 gates initial Track Mix identity by the pre-Play target: a mismatching CURRENT change is transition-only, its disappearing queue entry can open the bounded exact-path recovery for the known target, and the normal one-shot post-target rebind remains available for GMMP's second source rebuild. Prepared-pool Levels behavior, recommendation ranking and zero-pool mutation logic are unchanged.\n'''
}.items():
    p = Path(path)
    text = p.read_text()
    if paragraph.strip() not in text:
        p.write_text(text.rstrip() + "\n" + paragraph)

# Re-acceptance: r39 is the next single device candidate.
p = Path("docs/GMMP_421_REACCEPTANCE_CHECKLIST.md")
text = p.read_text()
text = re.sub(
    r"current \*\*r\d+\*\* Playlist Link / Track Mix repair candidate",
    "current **r39** Playlist Link / Track Mix repair candidate",
    text,
    count=1
)
text = re.sub(
    r"Repair source commit: `[^`]+`\.",
    "Repair source commit: `R39_REPAIR_SOURCE_PENDING`.",
    text,
    count=1
)
text = re.sub(
    r"Compatibility startup log reports revision `gmmp421-r\d+`;",
    "Compatibility startup log reports revision `gmmp421-r39`;",
    text,
    count=1
)
r39 = '''\n## r39 regression focus\n\n- Re-run zero-provider BUNT. The first unrelated CURRENT change must log `MIX PLAY TRANSITION` rather than `MIX PLAY DETECTED`; its Track ID must not become the selected Track Mix identity.\n- When that temporary queue entry disappears during Smart-Playlist materialization, expect `GMMP QUEUE CURRENT | track-mix path rebind` to recover Track 19118 and then `MIX PLAY DETECTED | source=preplay-exact-path-after-transition` for the exact pre-Play target.\n- If GMMP replaces that first recovered BUNT. queue row once more, the existing `MIX CURRENT REBIND ... verified=true` path must still be available exactly once. After settling, expect `MIX CURRENT PIN`, request-scoped Current-writer mapping, zero-pool seed isolation/rebase to queue position 1, four native Auto-DJ followers, compact positions 1..5 and native next-source refresh for position 2.\n- BUNT. must finish at **1 of 5** and naturally transition into the newly generated Track 2. Avicii `Levels (Radio Edit)` remains the prepared-pool regression check and must keep all four GoneSmart recommendation slots.\n- Safety invariants remain: no `CLEAR_QUEUE`, no materialized Track-ID rewrite, no same-position CURRENT refresh, no hard-coded obfuscated writer identity, and ambiguous/stale target recovery fails closed.\n'''
if "## r39 regression focus" not in text:
    p.write_text(text.rstrip() + "\n" + r39)
else:
    p.write_text(text)
