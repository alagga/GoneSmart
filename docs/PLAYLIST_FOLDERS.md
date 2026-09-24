# Playlist folders — staged v0.4.x implementation (GMMP 4.2.0)

## Product behavior agreed with the maintainer

The physical directories immediately below GMMP's configured **main playlist directory**
appear first as folders in both the native **Playlists** tab and the native
**Add to Playlist** picker. Nested physical directories should be navigable.
A playlist's native absolute path, not its displayed filename, is its identity.

The English-only GoneSmart companion app will have an independent
**Playlist folders** feature toggle and a **Group other locations** option:

- **On:** Direct-root playlists and playlists from *all* other locations
  appear together in a virtual **Other Locations** folder, after real folders.
- **Off:** The same playlists appear as individual entries **below** all real
  physical folders. There is no virtual Other Locations folder.

The option affects only presentation, not what GMMP scans or where it saves
playlists. In either mode, normal playlist taps, creation, native add actions,
and GoneSmart's existing multi-playlist selection must remain functional.
The companion app uses English labels. Native GMMP controls and messages use
GMMP's dynamically localized resources wherever a native equivalent exists.

## Step 1 — committed: read-only classification

`PlaylistFolderIndex` accepts **native GMMP playlist paths** and a caller-
supplied **actual main directory**. It builds deterministic physical/nested
folder nodes and either a virtual Other Locations group or loose rows.
It never touches playlist files. It deduplicates canonical paths, retains
distinct playlists with the same filename, rejects path traversal/similar
directory prefixes, and treats content URIs as external locations.

Unit tests cover both display modes, nested folders, duplicate names,
symlinks/path normalization boundaries, content URIs and folder-name
collisions. This pure classifier does **not** imply GMMP's native list UI
already displays folders.

## Step 2 — GMMP 4.2.0 device feasibility checkpoint

Before altering the native list or moving files, inspect **read-only**:

1. Determine the real configured main playlist path and whether the native
   playlist scanner includes nested folders (not merely additional top-level
   paths). Do not hardcode an English/default path or assume permission.
2. Create one disposable directory under the main playlist root, place a
   small test .m3u there, restart/rescan GMMP, verify it appears in the
   Playlists tab and the Add to Playlist picker, and verify native Add
   actually writes to that exact file.
3. Confirm where GMMP reads native `xn3.q` playlist paths in the *Playlists*
   tab, in addition to the already documented `bo3` Add to Playlist picker.
4. Check actual folder names, nested playlists, external playlists, native
   playlist creation, and scroll/Back behavior; keep regular native
   functionality if one hook cannot be installed.

Do not offer a visible setting until it changes a working native feature.

## Step 3 — native UI and file operations (not yet implemented)

Integrate the shared folder model with both GMMP surfaces via independently
switchable, reversible hooks. The picker must retain long-press multi-select
across folder navigation and dispatch each add through GMMP's native writer.
Start with **read-only navigation**. Only after native scan/write compatibility
is confirmed should GoneSmart offer Create Folder or Move Playlist actions.

Before any move, handle .m3u relative track paths, collisions, write
permissions, playlists held in the GMMP database, scanners, and rollback.
The virtual Other Locations folder must never move files automatically.
Smart Playlists are separate native records and should remain unchanged
until their behavior has been explicitly verified.

## Device test acceptance

A physical folder and a loose main-root playlist must appear in the correct
locations with Group other locations on **and** off. An external playlist
must remain openable and writable through GMMP. Any folder-navigation
extension must be disabled cleanly without changing existing playlist
files or the established multi-playlist picker.
