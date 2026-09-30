# Smart-Playlist folders

**Current status (30 September 2026): feature-complete on the maintainer's tested GoneMAD Music Player 4.2.0 setup.** The feature is available in both debug and release build variants of the v0.4 development branch. This is not a claim about other GMMP versions, skins or a published v0.4 release.

## Companion options

Under **UI → SMART-PLAYLISTS**:

- **Folders** — enables physical nested-folder navigation in GMMP's Smart-Playlists tab.
- **Multi-selection** — when Folders is enabled, long-press a native Smart-Playlist row, select more Smart-Playlists and move them together.
- **Group root Smart-Playlists** — places root `.spl` files inside virtual **Other Locations** while physical folders remain visible at the Smart root.

The companion labels intentionally mirror **UI → PLAYLISTS**. Their descriptions explain the different surface-specific action.

## Native-row contract

GMMP's real Smart-Playlist rows must stay real. The accepted implementation keeps the original `smartListRecyclerView`, `ls4` adapter, `vs4` holders and `ws4` models visible and interactive.

GoneSmart adds only:

- a fixed native-styled breadcrumb;
- a physical-folder band above the native rows;
- folder overflow actions;
- selection/Move chrome when GoneSmart multi-selection is active;
- the existing Smart-Playlists drawer item's GoneSmart sparkle while the feature is enabled.

Do not hide the native RecyclerView and redraw Smart-Playlist rows in a synthetic list.

## Folder snapshot and navigation

The configured Smart root is resolved through GMMP's original Smart-Playlist root path. For the current physical directory, GoneSmart:

1. scans direct child directories and `.spl` files on one background generation;
2. publishes physical folders from that same scan;
3. parses current-directory Smart files using GMMP's original `ws4.r(File)`;
4. sorts native models with GMMP's original Smart sorting path/preferences;
5. submits only changed ordered snapshots through the original `ls4.y` differ.

Android Back and the breadcrumb navigate upward through physical folders. Navigation resets the finite folder-band scroll position and waits for the new native list layout before re-enabling scroll coupling.

## Shared Playlist-folder chrome

Where the two GMMP surfaces can share UI, they must share implementation:

- `PlaylistFolderUiKit` owns native row cloning/styling, folder glyphs, overflow fallback, delete-only popup plumbing, selection presentation primitives and the segmented breadcrumb.
- `PlaylistFolderMoveChrome` owns contextual ActionMode, native AestheticFab creation, live `!mainColorAccent` observation, bar tint and cleanup.

Normal Playlist folders remain the visual reference. Smart-specific code exists only for the genuinely different `ws4/ls4/vs4` data/rendering boundary.

## Scrolling and native stretch

GMMP's Smart list and the synthetic physical-folder band must move as one visual surface while the breadcrumb stays fixed.

The accepted 30 September implementation:

- observes the host RecyclerView across the AndroidX classloader boundary through original host callbacks;
- uses the native RecyclerView's consumed scroll delta as the authoritative long-range scroll distance;
- clamps only the visible folder-band translation, not the stored native scroll distance;
- forwards a drag that begins on a synthetic folder row as the original MotionEvent stream to the host RecyclerView after touch slop, preserving native velocity/fling behavior;
- mirrors the host RecyclerView EdgeEffect distance into a framework EdgeEffect for native stretch rendering rather than approximating it with `scaleY`.

Do not restore scrollbar estimates, per-MOVE `scrollBy`, global-layout popup tracking or a synthetic scale animation.

## Multi-selection

Source inspection showed GMMP generic selection structures capable of storing `ws4`, but device testing proved native Smart-row long press does **not** start that ActionMode on the tested 4.2.0 runtime.

The accepted behavior is therefore extension-owned selection over verified native rows:

- long-press a bound `vs4` whose exact `ws4` path is known;
- start a contextual ActionMode with GMMP's localized `num_selected` wording;
- while selection is active, taps toggle only verified native Smart rows;
- apply the same 50%-alpha live accent selection overlay used by accepted normal Playlist selection;
- outside selection mode, original Smart row touch/click/context behavior is unchanged.

The generic native-selection observation remains only a compatibility concept; the tested runtime must not depend on it.

## Move safety

GMMP 4.2.0 exposes no native physical `.spl` Move writer. Smart Move is therefore a guarded GoneSmart filesystem transaction rather than a speculative call into an unverified writer.

