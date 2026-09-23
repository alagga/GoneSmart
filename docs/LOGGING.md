# GoneSmart runtime logging contract

The companion app's **Logs** tab is a high-level activity feed, not a
mirror of Android Logcat. It covers every independently enabled feature
without confusing UI activity with Smart DJ health.

## Categories and event boundaries

| Category | High-level event examples | Report at |
| --- | --- | --- |
| `[System]` | Module loaded with its real installed version; enabled feature hooks ready | Startup / readiness |
| `[Smart DJ]` | Recommendation selection, offline/native fallback, rating fallback, stopped | Existing Auto-DJ runtime-state updates |
| `[UI]` | Multi-playlist selection enabled/disabled; Flip enabled/disabled | Actual setting changes |
| `[Playlists]` | Native multi-add batch completed with confirmed success count, partial completion, no confirmed success or no accepted destinations | Native result callback / terminal dispatch |
| `[Flip]` | Existing queue fully reversed and verified; Playlist/Smart Playlist fully reversed and verified; native failure or rollback result | After verified outcome, never on speculative invocation |

**Status isolation:** `GoneSmartRuntimeReporter.report()` continues to
update the Home tab's Smart DJ readiness and fallback state.
`reportEvent(category, message)` uses an **event-only** broadcast. The
companion receiver appends it to the log without overwriting the Home
runtime status. UI and Flip features work independently of Smart DJ.

**Avoid duplicate success reports:** Mark an event as complete only after
native callbacks/queue verification confirm the actual result. An initial
Flip click, playlist dispatch and successful reverse are different stages.
Don't add repeated menu inflation, individual track IDs, full playlist
paths or internal reflective class names to the user-facing log.
Use `GoneSmart`, `GoneSmartPlaylist` or `GoneSmartFlip` in Android
Logcat for those details.

**Storage:** `GoneSmartEventStore` retains the most recent 400
timestamped lines in the companion application's existing
`gonesmart_runtime` preferences. Existing legacy lines are retained
as-is until they age out or the user clears the log. Copy exports all
current lines. The Logs tab shows category totals and puts uncategorized
older or UI/System lines in **Other**.

## Regression checklist for any new feature

1. Identify an unambiguous category in `GoneSmartRuntimeContract`.
2. Report exactly one terminal success or failure event per user action,
   with useful aggregate counts but no personal track/file metadata.
3. Do not replace Smart DJ status from a UI-only action.
4. Check that the latest events and category totals appear on the
   companion Logs tab, and that **Copy** preserves those entries.
5. Keep internal diagnostics in Logcat; don't flood the companion log
   with repeated menu openings, individual recommendation candidates
   or infrastructure callbacks.
6. Update unit tests for `GoneSmartLogSummary`, and perform the relevant
   Android device smoke test before release.

The Flip-specific native queue/order checks and on-device results are
documented in [QUEUE_FLIP_TESTING.md](QUEUE_FLIP_TESTING.md).
