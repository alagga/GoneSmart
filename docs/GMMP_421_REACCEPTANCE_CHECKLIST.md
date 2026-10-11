# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r31** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `5fe46716b58b60f889ec18c03671333c7991bd7e`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r30`;
- no temporary source-snapshot/apply workflow or helper script remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger the toolbar Link action.
2. Verify GoneSmart logs `BRIDGE TOOLBAR LINK | native menuLink click observed`.
3. Verify the following proven `as4$g.accept(...)` boundary logs `toolbarAdd=true | edit=false | intercepted=true` and the GoneSmart type menu appears first with **Smart Playlist** and **Playlist**.
4. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
5. Trigger Link again, choose **Playlist**, select a normal Playlist, and verify the list closes without a MaterialDialogs callback crash.
6. Save and reopen the Smart Playlist; verify the normal-Playlist link is still represented.
7. Change the source Playlist and verify the Smart Playlist evaluates against the updated source.
8. Reopen/edit/save once more to prove persistence rather than only in-memory behavior. A genuine existing-rule edit must still work without a recent toolbar-add marker.

Required runtime evidence: bridge bindings ready; the exact native toolbar View is observed without replacing GMMP's listener; `as4$g.accept(...)` is intercepted as Add only after that toolbar click even if GMMP's selected-index field is still non-null; the Smart branch resumes the captured continuation once; the Playlist branch returns the host-compatible dialog callback singleton; and no fail-closed binding exception occurs.

## Track Mix

1. Start Track Mix on a deliberately identifiable selected song.
2. GoneSmart must resolve that clicked Track before native Play from bounded semantic evidence and prepare the immutable selected-current recommendation IDs.
3. Verify `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` occur **before** `MIX START | ... nativePlay=dispatched`. If semantic target resolution is ambiguous, Track Mix must fail before native Play rather than guess.
4. The first target-owned native refill must log `MIX PREPLAY BOOTSTRAP OFFER`, `SMART DJ TRACK MIX BOOTSTRAP` and `MIX PREPLAY NATIVE REFILL`. GoneSmart Track IDs must therefore already be present when GMMP first materializes/preloads the upcoming rows.
5. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Queue DAO use after bootstrap is prune-only.
6. There must be no earlier unrelated Track-2 AudioSource/decoder that is later rewritten. Native Track IDs may remain only for a genuine prepared-pool shortfall.
7. Let playback cross from the selected seed into the first generated follow-up without manually skipping, pausing, power toggling or seeking. Track 2 must actually open a decoder/start audio, not merely change metadata/scrobbling.
8. Verify subsequent managed refill still works after the natural Current transition.

Required runtime evidence: `current+1` never disappears during Track Mix startup; there is no startup `next audio source is null`; prepared GoneSmart Track IDs are substituted into GMMP's native Auto-DJ selection before queue-row/playback materialization; there is no visible native-placeholder-then-replacement cycle; and the first generated Current produces a real decoder/audio start.

The r27 same-position Current refresh is intentionally absent: device evidence proved it could verify while Track 2 still failed to decode. The r28 clear/pre-command-fill ordering is also retired: device evidence proved `CLEAR_QUEUE` itself could make MusicService cache a null next AudioSource before the later refill started.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.


## r31 regression focus

- Playlist Link must retain the r30 accepted dispatch path: native Link click -> `toolbarAdd=true | edit=false | intercepted=true` -> the two-way **Smart Playlist / Playlist** menu.
- In the Smart Playlist editor, a native linked Smart Playlist must render with the host Smart-Playlist label, while a GoneSmart ordinary Playlist bridge must render with the host Playlist label.
- Opening/evaluating a Smart Playlist linked to an ordinary playlist must log `BRIDGE SOURCE IDS` with resolved library IDs and must not log `GMMP direct query carrier initialization failed` or compile the bridge through the Long.MIN fail-closed sentinel. Changing the source playlist must still change membership on the next evaluation.
- Track Auto-DJ must log `MIX CLICK`, then `MIX PREPLAY AUTO-DJ | available=true` or `recovered=context-graph`, then a unique `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` before native Play.
- 4.2.1 Track Auto-DJ still must not use `CLEAR_QUEUE`, post-retarget a materialized `current+1`, or send a discovery command merely to obtain `qr`. The first generated follow-up must naturally decode and play.
