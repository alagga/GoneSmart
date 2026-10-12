# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r34** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `5216cd527a77efa62c0efe0a7e70cce901519b10`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes generated-forum-copy verification, unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r34`;
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
7. If providers genuinely yield zero recommendations, native Auto-DJ may fill the shortfall, but Track Mix must not fail merely because the GoneSmart prepared pool is empty. A source Smart Playlist must be pruned to GMMP Initial Size while preserving the selected CURRENT row and `current+1`.
8. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Queue DAO use after bootstrap remains prune-only.
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
- Native Smart-Playlist rule rows must still read **Smart Playlist: <name>**, but their typography/alignment must be visually identical to the original native row. `BRIDGE RULE ROW LABEL ... rewritten=true` remains expected; no gravity/text-size/layout mutation is allowed.
- Re-run the zero-provider BUNT. Smart-Playlist Track Mix. Before materialization it should log `MIX NATIVE PLAY WINDOW` with `prepared=0`; the forwarded list must be no larger than GMMP Initial Size and begin at the uniquely pre-resolved BUNT. target. The request must then reach `MIX AUTO-DJ`/`MIX VERIFIED` instead of timing out on the discarded temporary queue_id.
- Re-run Avicii `Levels (Radio Edit)` as a regression. Because it has prepared GoneSmart recommendations, there must be **no** `MIX NATIVE PLAY WINDOW`; the accepted r33 `qr` bootstrap/prune path and natural Track-2 decoder transition must remain intact.
