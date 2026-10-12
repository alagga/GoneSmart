# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r32** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `721b8d08d297cef8ee2089231f728980a15b4ca4`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes generated-forum-copy verification, unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r32`;
- no temporary repair workflow or helper script remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger the toolbar Link action.
2. Verify GoneSmart logs `BRIDGE TOOLBAR LINK | native menuLink click observed`.
3. Verify the following proven `as4$g.accept(...)` boundary logs `toolbarAdd=true | edit=false | intercepted=true` and the GoneSmart type menu appears first with **Smart Playlist** and **Playlist**.
4. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
5. After selecting a Smart Playlist, the editor rule must read **Smart Playlist: <name>** using the host-localized wording. A rewritten label should log `BRIDGE RULE LABEL`.
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
4. If GoneSmart prepared recommendations, the explicit shortfall refill must log `MIX REQUEST REFILL | receiver=qr`; it must never attempt `dx3.z(int)`.
5. The target-owned native refill must reach the existing bootstrap path (`MIX PREPLAY BOOTSTRAP OFFER` / `SMART DJ TRACK MIX BOOTSTRAP` / `MIX PREPLAY NATIVE REFILL`) so prepared GoneSmart IDs are selected before queue-row/playback materialization.
6. If providers genuinely yield zero recommendations, native Auto-DJ may fill the shortfall, but Track Mix must not fail merely because the GoneSmart prepared pool is empty.
7. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Queue DAO use after bootstrap remains prune-only.
8. Let playback cross naturally from the selected seed into the first generated follow-up without skip/pause/seek. Track 2 must open a decoder and start audio, not merely update metadata/scrobbling.
9. Verify subsequent managed refill still works after a natural Current transition; continuity refill must also use the exact controller-owned `qr` receiver.

The r27 same-position Current refresh remains retired because it could verify while Track 2 still failed to decode. The r28 clear/pre-command-fill ordering remains retired because `CLEAR_QUEUE` itself could cache a null next AudioSource. r32 also forbids child/state-writer receivers such as `dx3` from becoming Track Mix refill owners.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.
