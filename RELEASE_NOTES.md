# GoneSmart v0.4.0

GoneSmart 0.4.0 is a major feature and compatibility update for **GoneMAD Music Player 4.2.1**. It adds several new playlist and playback tools, improves Smart Auto-DJ responsiveness, and makes GoneSmart more robust for future GMMP updates.

## What's new

- **Playlist folders:** browse your playlists in folders, create and delete folders, and move one or several playlists between them.
- **Smart-Playlist folders:** organize Smart Playlists in folders as well, including moving multiple Smart Playlists at once.
- **Multi-selection:** add tracks to several playlists in one step and use multi-selection in supported playlist management views.
- **Playlist Link:** use a normal playlist as a live source inside a Smart Playlist. Changes to the source playlist can be reflected the next time the Smart Playlist is evaluated.
- **Flip queue / Play flipped:** reverse the current queue, or start a normal or Smart Playlist from the end and play it backwards through its order.
- **Track Auto-DJ:** start a fresh Auto-DJ session directly from an individual song. The selected song becomes the starting point for the new session.
- **Improved status screen:** GMMP, Xposed, GoneSmart and version compatibility are shown separately so problems are easier to identify.

## Smart Auto-DJ improvements

- Recommendations are prepared earlier in the background, reducing delays when skipping through tracks quickly.
- Track Auto-DJ works more reliably with normal playlists and large Smart Playlists.
- Auto-DJ continuation and queue handling were hardened for GMMP 4.2.1.
- Several edge cases around rapidly changing playback and Smart-Playlist loading were fixed.

## Performance and usability

- Smoother navigation between Playlist and Smart-Playlist views.
- Less unnecessary background work when views are not visible.
- Faster and more responsive playback-related actions.
- Clearer confirmation and error messages instead of symbol-only popups.
- Numerous smaller UI, stability and cleanup improvements.

## Compatibility

- **Tested GMMP version:** 4.2.1
- **Android:** 8.0+
- **Hooking API:** libxposed API 102
- **Recommended rooted setup:** JingMatrix Vector v2.2+
- **LSPatch v1.2:** still considered experimental / less tested

GoneSmart still depends on GMMP internals, so a future GMMP update can require compatibility work. For 0.4.0, many hooks and lookups were made more dynamic and less dependent on fixed internal names to improve robustness across future versions.

## Planned for 0.5.0

The following areas are currently planned for the next major update:

- **More recommendation providers:** support additional sources for music recommendations, with Spotify, YouTube and YouTube Music among the planned options.
- **Bluetooth device audio profiles:** automatically use different GMMP equalizer and effects settings depending on which Bluetooth headphones, speakers or other audio device is connected.

These are planned features and may change during development.

## Installation

Download `GoneSmart-v0.4.0.apk`, install it, enable GoneSmart for `gonemad.gmmp` in Vector/LSPosed, force-stop GMMP and reopen it. See the repository README and `docs/INSTALLATION.md` for the full setup and troubleshooting guide.
