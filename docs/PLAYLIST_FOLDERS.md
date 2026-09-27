# Playlist folders — accepted feature on tested GMMP 4.2.0 setup (2026-09-28)

**Current status: FEATURE-COMPLETE ON MAINTAINER'S TESTED DEVICE.** The maintainer confirmed on 28 September 2026 that the final nested picker Back, native-list first-frame transition and Move FAB above the mini-player now work, along with the previously tested folder/navigation/creation/move flows. The chronological diagnostic notes below are historical; their old “not implemented”, “out of scope” and “pending test” statements do **not** describe the present branch. Moving one or multiple playlists is included in the implemented feature. Compatibility with other GMMP versions, alternate skins and native-speaker verification of all custom Move translations remains release-hardening work, not a claim of broader device testing.

## Navigation and two independent Other Locations choices

The real physical playlist folders in GMMP's main playlist directory
appear first in **both** the Playlists tab and the Add to Playlist picker.
Folders can contain more folders, without an artificial maximum depth.

GoneSmart's future English-only **Playlist folders** settings have an
independent feature switch plus two independent options:

- **Group external playlists:** whether playlist files outside the main
  GMMP playlist root (including external URIs) belong in **Other Locations**.
- **Group root playlists:** whether playlists stored directly in the main
  GMMP playlist root also belong in **Other Locations**.

| External option | Root option | Virtual Other Locations | Loose playlists below folders |
| --- | --- | --- | --- |
| On | On | External + main-root playlists | None |
| On | Off | External playlists | Main-root playlists |
| Off | On | Main-root playlists | External playlists |
| Off | Off | Absent | External + main-root playlists |

Order is always real physical folders, optional virtual Other Locations,
then loose playlists. If **Group root playlists** is enabled, the
virtual folder stays visible even when empty so the first main-root
playlist can be created from inside it. With root grouping disabled,
the virtual folder appears only if grouped external playlists exist.
A physical folder literally named "Other Locations" has a distinct
identity from the virtual folder; both are navigable. Both settings
only affect presentation, not file placement.

The existing multi-playlist picker must continue to support selecting
destinations across folders and invoking GMMP's native playlist writer.
Native GMMP menu terms continue to use live GMMP translations. The
GoneSmart companion app remains entirely English.

## Device findings — 24 September 2026

The maintainer moved two test playlists from
`/storage/emulated/0/gmmp/playlists/` into
`/storage/emulated/0/gmmp/playlists/GoneSmart Tests/`.
Moving them through the file manager left two empty, stale records at the
old paths in GMMP's playlist database. A subsequent GMMP scan added new
records without removing the old ones. After the maintainer moved the
test files out, cleaned GMMP's database, and rescanned, the playlists
appeared correctly. A track could then be added successfully to a
playlist in the subfolder.

The supplied Logcat shows the picker initializing normally and GMMP's
native `hp3` reader opening
`.../GoneSmart Tests/AAA-TestGoneSmart.m3u`.
The successful **write** was confirmed by the maintainer's on-device
observation, not by a dedicated native writer confirmation in that
short Logcat excerpt.

The maintainer subsequently tested a playlist several directory levels
deep. It appeared in both the normal Playlists tab and Add to Playlist
view, opened normally, accepted a native GMMP add, and participated in a
GoneSmart multi-destination add together with a playlist at a different
folder depth. That regression passed, so GoneSmart does not impose a
one-folder-depth limit.

A separate disposable M3U test started with a relative song path. After
adding a track through GMMP, GMMP rewrote the existing relative entry to
an absolute path too. This shows that GMMP's native write path normalizes
existing playlist entries, not only the newly added row. A future Move
action should therefore prefer a verified native GMMP save/rewrite path
after its database/file-path update instead of implementing an independent
M3U path converter.

## Step 1 — implemented and unit tested

`PlaylistFolderIndex` classifies native GMMP playlist paths using the
caller-supplied *actual* main playlist root. It handles all four grouping
combinations, external/content URIs, canonical-path deduplication,
distinct same-named playlists in different folders, empty folders
supplied by a read-only filesystem scanner, nested folders at arbitrary
depth, path traversal and physical/virtual name collisions.

`GoneSmartSettingsKeys`, `GoneSmartOptions` and
`GoneSmartSettingsRepository` persist the overall feature switch
(default off) and both grouping preferences (default on). Changing
these values must not reset the Smart DJ recommendation pool.

**Current limitation:** The tree classifier and preference persistence
are implemented. An opt-in **debug-only** read-only folder browser now
exposes the three switches in GoneSmart's companion UI. It overlays a
small Folders chip on native GMMP playlist RecyclerViews and browses a
preview tree without mutating native adapters. The final in-list folder
rows, in-folder native creation and native create-control visibility
are **not implemented** yet. The debug preview explicitly labels
possibly incomplete native model snapshots.

### Device diagnostic: normal tab vs Add picker

The first read-only diagnostic APK was tested on 24 September. It logged
`MULTI ROW BIND | native zn3.N0 hooks=1` and a fully initialized native
Add picker, but **no `FOLDER DISCOVERY` lines**. This does not establish
that the two native views use different adapters: the original probe only
logged when a `jo3` holder was both passed to `zn3.N0` and already
contained a readable `xn3.q` path.

The next diagnostic build logs bounded `FOLDER N0 CALL` argument types
whether or not a holder is present; observes RecyclerView
`setAdapter` and `onAttachedToWindow` independently of the known picker
adapter; and logs the picker's RecyclerView directly when captured by
`bo3.D1`. Inspect `FOLDER SURFACE`, `FOLDER N0 CALL` and
`FOLDER DISCOVERY` while opening the normal Playlists tab and the
native Add to Playlist picker. No playlist data or view changes occur.

### Device result: adapter structure, 24 September

The follow-up diagnostic Logcat established that the ordinary Playlists
tab and the Add to Playlist picker both use native adapter `zn3`
on separate `playlistListRecyclerView` views. The normal tab had
248 adapter items and showed `wp3` ViewHolders; the picker was observed
immediately after opening, before its rows populated.

The first surface probe incorrectly labeled the normal tab
`surface=add-picker` because it identified the picker solely from the
shared RecyclerView resource name. That label is now reserved for the
exact active picker instance; a same-named, uncaptured list is reported
as `playlist-list-uncaptured`.

Native `zn3.N0` takes `(int, t23, ViewGroup)` and returns `jw`.
The device log now proves that the ordinary Playlists tab receives
`wp3` while the Add picker receives `jo3`; both returned holders have
fields `A:xn3` and `B:int`. This strongly suggests one shared
`xn3` data path can drive folder grouping in both surfaces while their
row presentation and click behavior remain surface-specific.

The final bounded debug probe before the first real UI prototype logs
`FOLDER MODEL` for the returned holder's `A:xn3`, including its native
playlist path when available, and `FOLDER DATASET` for `zn3.i0()`
groups plus their `t23.r()` member types. After those structures are
mapped, broad diagnostics stop and implementation moves to read-only
folder navigation. The device's playlist data and native behavior remain
untouched.

### Playlist creation destination and action visibility

Creation always targets the currently viewed *physical* folder and never
opens a second folder chooser. The root grouping option controls which
of the two nonphysical navigation nodes may create directly in GMMP's
main playlist directory:

| Current location | Group root playlists ON | Group root playlists OFF |
| --- | --- | --- |
| Main/root view | Hide create action | Create in main root |
| Virtual Other Locations | Create in main root | Hide create action |
| Any physical subfolder | Create inside it | Create inside it |

The **Group external playlists** option does not change creation
permissions. Virtual Other Locations is kept navigable when root grouping
is ON even if no playlist exists, allowing creation of the first root
playlist. An *actual* physical directory named Other Locations follows
physical-folder rules, not the virtual node's rules.

Use the exact same decision for both surfaces. Hide only the native
create/add-playlist overflow item in the normal Playlists tab, and hide
the native **+** creation FAB in the Add to Playlist picker wherever
creation is forbidden. The latter **must remain available** as a
multi-destination *confirm* FAB while GoneSmart multi-select is active,
even in a folder where playlist creation is disabled. Guard the creation
callback as well as the visible control; cancel safely if navigation or
grouping settings change while an open creation dialog is pending.

This policy applies only while **Playlist folders** is enabled. When
the feature is disabled, preserve GMMP's ordinary playlist creation UI
and destination. No file relocation or GMMP database writes may occur
as part of folder-group presentation.

### Final model-binding probe

The previous device probe showed that `zn3.N0()` creates `wp3` in the
normal Playlists tab and `jo3` in the Add picker, but `A:xn3` is still
null at that creation point. The attempt to hook
`androidx.recyclerview.widget.RecyclerView$Adapter` failed on the tested
GMMP installation with `ClassNotFoundException`: that exact nested-class
name is not present in GMMP's obfuscated APK. Instead, the next
debug-only diagnostic reads the *actual visible ViewHolders* through the
verified native RecyclerView's `getChildViewHolder(view)` after layout
and examines their already bound `A:xn3.q`. No guessed adapter class name
or native playlist/database mutation is involved.

### First read-only folder-browser prototype

The 24 September bound-holder Logcat established that both native
playlist views expose actual `xn3.q` file paths after binding:
`wp3` in the normal Playlists tab and `jo3` in the Add picker.
The paths include the internal test tree down to
`GoneSmart Tests/Level2/Level3` and other playlists on the external
SD-card location. This validates stable path-based classification in
both native surfaces.

The opt-in debug-only preview adds a compact Folders entrypoint to
each native playlist RecyclerView. It uses the shared folder classifier,
with paths sourced from native playlist models. The earlier
`zn3.i0()`/`t23.r()` snapshot was confirmed to contain only
section/header data; GMMP reported 248 playlist rows while that
interface yielded no `xn3` models. Visible, fully bound native
rows exposed their paths but could not account for all entries.

The next diagnostic preview inspects native adapter backing fields,
bounded collections and the standard read-only `getItem(int)`
method to locate the authoritative complete `xn3` dataset.
It logs `FOLDER NATIVE SOURCE` and bounded
`FOLDER NATIVE TRACE` records on opening the preview, without
reading the playlist filesystem. Until the full source is proven,
the preview explicitly labels an incomplete snapshot **partial**.
Empty physical directories cannot be inferred from playlist paths and
require separate native-directory discovery or an explicitly justified
read-only folder enumeration later.

To avoid mistaking the SD-card playlist collection for GMMP's main
root, this experimental preview enables itself only when an observed
native playlist path proves that the primary storage directory's
`gmmp/playlists` directory is in use. This inference is **not**
a replacement for reading GMMP's actual configured playlist root;
that remains mandatory before enabling native creation or moves.

The preview deliberately leaves original native playlist lists
visible and does not yet filter them. Original native playlist
clicks, multi-select, overflow creation and picker FAB are unchanged.
Its purpose is to device-test nested navigation and both independent
grouping switches before altering the obfuscated native adapter.
The three folder-preview switches are displayed **only in debug
builds**, and the feature is disabled by default.

