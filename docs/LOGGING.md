# GoneSmart runtime logging contract

The companion app's **Logs** tab is a high-level activity feed, not a
mirror of Android Logcat. It covers every independently enabled feature
without confusing UI activity with Smart DJ health.

## Categories and event boundaries

| Category | High-level event examples | Report at |
| --- | --- | --- |
| `[System]` | Module loaded with its real installed version; enabled feature hooks ready | Startup / readiness |
| `[Smart DJ]` | Recommendation selection, offline/native fallback, rating fallback, stopped | Existing Auto-DJ runtime-state updates |
| `[UI]` | Playlist/Smart-Playlist folder, grouping, multi-selection and Playlist Link toggles; Flip / Track Auto-DJ toggles | Actual setting changes only |
| `[Playlists]` | Native multi-add batch completed with confirmed success count, partial completion, no confirmed success or no accepted destinations | Native result callback / terminal dispatch |
| `[Flip]` | Existing queue fully reversed and verified; Playlist/Smart Playlist fully reversed and verified; native failure or rollback result | After verified outcome, never on speculative invocation |
| `[Track Mix]` | Selected song started, new Auto-DJ queue verified, initial fill incomplete or native action failed | Only after playback/queue verification or an explicit failure |
| `[System]` | Module/feature readiness such as Playlist Link availability | Successful hook/binding readiness, not repeated UI inflation |

**Companion-language rule:** Internal event category `[Track Mix]` remains stable for stored records, but the English-only GoneSmart companion app displays **Track Auto-DJ** in its Logs labels and Help. GMMP context menus continue using native localized resources.

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
Use `GoneSmart`, `GoneSmartCompat`, `GoneSmartPlaylist`, `GoneSmartSmartFolders`,
`GoneSmartPlaylistBridge`, `GoneSmartFlip`, `GoneSmartTrackMix`,
`GoneSmartAudioProfiles` or `GoneSmartAudioApply` in Android Logcat for those details.
`GoneSmartAudioProbe` is development-only and is not a normal support filter.

**Summary counters:** the Logs header counts **Smart DJ**, **Playlists**, **Flip**, **Track Auto-DJ**, **UI**, and **System** separately. Only legacy/unknown categories remain under **Other**. Stored category name `[Track Mix]` remains stable for backwards compatibility; the companion renders it as **Track Auto-DJ**.

**Playlist Link:** the user-facing log records high-level System readiness while enabled and one `[UI]` event when its companion switch changes. Detailed Bridge evaluation belongs in Logcat under `GoneSmartPlaylistBridge`; no playlist name, source path or track content is needed in Companion Logs. Internal path diagnostics use privacy-safe hash/extension/length output.

**Audio Profiles:** normal route/profile application is event-driven and uses detailed Logcat rather than adding a companion-history row for every output or band mutation. Support diagnostics use `GoneSmartAudioProfiles` and `GoneSmartAudioApply`; only a future user-relevant terminal failure/state category should be promoted into the persistent event timeline.

**Storage:** `GoneSmartEventStore` retains the most recent 400
timestamped lines in the companion application's existing
`gonesmart_runtime` preferences. Existing legacy lines are retained
as-is until they age out or the user clears the log. Copy exports all
current lines. The Logs tab shows category totals and puts uncategorized
older or UI/System lines in **Other**.

## On-device detailed diagnostics (opt-in)

The companion **Logs → Debug Capture** section now offers:

- **Save last 5 minutes:** captures the surviving Android Logcat ring-buffer entries for GoneSmart and GMMP without requiring the recorder to have been running beforehand, then opens Android's standard ZIP sharing chooser. This is best for sporadic issues noticed immediately after they occur; Android may have overwritten some or all of the requested window.
- **Start recording / Stop recording:** starts an explicitly opted-in, visible foreground diagnostic service. Its logcat stream is filtered to the two Linux UIDs, and it rotates four 1.5 MB files under app-private storage (approximately 6 MB maximum). This remains enabled across GMMP restarts while Android allows the service to run. It is not enabled automatically on app installation or boot.
- **Share recorded log:** exports the retained segments as a ZIP through a narrowly scoped FileProvider. Cache exports are limited to the five newest ZIPs.
- **Copy:** still copies only the concise persistent event history, independent of detailed Logcat collection.

Android does not normally allow an ordinary app to read GMMP's logs. This feature requires the user's explicit **one-time ADB setup** after installing a build with `android.permission.READ_LOGS` declared:

```bash
adb shell pm grant io.github.alagga.gonesmart android.permission.READ_LOGS
```

To revoke: `adb shell pm revoke io.github.alagga.gonesmart android.permission.READ_LOGS`.

The system-wide log reading capability is powerful; even though GoneSmart restricts captured output to GMMP and GoneSmart UIDs, the resulting logs may contain song names, local paths or other personal information. Users must review exported files before distributing them. No logs are sent automatically. If permission or logcat is unavailable, diagnostics fail with a user-facing explanation; they must not falsely claim that a valid GMMP trace was collected. This is a device-test-pending RC diagnostic tool, not a regression-tested alternative to full `adb logcat` capture on every Android ROM.

## Regression checklist for any new feature

1. Identify an unambiguous category in `GoneSmartRuntimeContract`.
2. Report exactly one terminal success or failure event per user action,
   with useful aggregate counts but no personal track/file metadata.
3. Do not replace Smart DJ status from a UI-only action.
4. Check that the latest events and category totals appear on the
   companion Logs tab, and that **Copy** preserves those entries.
5. Keep internal diagnostics in Logcat; don't flood the companion log
   with repeated menu openings, individual recommendation candidates
   or infrastructure callbacks. Temporary reverse-engineering probes
   should be removed after their native boundary is accepted unless they
   remain a bounded compatibility diagnostic with a documented purpose.
6. Update unit tests for `GoneSmartLogSummary`, and perform the relevant
   Android device smoke test before release.
7. After compatibility acceptance, a successful structural binding/mapping is
   logged at most once per stable runtime shape. Do not key success diagnostics
   by changing queue size/current position or recreate their de-duplication set
   per reader instance. New/ambiguous/failing runtime shapes may still emit the
   bounded rich diagnostics needed for the next compatibility investigation.

The Flip-specific native queue/order checks and on-device results are
documented in [QUEUE_FLIP_TESTING.md](QUEUE_FLIP_TESTING.md).
