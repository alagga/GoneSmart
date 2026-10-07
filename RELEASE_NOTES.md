# GoneSmart v0.4.0

GoneSmart 0.4.0 expands the project from Smart Auto-DJ into a broader native-looking GMMP extension and completes compatibility work for **GoneMAD Music Player 4.2.1**.

## Highlights

- **GMMP 4.2.1 compatibility:** all enabled 0.4.0 feature families were migrated to the remapped internals and device-accepted on the maintainer setup.
- **Playlist folders:** browse nested physical playlist folders in the Playlists tab and Add-to-Playlist picker, create/delete folders, and move one or several playlists with native GMMP writers and verification.
- **Smart-Playlist folders:** independent physical folder view for Smart Playlists while GMMP keeps ownership of real Smart-Playlist rows, parsing and actions.
- **Multi-selection:** add songs to several ordinary playlists in one picker session and move multiple Smart Playlists where supported.
- **Playlist Link:** Smart Playlists can reference ordinary playlists as live membership rules; disabling GoneSmart leaves the saved Smart Playlist openable and the extension rule inert.
- **Flip queue / Play flipped:** reverse an existing queue or launch ordinary/Smart playlists in reverse order while preserving native playback ownership.
- **Track Auto-DJ:** start a fresh Auto-DJ session from an individual track, keep that track as the seed and fill to GMMP's configured Initial Size.
- **Status and compatibility UI:** one coherent Status card with independent GMMP, Xposed, GoneSmart and Compatibility health sections; untested GMMP versions are shown in amber.

## Smart Auto-DJ and playback hardening

- Recommendation pools prewarm and top up in the background so rapid skipping does not wait unnecessarily for another provider round.
- Successful pool fills are no longer throttled by the provider backoff intended for unproductive requests.
- Track Auto-DJ uses GMMP's verified native queue/refill paths, preserves the clicked seed across large Smart-Playlist rebuilds and repairs the 4.2.1 queue-continuation edge case without guessing an obfuscated append allocator.
- The first CURRENT exposed by Smart-Playlist playback is treated as provisional during the bounded native-Play settle window. If that transient row disappears, GoneSmart can retarget to the independently observed live CURRENT; outside that window it fails closed.
- Queue Flip learns the current-position writer passively from natural GMMP playback and verifies it independently before controlled use.

## Performance and cleanup

- Offscreen Playlist/Smart-Playlist ViewPager pages are effectively idle.
- Player and navigation badges reuse verified native UI anchors instead of repeatedly rescanning the full view tree.
- Accepted-version discovery probes retire their runtime cost as well as their verbose logs.
- Play-flipped verification uses one bounded postcondition read instead of a polling loop.
- Track Auto-DJ pre-action queue reads run off GMMP's main thread.
- User-facing success/error popups now use readable text fallbacks instead of symbol-only checkmarks/warnings; native GMMP translations are still preferred where available.

## Compatibility

- **GoneMAD Music Player:** 4.2.1 is the tested target for this release.
- **Android:** 8.0+ (`minSdk 26`).
- **Hooking API:** libxposed API 102.
- **Rooted setup:** JingMatrix Vector v2.2+ is the recommended path.
- **LSPatch v1.2:** remains experimental / less tested.

GoneSmart hooks obfuscated GMMP internals. Future GMMP versions are intentionally marked untested until the compatibility workflow in `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` has been completed.

## Installation

Download `GoneSmart-v0.4.0.apk`, install it, enable GoneSmart for `gonemad.gmmp` in Vector/LSPosed, force-stop GMMP and reopen it. See the repository README and `docs/INSTALLATION.md` for the full setup and troubleshooting guide.
