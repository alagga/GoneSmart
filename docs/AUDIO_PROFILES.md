# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

GoneSmart has one **Standard (no Bluetooth)** audio state and one optional overlay per remembered Bluetooth output.

Standard is not another GoneSmart override profile. It is the user's normal GMMP Equalizer/Effects state when no Bluetooth output is active. Bluetooth profiles are temporary overlays on top of that baseline:

- **Equalizer** is one complete override. Preset, enabled state, PRE and all bands belong to that one profile section.
- **Effects** are overridden per native GMMP card. A card that contains both an enable switch and one or more sliders is one override, for example Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier.
- Standalone native rows such as Force mono and Auto pitch correction remain standalone overrides.
- Anything without an active Bluetooth override uses the saved Standard value.

Changes made directly in GMMP while **Standard** is active become the new Standard baseline. Changes made directly in GMMP while a Bluetooth output is active are temporary session edits only: they do not rewrite Standard and they do not rewrite the saved Bluetooth profile. On the next output change, the final native-application path reconstructs the destination from Standard plus that destination's saved overrides, so temporary edits cannot leak between outputs.

Disabling a Bluetooth override returns that section to Standard without deleting the dormant custom values. Resetting a Bluetooth profile removes its dormant values as well.

## Native inherited state

GoneSmart distinguishes two kinds of observed native state:

- `native.<field-id>` is the last passively observed **live** GMMP value. It may therefore contain temporary Bluetooth-session state.
- `standard.<field-id>` is the saved normal GMMP baseline and is updated only by native observations attributed to the no-Bluetooth route.

Bluetooth inheritance and override seeding use `standard.*`, never a temporary `native.*` value. Missing Standard values fail closed rather than silently turning suggested UI fallbacks into saved overrides.

The GMMP-side libxposed RemotePreferences object is read-only. The hooked GMMP process therefore never calls `edit()` on GoneSmart RemotePreferences. Native observations are sent through explicit package-targeted broadcasts to the GoneSmart companion process; only the companion writes the persisted mirror. This cross-process ownership rule is mandatory because writing from the GMMP process crashes the host with libxposed's read-only implementation.

## Equalizer

GoneSmart mirrors the current GMMP Equalizer model as one device-specific profile:

- Equalizer enabled state.
- Preset selection.
- `PRE`.
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`Custom` is a state, not a selectable preset. Selecting a named preset loads the native PRE and all ten band values that GoneSmart has passively observed for that GMMP preset. Manually moving PRE or any band changes the Bluetooth profile's preset label to `Custom`.

A named preset whose curve has not yet been observed is never guessed. Preset snapshots are learned passively when GMMP itself loads a preset. Startup preset observation must preserve any already observed native PRE/band setter shape; only a real runtime preset change invalidates the previous shape before learning the new one.

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

GoneSmart observes output changes through Android audio-device APIs both inside the hooked GMMP process and directly from the companion Audio Profiles screen. Bluetooth routes are canonicalized primarily by normalized product name, with type/address used only as fallback when no name exists.

Audio-profile preferences live in the dedicated `audio_profiles_v2` RemotePreferences group. Stored keys are separated into group override markers and field values:

- `override.<route-id>.<group-id>`: complete Bluetooth section/card override is active.
- `value.<route-id>.<field-id>`: stored Bluetooth custom value.
- `standard.<field-id>`: saved no-Bluetooth GMMP baseline.
- `native.<field-id>`: last live passively observed GMMP value.
- `native_preset.<preset-id>.<field-id>`: passively learned native PRE/band snapshot for one preset.

The local route may contain stale v2 development keys from older builds, but runtime policy ignores local override markers; Standard itself is never an overlay.

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

The same runtime class exposes `D()`, `F()`, `K(int)` and `N(int)`. They remain bounded read-only development candidates for completing a preset snapshot and are accepted only after semantic agreement with the proven setter observations/ranges.

The 2026-10-16 crash log additionally proved that `getRemotePreferences(audio_profiles_v2)` is read-only inside the hooked GMMP process: calling `edit()` from native-value mirroring throws `UnsupportedOperationException: Read only implementation`. Native observation persistence therefore belongs exclusively to the companion process.

## Native-first application contract

GoneSmart must not implement a parallel equalizer or write guessed GMMP settings. The final runtime path must reuse GMMP's own DSP state/writer/reload boundary so playback behavior stays native.

Profile editing, route detection and inherited-state observation are already separated from GMMP mutation. Stored Bluetooth overrides still fail closed for actual playback application until the native mutation/reload boundary has been proved for the supported GMMP build.

When that boundary is accepted, every route transition must conceptually do this:

1. restore the complete Standard baseline for all managed controls;
2. apply only the destination Bluetooth profile's enabled overrides;
3. leave non-overridden destination controls at Standard;
4. treat subsequent direct GMMP edits during Bluetooth as temporary until the next route transition.

The development build keeps only bounded diagnostics for the unresolved native boundary. Broad unrelated preference logging is not part of the normal path and all discovery-only diagnostics must be retired once accepted.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and Standard/no-Bluetooth case.
2. The same physical Bluetooth output must not fragment into separate profiles merely because Android reports multiple Bluetooth audio-device types.
3. Standard must reflect native GMMP edits made while no Bluetooth output is active.
4. Native edits made while Bluetooth is active must remain temporary and must not rewrite Standard or the saved Bluetooth profile.
5. Equalizer inheritance must use Standard rather than a temporary live Bluetooth value.
6. `Custom` must appear only after manual PRE/band editing; selecting a named preset must restore that preset label and its complete observed native PRE/band shape.
7. Equalizer override must apply the complete profile through a proved native GMMP boundary, including preset/PRE/bands.
8. Each Effects override must apply its complete native card as one unit.
9. Switching outputs must restore Standard first and then apply only the destination's saved overrides.
10. Missing native/Standard values must fail closed rather than becoming persisted suggested defaults.
11. The GMMP process must never write GoneSmart RemotePreferences; cross-process observations are persisted by the companion only.
12. The final accepted path must not retain broad preference probes, class inventories or polling.
13. The normal Build workflow must pass on the exact feature-branch head before merge.