A temporary on-device prototype filled the incomplete root list by
reading 61 physical playlist files from GMMP's main folder. This fixed
root-grouping display but did not solve external playlists: the native
adapter still held 248 rows while only 15–70 bound paths had been cached,
so the virtual Other Locations view remained incomplete.

**Decision:** the physical-file fallback has been removed from the next
diagnostic build. All displayed playlist paths must come from GMMP's
native model source or its already bound native rows; no internal- or
external-storage playlist scan occurs. This can temporarily make the
root preview partial again until the complete native data structure is
identified. The primary playlist root is still inferred conservatively
from an observed native path. The final writable feature must read
GMMP's configured playlist root instead of relying on path inference.



### Full GMMP dataset and playlist display names — 24 September

A device test of the native adapter inspector at 23:01 returned
**248 distinct native xn3 playlist models for 248 adapter rows**,
with 61 models under the verified internal GMMP playlist root and
187 external models. The bounded traversal visited 516 objects and
reported no truncation or filesystem scan. This establishes snapshot
completeness relative to GMMP's ordinary Playlists adapter; it is
not an independent proof that the native database contains no
additional hidden/filtered playlist records.

The next combined device-test build reads text metadata on every
native xn3 model, including one-level nested native metadata fields
and semantically named read-only getters. It compares candidate
name fields against GMMP's actual TextView titles from visible,
already-bound wp3/jo3 rows, then selects a matching native name
field for the entire native dataset. Playlist identity remains
xn3.q, and the folder index sorts and displays the resulting
native names. In ambiguous cases it uses verified visible native
titles first and filename fallback only for otherwise unresolved
rows; it never invents a title.

To reduce on-device test cycles, this build also reports native
model counts and a stable snapshot fingerprint for each surface,
the title-field match and fallback statistics, and all four
combinations of the independent grouping options in one Folders
preview operation. Open the preview once in the normal Playlists
tab and once in Add to Playlist to test both native adapters.
All diagnostics are debug-only and read-only; there is no playlist
file scan, DB write, file move or native adapter replacement.



### First inline integration build

After the complete native dataset and display-name checks passed on both
surfaces, the next debug build removes the separate Folders-preview button.
When Playlist folders is enabled, GoneSmart places a scrollable folder view
directly over the native `playlistListRecyclerView` in both the normal
Playlists tab and Add to Playlist picker. The underlying `zn3` RecyclerView
stays VISIBLE, laid out and populated, but is rendered transparent. This
preserves GMMP's native `wp3`/`jo3` holders and click/add handlers.

Folder navigation itself is GoneSmart presentation state. Playlist identity
and titles come from the proven complete native `xn3` dataset. A playlist click
temporarily rebinds one already-bound native holder's `A:xn3` field to the
target model, invokes the row's native click/long-click synchronously, and
then restores the original model. The Add picker can also route inline
long-press/toggle selection into the existing GoneSmart multi-destination
state, whose final write still uses native `io3.r(Context, ie0)`.

Android Back first cancels an active multi-selection as before, then moves
one folder level up; at the folder root GMMP receives Back normally. If the
complete 248-model native snapshot, model objects or all native titles are
not available, the inline browser fails closed and leaves the original GMMP
list visible. No playlist file or GMMP database record is modified.

This build intentionally does **not** make native playlist creation
folder-aware yet. The already-defined creation visibility/destination policy
will be integrated only after inline navigation, native playlist opening,
picker single-add, multi-add, long-press and Back have passed together.



### Inline stability and native visual style (25 September)

The first inline-device test demonstrated that folder navigation reaches
nested levels and virtual Other Locations in the Add picker (248 native
models), but the normal Playlists tab crashed. In that view the playlist
RecyclerView is a direct child of FragmentContainerView; adding a custom
overlay there violates FragmentContainerView's child restrictions.
The stabilizing build attaches the browser to the safe DecorView
FrameLayout instead and positions it using absolute screen coordinates.
Every attachment, layout and rendering operation now catches errors and
restores the original GMMP list if inline rendering fails. A layout
observer also resumes attachment after GMMP populates a previously empty
adapter.

The first inline build also used standalone TextViews with hard-coded
sizes/insets and theme attributes that did not match GMMP's dynamic
Aesthetic palette. The stabilizing build now samples the actual bound
native wp3/jo3 row for its text color, pixel font size, typeface, row
height, title inset, row background and enclosing surface color. The
browser refreshes on native style changes. Logging records the original
native row's XML layout resource when Android exposes it; using the
actual native row layout/binder for a truly pixel-identical final UI
remains the next integration objective.

Critically, setting a DIFFERENT xn3 model into an arbitrary visible
ViewHolder before calling performClick was unsafe: native click lambdas
may capture the original playlist independently of the mutable
holder.A field. The stabilizing build no longer swaps native models for
single-playlist actions. It searches for an actually-bound holder with
the target xn3.q path and invokes only its own native click. For
off-screen targets, it scrolls the original RecyclerView to the
candidate native position and verifies the rebound path before clicking.
If the actual native row does not match, it refuses the action instead
of opening/adding to the wrong playlist.

The native overflow menu must remain available. Eventually, ONLY its
individual Add/Create Playlist MenuItem may be hidden according to the
documented creation policy. Since GMMP's exact native menu item ID and
creation-destination callback have not yet been verified, the stabilizing
build only logs the actual menu resources and items; it does not remove
the menu or change any creation destination. The same restriction
applies to the native Add-picker FAB until native folder creation is
implemented. GoneSmart's multi-destination confirmation remains intact.



### Scoped inline UI and selection stabilization (25 September)

The next device log confirms 248 native models and names in both views.
The previous inline browser used DecorView as an overlay host and
therefore covered GMMP's navigation drawer, mini player and native picker
FAB. Native style sampling also chose `metadataTextEntry`
(30px at a 3x display density) instead of checking that the sampled
TextView actually displayed `xn3.p`. The native menu probe
established `menu_gm_playlist_list → menuAdd` (`Hinzufügen`).
Both picker FAB and native Aesthetic dynamic color were already
captured, but the DecorView overlay obscured them. There was no
`FATAL EXCEPTION` stack in the latest filtered attachment;
AndroidX OnBackPressedDispatcher is also absent from GMMP's optimized
classloader.

The stabilizing build now hosts the browser inside the nearest native
AestheticCoordinatorLayout, after its own fragment contents but before
the native picker FAB. This confines it to the actual playlist-list bounds
inside the drawer and mini-player content. It inflates GMMP's own
`rv_listitem_metadata_compact` layout for folder and playlist
rows when the native template is available, identifies the playlist
title by matching the real bound `xn3.p`, and copies live
title typography, text color, padding, height and row ripple/background.
GoneSmart's extra row dividers are removed, and folder-only icons now
use a GMMP drawable when present, otherwise an outline vector tinted
from the current native row text. Overlay background is sourced from the
native content host. The layout observer refreshes style after native
theme changes.

The native picker FAB is now brought in front of the scoped browser.
It is visible for a physical folder and virtual Other Locations when
the corresponding creation policy permits, hidden at a forbidden
location, and ALWAYS shown for active multi-destination confirmation.
The selected row overlay comes from the existing picker controller's
live native Aesthetic primary/FAB palette, and all selection exit paths
notify the browser to repaint stale rows. A platform Activity
onBackPressed hook is the fallback when the public AndroidX dispatcher
class is missing.

The normal playlist overflow menu remains intact. Only its verified
`menuAdd` item is hidden when root creation is forbidden,
and its visibility is refreshed as the folder changes. Native creation
is currently verified to target GMMP's main root only: in a physical
folder the menu item is kept hidden and the picker's visible creation
FAB displays an explicit unsupported-action message instead of silently
creating a playlist in the wrong folder. Actual in-folder native
creation needs a separately verified GMMP destination hook. Existing
playlist clicks and native multi-destination writes remain native.


### Device report and crash-stack diagnosis — 25 September, 01:20–01:26

The user's 4 screenshots compare GMMP's original black Playlists/Add
rows against GoneSmart's small-font, gray Add overlay with filled folder
icons. The original compact native row is 144px high; our style probe
selected `metadataTextEntry` at only 30px for its title, although
the visible native playlist headline appears around 45px. The device
log verifies all 248 native models and 248 native display names.

More importantly, two reproduced normal-tab crashes have the same
main-thread stack: `NullPointerException in ViewGroup.dispatchDetachedFromWindow`
called by AndroidX Fragment `SpecialEffectsController` while removing
the old playlist fragment after GoneSmart invokes a real native
`wp3` row click. The Add picker also reports the same NPE
on back navigation immediately after its selection session exits.
The previous controller removed the sibling overlay synchronously
inside the native list's `onViewDetachedFromWindow` callback,
thereby changing an ancestor's children during Android's active
detach traversal. It could also re-render picker selection from that
same callback and leave an offscreen browser blocking other pages.

The next combined stability build hides the overlay immediately but
deletes it from the parent on the **next main-loop turn**, after
FragmentManager finishes detachment. Selection notifications render
on the next turn too; a successful native playlist click also retires
the browser, suppresses stale auto-reattach until the original list
actually detaches, and restores the native list's alpha. Overlay
visibility is conditioned on the native list's actual global visible
bounds and not merely `isShown()` (which is true for some
offscreen pages).

Visual changes in the same build: the known compact GMMP metadata title
is expanded proportionally (30→45px on the observed 144px row, while
other native title views retain their own live sizes); the original
typeface, row XML and metrics remain native. Folder icons are now
thin-stroke vector outlines, not the previously selected filled GMMP
drawable. Its stroke is 1.25 units in a 24x24 viewport, avoiding the
earlier accidental double application of density scaling.
The Add screen's background now clones the actual GMMP window
`DecorView` surface, rather than its inner #303030 placeholder
CoordinatorLayout, and redraw/style checks follow the live theme.

Unit tests cover compact metadata correction, alternate GMMP view
sizes, native large headlines and scaled compact type. Android
FragmentManager teardown and visual skin parity still require one
on-device verification: JVM unit tests cannot simulate GMMP's actual
obfuscated runtime and FragmentContainerView lifecycle.

An additional front-page guard compares the playlist tab's own
`baseMiniPlayerRoot` child against the top visible child of GMMP's
`mainFragmentSlot`. The stale playlist RecyclerView can remain
attached and even report a nonempty global rectangle underneath
Now Playing or playlist-details. The browser now hides whenever a
later fullscreen fragment occupies that host. After native navigation,
the previous list is held from reattachment until it has been seen
behind another fragment and subsequently returns to the foreground.
An actual list detach always clears the hold. The Add picker uses
its own independent window and does not apply this tab-only guard.

### 2026-09-25 — latest 12:43–12:48 device report and effective-native-font fix

The user's test of commit `df1d04e` confirms the root and virtual Other Locations menu-create visibility policy for both values of Group root playlists. At 12:46, a native creation succeeded: the playlist adapter increased from 248 to 249 entries. The Add picker has the proper background, native highlight and plus button. The log also confirms that real physical creation was still blocked at `GoneSmart Tests/Level2`: `FOLDER CREATE GUARD | native root-only callback`.

