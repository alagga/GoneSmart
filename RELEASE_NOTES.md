# GoneSmart v0.5.0-rc.1

This is the first **release candidate for GoneSmart 0.5.0**. It combines the three completed 0.5.0 workstreams so they can be tested together before the stable release.

## Highlights

### Bluetooth Audio Profiles

- Remember different GMMP Equalizer and Effects overrides for Bluetooth outputs.
- Keep the normal no-Bluetooth GMMP setup as **Standard** and restore it automatically when appropriate.
- Use GMMP Equalizer presets, PRE and adaptive 2–10-band Equalizer layouts.
- Override Effects individually, including Balance, Force mono, Limiter, Bass boost, Tempo, Auto pitch correction, Virtualizer and Amplifier.

### Smarter Smart Auto-DJ

- Combines **Local Playlist Affinity**, **ListenBrainz** and **Last.fm**.
- Adds Smart and Custom provider modes with optional source weights.
- Adds controlled near-tie variety, staged artist rescue and better caching for large libraries/playlists.
- Keeps the final playable choice local: GoneSmart only selects tracks that exist in your GMMP library.

### GMMP 4.2.1 compatibility and reliability

- Playlist Link is re-accepted on GMMP 4.2.1.
- Track Auto-DJ is re-accepted across normal playlists, Queue, playable file-browser rows and large Smart Playlists.
- Compatibility-sensitive GMMP lookups prefer semantic/structural validation and fail closed rather than guessing when a state-changing boundary is ambiguous.

## Prerelease note

This build is intentionally marked **prerelease**. Please test the combined feature set before treating it as the stable 0.5.0 release, especially Bluetooth output switching, Equalizer/effects restoration, Smart Auto-DJ refills and Playlist Link/Track Auto-DJ on GMMP 4.2.1.

If something breaks, copy the companion app's **Logs** view first and attach filtered Android Logcat when deeper diagnosis is needed.

## Install / update

Install `GoneSmart-v0.5.0-rc.1.apk` over your existing GoneSmart installation, then force-stop GMMP and reopen it. Existing GoneSmart settings are preserved. See the repository README and `docs/INSTALLATION.md` for full setup and troubleshooting information.
