# GoneSmart v0.4.0 (upcoming)

Smart Auto-DJ remains the core feature; this update adds a separately controlled GMMP interface extension and companion-app update awareness.

## New: Flip Queue / Play Flipped

- Optional **Flip queue / Play flipped** under **UI → Playback & Queue**;
  independent of Smart DJ and disabled by default.
- The Queue overflow can reverse every queue entry while the currently
  playing/paused track follows its new position, preserving its identity
  and playback state.
- Playlist and Smart Playlist row menus offer **Play Flipped**, playing
  the original last track first and continuing through to the original
  first. GMMP resolves Smart Playlist membership before reversal.
- Localized native Play/Queue labels, two spaced typographic arrows,
  GoneSmart's full-size lilac two-star sparkle, and native menu row height.
- Uses GMMP's native queue/playlist APIs, verifies resulting order, and
  attempts to restore the previous queue if the native queue update fails.
- **Device validation (GMMP 4.2.0):** 31-track queue reversal (current
  position 8 → 24), 17-track playlist and Smart Playlists of 31 and 140
  tracks all passed native queue/order verification. The user also
  reported the feature working on-device. Other GMMP versions and every
  possible playback/queue race have not been tested.

## New: Track Auto-DJ

- Third item after **Play** and **Play next** in individual-song
  three-dot menus (queue, track library, playlist details, search,
  file browser and shared tracks).
- Plays the selected song, isolates it as the first/seed queue entry,
  enables Smart DJ if it was disabled and switches GMMP into Auto-DJ.
  Initially fills the queue to GMMP's configured **Initial Size** and
  then continues using the normal Upcoming Tracks setting.
- Uses GMMP's native playback commands and GoneSmart's existing
  recommendation pipeline. Includes a timeout, conservative queue
  verification, and safeguards against dispatching multiple mixes.
- **Track Auto-DJ** now has an independent live switch under **UI → Playback & Queue** (enabled by default for existing users).
- The selected seed and its exact native queue-entry ID are preserved in one native Room transaction. New old-session Auto-DJ refills are held briefly during native Play and isolation. Instead of retrying an asynchronous Clear Queue command, GoneSmart atomically removes every other queue entry and updates the playback pointer before native Auto-DJ fills the queue.
- **One localized confirmation only** after verified success; native GMMP Play/Clear/Auto-DJ Toasts and Snackbars—including delayed Auto-DJ-rules-changed UI—are suppressed only during the bounded Track Auto-DJ transition. Important failures still produce one warning and are recorded in Logs.
- The GoneSmart companion app always uses English **Track Auto-DJ** in its UI, Logs and Help. GMMP's own context menus compose the name from its localized **track** and **Auto-DJ** resources (for example, German **Titel Auto-DJ**). The confirmation reuses GMMP's translated *started* resource when available.
- **Feature complete in the development branch:** the maintainer reports Track Auto-DJ working on-device with GMMP 4.2.0. The 24 September development log contained six successful five-track starts and one intermittent failure with the older asynchronous queue-clearing path, now replaced by atomic native queue-ID isolation. The maintainer retested Track Auto-DJ from an existing queue with the corrected build and reported no repeat failure; the targeted on-device regression is accepted as passed.

## In development: Playlist folders

- GMMP 4.2.0 read a playlist inside a physical main-root subfolder; the
  maintainer confirmed the native Add to Playlist operation worked.
- Read-only folder model supports multiple depths and two **independent**
  settings for placing external playlists and main-root playlists in
  virtual **Other Locations** or displaying them as loose entries.
- Settings keys and local/remote persistence have been staged, but native
  UI hooks and working folder controls are not yet implemented.
- External file moves left stale native playlist records. Before enabling
  native folder creation and Move Playlist, verify scoped GMMP database
  synchronization and rollback rather than requiring manual full cleanup.

## Expanded companion Logs

- Logs now covers **Smart DJ**, **multi-playlist selection**, **Flip**,
  **Track Auto-DJ** and **UI/System** events, with category labels and recent
  category totals.
- Native-verified Flip successes and failures/recovery attempts, playlist
  multi-add completion, and live UI option changes are shown in the app.
- UI activity does not overwrite the separate Smart DJ readiness and
  fallback status on Home. Detailed internal diagnostics remain in Logcat.

## New: UI extensions

- **Multi-playlist selection**, independently enabled under the new **UI** tab. Long-press a playlist in GMMP's Add to Playlist dialog, select multiple destinations and confirm once.
- Writes through GMMP's native playlist-add operation for every selected source file. Normal single-destination taps and create-playlist behavior are unchanged.
- Correct highlights while scrolling, GMMP's dynamic theme colors, native localized selection text, back cancellation and one aggregate completion message.
- A compact GMMP-style headphones icon replaces the Smart DJ sparkle-only bottom-navigation icon. The player itself keeps its colored sparkle status indicator.
- Home has balanced Smart DJ/UI feature summaries and a shared **Settings apply live** explanation, rather than repeating it under one feature.
- **Rating fallback** is disabled and unchecked whenever Minimum rating is off and Smart rating is disabled. It becomes selectable when either rating restriction is active.

## Update management

- **Home → Updates** checks GitHub's latest published stable release on companion-app launch and shows current/newer/development/error states.
- **Add to Obtainium** hands the repository over to Obtainium for update management. GoneSmart does not download or silently install APKs.

## Compatibility and limitations

- Currently tested with **GoneMAD Music Player 4.2.0**, **libxposed API 102**, rooted Vector v2.2+ and Android 8.0+.
- The optional LSPatch 1.2 path remains experimental. GMMP upgrades may change internal obfuscated hooks or localized string resources.
- A future release must be built and signed with the same release key to update existing installations.

## Release process

Merge and review the feature branch, verify on-device compatibility, then update the release tag and publish the signed APK through GitHub Actions. These notes describe the **upcoming release**, not an APK already published.