The title mismatch persisted because GoneSmart copied a base `metadataTextEntry` of 30 px from a native `SpannedString` without resolving its full-range `RelativeSizeSpan`, `TypefaceSpan` and `ForegroundColorSpan`. The next debug implementation reads Android's actual native `TextPaint`, applies relevant `MetricAffectingSpan.updateMeasureState` followed by draw-only spans and copies the resulting effective pixel size, color and typeface to each synthetic row. It does **not** use a static size or multiply the effective size twice. The diagnostic now logs actual relative factors and span ranges.

The native tab briefly flashed during native detail and Now Playing transitions because the native list alpha was restored while GMMP was still navigating. The next iteration preserves the invisible native recycler alpha during fragment transitions and restores it only when the feature is turned off or attachment fails; it also avoids hiding the folder overlay merely because native navigation has been requested, waiting for actual foreground visibility.

**Physical-folder creation remains an explicit safety blocker.** To make it real instead of cosmetic, map GMMP 4.2.0's native playlist-creation destination writer and DB registration, preferably from the installed APK. Do not create in root and then move the M3U or mutate `xn3` objects without confirmed native transactional behavior; manual moves previously left stale database rows. After identifying the writer, enable the same current-folder destination in both the normal overflow `menuAdd` and picker FAB, with confirmation continuing to take priority when multi-select has selected paths.

### 2026-09-25 — unified create-control matrix

The same pure creation UI policy now drives both native surfaces so they cannot drift apart during folder navigation or option changes. The intended matrix for **Group root playlists** is:

| Location | Group root ON | Group root OFF |
| --- | --- | --- |
| Root | hide `menuAdd` and picker creation `+` | show native root creation |
| Virtual Other Locations | show native root creation | hide create control |
| Physical subfolder | normal overflow item hidden until native subfolder destination is verified; picker `+` remains visible but its create click is guarded | same |

**Group external playlists is deliberately irrelevant to creation permission**; it changes only whether external playlists appear inside virtual Other Locations. Active multi-destination selection always keeps the picker FAB visible as the confirmation control, even where playlist creation itself is hidden. Unit tests cover root, virtual, physical, selection override, and folders-disabled behavior.

### 2026-09-25 — runtime-native typography and folder-return state

The maintainer's latest device test confirms the thin folder outline, corrected Add-picker background, picker plus FAB, live selection color and two-destination multi-add. The remaining visual mismatch is typography: the native bound compact row reports a 30 px base `metadataTextEntry`, but GMMP renders its playlist headline differently. The previous GoneSmart 30→45 px proportional correction is therefore removed as a primary strategy.

The folder browser must copy the **actual bound GMMP title rendering**, including styled CharSequence / TextAppearance spans and exact TextView runtime metrics (base textSize, Typeface, letterSpacing, textScaleX, line spacing, includeFontPadding, maxLines/ellipsize and padding). This is intentionally device/theme/view-mode adaptive; fixed px/sp conversions are only defensive fallbacks when no native source exists.

Navigation polish in the same iteration: remember the current folder separately per surface, restore it after returning from a native playlist-details page if that folder still exists, and keep the folder overlay visually present (but non-interactive) until GMMP's native detail fragment has actually taken foreground. This avoids briefly revealing the underlying ungrouped native playlist list during the transition. Grouping-option changes validate remembered folder IDs against the rebuilt index before restoring them.

## Step 2 — native GMMP navigation

Determine the actual GMMP main-root setting without hardcoding a
device-specific directory. Identify native playlist data sources and
menu/list hooks in **both** the Playlists tab and the Add to Playlist
picker. The latter already exposes `xn3.q` native playlist paths through
the `bo3` picker; its native `io3` writer must remain the only method
for adding songs. A read-only `FOLDER DISCOVERY` diagnostic now records
distinct `zn3.N0 -> jo3 -> xn3` surfaces, including adapter class,
RecyclerView resource ID and view ancestry. Opening the normal Playlists
tab and then the Add to Playlist picker will show whether both surfaces
reuse the same native row/adapter stack.

Use the shared classifier for both screens. Maintain Back/up navigation,
scroll state, dynamic theme colors, normal single-playlist taps,
playlist creation, and multi-selection across nested folders.
If one hook cannot be installed, leave the original GMMP UI untouched.

Verify all four grouping combinations. Folder names come from native
physical directories. Empty folders require a separate physical-directory
enumerator: native playlist records alone cannot reveal them.

## Step 3 — synchronized creation and moves

**Do not enable Move Playlist yet.** The actual GMMP native
rename/remove/reindex operation or its scoped DB transaction must first
be identified and verified on the tested GMMP version.

A safe move must ensure write permissions and a collision-free target,
check any relative M3U song references, preserve playlist contents,
change the file path, update only the affected GMMP database record,
request a native refresh and verify that exactly one live entry remains
at the new path and **no** stale old entry remains. If verification
fails, restore the file and native record where technically possible.
Never perform a blanket database cleanup, and never move files just
because their display grouping changed.

Smart Playlists are separate native objects; do not treat them as
ordinary physical M3U files without confirming their native behavior.


### 2026-09-25 — supplied GMMP APK: native create-path investigation

The maintainer provided the installed GMMP APK for direct offline investigation (do not commit or redistribute it). DEX inspection confirmed a dedicated `onAddNewPlaylist` event in `tp3` / `PlaylistListPresenter`, a native `hp3.d()` playlist-file save implementation that creates parent folders, a separate `x6.b(Context,File)` file-registration/notification candidate, and generated playlist-file database insert/update methods. The picker has its own `go3` presenter and `bo3.k2()` FAB path; common new-playlist creation across the two surfaces is not yet proved.

See [GMMP 4.2.0 native playlist creation — APK investigation](GMMP_420_PLAYLIST_CREATION_RE.md) for the exact observed obfuscated signatures, limitations, verification gates and disposable on-device regression cases. **This does not lift the physical-create safety guard:** the event subscriber, configured root and original file+DB creation transaction must be traced first. The installed GMMP APK remains private.

### 2026-09-25 — device feedback: immediate refresh, picker flow, breadcrumbs

The maintainer accepts the current native typography and overall appearance. New observations: a newly created root playlist is not visible in the *already-open* folder browser until leaving and re-entering the Playlists tab; creating from the Add picker still briefly flashes the native ungrouped playlist list; when Multi-playlist Selection is enabled, creation from the picker should **create without immediately adding the source track or closing the picker**, leaving the new playlist available for deliberate selection together with other destinations; nested folder navigation should show its complete ancestor path rather than only the last folder title. The maintainer pulls commits in Android Studio and uses Run App; do not routinely distribute debug APKs.

A narrow UI change on the development branch now watches changes in the *existing native playlist adapter's model count* during its current view lifecycle. When GMMP supplies an updated full model, the visible browser rebuilds its folder index and re-renders without a tab change; the existing folder and native playlist model identity are retained. This cannot force GMMP to fetch a newer database snapshot if its adapter never updates, so success must be verified on device. The folder header now displays a complete root-to-current breadcrumb with the root label taken from GMMP's native resources, while tapping it retains the existing one-level Back behavior. No extra filesystem scan, direct DB write, file move, or guessed native refresh method is introduced.

**Still unfinished:** picker creation-only behavior needs interception at the confirmed `fo3.invoke` callback and verified native result/event handling; do not skip GMMP's underlying creation transaction or simulate success. The picker flash may be caused by its native post-create auto-close; preserve browser coverage through a real foreground transition while verifying create-only semantics. **Updated evidence:** the subsequent 16:26–16:27 device log confirms `NATIVE CREATE PROBE | surface=main` and `surface=picker` both enter and return successfully. Main root creation increased the native adapter from 251 to 252 and emitted `FOLDER INLINE REFRESH` without a tab change. In the picker, `MULTI PICKER | view detached; clearing session` and `MULTI MODE | exited` occur *before* `NATIVE CREATE PROBE | surface=picker | returned`, demonstrating that native picker creation still closes the picker and directly adds its original source track. See the separate APK investigation document for exact callback provenance. The physical-folder creation guard remains active pending destination/DB confirmation.

### 2026-09-25 — native Files-style breadcrumb and per-playlist context menu (new code, not device-verified)

The maintainer's screenshot of GMMP's **Files/Folder tab** shows a fixed horizontal navigation strip containing individually clickable, bold, separated path components (e.g. localized `Storage › Music › ...`). GoneSmart's previous one-line `‹ Current / Parent` pseudo-row is removed. In the new Playlist folder browser, the navigation strip sits **above** the vertical playlist scroller and does not scroll with the playlist rows. At the root the strip is `GONE` and uses no height; inside a folder, the first segment is GMMP's own `storage` string in the current locale (German: Speicher), followed by every ancestor and the current folder. Clicking any earlier segment jumps directly to its folder. Android Back still goes up one level. Breadcrumb state is backed only by the indexed native folder tree; the root has a null ID, physical folder IDs remain canonical native paths and the virtual Other Locations node retains its distinct ID. Unit tests cover all of these path cases.

The controller listens for a bound `quickNavRecyclerView` in the player's own Files tab and samples the *effective* rendered native `TextPaint` (including any spans), row height and padding. While GMMP has not shown that tab this session, it uses the currently bound native playlist headline style with bold weight as a documented defensive fallback. The displayed breadcrumb foreground/background follows the current native playlist style on theme changes; its native right-chevron drawable is used where available. **Device verification remains necessary** to confirm the exact family, weight, clickable bounds and header height match the screenshot on the maintainer's GMMP skin.

The same supplied private GMMP 4.2.0 APK's `rv_listitem_metadata_compact` XML confirms a native `AestheticTintedImageButton` with ID `rvContextMenu` and the player's `ic_gm_more_vert` drawable. Bare XML inflation in GoneSmart had omitted the *bound* icon/click callback. The new folder renderer restores the original control **on each actual playlist row in the normal Playlists tab only**, cloning its bound drawable/tint and localized native content description. Clicking it brings the exact requested `xn3.q` playlist's own `wp3` native row into view if necessary, verifies the bound target, and invokes that row's *original* `rvContextMenu.performClick()` so all native context actions come from GMMP. Folder navigation rows and the Add picker do not receive fabricated per-playlist context controls. Neither playlist files nor the DB are modified by the renderer. **Test the icon's placement, menu anchor, actions against an offscreen playlist, and post-rename/delete visual refresh on the actual device**; the native popup anchor may initially be the hidden underlying GMMP row instead of the overlaid synthetic row, pending real-device confirmation.

### 2026-09-25 — create-only behavior in the Add picker, Multi-playlist selection ON

