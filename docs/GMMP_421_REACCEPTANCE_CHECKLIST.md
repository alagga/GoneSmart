# GMMP 4.2.1 bundled re-acceptance

This checklist is the single device pass for the current Playlist Link / Track Mix repair candidate. Do not split it into exploratory builds unless one of the independent postconditions below fails and the exact-head log proves a new boundary.

## Before device install

- exact branch head is recorded by `BuildConfig.GIT_REVISION`;
- normal `Build` CI passes unit tests, Debug APK and unsigned Release smoke test on that exact head;
- Compatibility startup log reports revision `gmmp421-r26`;
- no temporary source-snapshot/apply workflow remains in the repository.

## Playlist Link

1. Open an existing Smart Playlist editor.
2. Trigger Link and verify the GoneSmart choice path opens instead of silently doing nothing.
3. Choose a normal Playlist and save.
4. Reopen the Smart Playlist and verify the link is still represented.
5. Change the source Playlist and verify the Smart Playlist evaluates against the updated source.
6. Reopen/edit/save once more to prove persistence rather than only in-memory behavior.

Required runtime evidence: bridge bindings ready, at least one editor/chooser hook installed, native chooser continuation remains pass-through for ordinary GMMP choices, and no fail-closed binding exception.

## Track Mix

1. Start Track Mix on a deliberately identifiable selected song.
2. Let the first generated follow-up appear and verify it is selected through the same GoneSmart recommendation/quality-gate path when a usable match exists; native selection is allowed only for a genuine prepared-pool shortfall/fallback.
3. Let playback cross from the selected seed into that first follow-up without manually skipping.
4. Verify there is no `next audio source is null` gap or stalled transition.
5. Verify Initial Size is reached and subsequent managed refill still works after the natural Current transition.

Required runtime evidence: selected-current pool preparation and native Auto-DJ prime complete before `CLEAR_QUEUE`; the clear preserves the same Current Track ID even if `queue_id` changes; prepared Smart rows are requested before any native remainder; the recommendation session is not reset solely because GMMP recreated the Current queue row.

Device acceptance remains pending until this complete pass succeeds on the exact CI-built APK.
