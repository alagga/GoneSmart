# GoneSmart roadmap

This roadmap describes the current direction for upcoming GoneSmart releases. Planned items may change as development and GMMP compatibility work progresses.

## 0.5.0 integration

### Bluetooth device audio profiles — implemented and device-accepted

The `feature/bluetooth-audio-profiles` branch now provides per-output GMMP Equalizer and Effects profiles for Bluetooth playback devices.

Accepted behavior on GMMP 4.2.1 includes:

- Standard no-Bluetooth baseline plus remembered Bluetooth-output overrides;
- complete Equalizer override with native presets, PRE and adaptive 2–10-band layouts;
- per-card Effects overrides for Balance, Force mono, Limiter, Bass boost, Tempo, Auto pitch correction, Virtualizer and Amplifier;
- automatic restoration when switching between Bluetooth and Standard;
- direct import of GMMP's native ten-band preset catalog;
- frequency-aware remapping when GMMP's selected band count changes.

The remaining work for 0.5.0 is integration/release work: combine this accepted feature with the other current 0.5.0 branches, reconcile shared documentation/UI surfaces, run the merged exact-head CI/device smoke checks and publish a pre-release before promoting it further.

See `docs/AUDIO_PROFILES.md` and `docs/AUDIO_PROFILES_COMPLETION.md`.

### More recommendation providers

Additional Smart Auto-DJ provider work is being developed separately on `feature/auto-dj-providers`. Its final public behavior should be documented from the merged 0.5.0 tree rather than duplicated here before integration.

## Later ideas

- Distinguish two simultaneously remembered Bluetooth devices that expose exactly the same product name without reintroducing route-ID fragmentation.
- Consider caller-authenticated IPC for the GMMP-to-companion audio-observation fallback where Android package visibility prevents the preferred provider transport.
- Revalidate the GMMP Equalizer center-frequency table when support moves beyond GMMP 4.2.1.
- Additional smart-library, playback and GMMP workflow extensions after the 0.5.0 pre-release is stable.