The privately supplied APK's `fo3.invoke` contains an actual create-only branch. Temporarily set its owning `go3.z` Boolean to true for the **original** native create callback, preventing automatic copying of the picker's source tracks into the new M3U without replacing GMMP's file-save/rescan steps. Restore the original value before returning. Its unconditional `f2.b(j83)` event previously closed the picker; suppress only that event inside a short-lived thread-local create scope when Multi-playlist selection is ON and the picker has no selected destinations. The existing `jd(mode=4)` multi-add close-event guard remains independent, so selecting and confirming playlist destinations after the new creation still performs actual native additions and closes once. Multi-playlist selection OFF retains default GMMP automatic addition/exit. If the verified native shape/event hook is missing, preserve native GMMP behavior instead of risking inconsistent playlist state. This code is not yet proven on-device, and physical-subfolder create remains intentionally blocked.

### 2026-09-25 — later log and requested native breadcrumb parity

The 23:10:45 device trace confirms the picker create-only close-event scope is effective but also confirms (via the maintainer's observation) that the previous `go3.z` write did **not** suppress automatic track addition. The actual native list assignment is `go3.A:ho3.a`; its temporary empty-list substitution and `finally` restoration now wrap the original native save/rescan. At 23:13:01 the Files-tab `quickNavRecyclerView` supplied an effective **58.8px bold font and 144px row height** for the active skin. The new breadcrumb samples its **actual native touch background** when available, otherwise uses a themed borderless ripple, preserves the user's manual horizontal scroll on ordinary refreshes and reveals the last segment only when the folder changes. The extra arrow suffix after each folder row's title is removed: ancestry arrows remain exclusively between breadcrumb path segments. The keyboard/scroll/render changes still require a device check.

### 2026-09-25 — first scoped native physical-create implementation (device test pending)

The exact GMMP configured-root source is now mapped as `vp3.F:k15 -> va4.getValue()` (preference `playlist_saveLocation`), read synchronously in **both** real native create lambdas before their original `File(parent,name.m3u)` constructor. Debug builds install a narrowly scoped getter hook; the current physical folder becomes the parent only on the *same thread and same delegate identity* while the native create callback executes, after verifying the live folder is still indexed, exists, the native preference matches the indexed root, and the getter's redirected value passes a no-write probe. Original GMMP filename validation, `hp3.d()` file-save and `t6.f()` native rescan remain untouched. Both main `menuAdd` and Add-picker `+` are enabled in physical folders after their respective native hooks install, while root/virtual grouping rules and existing multi-select confirmation remain unchanged. If any precondition fails, cancel instead of making a root playlist. Neither moving existing files nor direct database edits are used. Actual device validation of the index and M3U destination is still required.

### 2026-09-25 — stable horizontal interaction follow-up

A further header lifecycle adjustment avoids tearing down/rebuilding the breadcrumb after every playlist selection/list refresh when the folder, native appearance and path labels have not changed; this prevents intermittent interruption of an in-progress horizontal swipe. The **current last folder segment is also clickable for native ripple feedback**, but leaves navigation unchanged. Ancestor taps retain the actual GMMP quickNav ripple when sampled and defer changing folders until the next animation frame so the original pressed feedback can be rendered. These Android touch/animation details still require on-device verification against the native Files tab.

### 2026-09-25 — device confirmation and next native-UI parity refinement

The maintainer tested debug commit `33ac8a0` with GMMP 4.2.0. **Confirmed:** with Multi-playlist Selection ON, new picker playlists no longer receive the source track automatically; creating inside Level3 through the picker and inside a physical folder from the regular Playlists tab worked, and adding files to the new playlist also worked. The attached 23:47–23:50 device log independently shows `PICKER CREATE ONLY | native source emptied for create`, scoped close-event suppression, native folder destination substitution on both surfaces, and native adapter refreshes to 255, 256 and 257 items. The three-dot controls, folder path navigation and overall layout are accepted.

**Remaining UI parity, implemented in the next debug commit but device-verification pending:** on the first picker opening, the native Files tab's `quickNavRecyclerView` has not yet been shown, so its exact 58.8px header style has not been sampled; the first header previously used the smaller 48px playlist-title fallback. The new fallback derives the header from the *bound* GMMP playlist text size using the version-specific measured ratio 58.8/48 = 1.225 until the genuine native quickNav is encountered. It also caches the measured ratio in GMMP-local private preferences for subsequent process launches and always prefers the live native source when available. The horizontal strip uses native Android edge stretch even with a short path (1px overflow), retains manual swipes across unchanged rerenders, and scrolls to the last path segment after every real folder change. A no-op rerender no longer increments the render generation, which previously invalidated a pending scroll callback before it could run. Wait for layout completion before scrolling; when a delayed native style makes the header wider while the user was already at the end, keep the newest segment visible. This applies equally to the normal Playlists tab and Add picker.

**New requested playlist insertion transition:** GMMP's native RecyclerView uses an ItemAnimator, whereas the synthetic GoneSmart playlist rows had previously been replaced instantly. The overlay now reads the *installed native* ItemAnimator's add/move durations and animates only pure new playlist/folder rows in the **same displayed folder**: the new native XML row fades in and existing rows below it move down, using Android's default RecyclerView ValueAnimator interpolator. No animation is played for initial list load, folder navigation, selection-only redraws, sorting/rename or bulk rescan. New pure unit tests cover the insertion planner and stable scroll-to-last policy. Check the `FOLDER INLINE INSERT` log's actual native animator class/durations on device and compare the visual with GMMP's unmodified list; timings may need more specific handling if that GMMP skin uses a custom animation implementation. CI compilation does not verify animation pixels or exact runtime appearance.

### 2026-09-26 — direct native RecyclerView reuse instead of visual approximations

The maintainer requested reusing GMMP's *actual* add animation and breadcrumb stretch implementation rather than copying durations and a hand-made 1px HorizontalScrollView overscroll hack. Inspection of the supplied GMMP 4.2.0 APK confirmed **AndroidX RecyclerView 1.4.0** (`META-INF/androidx.recyclerview_recyclerview.version`) and its native `DefaultItemAnimator` implementation. The injected breadcrumb now runs in an actual AndroidX horizontal RecyclerView with a single wide, independently tappable path-content holder (the existing GMMP-sampled glyph paints, rounded native ripple and actual icons are preserved). It adopts the installed Files-tab `quickNavRecyclerView`'s **live EdgeEffectFactory, overscroll mode, clipToPadding and nested-scrolling mode** whenever that native view has been observed, and otherwise relies on the same installed RecyclerView's native Android default until native quickNav is shown. Do not reparent the live Files quickNav view: doing so would break GMMP's Folder tab. The path's scroll offset now tracks the RecyclerView LayoutManager's actual child offset, not `View.scrollX` as on the old HorizontalScrollView.

For a playlist insertion inside the currently visible folder, GoneSmart reads GMMP's installed original playlist RecyclerView ItemAnimator, reflectively creates a separate instance of **that same concrete class**, copies the native add/move/change/remove timings and SimpleItemAnimator change option, wraps each bound native XML row in an independent ViewHolder and invokes the real native `animateAdd/animateMove/runPendingAnimations` methods. The original GMMP animator is **not shared**, since a shared pending holder queue would damage GMMP's list. If the current GMMP skin installs a non-clonable custom animator, the previous manual transition remains as a clearly logged fallback rather than an unverified claim of exact parity. Native creation, selection, file writing and existing native row context menus are unchanged. CI compilation cannot verify exact native view behavior; test the installed animator class in the `FOLDER NATIVE ANIMATOR` log and short-path overscroll after launching the Files tab to capture its EdgeEffectFactory.

### 2026-09-26 — original GMMP quickNav XML/Dex inspection and actual per-item reuse

The maintainer reported that the first path label started farther left, `>` separators were too small, and entering/returning between folders lacked GMMP's original segment motion despite matching elastic overscroll. Direct inspection of the privately supplied **GMMP 4.2.0 APK** established that `qg1` (the native quickNav adapter) actually emits alternating positions: even items use XML `rv_horiz_metadata` (`0x7f0c011d`, `MetadataTextView` root with XML-selectable background and 48dp minHeight) and odd positions use `rv_horiz_separator` (`0x7f0c011e`, native `AestheticTintedImageButton` with XML-defined 64dp width, 8dp padding, original arrow drawable and dynamic tintedState). The previous GoneSmart single wide holder used custom `TextView`s and 24dp arrows plus 8dp margins, so geometry and native enter/back animations were structurally wrong. The original native Files tab's `quickNavRecyclerView` uses `qg1`, `sg1` view holders and a horizontal GMMP SafeGridLayoutManager.

The new separate header adapter inflates the two real GMMP resource layouts from the running host, not hardcoded visual replicas; binds the actual styled native text paint; copies the visible original Files quickNav's measured first-child start inset, end padding, scroll/edge physics and cloneable ItemAnimator; and updates the alternating item list with native RecyclerView `notifyItemRangeInserted`/`Removed` to animate both entering folders and returning to ancestors. It does not touch the live native `qg1` adapter or recycle its item views, since the 2026-09-26 log includes a *native GMMP* `quickNavRecyclerView` invalid-position/inconsistency warning during navigation. The header still hides entirely at playlist root and all ancestor segments are separately actionable. All fallback components are explicit for future unsupported GMMP versions. Native run-time visual parity needs one consolidated device test (spacing, native chevrons, animation, first-open picker, default/alternate theme). The log also records `FOLDER NATIVE ANIMATOR | fallback ... sourceClass=none` for the original Playlists tab: **there is no installed ItemAnimator to copy there in this session**, so the separate playlist insertion mechanism remains a documented fallback until the actual GMMP animation source is identified; do not claim exact parity from that signal.

### 2026-09-26 — automatic native geometry/animation diagnostic

The original GMMP quickNav may update its `qg1` adapter without changing the bounds of the RecyclerView. A read-only `AdapterDataObserver` now captures the real native `insert/remove/move/update/changed` pattern and re-samples the runtime source style, first-item X offset, separator width and animator class after updates. The independently inflated GoneSmart quickNav measures its own first title position, separator width and effective text size on the following animation frame and logs a bounded `FOLDER QUICKNAV PARITY` comparison against the actual GMMP view (no track names or paths). This diagnostic is debug-only and never changes the native quickNav adapter. Future visual refinements should consult this programmatic parity measurement rather than relying exclusively on screenshots or the maintainer pointing out each tiny mismatch.

### 2026-09-26 22:55 device log — original qg1 sampled too early, cross-classloader diagnostics

The latest device log captured `FOLDER NATIVE BREADCRUMB` size 58.8px but `nativeAdapter=none`, `nativeAnimator=none`, `firstTextX=0` **18 ms after native qg1 was set**. The apparent `FOLDER QUICKNAV PARITY firstX=0/0 match=true` was a false positive: first native geometry had not been sampled from a real bound holder, and the 0px native sample then overwrote GMMP's authentic inflated metadata-label start padding in the overlay. A later `firstX=-158/0` happened while the overlay strip was horizontally scrolled, and must not be interpreted as a geometry mismatch. The log includes two `safeRun` catches for GMMP's live qg1 RecyclerView invalid item positions while navigating the **original** Files tab. The injected overlay adapter must never mutate that instance; there is no `FATAL EXCEPTION` in the visible file, but do not assert independence until reproduced without GoneSmart.

The fix preserves `rv_horiz_metadata`'s own XML padding unconditionally, accepts native quickNav metrics only from a real bound qg1 metadata holder, and watches global layouts because content may bind without a viewport-size change. A classloader-aware **read-only** reflection bridge reads the actual GMMP RecyclerView's public `getAdapter`, `getItemAnimator`, `getEdgeEffectFactory` and `getChildAdapterPosition`: an AndroidX type-cast failure is *not* evidence that the native animator is absent. Only the original RecyclerView's own padding is transferred to the independent overlay RecyclerView; if a valid original first-text X is captured, the overlay calibrates the remaining small discrepancy from actual original-vs-overlay measurements when at scroll start. The parity audit now skips scrolled/clipped first holders. For playlist insertion, a verified host `androidx.recyclerview.widget.DefaultItemAnimator` is reproduced using GoneSmart's independently bundled **same AndroidX 1.4.0 implementation** and the host's measured add/move/change/remove durations, even when classloaders differ; unknown native custom animators still fall back with explicit logs. No native qg1 adapter or live animator state is modified. Until device logs confirm the host class and geometry, exact playback/animation parity is not claimed.

### 2026-09-26 23:14–23:19 follow-up — native-first implementation and session scope

The full new log contains an earlier GMMP session ending before line 2090 and a later process starting near 23:14; do not mix the old 22:55-style capture with the latest build. The **latest** playlist insertion at **23:17:26.940** entered GMMP's main create callback; the direct native destination redirect returned at **23:17:27.026**, but the overlay's native playlist model increased to 262 only at **23:17:41.225**. The 14.2-second elapsed interval includes GMMP's native ScannerService binding and complete-track query and the absence of overlay refresh until a subsequent UI event; it cannot yet be attributed solely to debug logging. Measure original native update timing before changing the native writer/DB lifecycle. Earlier in that same process the picker was rebuilt several times while it was open; preserve navigation inside the same dialog, but restart at root for each *new* picker dialog. Persist main Playlist-tab folder navigation normally. The current native playlist RecyclerView on this GMMP 4.2.0 build uses **`androidx.recyclerview.widget.o`** across a separate classloader. Direct examination of the privately supplied original APK confirmed that `o` extends obfuscated SimpleItemAnimator `f0`, has one static TimeInterpolator and eleven ArrayList state queues, the verified R8-obfuscated stock AndroidX **DefaultItemAnimator** implementation. The old clone rejected it by expecting the unobfuscated class name and used a custom manual animation; now instantiate GoneSmart's independent AndroidX RecyclerView 1.4.0 DefaultItemAnimator with the actual host durations when reflectively available. Leave unknown future native animations unmodified/unapproximated until investigated. Exact on-device visual parity still needs a short follow-up test.

The latest process does **not** contain a bound original `qg1` geometry sample: the previously recorded `firstTextX=0` was from the earlier process before the new bound-holder probe. This is why the first label can still be too far left in both overlays. Do not insert an invented dp value. Cache the actual original first-title X on the first genuinely bound Files-tab qg1 encounter (per display density), calibrate both Playlists and picker from that proven value, and preserve the correction across rerenders instead of repeatedly resetting it to the source RecyclerView's zero padding. Ask the maintainer to briefly visit the original Files tab before testing the two overlay breadcrumbs; no alternate GMMP skin test is requested in this round.

**Maintainer scope decision:** playlist picker returns to the main playlist root on each new dialog; regular Playlists tab keeps its current folder. Alternate theme/view-mode cross-testing is postponed by user request. Move Playlist is not on the current roadmap: an external file manager can move M3Us for occasional maintenance, but the previous manual-move test produced stale old GMMP database rows and relative path normalization changes; require GMMP-side rescan/cleanup and do not implement a new synthetic mover without a new explicit request and verified native transaction.


### 2026-09-26 23:41–23:42: native geometry and main-tab insertion latency

Device feedback confirms that both overlay breadcrumb first-left insets, picker root reset, and original GMMP-matching insert animation are now correct. The user's 23:41:21 native Files-tab qg1 sample established a genuinely bound 24px first title offset, 192px native separator width and 58.8px effective headline text; at 23:41:45 and 23:42:13 BOTH overlay surfaces reported exact parity. Do not rework these verified properties without a new regression. A recoverable exception from **GMMP's own qg1 quickNav RecyclerView** occurred at 23:41:28 while navigating the Files tab (invalid adapter item position). Native layout/event observers are read-only. The log does not establish which code caused the exception; investigate if the user reports actual instability, do not blindly change GMMP's original adapter.

For the remaining latency: at **23:42:20.075** the original main create callback entered, returned at **23:42:20.181**, and GMMP's ScannerService bound at **23:42:20.225** and was destroyed at **23:42:20.550**. The folder overlay did not log a refreshed native 263-item model until **23:42:43.058**, after a new pointer event around **23:42:42.520**. This is ~23 seconds after native create return, **not** time demonstrably spent by the scanner or debug log output. Previously the only refresh source was a throttled PreDraw callback; an idle, otherwise static Playlist tab may not redraw, so a genuine adapter update can go unnoticed until another user input. The new primary trigger observes original GMMP 4.2.0 `zn3` adapter notifications. The inspected private APK confirms superclass chain `zn3 -> bw -> zw -> yw -> androidx.recyclerview.widget.RecyclerView$h`; the latter declares public final `notifyDataSetChanged()`, `notifyItemInserted(int)`, `notifyItemRangeInserted(int,int)`, `notifyItemRemoved(int)`, and `notifyItemRangeRemoved(int,int)`. GoneSmart observes the results of these original callbacks read-only and asynchronously schedules a full native-model refresh if the actual count changed. The old throttled PreDraw remains a safety fallback, with bounded event logs indicating when the source adapter changed. **The exact native-adapter event timing must be confirmed on-device before claiming the latency is fixed.**

The user explicitly requests that **after** playlist folder/create/responsiveness are confirmed, every older GoneSmart feature gets a retroactive native-first audit (GMMP original methods, functions, layouts, events, animations, resource strings). This audit is recorded in AGENTS.md and is NOT complete yet. No Move Playlist implementation; cross-skin testing remains postponed.

### 2026-09-27 — final device acceptance and responsiveness confirmation

The maintainer now confirms the folder feature behaves correctly and playlist insertion is substantially faster, with the desired original GMMP animation and appearance. The latest supplied Logcat (current run, 2026-09-27 00:01:50–00:02:22; no conflation with older sessions) shows five native adapter event hooks installed at **00:01:50.221**, nested Playlists navigation to Level3 by **00:02:00.830**, and insertion from the regular tab: original main create callback entered **00:02:08.658**, verified native physical-folder destination redirected and original callback returned **00:02:08.815**, GMMP's ScannerService destroyed **00:02:09.639**, original adapter `notifyItemRangeInserted` observed **00:02:09.805** (263→264), and GoneSmart's exact independent AndroidX `DefaultItemAnimator` plus overlay refresh for 264 models at **00:02:09.929**. End-to-end from button's native create callback entry to visible overlay refresh: **1.271 seconds**; from original create callback return: **1.114 seconds**; from native adapter event: **0.124 seconds**. No manual pointer interaction was required to trigger that refresh. This resolves the previously observed ~23-second idle-refresh delay. These are observed timestamps from one test, not a promised upper bound across storage/library configurations. An unrelated SSL trust-chain exception appears later in the same log; do not conflate it with playlist creation.

**Accepted feature scope:** real physical folders and nested navigation in the Playlists tab and Add picker; independent root/external grouping options; actual original GMMP native create/write path redirected to verified target folders (including Level3); empty playlist creation in multi-select picker without auto-adding the source file; native post-create track addition, group selection and original per-row overflow actions; original quickNav XML/inset/font/separators/overscroll and native RecyclerView insert/remove behavior; independent equivalent original native playlist `DefaultItemAnimator` for visible insertions; picker starts at root on each new dialog whereas main tab remembers folder; immediate native-adapter-event-based refresh. The maintainer has explicitly accepted this as feature-complete for the tested GMMP 4.2.0 configuration. This does not prove compatibility with alternate GMMP themes/view modes or future APKs, and DEBUG-only safeguards remain until release-hardening is authorized.

**Next requested work:** retrospective native-first audit of every earlier GoneSmart feature against GMMP's existing methods/layouts/events, recording evidence and proposing changes before implementing when behavior is unclear. Multi-theme regression is deferred at the maintainer's request; Move Playlist will not be implemented without new explicit direction. The previous dated 'pending' notes are retained for reproducibility, not as present-tense feature status.

### 2026-09-27 — reopened work: main selection and native New Folder controls

The maintainer retracted the global feature-complete designation after finding that several playlists could be selected in the ordinary Playlists tab but their **synthetic rows lacked the original accent selection tint**. Source inspection found the overlay only read the separate Add-picker multi-select session and never the native main-tab selection. `NativeMainPlaylistSelectionMirror` now updates selected-path display solely after original matching native `wp3` row click/long-click succeeds. This keeps the original GMMP ActionMode, menu actions and queue semantics intact; selection and toolbar-back lifecycle need one consolidated on-device test.

The privately supplied original GMMP 4.2.0 APK provides the actual `com.afollestad.materialdialogs.files.DialogFileChooserExtKt.showNewFolderCreator(MaterialDialog,File,Integer,uq1)` action, including its own input dialog and `File.mkdir` call. New folder creation in GoneSmart now calls this original native action via `NativeGmmpFolderCreator`, with the host classloader, native `files_new_folder` string and `ic_gm_new_folder` icon; the Add picker uses two smaller instances of GMMP's own FloatingActionButton implementation so the playlist mini-action forwards the original GMMP FAB click unchanged. The folder index already supported `physicalDirectoryPaths`, and now enumerates ONLY physical directories below the authoritative native root (bounded 1024) so a newly created EMPTY folder is visible across both surfaces and after restarting; native playlist/M3U scanning remains GMMP-owned. After the original native folder callback, verify a real new directory exists before rebuilding the overlay index. None of these new additions is considered device-verified until the new debug build is tested.

Still unresolved, explicitly requiring a maintainer decision **before destructive implementation**: should a folder delete remove only an empty directory, or recursively delete nested folders and the playlists inside? When a folder is marked during playlist bulk selection, should selecting it recursively include all descendant playlists and apply GMMP's existing actions to that flattened selection? The original APK includes file operations, but an earlier raw M3U move left stale GMMP DB entries, so batch playlist relocation must use a verified original GMMP transaction or a separately approved, fully synchronized rollback path, not a raw `File.renameTo()` plus rescan. Folder context deletion and batch move are not yet implemented or certified. The retrospective audit of older GoneSmart functions remains postponed until this reopened feature scope is settled.

### 2026-09-27 00:47–00:51 — native ActionMode teardown, mini-FAB constructor and recursive delete

The next on-device log confirms multi-selection tint and original native folder creation, but notes three regressions: native toolbar Back closed GMMP's ActionMode while leaving GoneSmart's visual mirror highlighted; the added top-level folder menu used a differently translated native 'New Folder' title instead of the exact original adjacent 'Add' title plus a native folder icon; and tapping the Add picker FAB did nothing. The full log specifically identifies repeated `java.lang.NoSuchMethodException: com.afollestad.aesthetic.views.AestheticFab.<init>(Context)` at `nativeMiniFab`. Direct APK DEX inspection confirms this native FAB offers `AestheticFab(Context,AttributeSet)`; the new code invokes its real two-arg constructor and positions both independently instantiated original buttons inside a suitable full-height native parent. If neither button can be shown for any future GMMP skin, **let the original FAB click proceed** rather than swallow it.

The same original APK proves that native playlist ActionMode uses `yn3 extends n3` and `n3.onDestroyActionMode(ActionMode)` calls original `n3.c()` which clears its TreeSet/HashMap and finishes the original ActionMode. A new strictly passive post-original hook clears only GoneSmart's main-tab selection mirror when the actual playlist-mode callback is destroyed. Picker selection ownership remains unchanged. The new overflow's second title uses the original `menuAdd.title` verbatim in the current GMMP locale, followed by original `ic_gm_new_folder` inline icon. The placeholder 'This folder is empty' is removed entirely.

The user explicitly authorized recursive removal of every nested playlist/subfolder when deleting a folder, preserving real music files. Original GMMP uses **the same `py0.b(Context,List<pn1>)` confirmation and worker pipeline** from both native playlist bulk-delete `yn3.n` and original Files folder-delete `kg1.n`. The new folder per-row overflow uses the player's own XML `rvContextMenu` and native `menuContextDelete` menu resource, then calls original `py0.b` with native `th1(File,null)` wrappers for every **verified native M3U** in that subtree. A bounded full physical preflight rejects unindexed content or path escape, and a follow-up only deletes **already empty directories** after GMMP's physical M3Us and refreshed native adapter both show the original native deletion completed. Truly empty folders use native `py0.b` with that directory as a File target, preserving the original Files tab confirmation. The first build is experimental and requires a **disposable nested test folder** on-device before general use. Nothing has been tested on the user's device yet; do not mark the whole feature complete.

### 2026-09-27 01:25–01:27 — verified recursive delete, native label and inline icon corrections

The maintainer tested commit `2174174` on GMMP 4.2.0: two/four native main-tab selected rows reached original `n3.onDestroyActionMode` and GoneSmart cleared its mirror. Native populated-folder deletion opened the original `py0.b(Context,List)` dialog for two indexed M3Us, original `DeletePlaylistFileWorker` completed successfully, the native adapter removed both indexed playlists and GoneSmart subsequently pruned only the already-empty physical directories. The real deletion is device-confirmed, not merely CI-tested.

The user reported two remaining UI mismatches: the appended original `ic_gm_new_folder` inline menu icon was black against the currently white localized Add text, and native bulk deletion displayed a generic plural files label instead of the parent folder path. The next debug change dynamically tints the actual native drawable using its adjacent original title's ForegroundColorSpan, bound native playlist title color or current host textColorPrimary in that order. For the delete prompt, a strictly pending-thread-scoped post-original hook of the installed MaterialDialog's original `show()` updates one existing message/title TextView only on our verified folder delete request. It replaces the original native generic files label when it can resolve that resource or appends the canonical parent-folder path while preserving other native confirmation text. Buttons, callbacks, target native indexed files and GMMP's original deletion worker remain untouched. If the dialog uses an unknown layout, leave it unchanged and emit one diagnostic. Both UI fixes are **implemented but unverified on-device**, including the native dialog field selection and dynamically changing palettes.

Moving one or several existing playlists is still **blocked pending native move/DB transaction research**: manual M3U moves previously stranded native indexed records; do not silently replace them with a raw File rename or unsafe rescan. The earlier native insertion, picker creation, breadcrumbs and deletion behavior must stay intact.

### 2026-09-27 11:46 — accepted delete UI; single/bulk move prerequisite

The maintainer confirms `c964fb4` on GMMP 4.2.0: both native add-icon tint and folder path in the original delete confirmation now look correct, and deletion succeeds. No need to rerun accepted folder deletion without an affected regression. The original inline folder image is moved **before** the unchanged native localized `menuAdd` label; preserve the same live theme tint.

The maintainer has now explicitly requested the **complete movement of individual or multiple native-indexed M3U playlists** between existing physical playlist folders. Pending an actual DEX/runtime proof of the original native move and DB-row synchronization, the first preparatory commit adds `PlaylistMovePolicy` and unit coverage for per-batch canonical-root checks, native-index identity, same-root-only paths, overwrites/case-insensitive collisions, source content hashes, UTF-8 M3U path resolution, and post-native index verification. Read-only `NativeGmmpMoveDiscovery` checks existing host move/rename menu IDs and declared method signatures without invoking anything, modifying GMMP DB rows or exposing a fake move action. The publicly listed 4.2.0 release variant is not guaranteed to be byte-identical to the original private base APK previously inspected. **This is an implementation milestone, NOT completed moving**. After the original exact APK's native move/register/remove lifecycle is proved, implement the original destination picker and same-native-model menu actions, native-preserving transaction and rollback, then enable it for disposable test playlists and require live-device acceptance. No manual `File.renameTo()`, direct database writes or speculative playlist byte rewrites are permitted.

### 2026-09-27 12:05 — accepted picker speed dial; small-FAB palette and empty-create Toast

The maintainer confirms that both smaller native AestheticFab actions now open their correct original playlist/folder creation flows in the Add picker. Their background color still differs from the original large button: the existing pre-attachment tint copy could be overwritten when a new AestheticFab attached and installed its own dynamic-theme observer. The next debug change reads the big native FAB's live background tint or its actual ripple/shape tint/fill, applies it to BOTH mini buttons after they attach and after their native `show()`, and syncs during subsequent overlay layout updates while expanded. No static color or independent palette is introduced.

When Multi-playlist Selection is ON and no destinations are selected, the established verified `fo3` create-only path intentionally produces an EMPTY M3U, preserves the original source list and keeps the picker open, but native GMMP still displays “0 files added to playlist.” A newly scoped interceptor observes `Toast.makeText(Context,CharSequence,int)` and matches only the running GMMP's exact localized `add_to_playlist_toast` formatted with 0. It registers the exact native Toast instance ONLY during that create-only callback or a bounded 15-second one-shot window armed by its suppressed original `j83` event. Only that same instance can be suppressed at the existing `Toast.show` hook. Ordinary single/batch additions, errors, other Toasts and the original file/database transaction are untouched. This corrects feedback for an empty creation only, not normal GMMP behavior with the feature OFF. CI/unit coverage validates state and matching; true UI and delayed native callback timing remain **device-test pending**.

### 2026-09-27 — Direct one/many Move implementation; initial DEBUG device test pending

The maintainer clarified that the original player tab is named **Ordner**, not Files, and has **no native move action**. Once GMMP edits/saves an existing playlist, its original writer converts relative track references to absolute ones. Our original APK analysis currently establishes `hp3.d()` as native writer, but not a safely callable, fully loaded preexisting playlist `hp3` instance for arbitrary selected native `xn3` records. Do **not** assert that an arbitrary `hp3.d()` re-save was called. The implemented DEBUG move preflight instead resolves every relative line against the original M3U directory, checks that file, converts it to the exact canonical absolute target and preserves all original comments/EXTINF and newline conventions. Unknown encodings/remote or unresolvable references fail closed. All already-absolute lines remain unchanged. The user-requested native re-save optimization remains an optional future refinement after exact model-to-writer mapping.

The Move action is added to the existing individual native Playlists context menu (only when opened from an identified original bound `wp3` model) and the original main-tab multi-selection ActionMode menu. It uses the original native playlist list and the existing indexed physical folder tree, not a new M3U library scan. The destination chooser lists the main configured playlist root and already-existing writable nested physical folders; virtual Other Locations/external playlist paths and folder-to-folder directory moves are not supported in this first implementation. A verified same-root native-index-only preflight rejects collisions, unknown sources, missing/unreadable tracks and any change before starting.

The transaction durably stages original AND normalized playlist bytes under private GMMP app storage BEFORE opening GMMP's original visible `py0.b(Context,List<th1>)` delete confirmation for the selected original playlists. Canceling that original confirmation leaves originals untouched; private pending stage remains invisible and can be recovered/inspected. On confirmation GMMP's original `DeletePlaylistFileWorker` owns BOTH physical old-file removal and old playlist-index cleanup. Only when both absences are independently verified does GoneSmart publish the normalized copies to the already validated destination with new-file-only atomic renames, invoke original `t6.f(Context,String[])` for all new paths, and await full native destination indexing and content-hash verification before cleaning the private stage. It never writes GMMP's SQLite DB directly. A process restart can detect durable staged batches under the same verified root and resume a deletion-completed move. If any native step fails or a target conflicts, retain the stage for recovery rather than lose the original contents. This path has CI/JUnit coverage for normalized M3U staging, durable recovery, collision refusal and existing preflight policy, but native asynchronous worker timing and full original Android theme/localization require a **disposable-playlist device test** before declaring the move feature accepted.

Known UX limitation during DEBUG: GMMP's visible original confirmation has its original **delete** wording, because the operation genuinely invokes its own original delete worker AFTER durably preserving the playlist contents. The destination chooser uses the host Android themed AlertDialog, since the original Ordner tab has no native move/destination dialog. Exact translated Move label is read from GMMP resources if present, with German/English debug fallback; production translation and any original native hp3 re-save path require further proof. While the native adapter is asynchronous, the privately staged files are retained and completion is logged only after verification.

#### Recovery and partial-worker failure hardening

The recovery pass also handles partial original `DeletePlaylistFileWorker` completion: after a prolonged incomplete native batch or a target collision, restore every missing original from its own private durably staged byte copy, rescan restored original paths via `t6.f`, and wait for ALL source paths to reappear in the actual original native GMMP adapter. Do not erase the private backup on an unverified or partially restored rollback. After process death between target-file publication and native index insertion, detect all target files by exact staged SHA-256 and retry native indexing without overwriting any already published file. Adapter changes may arrive after initial browser attachment, so repeat pending-operation recovery on subsequent original native model refreshes. Cancellation can leave an inert staged private backup; nothing is moved until GMMP's original worker actually removes every selected original and its indexed records.

#### Interrupted UI state and cancellation safety

If GMMP recreates its playlist fragment while the original delete worker or native scanner is running, the orchestrator rebinds to the newly attached real native adapter (same verified root) instead of polling the detached old view indefinitely. The stage manifest independently stores/verifies **both** the original and normalized M3U SHA-256 hashes. A native confirmation dismissed while all originals remain physically present **and** in GMMP's original index for the bounded observation window is marked cancelled in the private manifest: private backups remain available for emergencies but a later unrelated user deletion cannot automatically restart that old destination write. A real move still reports success only after full target bytes/hash and original GMMP index verification.

#### Native confirmation timing

A playlist move opens GMMP's actual original delete confirmation after every original M3U was durably staged. The user's time viewing this dialog must NOT count as asynchronous worker or scan time: the cancellation grace interval is measured only from the original MaterialDialog window's actual dismissal, and an open observed dialog is excluded from the generic operation timeout. Native destination-index timeouts begin afresh after the original indexed sources are gone. This prevents a user waiting longer than 30 seconds before tapping the original native confirmation from causing premature stage cancellation.

#### Same device cycle: identify the original existing-playlist save function

A passive `NATIVE SAVE DISCOVERY` probe has been added to the DEBUG build before testing one/many moves. The GMMP Ordner tab does not have a native Move command, and GoneSmart's first mover currently normalizes relative M3U lines itself. We already know from the private GMMP 4.2.0 DEX that `hp3.d():boolean` is the original M3U file-writer used for **new** playlist creation; what is still unknown is which exact existing-playlist edit/add callback builds/loads the `hp3` model, triggers `hp3.d` and reindexes the existing file.

Observe only the **actual original runtime method calls**, after each original returns or throws: `hp3.d`, `x6.b`, `t6.f`, `zp3.M` and `io3.r`. Each observer independently checks the actual host-classloader method signature. Log a strictly bounded number of anonymous samples for each method/origin, with native writer receiver **field names and types** and short original caller-class/method stacks; no playlist names, paths, songs, contents, raw data fields or sensitive arguments are logged. Preserve the original call, result/exception and all writer/DB side effects exactly. The new diagnostic is debug-only and deliberately makes **zero changes** to the mover's transaction during this device cycle.

**Consolidated test:** install the same-sha debug build, first open a disposable *existing* GMMP playlist and use GMMP's own normal add/remove/edit operation that saves the playlist, then move one disposable playlist between existing physical folders, and then move two disposable indexed playlists together. Capture `GoneSmartPlaylist` Logcat lines containing `NATIVE SAVE DISCOVERY` and `PLAYLIST MOVE`, plus any AndroidRuntime stack if a crash occurs. The native edit is essential: moving alone does not invoke the original hp3 writer in the current implementation. After the log identifies actual call sites, decide if a verified loaded native writable existing-playlist instance and side effects can replace the current line-normalization stage safely; do not infer that merely observing `hp3.d` establishes a callable model-loading API.

### 2026-09-27 13:21–13:25 device log: native save evidence and revised destination UI

The maintainer supplied Logcat for the experimental one-playlist move. At 13:24:06.897, `NATIVE SAVE DISCOVERY | hp3.d result=true` traces the **original loaded existing-playlist details** fragment `zo3.e2 > zo3.onStop`, proving GMMP writes an existing playlist when its details screen stops. The writer's exact observed receiver fields (names and types only) are `o:th1, p:k15, q:k15, r:ArrayList, s:boolean, t:boolean, u:String[]`. At 13:24:14.414, the actual native `io3.r` add flow runs, and the asynchronous `io3$a.apply` trace invokes both `x6.b` (13:24:14.422) and `hp3.d` (13:24:14.453). At 13:23:55, native `zp3.M` triggers `x6.b`. These are observed native calls rather than guesses; safely *instantiating or retrieving* a loaded writable existing `hp3` is still UNKNOWN. Do not call `hp3.d` on `xn3/wp3` models or replace the user's playlist edit logic without proving that link from the exact original APK.

At 13:25:07.605, GoneSmart staged original M3U backups and invoked the original `py0.b` confirmation. It was dismissed at 13:25:14.782; the real `DeletePlaylistFileWorker` returned SUCCESS at 13:25:15.030. GoneSmart's original `t6.f` destination rescan ran at 13:25:16.504 and verified original-index removal + new destination registration at 13:25:18.072. Thus the tested **single** move is device-confirmed as to filesystem/index lifecycle, albeit not independently audited for exact playlist playback/contents and not evidence for multi-move.

The maintainer rejected the separate alphabetical/flat path-list AlertDialog and the confusing second native Delete confirmation. The revised DEBUG mode temporarily repurposes the already installed Playlists-tab native-style folder browser: actual original playlist row XML, verified qg1 breadcrumb/layout/animations and live Aesthetic theme, physical-only folder navigation, bottom Cancel + original GMMP localized Select resource when present (explicit `Auswählen`/`Select` debug fallback when absent). Actual native playlists remain visible but do not open or select during destination navigation; virtual Other Locations are omitted to prevent an invalid destination. The previous tab folder is restored after choosing or cancelling. The user's Select is the sole confirmation. After original and normalized bytes are durably staged and the original index is rechecked, the EXACT native original MaterialDialog created inside our move transaction has its verified original POSITIVE action button clicked automatically. It continues to use GMMP's own native `DeletePlaylistFileWorker`, never raw DB writes or bypassing the original worker. If native positive button lookup fails, the original confirmation remains visible and reports a bounded diagnostic; no unproven click target is guessed. This debug UI/automatic-dialog step is **not yet device-tested**; do not assert the second dialog is gone until the log and actual UI confirm it.

### 2026-09-27 14:27–14:28 regression: move browser must visibly confirm, with native accent

The user accepts the new existing-Playlists-tab folder navigation UX of commit `47bda33`, but reports that there was NO visible confirmation to select the destination. Full attached Logcat shows a separate ORIGINAL native add-to-existing-playlist transaction `io3.r` at 14:27:28.407, `x6.b` at 14:27:28.408 and `hp3.d=true` at 14:27:29.123, then a single playlist move destination browser opens 14:27:53.875; native-style folder navigation works 14:27:59.814. Crucially there is no `PLAYLIST MOVE UI | select clicked` or staging/deletion/scan after that, so the test does **not** validate the new automatic GMMP native-positive-action path. The prior bar was added as a final child of a full-height list column and had Android `buttonBarButtonStyle` text/background colors with no runtime geometry diagnostics; the precise visibility cause is not proven by that log.

The DEBUG fix attaches ONE existing-native-themed action bar DIRECTLY to the already installed `playlistListRecyclerView` overlay `FrameLayout`, pins it to its actual bottom with elevated Z-order, and sets the ScrollView bottom inset to the measured bar height only during move-destination mode (including layout changes). Cancel now uses the original GMMP-bound row text color for visibility; the actual Confirm button has the verified native runtime Aesthetic `colorAccent` value, not a hardcoded GoneSmart palette, and dynamically updates from the same `Aesthetic.e(attr)` getter already verified in `PlaylistMultiSelectController` when the GMMP theme changes. It chooses black/white text by actual WCAG contrast and uses original GMMP `select`-family translation only where the installed string resource exists. Bounded `PLAYLIST MOVE UI | bottom confirmation layout`, `select clicked` and `original GMMP Aesthetic accent` diagnostics distinguish hidden-button geometry, hit target and native palette failures during the next device test. Pure JUnit `MoveConfirmationUiPolicyTest` covers the bottom-space lifecycle and dark/light native accent legibility.

**Translation truth:** This version does NOT guarantee `Verschieben` is originally localized by GMMP. At runtime `nativeMoveLabel` probes actual installed original GMMP string IDs `move/move_to/menuMove/action_move/move_file/files_move/menuContextMove`; if none exists, current DEBUG UI uses the literal German `Verschieben` or English `Move` as a clearly annotated fallback, and final public localization is not approved yet. Added bounded `NATIVE MOVE LABEL | source=GMMP string/... OR DEBUG fallback` and `NATIVE SELECT LABEL` logs to resolve this on the user's exact GMMP 4.2.0 install. Do not describe a fallback as a native GMMP translation.

### 2026-09-27 17:51 — confirmed mini-player/page clipping; exact APK translation audit

The latest on-device log resolves why the confirm control is still invisible. GoneSmart logs its real native Aesthetic accent `#ff8e0e00` and successfully enters destination mode. The bar itself is laid out and the confirm button is enabled, but the geometry line reports `barHeight=210`, `visibleHeight=42`. So only the top ~42 px of the 210 px action bar reaches the globally visible viewport. This is consistent with the Playlists RecyclerView/overlay extending behind GMMP's persistent lower page/mini-player geometry; the button is not absent or disabled.

The next DEBUG fix retains the accepted native-style playlist browser, but computes the bottom obstruction from the **actual original native list's `getGlobalVisibleRect()`**, not a guessed mini-player height: `max(0, overlayBottomOnScreen - nativeVisibleBottom)`. That exact obstruction becomes the existing bar's `FrameLayout.LayoutParams.bottomMargin`; it is updated by the existing global-layout/pre-draw path and after the bar has a measured height. The underlying ScrollView still reserves the bar height so the last playlist/folder row is not covered. This remains inside the verified Playlists-page overlay and does not put a new control over GMMP's global DecorView.

Translation was also audited directly against the saved exact GMMP 4.2.0 `base.apk` compiled resource table. Of the queried destination-confirm string names, the APK exposes exact resource name **`select`**; its German string pool contains exact **`Auswahl`** and no exact `Auswählen`. Of the queried Move names (`move`, `move_to`, `menuMove`, `action_move`, `move_file`, `files_move`, `menuContextMove`), **none exists** as an exact resource name, and the German string pool has no exact **`Verschieben`** or **`Bewegen`**. Consequently **`Verschieben` is currently a GoneSmart DEBUG fallback, not a GMMP translation**. The bottom confirmation uses GMMP's actual `select` resource when available; on this APK the localized German wording is expected to be `Auswahl`. Keep runtime source diagnostics; do not invent a native Move translation that the APK does not have.

### 2026-09-27 ~19:00 — accepted bar visibility, native v3 dialog and localization correction

The maintainer confirms after installing `7e1fc06` that `Wählen` / `Abbrechen` are visible, the destination selection now **successfully moves** a disposable indexed playlist, but neither button is filled with the current GMMP accent, and GMMP still displays a separate original DELETE confirmation. The newly described log is not yet retrievable as an attached new file in this session, so the exact new `PLAYLIST MOVE` lines are not yet confirmed.

A targeted **actual compiled GMMP 4.2.0 APK DEX** investigation replaces the previously guessed MaterialDialogs v0.9 callback. This original APK uses **MaterialDialogs v3**. `MaterialDialog.show():void` is declared on the class, `WhichButton.POSITIVE` is its real button enum, `MaterialDialog.positiveListeners` contains the registered original callback, and its original `onActionButtonClicked$core(WhichButton)` dispatches that callback and handles dismissal. Its optional visible button lookup is **static** `DialogActionExtKt.getActionButton(MaterialDialog,WhichButton)`. The old `DialogAction` class and `dialog.getActionButton` lookup do NOT exist, which is why the prior auto-approval fell back to a visible DELETE dialog. The next version hooks exactly the declared native `show():void`: after a preflight proves this is the synchronous dialog of our own durably staged `py0.b` batch, ensure original positive listeners are nonempty and dispatch the **original v3 positive callback before any window is shown**, disarming its one-shot scope first. Native original DeletePlaylistFileWorker still owns file/index deletion; GoneSmart independently verifies/remaps with the existing original `t6.f`, staged source backups and safe rollback. Unknown signatures leave the original confirmation visible as a safe fallback. No generic hook hides other GMMP dialogs. The exact v3 static original button action is also used for fallback when the pre-show path is unavailable. This change needs new on-device testing before acceptance.

The active `buttonBarButtonStyle` gives the confirm no tintable background drawable on the maintainer's skin. `backgroundTintList` therefore failed even though the real GMMP Aesthetic accent `#ff8e0e00` was retrieved. The confirm now receives a real filled gradient shape with that **live native** accent and an Android `RippleDrawable` for touch response, with the existing contrast-safe text logic and Aesthetic update probe. Cancel remains an ordinary native-text button.

**Localization:** The original compiled APK contains localized `folder`, `folders`, `playlist_saved`, `select` and `ic_gm_folder`, but no Move action or `Verschieben` in native resources. `copy` and `menuContextRename` exist but misrepresent a destructive move. Instead compose a short action label `→ ` + installed GMMP's actual localized `folder` word, mirrored to `← ` in RTL locales; e.g. `→ Ordner`, `→ Folder`. The user's observed `Wählen` is indeed the native `select` string in their running GMMP; an earlier argument based on the unrelated German string pool entry `Auswahl` was overconfident and is superseded. For a single success reuse the native `playlist_saved` term if available. No separate DE/EN `Verschieben` fallback remains in the production menu. Verify exact locale behavior and silent pre-show callback on a disposable file in the next device cycle.

### 2026-09-27 later maintainer UI decision: native selection ActionMode + native confirm FAB

The maintainer rejected both bottom `Wählen/Abbrechen` button styles, including the earlier manually tinted filled shape, and the destination menu label `→ Ordner`. Keep the currently tested, working file/index move transaction and the safe pre-show original GMMP v3 delete callback from the preceding build; its silent behavior is not yet independently device-tested. Replace ONLY the destination chrome:

- Start an independent actual Android/GMMP contextual `ActionMode` on the original normal-Playlists RecyclerView, just as GoneSmart's already implemented picker multi-selection does. Its native left BACK arrow cancels the MOVE destination mode and restores the folder from which the user started; the title comes from GoneSmart's new central Move translation. Tint the actual native `action_mode_bar` using the same helper as the established multi-selection, driven by the native FAB palette.
- Clone an INDEPENDENT GMMP 4.2.0 `com.afollestad.aesthetic.views.AestheticFab` through its verified `(Context,AttributeSet)` constructor. Reuse the same shared `PlaylistConfirmDrawable` formerly called private `MultiConfirmDrawable`, plus the existing small lilac playlist sparkle. The original Add-picker FAB is never borrowed or reparented. The new FAB confirms a move into the **CURRENT displayed physical folder**, including the main playlist directory when the destination root is currently displayed. It always invokes the existing complete `PlaylistMovePolicy.prepare` native-index preflight and durable native-worker move; no direct file renaming or DB manipulation.
- GMMP's original active picker FAB color was previously observed to be `#ffbfbfcc` with raw color attribute `!mainColorAccent`, which differs from the ordinary `colorAccent=#ff8e0e00` used unsuccessfully in the rejected bottom control. Subscribe through the same original `Aesthetic/oy0.h(...,"!mainColorAccent",...)/nf3` dynamic observable used by the existing multi-picker. Apply its live color to both the independent original native AestheticFab's actual `backgroundTintList` and the native top ActionMode. Fall back to the real attached original AestheticFab's own live tint if the observable cannot be resolved. Never hardcode an arbitrary custom fill.
- Anchor the native confirm FAB above the REAL original playlist-list visible bottom; calculate measured mini-player occlusion as in the successful previous button-visibility repair. Preserve nested folder navigation and original native rows/breadcrumbs. The bottom Cancel/Select bar, manual button recoloring, synthetic folder destination picker and `→ Ordner` are removed.
- The menu title/ActionMode title **Verschieben** belongs in one new `GoneSmartGmmpStrings.kt`, not in GMMP's missing native resources. Original `base.apk` exposes 76 locale language codes including old Android `in/iw`; 74 canonical codes and Traditional Chinese regional/script routing cover these. Use the host GMMP Activity locale at runtime, and use `Move` for an unrecognized future locale rather than copying a different GMMP command. All other existing GMMP strings remain native. The shared drawable extraction preserves the existing multi-selection checkmark geometry and separate sparkle.

This is a DEBUG device-test build until the user verifies the exact top bar, FAB live color, scrolling/mini-player position, nested destination correctness, native ActionMode back behavior and the prior pre-show original delete popup suppression on one disposable indexed playlist. Test a changed dynamic album palette only if relevant to currently enabled theme.

### 2026-09-27 accepted native move chrome; final revisions pending one combined device test

The latest maintainer device feedback **accepts** the upper ORIGINAL ActionMode style, current-folder destination confirm behavior, and native GMMP dynamic picker-FAB tint implemented by commit `4253160`. Requested finishing adjustments:

1. **Remove the lilac sparkle ONLY from MOVE-confirmation FAB.** Retain the identical shared `PlaylistConfirmDrawable` white checkmark and independent GMMP-native AestheticFab. The original Add-picker multi-selection's pre-existing sparkle stays unchanged.
2. **Move the lilac GoneSmart badge to GMMP's EXISTING Playlists navigation drawer entry** when and only when Playlist folders is enabled. The native observed `NavigationMenuView#design_navigation_view` is the actual installed navigation RecyclerView and its parent `AestheticNavigationView#mainNavigationView` owns the GMMP Menu. Use `getMenu` + actual host localized `playlists`/`playlist` resource name / native menu item ID, then attach a title-end `\uFFFC` span using the same 28 dp lilac two-star `BaselineCenteredSparkleSpan` extracted WITHOUT geometry changes from existing Play Flipped. Never inject a separate drawer item, replace the existing native clickable row, or override its glyph. Cache ORIGINAL native CharSequence and restore on disable; avoid duplication on live drawer binds. New unit tests verify native translated item matching and no other menu item decoration.
3. **First-frame low FAB:** User confirms FAB starts under GMMP's mini-player until they tap any destination folder, after which its vertical position is correct. The old initial binding calls `installMoveFab` during an early native-layout phase and `positionMoveFab` samples only native list `getGlobalVisibleRect`. Since a mini-player sibling may overlap without clipping, use ORIGINAL native `miniPlayerWrapper` / `miniPlayerLayout` / `libraryTabMiniPlayer` visible screen top as second bound, compute `min(nativeListVisibleBottom, visibleNativeMiniPlayerTop)`, call `positionOverlay` and reposition BEFORE original `AestheticFab.show`, start invisible and let original list global-layout/pre-draw refresh correct when geometry appears. No hardcoded mini-player height. Unit tests explicitly cover a 168 px sibling overlap while native list itself still reports full height.
4. **Unwanted bare success popup for multiple playlists:** Confirmed by PREVIOUS SOURCE: the move callback uses a localized `playlist_saved` Toast on count=1, but only the unhelpful menu title `Verschieben` for count>1. Standardize on **NO success popup for either count**; the native playlist index and displayed list visibly update only after original delete worker and verified target registration. Keep exactly ONE error Toast for actual failure, no broad GMMP Toast suppression. Retain original pre-show v3 delete callback and durable backups.

The supplied `Eingefügter Text(20260927-181531).txt` is a log from the older debug-fallback / original bottom-action-bar build: exact lines report at 19:02:11 old `NATIVE MOVE LABEL | source=DEBUG fallback`, 19:02:13 original bottom obstruction 168px, and a SINGLE playlist move at 19:02:31. This attachment does NOT log the new native ActionMode/FAB commit or multi-playlist popup, so the latest source and user report supply that evidence. New direct on-device verification remains required for the sidebar badge, FAB first-frame placement, 1+2 playlist moves without extraneous success UI and prior native v3 dialog suppression. Keep multi-selection/creation unrelated behavior unchanged.


### 2026-09-27: Nested picker Back and first-frame UI refinements (device verification pending)

In the Add-to-Playlist picker, both Android Back and the original native toolbar navigation button must first ascend one playlist folder at a time; only Back from the root may close the original GMMP picker. GoneSmart intercepts the actual Toolbar navigation ImageButton only while a visible nested picker folder exists. Normal GMMP toolbar actions and root cancellation are unchanged.

For playlist MOVE destination mode, GMMP's mini-player can be an overlapping sibling whose Z-order covers a correctly offset FAB. Crop the entire Move browser to the verified native mini-player's visible top before making the existing AestheticFab visible, then keep it synchronized with native layout. No fixed player-height guess. The native zn3 playlist RecyclerView is made transparent immediately after adapter verification while its full model/style is loaded to reduce the native-screen flash, with a bounded fallback that restores its original alpha if the folder browser fails to attach. Recheck both surfaces and initial-frame geometry on a real GMMP 4.2.0 device before calling the visual bugs resolved.


## Accepted feature baseline — 28 September 2026

The maintainer confirmed that the final combined build at `ece4b658` fixes all outstanding reported folder-view issues. In particular, the Add-to-Playlist toolbar Back navigates from a nested folder to its parent and closes the picker only from root; the normal native Playlist list no longer flashes noticeably when the folder overlay opens; and the normal-Playlists Move-confirmation FAB starts above the actual native mini-player instead of jumping only after the first folder tap. This acceptance applies to the tested GMMP 4.2.0 device and current layout.

The complete accepted user flow includes: independent external/root virtual grouping; real nested physical folders; original GMMP style, localized labels and native playback/playlist actions; playlist creation into the current eligible folder, folder creation/deletion, regular-Playlists multi-selection and native-worker one-or-many playlist moves; original Add-picker multi-destination selection and create-only behavior; context menus and selected-item visual state; live native palette, persistent main-tab location and fresh picker root. The original native drawer Playlists menu entry carries the GoneSmart badge when folders are enabled. Move uses a shared native-looking white-check confirmation with **no** purple sparkle, one central host-locale GoneSmart-only translation key (`Move`), and **no** redundant success Toast. Real failures still produce feedback.

This document intentionally retains previous failed approaches and diagnostic logs for regression context. The current code and this acceptance section take precedence over older chronological statements. Before publishing a signed release, audit all injected GMMP text/native API paths and verify alternative skins/locales and any untested native operations separately.


### 2026-09-28 native-function and full localization audit

The post-acceptance source review is documented in [NATIVE_GMMP_AUDIT.md](NATIVE_GMMP_AUDIT.md). Important correction to earlier single-custom-word claims: **Move is the only new GoneSmart command verb**, but the virtual **Other Locations** node also needs its own fallback translation when the live installed GMMP APK offers no equivalent resource. Its label now resolves via `NativeGmmpUiText.otherLocations` on all four creation/refresh paths; real folder names are left untouched. Source-verified native row, writer, original folder creator, bulk-delete worker and scanner use are listed there, along with outstanding native existing-playlist save/relocation API questions and the remaining multilingual/theme smoke tests. The already accepted Move transaction is preserved without attempting an unverified writer swap.
