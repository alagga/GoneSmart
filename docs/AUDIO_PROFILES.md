# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

Each remembered Bluetooth output has its own audio profile. A separate **No Bluetooth device** profile covers playback without a Bluetooth output.

The override granularity mirrors GMMP's UI instead of treating every slider as a separate profile entry:

- **Equalizer** is one complete override. Preset, enabled state, PRE and all bands belong to that one profile section.
- **Effects** are overridden per native GMMP card. A card that contains both an enable switch and one or more sliders is one override, for example Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier.
- Standalone native rows such as Force mono and Auto pitch correction remain standalone overrides.
- Anything without an active override continues to use GMMP's current native value.

Disabling an override returns that section to GMMP inheritance without deleting the stored custom values. Those dormant values must not be displayed as GMMP defaults. Resetting a complete output profile removes the dormant values as well.

## Native inherited state

An inherited control represents **GMMP's current state**, not a GoneSmart default and not a previously stored device value.

GoneSmart therefore mirrors the relevant GMMP preferences and the proven Equalizer state calls into its own read-only native-value cache. The Audio Profiles UI reads that cache when Override is off. If a value has not been observed yet, the UI may show a clearly labelled preview fallback, but it must never call that fallback a GMMP default or silently promote it into an override.

Enabling an override fails closed until every required field for that complete Equalizer/profile card has a real observed native value. This avoids replacing an unknown GMMP setting with a guessed value.

## Equalizer

GoneSmart mirrors the current GMMP Equalizer model as one device-specific profile:

- Equalizer enabled state.
- Preset selection.
- `PRE`.
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`PRE` is GMMP's preamp control and remains part of the complete Equalizer profile rather than being an independent override.

`Custom` is a state, not a selectable preset. Selecting a named preset loads the native PRE and all ten band values that GoneSmart has passively observed for that GMMP preset. Manually moving PRE or any band immediately changes the profile's preset label to `Custom`, and it remains `Custom` even if the slider is later moved back to the same numerical value.

A named preset whose curve has not yet been observed is not guessed. During development, selecting such a preset leaves the current profile unchanged and explains that the native curve still needs to be observed. Preset snapshots are learned passively when GMMP itself loads a preset.

## Effects

The grouped Effects contract is:

- Balance: enabled + amount.
- Force mono: enabled.
- Limiter: enabled + attack + release.
- Bass boost: enabled + strength.
- Tempo: enabled + amount.
- Auto pitch correction: enabled.
- Virtualizer: enabled + strength.
- Amplifier: enabled + gain.

This matches the visual grouping in GMMP and avoids partial states such as overriding Bass boost strength while inheriting the Bass boost enabled switch from another context.

## Route identity and storage

GoneSmart observes output changes through Android audio-device APIs both inside the hooked GMMP process and directly from the companion Audio Profiles screen. The direct companion observation is intentional: the currently connected Bluetooth output must be selectable even if the cross-process route notification has not yet reached the companion.

The first development implementation derived route identity from output type plus address. Device testing showed the same physical `SOUNDPEATS Air3 Deluxe HS` output appearing through multiple Android audio-device representations within the same connection. The v2 contract therefore canonicalizes Bluetooth routes primarily by normalized product name and uses type/address only as a fallback when no name exists.

Audio-profile preferences live in the dedicated `audio_profiles_v2` RemotePreferences group. The companion local mirror also uses a v2 store so development data from the old per-field/unstable-route schema does not leak into the new model.

Stored keys are separated into group override markers and field values:

- `override.<route-id>.<group-id>`: the complete section/card is active for this output.
- `value.<route-id>.<field-id>`: stored custom value, active only while its group override marker exists.
- `native.<field-id>`: last passively observed current GMMP value used for inherited display/seeding.
- `native_preset.<preset-id>.<field-id>`: passively learned native PRE/band snapshot for one preset.

## GMMP 4.2.1 evidence

Device testing proves these native preference identities without GoneSmart mutating GMMP state:

- Equalizer enabled: `player_eqEnabled`.
- Active Equalizer preset: `dspSettings_activePreset`.
- Balance: `dspSettings_balanceEnabled` + `dspSettings_balance`.
- Force mono: `player_forceMono`.
- Limiter: `dspSettings_limiterEnabled`, `dspSettings_attack`, `dspSettings_release`.
- Bass boost: `dspSettings_bassBoostEnabled`, `dspSettings_bassBoostStrength`.
- Tempo: `player_tempoEnabled`, `player_tempo`.
- Auto pitch correction: `player_autoPitchCorrection`.
- Virtualizer: `dspSettings_virtualizerEnabled`, `dspSettings_virtualizerStrength`.
- Amplifier: `dspSettings_loudnessEnabled`, `dspSettings_loudnessGain`.

The proven runtime Equalizer observations for GMMP 4.2.1 are:

- `r61.r(double)` receives PRE.
- `r61.z(int,double)` receives the ten band gains by index.
- `r61.d(boolean)` receives the Equalizer enabled state.

The same runtime class exposes `D()`, `F()`, `K(int)` and `N(int)`. They are used only as bounded read-only development candidates for completing a preset snapshot. A snapshot is accepted only when the band count and returned values semantically agree with the proven setter observations/ranges; the obfuscated method names themselves are not treated as proof.

Preset changes such as Club, Soft, Flat and Live write `dspSettings_activePreset`, after which GMMP saves its current Equalizer state. The development observer uses that natural transition to cache the corresponding PRE/band snapshot. Obfuscated names remain version evidence rather than semantic identity.

## Native-first application contract

GoneSmart must not implement a parallel equalizer or write guessed GMMP settings. The final runtime path must reuse GMMP's own DSP state/writer/reload boundary so playback behavior stays native.

Current profile editing and inherited-state observation are read-only with respect to GMMP. Stored overrides still fail closed for actual playback application until the native mutation/reload boundary has been proved for the supported GMMP build.

The development build keeps only bounded diagnostics for the unresolved boundary: targeted audio-preference observation, passive Equalizer calls and a one-time startup scan of known audio keys. Broad unrelated preference logging is not part of the normal path and all discovery-only diagnostics must be retired once accepted.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and the no-Bluetooth case.
2. The same physical Bluetooth output must not fragment into separate profiles merely because Android reports multiple Bluetooth audio-device types.
3. Equalizer inheritance must leave the complete native GMMP Equalizer untouched and display observed current GMMP values rather than dormant/guessed values.
4. `Custom` must appear only after manual PRE/band editing; selecting a named preset must restore that preset label and its complete native PRE/band shape.
5. Equalizer override must apply the complete profile through a proved native GMMP boundary, including preset/PRE/bands.
6. Each Effects override must apply its complete native card as one unit.
7. Switching outputs must restore inheritance for groups no longer overridden on the destination profile.
8. Editing GMMP's normal setting while a profile inherits it must remain visible to that profile.
9. Missing native values must fail closed rather than becoming persisted suggested defaults.
10. The final accepted path must not retain broad preference probes, class inventories or polling.
11. The normal Build workflow must pass on the exact feature-branch head before merge.