Rules:

- sources and destination must remain inside the configured Smart root;
- only verified `.spl` files are moved;
- existing destination files are never overwritten;
- atomic move is preferred, with normal `Files.move` fallback;
- a multi-move rolls back earlier completed items if a later item fails;
- before moving, GoneSmart parses every Smart-Playlist under the root and recursively checks native linked-Smart rules;
- if another native Smart-Playlist references any selected source by absolute `.spl` path, the move is blocked;
- Playlist Link sentinel rules are excluded from that native-link test.

After verified success, GoneSmart uses GMMP's complete localized `playlist_saved` phrase; if unusable, the fallback is a neutral checkmark. Failures use native error wording/neutral fallback.

## Folder creation

On the verified GMMP 4.2.0 path, GoneSmart reuses the native **New Playlist** creation shell rather than constructing a visually similar dialog from scratch. The native MaterialDialog owns theme, focus, cursor, keyboard and animation. GoneSmart changes only the requested noun/hint and consumes the verified callback to create exactly one canonical direct child directory instead of a `.spl`.

The latest device correction captures live `tp3` presenters from constructors and keeps `y2` only as a secondary observer. The older `showNewFolderCreator` path remains a guarded fallback; because that native helper creates a child dialog, the fallback show scope tracks the actually displayed MaterialDialog.

The tested fallback dialog keeps its live Aesthetic accent for its entire lifetime, reuses the native Cancel button/listener and suppresses the unwanted floating “New Folder Name” caption.

## Folder deletion

A physical Smart folder's three-dot button reuses GMMP's Smart context-menu Delete title/icon. Deletion delegates to the already verified original `py0.b(Context,List<th1>)` confirmation/worker for indexed Smart files. GoneSmart refuses unsafe unexpected subtree contents and only completes already-empty directory cleanup within the existing guarded policy.

Folders themselves are not movable.

## Drawer badge and first frame

The Smart-folder surface must publish a **single atomic visible frame**. GMMP may refresh its root `List<ws4>` while the user is inside a nested physical folder (notably when returning from a Smart-Playlist detail). GoneSmart masks that transient native root submit before it can draw, rebuilds the remembered folder snapshot, and reveals the synthetic folder chrome plus native `ls4/vs4` rows only after the native AsyncListDiffer reports the expected projection. Initial tab opening likewise builds one complete folder + Smart-row snapshot instead of exposing a header-only intermediate frame. A bounded fail-open remains only to avoid trapping the player on an invisible list if an unknown GMMP runtime never commits the expected adapter state.


The sparkle is attached only to GMMP's existing native **Smart-Playlists** drawer item while Smart folders are enabled. Matching explicitly excludes the normal Playlists entry.

For first-frame stability, GoneSmart stages one current-directory scan/generation: physical folder chrome can appear before expensive Smart model parsing finishes, but raw root Smart rows must not flash before the current-folder snapshot is ready. There is no second competing “fast header” loader.

## Device acceptance

The maintainer's tests across 28–30 September 2026 accepted the final architecture after earlier discarded implementations. The accepted current setup includes:

- native Smart rows remaining visible;
- nested folder navigation;
- physical folder rendering and shared breadcrumb geometry;
- root grouping;
- single and multi Smart Move;
- native-link move blocking;
- folder Delete;
- Smart drawer badge;
- selection tint/action mode;
- first-frame staging;
- folder-row and native-row scrolling;
- native top/bottom stretch behavior;
- native creation-shell/fallback dialog appearance, focus, keyboard and Cancel behavior.

Historical black-row, `ws4 -> t23`, keyed-tag, popup relayout and scroll-authority failures are no longer the current contract and should not be reintroduced.

## Compatibility boundaries

- Tested target: GMMP **4.2.0**.
- Alternative GMMP versions and skins remain unverified.
- The feature depends on obfuscated internal models/resources.
- Custom/changed Smart root behavior must continue to be resolved through the verified GMMP path rather than guessed.
- CI verifies source/build behavior; real injected UI still requires device testing after relevant changes.

See [Playlist folders](PLAYLIST_FOLDERS.md), [Playlist Link](SMART_PLAYLIST_LINKS.md), [native audit](NATIVE_GMMP_AUDIT.md), [design system](DESIGN_SYSTEM.md), and [AGENTS.md](../AGENTS.md).
