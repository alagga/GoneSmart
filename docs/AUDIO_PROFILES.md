# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

GoneSmart has one **Standard (no Bluetooth)** audio state and one optional overlay per remembered Bluetooth output.

Standard is not another GoneSmart override profile. It is the user's normal GMMP Equalizer/Effects state when no Bluetooth output is active. Bluetooth profiles are temporary overlays on top of that baseline:

- **Equalizer** is one complete override. Preset, enabled state, PRE and all bands belong to that one profile section.
- **Effects** are overridden per native GMMP card. A card that contains both an enable switch and one or more sliders is one override, for example Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier.
- Standalone native rows such as Force mono and Auto pitch correction remain standalone overrides.
- Anything without an active Bluetooth override uses the saved Standard value.

Changes made directly in GMMP while **Standard** is active become the new Standard baseline. Changes made directly in GMMP while a Bluetooth output is active are temporary session edits only: they do not rewrite Standard and they do not rewrite the saved Bluetooth profile. On the next output change, the native-application path reconstructs the destination from Standard plus that destination's saved overrides, so temporary edits cannot leak between outputs.

Disabling a Bluetooth override returns that section to Standard without deleting the dormant custom values. Resetting a Bluetooth profile removes its dormant values as well.

### Route-transition lifecycle

The runtime lifecycle is deliberately deterministic:

1. With no Bluetooth output, GMMP's current managed Equalizer/Effects state is **Standard**. A native GMMP edit in this state updates Standard.
2. When Bluetooth output A becomes active, GoneSmart restores Standard first and then applies only A's enabled overrides.
3. A direct GMMP edit while A is active is allowed to affect the current session and GMMP may display that temporary value, but it does not modify Standard or A's stored profile.
4. When output B becomes active, GoneSmart restores Standard first and then applies only B's enabled overrides. A's temporary session edits are therefore gone.
5. When Bluetooth is disconnected, GoneSmart restores the complete Standard state.
6. Reconnecting A later restores Standard plus A's saved overrides, not whatever temporary values happened to be visible during the previous A session.

This means the values visible in GMMP while a Bluetooth overlay is active describe the **current live DSP state**; they are not automatically the new default.

Because GMMP's native Effects UI persists its live controls through GMMP SharedPreferences, the development implementation uses those same native preference keys for Effects. GoneSmart keeps a tiny private runtime marker in the hooked GMMP process so an interrupted Bluetooth session can be recognized on the next process start. The application module is initialized before passive Standard observation: if the previous process died while a Bluetooth overlay was active and the next start has no Bluetooth output, Standard is restored before those temporary values can be mistaken for a new baseline.

## Native inherited state

GoneSmart distinguishes two kinds of observed native state:

- `native.<field-id>` is the last passively observed **live** GMMP value. It may therefore contain temporary Bluetooth-session state.
- `standard.<field-id>` is the saved normal GMMP baseline and is updated only by native observations attributed to the no-Bluetooth route.

Bluetooth inheritance and override seeding use `standard.*`, never a temporary `native.*` value. Missing Standard values fail closed rather than silently turning suggested UI fallbacks into saved overrides.

The GMMP-side libxposed RemotePreferences object is read-only. The hooked GMMP process therefore never calls `edit()` on GoneSmart RemotePreferences. Native observations are sent through explicit package-targeted broadcasts to the GoneSmart companion process; only the companion writes the persisted profile mirror. This cross-process ownership rule is mandatory because writing from the GMMP process crashes the host with libxposed's read-only implementation.

GMMP's own SharedPreferences are a separate native host boundary. Natural GMMP 4.2.1 UI writes prove the managed Effects values, Equalizer enabled state and active preset are persisted through those native keys. GoneSmart resolves the correct GMMP preference store by the set of semantic audio keys it contains; the XML file name itself is not a compatibility identity.

## Equalizer

GoneSmart mirrors the current GMMP Equalizer model as one device-specific profile:

- Equalizer enabled state.
- Preset selection.
- `PRE`.
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`Custom` is a state, not a selectable preset. Selecting a named preset asks GMMP's native preset path to load that preset. Once GMMP exposes the resulting PRE and ten bands through its natural Equalizer setters, GoneSmart stores the exact native shape and backfills every Bluetooth profile that points at that preset. Manually moving PRE or any band in a Bluetooth profile changes its label to `Custom`.

### Native preset discovery and on-demand loading

Preset discovery has two native-first paths. GoneSmart first performs a bounded, read-only startup import of actual GMMP preset files. Historical GMMP versions stored saved presets under `gmmp/eq_preset`; the runtime also searches GMMP-owned current/private locations with semantic `eq_preset` / `equalizer_preset` names and matching packaged entries so a storage move does not become another hard-coded version dependency.

