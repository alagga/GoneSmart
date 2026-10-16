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

### Route-transition lifecycle

The intended runtime lifecycle is deliberately deterministic:

1. With no Bluetooth output, GMMP's current managed Equalizer/Effects state is **Standard**. A native GMMP edit in this state updates Standard.
2. When Bluetooth output A becomes active, GoneSmart restores Standard first and then applies only A's enabled overrides.
3. A direct GMMP edit while A is active is allowed to affect the current session and GMMP may display that temporary value, but it does not modify Standard or A's stored profile.
4. When output B becomes active, GoneSmart restores Standard first and then applies only B's enabled overrides. A's temporary session edits are therefore gone.
5. When Bluetooth is disconnected, GoneSmart restores the complete Standard state.
6. Reconnecting A later restores Standard plus A's saved overrides, not whatever temporary values happened to be visible during the previous A session.

This means the values visible in GMMP while a Bluetooth overlay is active describe the **current live DSP state**; they are not automatically the new default.

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

`Custom` is a state, not a selectable preset. Selecting a named preset loads that preset's complete native PRE and ten-band shape. Manually moving PRE or any band changes the Bluetooth profile's preset label to `Custom`.

### Native preset catalog

Named presets must not require the user to open every preset manually just so GoneSmart can learn it. GMMP has a native preset storage/restore system, so GoneSmart performs a bounded, read-only startup import of actual GMMP preset files. Historical GMMP versions stored saved presets under `gmmp/eq_preset`; the runtime also searches GMMP-owned current/private locations with semantic `eq_preset` / `equalizer_preset` names and matching packaged entries so a storage move does not become another hard-coded version dependency.

A preset file is accepted only when it yields a complete ten-band shape plus PRE and every value is inside the supported native gain range. Text key/value, frequency/index keyed and strict compact 11-value layouts are supported, with a narrowly validated raw float/double fallback. Incomplete or ambiguous files fail closed. GoneSmart never generates a curve merely from a preset name.

Imported preset curves are route-independent reference data (`native_preset.*`) and become immediately usable by Bluetooth profiles. Passive observation of GMMP's natural Equalizer setter calls remains a fallback for a preset format that cannot be parsed safely and for current live-state mirroring, but manual per-preset opening is not the primary discovery mechanism.

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
- `native_preset.<preset-id>.<field-id>`: native PRE/band reference shape imported from GMMP or learned passively.

The local route may contain stale v2 development keys from older builds, but runtime policy ignores local override markers; Standard itself is never an overlay.

## GMMP hook compatibility contract

Audio hooks follow the same compatibility rules as the rest of GoneSmart: **obfuscated R8 names are evidence, not semantic identity**.

For GMMP 4.2.1, the previously observed Equalizer owner name and methods remain useful only as a tested fast path. Before that path is accepted at runtime, GoneSmart validates the semantic class shape: one PRE-like `void(double)` natural setter, one band-like `void(int,double)` setter, one enabled-like `void(boolean)` setter, the native gain-array state, and supporting Equalizer-state structure. If the tested class name changes, a bounded startup-only Dex scan searches GMMP implementation classes for the same structural contract and accepts only one unique highest-confidence candidate. Ambiguity fails closed.

GoneSmart never invokes a candidate mutation merely to discover what it does. The resolved Equalizer methods are hooked passively and only natural GMMP calls are observed. The previous hard-coded read-only getter-name probe is not needed for normal preset discovery now that native preset files are imported directly. Accepted-version hot paths therefore avoid continuous reflection/scanning; structural fallback runs only when the validated fast path is unavailable.

The native preference observer is keyed by stable semantic preference IDs rather than obfuscated implementation class names. Any future GMMP version that changes those IDs or the Equalizer state contract remains untested until the normal bounded compatibility workflow has re-established the mapping.

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

The proven passive runtime Equalizer observations for GMMP 4.2.1 are:

- a `void(double)` call on the resolved Equalizer state owner receives PRE;
- a `void(int,double)` call receives the ten band gains by index;
- a `void(boolean)` call receives the Equalizer enabled state.

The accepted 4.2.1 build exposed these roles as `r61.r(double)`, `r61.z(int,double)` and `r61.d(boolean)`, but those names are retained as compatibility evidence only. Runtime hook ownership is resolved and validated structurally as described above.

The 2026-10-16 crash log additionally proved that `getRemotePreferences(audio_profiles_v2)` is read-only inside the hooked GMMP process: calling `edit()` from native-value mirroring throws `UnsupportedOperationException: Read only implementation`. Native observation persistence therefore belongs exclusively to the companion process.

The later 2026-10-16 device log proved a complete natural ten-band `Flat` initialization through the resolved PRE/band setter roles. That confirms passive shape capture works, but it also demonstrated why observation-only preset discovery is insufficient: without a native catalog import only the naturally loaded preset is immediately available.

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
6. Native preset files must be imported read-only when a complete curve can be proven; users must not be required to open every built-in preset manually.
7. `Custom` must appear only after manual PRE/band editing; selecting a named preset must restore that preset label and its complete native PRE/band shape.
8. Equalizer observation/apply boundaries must resolve semantically; tested obfuscated names may only be validated fast paths and ambiguous future mappings fail closed.
9. Equalizer override must apply the complete profile through a proved native GMMP boundary, including preset/PRE/bands.
10. Each Effects override must apply its complete native card as one unit.
11. Switching outputs must restore Standard first and then apply only the destination's saved overrides.
12. Missing native/Standard values must fail closed rather than becoming persisted suggested defaults.
13. The GMMP process must never write GoneSmart RemotePreferences; cross-process observations are persisted by the companion only.
14. The final accepted path must not retain broad preference probes, class inventories or polling.
15. The normal Build workflow must pass on the exact feature-branch head before merge.
