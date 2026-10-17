# GMMP 4.2.1 bundled re-acceptance — COMPLETED

**Completed 17 October 2026.** The final R72-era device run accepted the repaired Playlist Link / Track Auto-DJ behavior, including repeated verified Track Auto-DJ initial queues and the corrected file-browser menu gate, without the previous Current-position writer dispatch timeout. The checklist below is retained as a historical investigation record from the r45 phase; it is no longer an open release blocker.

For current merge/release gating use `AGENTS.md`, `NATIVE_GMMP_AUDIT.md`, the exact-head normal Build workflow and the 0.5.0 integration test on the eventual merged tree.

---

## Historical r45 checklist


This checklist is the single device pass for the current **r45** Playlist Link / Track Mix architecture-correction and read-only mapping candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Architecture correction source: `019c837ce624cdb721621b95829a8080314b1dcf`.

Read-only 4.2.1 context-Play observer source: `6ea2c00f649a524ae0132da1fbe77470d5737815`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes generated-forum-copy verification, unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r45`;
- no temporary repair workflow or helper script remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger the toolbar Link action.
2. Verify GoneSmart logs `BRIDGE TOOLBAR LINK | native menuLink click observed`.
3. Verify the following proven `as4$g.accept(...)` boundary logs `toolbarAdd=true | edit=false | intercepted=true` and the GoneSmart type menu appears first with **Smart Playlist** and **Playlist**.
4. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
5. After selecting a Smart Playlist, the editor rule must read **Smart Playlist: <name>** using the host-localized wording. The native row should log `BRIDGE RULE ROW LABEL ... rewritten=true`.
6. Trigger Link again, choose **Playlist**, select a normal Playlist, and verify the rule reads **Playlist: <name>**.
7. Save/reopen the Smart Playlist and open its generated track list. The linked ordinary Playlist must contribute its actual members.
8. Startup/prewarm must log `GMMP library path index ready`; bridge evaluation must log `BRIDGE SOURCE IDS ... source=prewarmed-cache` with real resolved IDs. The Smart-rule evaluator must not access Room, throw the main-thread database exception, or fall back to `Long.MIN_VALUE` for a valid linked source.
9. Change membership in the source Playlist and reopen/evaluate the Smart Playlist; the result must follow the changed source file.
10. Reopen/edit/save once more to prove persistence rather than only in-memory behavior. A genuine existing-rule edit must still work without a recent toolbar-add marker.

The complete `track_uri -> song_id` index is built together with `GmmpLibraryReader`'s off-main-thread startup/cache refresh. Smart-rule compilation may only perform bounded lookups against that finished snapshot; it must not query Room or synchronously scan/canonicalize the whole library.

## Track Mix

1. Start Track Auto-DJ on Avicii `Levels (Radio Edit)` first. This is the positive SMART-path regression.
2. Verify `MIX CLICK`, `MIX PREPLAY AUTO-DJ | available=true`, and `MIX PREPLAY TARGET | resolved=true | trackId=3737`.
3. The pre-Play smart preparation may wait up to the established 60 s bound. It must finish with real GoneSmart local matches; `origin=NATIVE_FALLBACK`, `SMART DJ TRACK MIX NATIVE PRESELECT`, `MIX NATIVE FALLBACK PRELOAD`, and post-materialization Track-ID retarget markers are forbidden.
4. `MIX PREPLAY SMART POOL` must occur before `MIX START | ... nativePlay=dispatched`. Prepared GoneSmart IDs must continue through the accepted target-owned `qr.z(int)` bootstrap before GMMP materializes/caches the following AudioSource.
5. During this Levels request, the one-shot r45 observer may log `MIX CONTEXT PLAY BOUNDARY ... readOnly=true`. If present, record `listSize`, element classes, `resolvedTrack`, and `expectedTrack`. The observer must not change playback, arguments, queue rows, or method invocation order.
6. Let Levels cross naturally into Track 2. Queue metadata and the opened decoder/audio path must agree.
7. Then invoke Track Auto-DJ on the known zero-provider BUNT. / Røam `Nothing Lasts Forever` row. It must finish smart preparation with zero real GoneSmart matches, switch the GoneSmart badge/runtime to FALLBACK/red, log `MIX PREPLAY FALLBACK ... native Play not dispatched | failClosed=true`, and leave the currently playing audio plus queue untouched.
8. BUNT. must not turn green and must not emit `MIX START`, `SMART DJ TRACK MIX NATIVE PRESELECT`, `MIX NATIVE FALLBACK PRELOAD`, `TRACK MIX PRELOAD NEXT`, post-materialization Track-ID writes, `CLEAR_QUEUE`, or manual replay/refresh of a MusicService next-source boundary.
9. Do not treat BUNT. native-fallback playback as accepted in r45. Restoring that behavior requires independent proof of a 4.2.1 pre-materialization native boundary equivalent in ownership semantics to the accepted 4.2.0 queue-owner transaction; direct generated-DAO mutation is not sufficient evidence.
10. Playlist Link and the accepted span-preserving **Smart Playlist:** label remain regression invariants and need no repeated exploratory changes.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.

## r33 regression focus

- Playlist Link membership evaluation is already accepted from r32 and must remain unchanged.
- Open a Smart Playlist editor containing both a native Smart-Playlist link and a GoneSmart ordinary Playlist link. The native row must log `BRIDGE RULE ROW LABEL ... rewritten=true` and display **Smart Playlist: <name>**; the ordinary bridge must remain **Playlist: <name>**.
- Start Track Auto-DJ from the BUNT. Smart-Playlist row used in r32. After `MIX PLAY DETECTED`, the selected native `queue_id` must log `MIX CURRENT PIN`; the original Smart-Playlist queue must be pruned to GMMP Initial Size while preserving selected/current+1, then Track Mix must reach `MIX AUTO-DJ` rather than falsely report that the selected song never started.
- Start Track Auto-DJ on Avicii `Levels (Radio Edit)`. The four prepared GoneSmart IDs must still reach `SMART DJ TRACK MIX BOOTSTRAP`, then the pinned queue entry must allow post-refill verification, prune and the native Auto-DJ command. Let playback cross naturally into Track 2 and verify decoder/audio, not only metadata.
- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no post-materialization Track-ID retarget, no same-position playback refresh, and no current selection inferred from track ID alone.

## r34 regression focus
- Native Smart-Playlist rule rows must still read **Smart Playlist: <name>**, with typography/alignment identical to the original native row. The span-preserving prefix replacement is accepted and remains invariant.
- The r34 zero-provider `MusicService.w1` source-window experiment is retired: the exact-head BUNT. log proved that context-track Play never crossed this list boundary, so `MIX NATIVE PLAY WINDOW` must not be required or revived.
- Avicii `Levels (Radio Edit)` remains the accepted prepared-pool regression: the r33 controller-owned `qr` bootstrap/prune path and natural Track-2 decoder transition must remain intact.

## r35 regression focus
- The Smart-Playlist rule label is accepted from r34: it must remain **Smart Playlist: <name>** with native typography/alignment unchanged.
- Re-run the zero-provider BUNT. Track Mix. The retired `MIX NATIVE PLAY WINDOW` marker must be absent. After the initially proven BUNT. queue entry disappears during Smart-Playlist materialization, expect exactly one `GMMP QUEUE CURRENT | track-mix path rebind ... exact=true` followed by `MIX CURRENT REBIND ... source=exact-path | verified=true`.
- The rebind must adopt the replacement native `queue_id`, then reach `MIX PLAY VERIFIED` and `MIX CURRENT PIN`. Zero or duplicate exact path matches must not rebind.
- Re-run Avicii `Levels (Radio Edit)` as regression. Its prepared recommendation pool must continue through the accepted controller-owned `qr` bootstrap; no exact-path rebind should be needed in the normal successful path, and the natural Track-2 decoder/audio transition must remain intact.
- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no same-position playback refresh, no post-materialization Track-ID retarget, and no current selection inferred from track ID alone.

## Historical note: r36-r39 zero-provider mechanics are retired

The sections below document device experiments that led to r44. Their seed-isolation, explicit native-refill and `MIX NEXT SOURCE REFRESH` expectations are **historical evidence only and must not be used as current acceptance criteria**. r44 supersedes those mechanics because the 2026-10-14 device log proved the temporary missing-current+1 interval cached a null AudioSource and manual MusicService replay did not restore decoder continuity.

## r36 regression focus
- The accepted r35 exact-path rebind must still occur for the zero-provider BUNT. run, but the final queue must no longer retain source Smart-Playlist followers. Expect a passively observed state-writer candidate, one promoted Track Mix rebase write, `TRACK MIX ZERO-POOL SEED ... oldPosition=6 | newPosition=1`, an explicit native refill for four tracks, `TRACK MIX ZERO-POOL REBASE ... currentPosition=1`, and `MIX NEXT SOURCE REFRESH ... position=2`.
- The queue UI must settle at **1 of 5**, not `6 of 5`. The five native rows must have compact queue positions 1..5; BUNT. remains the exact pinned CURRENT row at position 1.
- The four rows after BUNT. must be created by GMMP's native Auto-DJ refill after source isolation (`nativeGenerated=4`), not retained rows from the source Smart Playlist. No Track ID is rewritten in place.
- Let BUNT. cross naturally into Track 2. The refreshed position-2 row must be the decoder/audio source, not merely the visible queue metadata.
- Re-run Avicii `Levels (Radio Edit)` as the prepared-pool regression. It must continue through the accepted r35 controller-owned `qr` bootstrap. The r36 zero-pool seed/rebase/next-source-refresh markers must be absent for Levels, and its natural Track-2 transition must remain correct.
- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no post-materialization Track-ID retarget, no same-position CURRENT refresh, no hard-coded obfuscated next-source method, and any ambiguous passive boundary fails closed.

## r37 regression focus
- Re-run Avicii `Levels (Radio Edit)`. The provider/local-matching stage should still report a populated prepared pool (the r36 device run produced 20 accepted local matches and `prepared=4`). After `MIX CURRENT PIN`, every remaining bootstrap refill must resolve CURRENT through the pinned native queue entry rather than log `MIX PREPLAY BOOTSTRAP SKIP | current=null`. All four prepared IDs must materialize before prune; no `SMART DJ NATIVE FALLBACK` may replace the remaining prepared slots with GMMP random tracks.
- Re-run zero-provider BUNT. The accepted r35 exact-path rebind and r36 next-source boundary must still occur. The Track-Mix-only Current writer should log a request-scoped mapping whose argument/signal offsets are each 0 or 1, then the zero-pool seed must rebase the exact pinned BUNT. entry to queue position 1, refill four native Auto-DJ rows, compact 1..5 and refresh native position 2.
- BUNT. must end at **1 of 5** and naturally transition into the newly generated Track 2. Levels must keep the four GoneSmart recommendation slots and naturally decode Track 2.
- Safety invariants remain: no `CLEAR_QUEUE`, no Track-ID rewrite after playback materialization, no same-position CURRENT refresh, no hard-coded `dx3.c2`/`MusicService.I2`, and stale/ambiguous coordinate evidence fails closed.

## r38 regression focus

- Avicii `Levels (Radio Edit)` is accepted from the r37 device run: all prepared GoneSmart slots materialized correctly. Re-run it once only as a regression; the zero-provider path must remain inactive.
- Re-run zero-provider BUNT. After the natural child-state call, expect `QUEUE POSITION TRACK MIX CANDIDATE` while the retained Auto-DJ owner remains the controller-owned refill/service instance. A child/state receiver must never replace that owner.
- After the accepted exact-path rebind and `MIX CURRENT PIN`, expect `QUEUE POSITION TRACK MIX MAPPING` with only a 0/1 request-scoped offset, then seed isolation/rebase to queue position 1, four native Auto-DJ followers, compact positions 1..5 and the already accepted next-source refresh for position 2.
- BUNT. must finish at **1 of 5** and transition naturally into the newly generated Track 2.
- Safety invariants remain: no `CLEAR_QUEUE`, no materialized Track-ID rewrite, no same-position CURRENT refresh, no hard-coded state/next-source method identity, and ambiguous/stale owner or coordinate evidence fails closed.

## r39 regression focus

- Re-run zero-provider BUNT. The first unrelated CURRENT change must log `MIX PLAY TRANSITION` rather than `MIX PLAY DETECTED`; its Track ID must not become the selected Track Mix identity.
- When that temporary queue entry disappears during Smart-Playlist materialization, expect `GMMP QUEUE CURRENT | track-mix path rebind` to recover Track 19118 and then `MIX PLAY DETECTED | source=preplay-exact-path-after-transition` for the exact pre-Play target.
- If GMMP replaces that first recovered BUNT. queue row once more, the existing `MIX CURRENT REBIND ... verified=true` path must still be available exactly once. After settling, expect `MIX CURRENT PIN`, request-scoped Current-writer mapping, zero-pool seed isolation/rebase to queue position 1, four native Auto-DJ followers, compact positions 1..5 and native next-source refresh for position 2.
- BUNT. must finish at **1 of 5** and naturally transition into the newly generated Track 2. Avicii `Levels (Radio Edit)` remains the prepared-pool regression check and must keep all four GoneSmart recommendation slots.
- Safety invariants remain: no `CLEAR_QUEUE`, no materialized Track-ID rewrite, no same-position CURRENT refresh, no hard-coded obfuscated writer identity, and ambiguous/stale target recovery fails closed.


## Historical r44 regression focus — retired by r45

- BUNT. zero-provider/native-fallback run: expect `SMART DJ TRACK MIX NATIVE PRESELECT ... selected=4`, then `MIX NATIVE FALLBACK PRELOAD | primed=true` before GMMP's natural position-2 AudioSource read. There must be no seed-only interval, no `next audio source is null`, and no `MIX NEXT SOURCE REFRESH`.
- After Smart-Playlist rebuild/path rebind, expect `MIX NATIVE FALLBACK QUEUE | verified=true | preloadPreserved=true`. Final queue must be positions 1..5 with BUNT. CURRENT at 1 and the exact preloaded fallback Track ID at slot 2.
- Let BUNT. finish naturally. Queue slot 2 must open `DecoderCommon` for its exact final path and produce audio; metadata-only advancement is a failure.
- Immediately start Avicii `Levels (Radio Edit)` afterwards. It must still use `TrackMixPreparedOrigin.SMART`, materialize all prepared GoneSmart IDs through the accepted native bootstrap and start playback normally. Native-fallback preload markers must be absent for Levels.
- Safety: no `CLEAR_QUEUE`, no seed-only current+1 gap, no manual playback-boundary invocation/replay, no track-ID-only CURRENT guess. Ambiguous priming fails before source pruning; if a temporary next slot was already primed, recovery aligns rebuilt current+1 to that same Track ID.


## GMMP 4.2.1 Track Mix architecture correction (r45)

The 2026-10-14 r44 device trace proved that native-fallback preselection plus post-materialization `current+1` retargeting violates the accepted playback ownership model: BUNT. had zero GoneSmart recommendations but was presented as a successful four-track prepared pool, and the queue row for slot 2 diverged from GMMP's already cached decoder AudioSource. r45 retires that path. Track Mix again waits on the same provider/local-match pipeline up to the established 60 s preparation bound. A zero real-match result is FALLBACK/red and fails closed before native Play, leaving queue/audio state untouched. Device re-acceptance must first prove Levels uses real GoneSmart matches again; BUNT. must no longer turn green or mutate playback when the smart pool is empty. A native no-match fallback may be restored only after the 4.2.1 equivalent of the accepted 4.2.0 seed-isolation boundary is independently proven before decoder materialization.