A preset file is accepted only when it yields a complete ten-band shape plus PRE and every value is inside the supported native gain range. Text key/value, frequency/index keyed and strict compact 11-value layouts are supported, with a narrowly validated raw float/double fallback. Incomplete or ambiguous files fail closed. GoneSmart never generates a curve merely from a preset name.

Built-in presets are not assumed to exist as loose preset files. If a named preset has no imported curve, selecting it is still allowed: GoneSmart writes the same native active-preset setting changed by GMMP's own preset UI. GMMP then remains responsible for loading the preset. Passive observation captures the resulting native PRE/bands and turns that result into route-independent `native_preset.*` reference data. Therefore users do not have to pre-open every built-in preset manually.

For a named preset whose reference curve is already known, GoneSmart also reapplies the exact observed PRE/band shape through the resolved live GMMP Equalizer setters. `Custom` never gets invented as a GMMP preset name; its stored PRE/bands are applied directly through those same proved setter roles.

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

The application path writes complete groups through the same native GMMP preference keys and native units observed from ordinary GMMP UI changes. Reverse conversions are explicit and host-tested: Balance and Tempo use native fractions, Bass boost and Virtualizer use GMMP's 0..1000 integer scale, and Amplifier uses hundredths of a dB. A native preference commit is read back immediately; failure or an unresolved store fails closed instead of pretending the effect was applied.

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

GoneSmart never invokes a candidate mutation merely to discover what it does. The Equalizer application module captures a real live instance only from natural GMMP calls after the owner has been resolved semantically. Only then may the already-proved PRE/band setter roles be invoked for an actual requested profile. The native preference store is likewise resolved by its semantic audio-key set rather than a historical file name.

Accepted-version hot paths avoid continuous reflection/scanning: structural fallback is startup-only and runs only when the validated fast path is unavailable. Any future GMMP version that changes the semantic preference IDs or Equalizer state contract remains untested until the normal bounded compatibility workflow has re-established the mapping.

## GMMP 4.2.1 evidence

Device testing proves these native preference identities through ordinary GMMP UI writes:

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

The later 2026-10-16 device log proved a complete natural ten-band `Flat` initialization through the resolved PRE/band setter roles. A subsequent run found no loose preset-file candidates, demonstrating that built-in presets cannot rely on file discovery alone. Named built-ins therefore use the native active-preset boundary on demand, while the resulting exact shape is learned from GMMP itself.

## Native-first application contract

GoneSmart does not implement a parallel equalizer or write guessed DSP values. The development runtime now applies profiles through native GMMP boundaries already established by natural 4.2.1 behavior:

1. resolve the real GMMP audio preference store from semantic keys;
2. reconstruct the destination as Standard plus that Bluetooth output's enabled complete-group overrides;
3. write Effects, Equalizer enabled state and a named active preset using GMMP's own proven preference keys and native units;
4. apply exact Standard/Custom/known-preset PRE and bands through the semantically resolved live GMMP Equalizer setter roles;
5. immediately read back native preference writes and fail closed on unresolved or incomplete groups;
6. preserve an interrupted-overlay marker so a later no-Bluetooth process start restores Standard before passive baseline observation.

This mutation path is implemented for device testing but is not considered accepted merely because it compiles. The next device round must prove that programmatic native preference changes trigger the same live GMMP Effects/preset behavior as natural UI writes and that route transitions restore Standard correctly. After that evidence, only the accepted bounded postconditions remain and development-only diagnostics can be retired.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and Standard/no-Bluetooth case.
2. The same physical Bluetooth output must not fragment into separate profiles merely because Android reports multiple Bluetooth audio-device types.
3. Standard must reflect native GMMP edits made while no Bluetooth output is active.
4. Native edits made while Bluetooth is active must remain temporary and must not rewrite Standard or the saved Bluetooth profile.
5. Equalizer inheritance must use Standard rather than a temporary live Bluetooth value.
6. Preset files must be imported read-only when a complete curve can be proven, while built-in presets with no loose file must load on demand through GMMP's native preset boundary; users must not be required to pre-open every built-in manually.
7. `Custom` must appear only after manual PRE/band editing; selecting a named preset must restore that preset label and its complete native PRE/band shape.
8. Equalizer observation/apply boundaries must resolve semantically; tested obfuscated names may only be validated fast paths and ambiguous future mappings fail closed.
9. Equalizer override must apply the complete profile through the proved native GMMP boundary, including preset/PRE/bands.
10. Each Effects override must apply its complete native card as one unit and device evidence must confirm live playback follows the programmatic native preference write.
11. Switching outputs must restore Standard first and then apply only the destination's saved overrides, including after an interrupted Bluetooth process.
12. Missing native/Standard values must fail closed rather than becoming persisted suggested defaults.
13. The GMMP process must never write GoneSmart RemotePreferences; cross-process observations are persisted by the companion only.
14. The final accepted path must not retain broad preference probes, class inventories or polling.
15. The normal Build workflow must pass on the exact feature-branch head before merge.
