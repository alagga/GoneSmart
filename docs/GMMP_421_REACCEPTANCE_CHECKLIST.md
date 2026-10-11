# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

Repair source commit: `7da5bf88695dd1339c17bbeb095a77e08d9ec98b`.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r28`;
- no temporary source-snapshot/apply workflow remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger Link.
2. Verify the GoneSmart type menu appears first with **Smart Playlist** and **Playlist**.
3. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
4. Trigger Link again, choose **Playlist**, select a normal Playlist, and verify the list closes without a MaterialDialogs callback crash.
5. Save and reopen the Smart Playlist; verify the normal-Playlist link is still represented.
6. Change the source Playlist and verify the Smart Playlist evaluates against the updated source.
7. Reopen/edit/save once more to prove persistence rather than only in-memory behavior.

Required runtime evidence: bridge bindings ready, `as4$g.accept(...)` is intercepted after the native toolbar action, the editor's real Activity/menuLink anchor is retained so the type menu can be shown from that callback, the Smart branch resumes the captured continuation once, the Playlist branch returns the host-compatible dialog callback singleton (no `uf5 -> ag5` cast), and no fail-closed binding exception occurs.

## Track Mix

1. Start Track Mix on a deliberately identifiable selected song.
2. Let the first generated follow-up appear and verify it is selected through the same GoneSmart recommendation/quality-gate path when a usable match exists; native selection is allowed only for a genuine prepared-pool shortfall/fallback.
3. Before native `AUTO_DJ` is sent, verify the selected seed remains Current and the complete configured Initial Size is already present.
4. Let playback cross from the selected seed into the first follow-up without manually skipping, pausing, power toggles, or seeking. The generated row must actually open a decoder/start audio, not merely change metadata/scrobbling.
5. Verify Initial Size remains intact and subsequent managed refill still works after the natural Current transition.

Required runtime evidence: selected-current pool preparation and native Auto-DJ prime complete before `CLEAR_QUEUE`; the clear preserves the same Current Track ID even if `queue_id` changes; prepared Smart rows are requested before any native remainder; `MIX PRE-COMMAND FILL | verified=true` occurs before `MIX AUTO-DJ | command=sent-after-initial-fill`; no startup `next audio source is null` is cached for position 2; the recommendation session is not reset solely because GMMP recreated the Current queue row; and the first generated Current produces a real decoder/audio start. The r27 same-position `MIX PLAYBACK REFRESH` path is intentionally removed because device evidence proved it could verify while Track 2 still failed to decode.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.

## r28 regression focus

- Playlist Link: tapping the editor Link action must first show `Smart Playlist` / `Playlist`; choosing Smart Playlist must then enter GMMP's native Smart-Playlist chooser; choosing Playlist must enter GoneSmart's playlist chooser without crashing.
- Track Mix: after `CLEAR_QUEUE`, logs must show `MIX PRE-COMMAND FILL | verified=true` before `MIX AUTO-DJ | command=sent-after-initial-fill`; there must be no `next audio source is null` during startup, and the first generated Track must naturally decode/play without pause/power toggles.
