# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current **r31** Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `b9e4aebc93598de684e54a9a78f083a5fc906e75`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r31`;
- no temporary source-snapshot/apply workflow or helper script remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger the toolbar Link action.
2. Verify GoneSmart logs `BRIDGE TOOLBAR LINK | native menuLink click observed`.
3. Verify the following proven `as4$g.accept(...)` boundary logs `toolbarAdd=true | edit=false | intercepted=true` and the GoneSmart type menu appears first with **Smart Playlist** and **Playlist**.
4. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
5. Trigger Link again, choose **Playlist**, select a normal Playlist, and verify the list closes without a MaterialDialogs callback crash.
6. In the editor, verify a native linked Smart Playlist renders with the host Smart-Playlist label and a GoneSmart ordinary Playlist bridge renders with the host Playlist label.
7. Save and reopen the Smart Playlist; verify the normal-Playlist link is still represented.
8. Open/evaluate the Smart Playlist and verify the linked ordinary Playlist contributes its actual members. `BRIDGE SOURCE IDS` must resolve real library IDs; there must be no `GMMP direct query carrier initialization failed` and no Long.MIN fail-closed sentinel for a valid source.
9. Change the source Playlist and verify the Smart Playlist evaluates against the updated source.
10. Reopen/edit/save once more to prove persistence rather than only in-memory behavior. A genuine existing-rule edit must still work without a recent toolbar-add marker.

Required runtime evidence: bridge bindings ready; the exact native toolbar View is observed without replacing GMMP's listener; `as4$g.accept(...)` is intercepted as Add only after that toolbar click even if GMMP's selected-index field is still non-null; the Smart branch resumes the captured continuation once; the Playlist branch returns the host-compatible dialog callback singleton; ordinary-playlist membership resolves through the parameter-free cached Track-URI index; and no fail-closed binding exception occurs.

## Track Mix

1. Start Track Mix on a deliberately identifiable selected song.
2. Verify `MIX CLICK` is logged. GoneSmart must have a live accepted Auto-DJ instance through an already captured reference or passive context-graph recovery; zero/multiple candidates fail closed without issuing a discovery command.
3. GoneSmart must resolve that clicked Track before native Play from bounded semantic evidence and prepare the immutable selected-current recommendation IDs.
4. Verify `MIX PREPLAY TARGET | resolved=true` and `MIX PREPLAY SMART POOL` occur **before** `MIX START | ... nativePlay=dispatched`. If semantic target resolution is ambiguous, Track Mix must fail before native Play rather than guess.
5. The first target-owned native refill must log `MIX PREPLAY BOOTSTRAP OFFER`, `SMART DJ TRACK MIX BOOTSTRAP` and `MIX PREPLAY NATIVE REFILL`. GoneSmart Track IDs must therefore already be present when GMMP first materializes/preloads the upcoming rows.
6. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Queue DAO use after bootstrap is prune-only.
7. There must be no earlier unrelated Track-2 AudioSource/decoder that is later rewritten. Native Track IDs may remain only for a genuine prepared-pool shortfall.
8. Let playback cross from the selected seed into the first generated follow-up without manually skipping, pausing, power toggling or seeking. Track 2 must actually open a decoder/start audio, not merely change metadata/scrobbling.
9. Verify subsequent managed refill still works after the natural Current transition.

Required runtime evidence: `current+1` never disappears during Track Mix startup; there is no startup `next audio source is null`; prepared GoneSmart Track IDs are substituted into GMMP's native Auto-DJ selection before queue-row/playback materialization; there is no visible native-placeholder-then-replacement cycle; and the first generated Current produces a real decoder/audio start.

The r27 same-position Current refresh is intentionally absent: device evidence proved it could verify while Track 2 still failed to decode. The r28 clear/pre-command-fill ordering is also retired: device evidence proved `CLEAR_QUEUE` itself could make MusicService cache a null next AudioSource before the later refill started.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.

## r31 regression focus

- Playlist Link retains the r30 accepted dispatch path: native Link click -> `toolbarAdd=true | edit=false | intercepted=true` -> the two-way **Smart Playlist / Playlist** menu.
- Native Smart-Playlist rule labels and GoneSmart ordinary-Playlist bridge labels must remain distinct.
- Ordinary Playlist membership is resolved by a short-lived parameter-free `song_id`/`track_uri` index and canonical path matching, never by guessing non-zero direct-query argument fields.
- Track Auto-DJ passively recovers `qr` from proven natural state-writer calls and, if necessary, the bounded context-menu graph. Discovery itself never changes playback state.
- 4.2.1 Track Auto-DJ still must not use `CLEAR_QUEUE`, post-retarget a materialized `current+1`, or send a discovery command merely to obtain `qr`.
