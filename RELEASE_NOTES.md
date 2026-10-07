# GoneSmart v0.4.1

> **Small follow-up to v0.4.0.** GoneSmart 0.4.0 remains the major feature release: it introduced Playlist folders, Smart-Playlist folders, multi-selection, Playlist Link, Queue Flip / Play flipped, Track Auto-DJ, the redesigned status screen, and the larger GMMP 4.2.1 compatibility/performance update. See the [v0.4.0 release](https://github.com/alagga/GoneSmart/releases/tag/v0.4.0) for the full feature notes.

GoneSmart 0.4.1 is a small compatibility and documentation follow-up. It does not replace the 0.4.0 feature set.

## What's new in 0.4.1

- **GMMP update warning:** the companion app now warns about automatic GMMP Play Store updates. The warning can be dismissed for the currently installed GMMP version and appears again when a different GMMP version is detected.
- **Compatibility help:** the companion app keeps the GMMP update/compatibility advice available in Help and can open GMMP's Play Store page directly.
- **Clearer support guidance:** public documentation now makes the project boundary and GMMP bug-reporting procedure explicit.

## Important compatibility note

- **Tested GMMP version:** 4.2.1
- **Android:** 8.0+
- **Hooking API:** libxposed API 102
- **Recommended rooted setup:** JingMatrix Vector v2.2+
- **LSPatch v1.2:** documented as an experimental no-root path, but **not tested by the GoneSmart maintainer**

GoneSmart is an independent project and is not affiliated with GoneMAD Software. Before reporting a suspected GMMP bug to the GMMP developer, disable GoneSmart and verify that the problem is still reproducible without GoneSmart. Problems that only occur with GoneSmart enabled should be reported to GoneSmart instead.

## GMMP updates

GoneSmart integrates with GMMP internals, so a future GMMP update can temporarily break compatibility. We recommend disabling automatic Play Store updates for GMMP and checking GoneSmart's tested GMMP version before updating manually.

## Looking toward 0.5.0

- **More recommendation providers:** additional recommendation sources are being explored. Services such as Spotify, YouTube and YouTube Music are examples of possible directions only; the exact providers and feasibility still need to be evaluated.
- **Bluetooth device audio profiles:** automatically use different GMMP equalizer and effects settings depending on which Bluetooth headphones, speakers, car audio system or other playback device is connected.

These are current directions and may change during development.

## Installation

Download `GoneSmart-v0.4.1.apk`, install it over the existing GoneSmart installation, then force-stop GMMP and reopen it. Existing GoneSmart settings are preserved. See the repository README and `docs/INSTALLATION.md` for the full setup and troubleshooting guide.
