# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r37** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `R37_REPAIR_SOURCE_PENDING`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes generated-forum-copy verification, unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r37`;
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

1. Start Track Auto-DJ on a deliberately identifiable selected song.
2. Verify `MIX CLICK` and `MIX PREPLAY AUTO-DJ | available=true` (or the bounded passive recovery marker).
3. Verify `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` occur **before** `MIX START | ... nativePlay=dispatched`.
4. Once the selected native queue row is proven, verify `MIX CURRENT PIN | entry=... | track=... | verified=true`. During the pending startup this unique native `queue_id` is the authoritative temporary CURRENT identity; a missing/duplicated entry must never be replaced by a track-ID guess.
5. If GoneSmart prepared recommendations, the explicit shortfall refill must log `MIX REQUEST REFILL | receiver=qr`; it must never attempt `dx3.z(int)`.
6. The target-owned native refill must reach the existing bootstrap path (`MIX PREPLAY BOOTSTRAP OFFER` / `SMART DJ TRACK MIX BOOTSTRAP` / `MIX PREPLAY NATIVE REFILL`) so prepared GoneSmart IDs are selected before queue-row/playback materialization.
7. If providers genuinely yield zero recommendations, Track Mix must preserve only the exact pinned selected row from the source queue, rebase it to position 1, and let GMMP's native Auto-DJ refill create the Initial-Size followers. Source Smart-Playlist followers must not be retained as a substitute recommendation set.
8. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Prepared-recommendation queue mutation after bootstrap remains prune-only; the zero-provider fallback may delete source rows and compact native queue positions, but it must never rewrite follower Track IDs.
9. Let playback cross naturally from the selected seed into the first generated follow-up without skip/pause/seek. Track 2 must open a decoder and start audio, not merely update metadata/scrobbling.
10. Verify subsequent managed refill still works after a natural Current transition; continuity refill must also use the exact controller-owned `qr` receiver.

The r27 same-position Current refresh remains retired because it could verify while Track 2 still failed to decode. The r28 clear/pre-command-fill ordering remains retired because `CLEAR_QUEUE` itself could cache a null next AudioSource. r32 also forbids child/state-writer receivers such as `dx3` from becoming Track Mix refill owners.

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

