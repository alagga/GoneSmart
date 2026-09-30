# GoneSmart v0.4.0 (upcoming)

> **Development snapshot — 30 September 2026.** These notes track the current v0.4 branch and are not final release notes yet. More features may land before v0.4 is tagged. Current device acceptance refers to the maintainer's GoneMAD Music Player 4.2.0 setup unless stated otherwise.

Smart Auto-DJ remains the core feature. v0.4 expands GoneSmart into a broader set of native-looking GMMP quality-of-life extensions while keeping playback, playlist writing and Smart-Playlist evaluation as close to GMMP's original paths as possible.

## Playlist and Smart-Playlist organization

### Playlist folders

- Optional **UI → Playlists → Folders** view for nested physical playlist folders in both GMMP's Playlists tab and Add to Playlist picker.
- Folder creation reuses GMMP's native playlist-creation shell on the verified 4.2.0 path, with only the folder-specific text/action redirected; a guarded native folder-creator fallback remains for unsupported mappings.
- Physical folder deletion uses GMMP's original delete confirmation/worker. GoneSmart removes only already-verified empty directories after native playlist deletion completes.
- One or several playlists can be moved with the shared native-styled destination browser and white-check FAB. The move transaction stages a recoverable copy, invokes GMMP's original delete and rescan/index paths and verifies the result.
- **Group external playlists** and **Group root playlists** independently control the virtual **Other Locations** node.
- The existing native Playlists drawer entry receives GoneSmart's lilac sparkle only while the feature is enabled.
- Accepted on the maintainer's GMMP 4.2.0 device, including nested navigation, picker/root behavior, creation, deletion, multi-selection, Move, first-frame behavior, native dialog focus/accent and overscroll/animation parity.

### Smart-Playlist folders

- First-frame/return staging now keeps an already-correct physical-folder projection continuously visible: GMMP's redundant root-only Smart-list submit is bypassed once that projection is prepared, so returning from a nested Smart-Playlist no longer needs a hide/reveal blackout. First construction still masks any transient root dataset until the remembered projection has committed, and initial tab opening no longer exposes the former header-first layout shift.

- Separate **UI → Smart-Playlists → Folders** option; independent from normal Playlist folders.
- Real Smart-Playlist entries remain GMMP's original `ls4/vs4/ws4` rows. GoneSmart adds only the physical-folder header, breadcrumb and folder actions.
- Shared folder chrome is reused through `PlaylistFolderUiKit` and `PlaylistFolderMoveChrome` rather than maintaining a second visual implementation.
- **Group root Smart-Playlists** optionally moves root `.spl` entries into virtual **Other Locations**.
- **Multi-selection** long-presses verified native Smart rows and moves several selected Smart-Playlists together. This is extension-owned on the tested runtime because GMMP's generic selection structures do not actually start the Smart-row ActionMode there.
- Smart moves are blocked when another native Smart-Playlist links to a selected `.spl` by absolute path. Multi-item moves roll back earlier files if a later move fails.
- Folder Delete reuses GMMP's native Smart delete label/icon and original delete workflow.
- Accepted on the maintainer's GMMP 4.2.0 device through 30 September 2026, including scrolling/stretch, first-frame staging, drawer badge, folder creation and single/multi Move.

## Playlist Link

- New independent **UI → Smart-Playlists → Playlist Link** switch, enabled by default to preserve the behavior of existing development installs.
- GMMP's original Smart-Playlist editor Link action offers **Smart-Playlist** or ordinary **Playlist** while the option is enabled.
- Choosing Playlist creates a live membership rule backed by the selected ordinary playlist. Membership is read through GMMP's original playlist parser and compiled into GMMP's native Smart query predicates; no static track snapshot is stored.
- Add/save/reopen/edit, normal Smart display/playback and dynamic source changes were verified on-device.
- The V2 persisted representation keeps saved Smart-Playlists usable when GoneSmart is disabled on the tested setup. The new Bridge switch uses the same native fallback path: saved Bridge leaves remain visible but become boolean-neutral while the option is off, and resume live membership after re-enabling it.
- Missing, empty or unreadable Bridge sources fail closed while GoneSmart is active.
- The feature now installs its functional hooks in both debug and release build variants. Old reader/query/save exploration probes were removed from the shipping path.

