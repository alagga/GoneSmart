# GoneSmart roadmap

This roadmap describes the current direction for upcoming GoneSmart releases. Planned items may change as development and GMMP compatibility work progresses.

## 0.5.0 release candidate

`0.5.0-rc.1` combines the three completed 0.5.0 workstreams in one release tree:

### Bluetooth Audio Profiles

Per-output GMMP Equalizer and Effects profiles are implemented and device-accepted on GMMP 4.2.1. GoneSmart remembers the normal no-Bluetooth state as Standard, applies enabled Bluetooth overrides automatically, imports compatible GMMP Equalizer presets and adapts stored curves across supported 2–10-band layouts.

See `docs/AUDIO_PROFILES.md` and `docs/AUDIO_PROFILES_COMPLETION.md`.

### Smarter recommendation blend

Smart Auto-DJ combines Local Playlist Affinity, ListenBrainz and Last.fm with Smart/Custom provider policy, optional per-provider weights, bounded corroboration, controlled near-tie variety, staged artist rescue and cached large-library/playlist reads. The final playable result must still exist in the local GMMP library.

See `docs/AUTO_DJ_PROVIDERS.md` and `docs/PROVIDERS.md`.

### GMMP 4.2.1 compatibility

The accepted Playlist Link and Track Auto-DJ compatibility work is part of the same candidate. Native GMMP behavior remains authoritative, dynamic semantic/structural resolution is preferred over obfuscated names, and ambiguous state-changing boundaries fail closed.

Stable `0.5.0` follows after the combined release candidate has passed prerelease testing.

## 0.6.0 outlook

- Evaluate **SoundCloud** as an additional online recommendation source if a safe distributed-client authentication model is available.
- Evaluate **Smart Playlists** as an optional, deliberately weaker local affinity signal.
- Revalidate Equalizer compatibility data when support moves beyond GMMP 4.2.1.
- Continue replacing version-specific compatibility fast paths with cached semantic bindings where that can be proven safely.

## Later ideas

- Distinguish remembered Bluetooth devices that expose the same product name without reintroducing unstable route-ID fragmentation.
- Consider caller-authenticated IPC for the GMMP-to-companion audio-observation fallback where Android package visibility prevents the preferred provider transport.
- Additional smart-library, playback and GMMP workflow extensions after 0.5.0 is stable.
