# Playlist folders — staged v0.4.x implementation (GMMP 4.2.0)

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
