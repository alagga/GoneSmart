# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r27`;
- no temporary source-snapshot/apply workflow remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor and trigger Link.
2. Choose **Smart Playlist** and verify GMMP's original Smart-Playlist chooser opens through the captured native callback.
3. Trigger Link again, choose **Playlist**, select a normal Playlist, and verify the list closes without a MaterialDialogs callback crash.
4. Save and reopen the Smart Playlist; verify the normal-Playlist link is still represented.
5. Change the source Playlist and verify the Smart Playlist evaluates against the updated source.
6. Reopen/edit/save once more to prove persistence rather than only in-memory behavior.

Required runtime evidence: bridge bindings ready, `as4$g.accept(...)` is intercepted after the native toolbar action, the Smart branch resumes the captured continuation once, the Playlist branch returns the host-compatible dialog callback singleton (no `uf5 -> ag5` cast), and no fail-closed binding exception occurs.

## Track Mix

1. Start Track Mix on a deliberately identifiable selected song.
2. Let the first generated follow-up appear and verify it is selected through the same GoneSmart recommendation/quality-gate path when a usable match exists; native selection is allowed only for a genuine prepared-pool shortfall/fallback.
3. Let playback cross from the selected seed into that first follow-up without manually skipping. The generated row must actually open a decoder/start audio, not merely change metadata/scrobbling.
4. A transient `next audio source is null` during `CLEAR_QUEUE` is tolerated only if the post-fill native playback refresh rebuilds the source before the seed ends; the natural transition itself must not stall.
5. Verify Initial Size is reached and subsequent managed refill still works after the natural Current transition.

Required runtime evidence: selected-current pool preparation and native Auto-DJ prime complete before `CLEAR_QUEUE`; the clear preserves the same Current Track ID even if `queue_id` changes; prepared Smart rows are requested before any native remainder; after Initial Size, `MIX PLAYBACK REFRESH` is verified on the already proven same-position writer; the recommendation session is not reset solely because GMMP recreated the Current queue row; and the first generated Current produces a real decoder/audio start.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.
