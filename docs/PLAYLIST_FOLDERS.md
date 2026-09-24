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
then loose playlists. The virtual folder appears only when a selected
category contains at least one playlist. A physical folder literally named
"Other Locations" has a distinct identity from the virtual folder; both
are navigable. Both settings only affect presentation, not file placement.

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

This confirms one-level GMMP scanner/picker compatibility. Deeper
navigation is supported by GoneSmart's read-only tree model, but
multi-level GMMP scanning and writing still need a device check.

The maintainer reports GMMP-written playlists use absolute song paths.
Before **moving** imported third-party playlists, still check for
possible relative song paths rather than assuming that every M3U is
GMMP-generated.

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

**Current limitation:** Only the classifier and preference persistence
are implemented. The two options are intentionally not exposed as
working GoneSmart UI controls until there are actual native folder
navigation hooks to consume them.

## Step 2 — native GMMP navigation

Determine the actual GMMP main-root setting without hardcoding a
device-specific directory. Identify native playlist data sources and
menu/list hooks in **both** the Playlists tab and the Add to Playlist
picker. The latter already exposes `xn3.q` native playlist paths through
the `bo3` picker; its native `io3` writer must remain the only method
for adding songs.

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
