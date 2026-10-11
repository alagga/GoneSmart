# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r29** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `b7019ba23c638d0567a36d1dc0e31ada99445aca`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r29`;
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
2. During GMMP's native Play/`WAIT_PLAY` settling, continuity rows may already be visible. They are safety slots and must not be deleted/re-added merely to rebuild Initial Size.
3. Let GoneSmart's selected-current recommendation preparation complete. Prepared GoneSmart Track IDs must retarget the first existing contiguous upcoming native queue rows **in place**; native Track IDs may remain only for a genuine prepared-pool shortfall.
4. Verify startup contains no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR`. If the selected source does not already expose enough future slots, only the exact slot deficit may be requested through bounded `PRIME_NATIVE` before provider work.
5. Verify `TRACK MIX RETARGET | verified=true` and `MIX INITIAL RETARGET | verified=true` occur before `MIX AUTO-DJ | command=sent-after-in-place-retarget`.
6. Let playback cross from the selected seed into the first generated follow-up without manually skipping, pausing, power toggling or seeking. Track 2 must actually open a decoder/start audio, not merely change metadata/scrobbling.
7. Verify subsequent managed refill still works after the natural Current transition.

Required runtime evidence: `current+1` never disappears during the Track Mix startup mutation; there is no startup `next audio source is null`; the queue rows used as native continuity slots keep their native queue IDs/positions while their Track IDs are retargeted; surplus rows are deleted only after rewritten `current+1` verifies; there is no second Initial-Size `qr.z(4)` after Smart preparation; and the first generated Current produces a real decoder/audio start.

The r27 same-position Current refresh is intentionally absent: device evidence proved it could verify while Track 2 still failed to decode. The r28 clear/pre-command-fill ordering is also retired: device evidence proved `CLEAR_QUEUE` itself could make MusicService cache a null next AudioSource before the later refill started.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.


## r30 regression focus

- Playlist Link retains the r29 requirement: `BRIDGE TOOLBAR LINK` then `toolbarAdd=true | edit=false | intercepted=true`, followed by the two-way `Smart Playlist` / `Playlist` chooser.
- Track Mix must log `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` **before** `MIX START | ... nativePlay=dispatched`. If semantic target resolution is ambiguous, Track Mix must fail before native Play rather than guess.
- The first target-owned native refill must log `MIX PREPLAY BOOTSTRAP OFFER`, `SMART DJ TRACK MIX BOOTSTRAP` and `MIX PREPLAY NATIVE REFILL`. GoneSmart Track IDs must therefore be present when GMMP first materializes/preloads the upcoming rows; there must be no earlier unrelated Track-2 AudioSource/decoder that is later rewritten.
- 4.2.1 startup must contain no `CLEAR_QUEUE`, no post-materialization Smart Track-ID retarget, and no playback-refresh workaround. Queue DAO use after bootstrap is prune-only. The first generated Track must naturally open its decoder and play audio.