## Multi-selection

- **UI → Playlists → Multi-selection**: long-press a destination in Add to Playlist, select more playlists and confirm once. GMMP's own native playlist-add operation performs each write.
- **UI → Smart-Playlists → Multi-selection**: long-press native Smart rows and move several Smart-Playlists together when Smart folders are enabled.
- The companion labels are intentionally parallel while their descriptions state the surface-specific action.
- Selection colors follow GMMP's live theme and selection is keyed to verified playlist paths rather than recycled row identity.

## Flip queue / Play flipped

- Optional **UI → Playback & Queue → Flip queue / Play flipped**, disabled by default.
- Reverse the entire current queue while the current queue entry, play/pause state and playback progress follow their new position.
- Playlist and Smart-Playlist menus can play the native resolved list from last item to first.
- Uses native queue/playlist APIs and verifies the resulting order; the queue path attempts rollback if its native update fails.
- Device-validated on GMMP 4.2.0 with queue, ordinary-playlist and Smart-Playlist cases.

## Track Auto-DJ

- Native-looking per-track action directly after Play next on supported individual-song menus.
- Starts the selected song as the seed of a fresh queue, enables Smart DJ when needed and lets GMMP Auto-DJ fill to its configured Initial Size.
- Uses native queue-entry IDs and one native Room transaction to isolate the selected entry before refill, avoiding the older asynchronous clear-queue race.
- Shows one verified localized confirmation; intermediate native startup Toast/Snackbar noise is suppressed only inside the bounded transition.
- The companion consistently calls the feature **Track Auto-DJ**; injected GMMP text is composed from GMMP's localized resources.
- Accepted on-device after the queue-isolation correction.

## Companion UI and logs

- UI settings are grouped into **PLAYLISTS**, **SMART-PLAYLISTS**, and **PLAYBACK & QUEUE**.
- Both playlist groups now use the same concise **Multi-selection** and **Folders** labels, with truthful descriptions of the different actions.
- Folder/grouping/multi-selection and Playlist Link setting changes generate high-level `[UI]` log events.
- The Logs summary counts **Smart DJ**, **Playlists**, **Flip**, **Track Auto-DJ**, **UI**, and **System** separately; only legacy/unknown entries remain under **Other**.
- Playlist Link readiness is recorded as a high-level System event without exposing playlist names or paths.
- Internal Logcat keeps feature-specific tags; exploratory native-save/reader/surface probes that were only needed during reverse engineering have been removed.

## Release hardening completed in this branch

- Playlist Link moved out of its obsolete debug-PoC gate and now has a normal functional hook path.
- Obsolete native playlist-save traces, Smart-folder loader diagnostics, move-discovery probes and playlist-surface discovery logging were removed.
- Bridge path logging remains privacy-safe; file paths are reduced to extension/hash/length where diagnostic output is still needed.
- Companion Help, README, architecture, logging and feature docs were synchronized with the accepted current behavior.
- No broader compatibility claim is made: GMMP 4.2.0 remains the tested target and injected UI still depends on obfuscated internals.

## Compatibility

- Tested target: **GoneMAD Music Player 4.2.0**
- Android: **8.0+**
- libxposed API: **102**
- Vector v2.2+ remains the recommended rooted path.
- LSPatch 1.2 remains experimental / less tested.
- Alternative GMMP versions, custom playlist roots and untested skins/languages remain explicit compatibility work.

## Before the actual v0.4 release

Continue normal feature development on the current branch. Before tagging v0.4, run the complete CI/release checklist, repeat device smoke tests for any subsequently touched injected surface, finalize these notes and publish a signed build through the documented release workflow.
