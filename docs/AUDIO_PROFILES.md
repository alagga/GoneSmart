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

Because GMMP's native Effects UI persists its live controls through GMMP SharedPreferences, the development implementation uses those same native preference keys for Effects. GoneSmart keeps a tiny private runtime marker in the hooked GMMP process so an interrupted Bluetooth session can be recognized on the next process start.

## Native inherited state and Standard bootstrap

GoneSmart distinguishes two kinds of observed native state:

- `native.<field-id>` is the last passively observed **live** GMMP value. It may therefore contain temporary Bluetooth-session state.
- `standard.<field-id>` is the saved normal GMMP baseline and is updated only by native observations attributed to the no-Bluetooth route.

Bluetooth inheritance and override seeding use `standard.*`, never a temporary `native.*` value. Missing Standard values fail closed rather than silently turning suggested UI fallbacks into saved overrides.

A route change can leave a native value numerically unchanged. The live mirror intentionally deduplicates repeated values, but Standard bootstrap must not depend on a value changing. Therefore, when the no-Bluetooth route becomes active, the observer performs one delayed bounded snapshot of the known native GMMP audio preferences and force-republishes those values with the local route identity. The validated Equalizer readback/observed-setter state is included when available. This is what promotes unchanged current GMMP values into `standard.*`.

The apply module refuses every Bluetooth mutation while the complete Standard baseline is missing. This is important during first-time setup: a user may start GMMP while Bluetooth is already connected, so those first observations cannot safely be promoted to Standard. The Audio Profiles UI shows a short Snackbar telling the user to disconnect Bluetooth once, wait briefly, and reconnect. The local-route snapshot then captures the normal GMMP state before any Bluetooth overlay is allowed to run.

The GMMP-side libxposed RemotePreferences object is read-only. The hooked GMMP process therefore never calls `edit()` on GoneSmart RemotePreferences. Native observations are sent through explicit package-targeted broadcasts to the GoneSmart companion process; only the companion writes the persisted profile mirror. This cross-process ownership rule is mandatory because writing from the GMMP process crashes the host with libxposed's read-only implementation.

GMMP's own SharedPreferences are a separate native host boundary. Natural GMMP 4.2.1 UI writes prove the managed Effects values, Equalizer enabled state and active preset are persisted through those native keys. GoneSmart resolves the correct GMMP preference store by the set of semantic audio keys it contains; the XML file name itself is not a compatibility identity.

## Equalizer

GoneSmart mirrors the current GMMP Equalizer model as one device-specific profile:

- Equalizer enabled state.
- Preset selection.
- `PRE`.
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`Custom` is a state, not a selectable preset. A named preset is selectable for a Bluetooth profile only after GoneSmart has a complete native PRE + ten-band reference curve for that name. Manually moving PRE or any band in a Bluetooth profile changes its label to `Custom`.

### Native preset discovery and validated readback

Preset discovery has two read-only paths. GoneSmart first performs a bounded startup import of actual GMMP preset files. Historical GMMP versions stored saved presets under `gmmp/eq_preset`; the runtime also searches GMMP-owned current/private locations with semantic `eq_preset` / `equalizer_preset` names and matching packaged entries so a storage move does not become another hard-coded version dependency.

A preset file is accepted only when it yields a complete ten-band shape plus PRE and every value is inside the supported native gain range. Text key/value, frequency/index keyed and strict compact 11-value layouts are supported, with a narrowly validated raw float/double fallback. Incomplete or ambiguous files fail closed. GoneSmart never generates a curve merely from a preset name.

The tested GMMP 4.2.1 install exposes no loose built-in preset files. Device evidence also proves that writing `dspSettings_activePreset` by itself changes the stored preset name but does **not** load the corresponding live PRE/band curve. GoneSmart therefore no longer treats that preference write as a native preset loader.

For natural preset selections made in GMMP's own Equalizer UI, the observer performs a delayed read-only state snapshot. The PRE reader is accepted only after it agrees with a naturally observed PRE setter value. The band reader uses the Equalizer owner's unique ten-element gain array and is accepted only after its indexed values agree with the naturally observed band setter calls. A complete validated readback becomes route-independent `native_preset.*` reference data. Until the actual native preset-action boundary is semantically mapped, an unknown built-in preset must be opened once in GMMP before GoneSmart can offer its curve safely.

For a named preset whose reference curve is known, GoneSmart applies the exact observed PRE/band shape through the resolved live GMMP Equalizer setters and stores the native preset label. `Custom` never gets invented as a GMMP preset name; its stored PRE/bands are applied directly through those same proved setter roles. Equalizer enabled state is also applied through the proved live boolean setter rather than relying only on a preference side effect.

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

The application path writes complete groups through the same native GMMP preference keys and native units observed from ordinary GMMP UI changes. Reverse conversions are explicit and host-tested: Balance and Tempo use native fractions, Bass boost and Virtualizer use GMMP's 0..1000 integer scale, and Amplifier uses hundredths of a dB. A native preference commit is read back immediately; failure or an unresolved store fails closed instead of pretending the effect was applied. Device acceptance still has to prove that each programmatic preference write reaches the same live GMMP effect behavior as a natural UI write.

## Audio Profiles UI

The Audio Profiles screen uses a native Material toolbar with standard back navigation and a top-bar info action. Long explanatory copy is kept behind the info dialog instead of permanently consuming vertical space. When Standard is incomplete on an active Bluetooth route, a short Snackbar gives the one-time setup action.

Re-rendering the same output/section after an override or preset action preserves the current vertical scroll position. Switching output or switching between Equalizer and Effects intentionally starts that destination at its own top position.

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

For GMMP 4.2.1, the previously observed Equalizer owner name and methods remain useful only as a tested fast path. Before that path is accepted at runtime, GoneSmart validates the semantic class shape: one PRE-like `void(double)` natural setter, one band-like `void(int,double)` setter, one enabled-like `void(boolean)` setter, the unique native gain-array state, and supporting Equalizer-state structure. If the tested class name changes, a bounded startup-only Dex scan searches GMMP implementation classes for the same structural contract and accepts only one unique highest-confidence candidate. Ambiguity fails closed.

GoneSmart never invokes a candidate mutation merely to discover what it does. The Equalizer application module captures a real live instance only from natural GMMP calls after the owner has been resolved semantically. Only then may the already-proved PRE/band/enabled setter roles be invoked for an actual requested profile. Read-only PRE/band snapshot helpers are additionally validated against natural setter calls before their values can be used for preset learning. The native preference store is likewise resolved by its semantic audio-key set rather than a historical file name.

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

Later 2026-10-16 device logs proved a complete natural ten-band `Flat` initialization through the resolved PRE/band setter roles and found no loose preset-file candidates. The next application test then proved that programmatically changing only `dspSettings_activePreset` updates the stored name but does not load the live curve. That evidence is why unknown named presets now fail closed rather than pretending the name write was sufficient.

## Native-first application contract

GoneSmart does not implement a parallel equalizer or write guessed DSP values. The development runtime applies only through native GMMP boundaries already established by natural 4.2.1 behavior:

1. require a complete Standard baseline before any Bluetooth mutation;
2. resolve the real GMMP audio preference store from semantic keys;
3. reconstruct the destination as Standard plus that Bluetooth output's enabled complete-group overrides;
4. write Effects through GMMP's observed native preference keys and native units;
5. apply complete Equalizer PRE/bands/enabled through the semantically resolved live GMMP setter roles, and write a named preset label only when its exact reference shape is already known;
6. immediately read back native preference writes and fail closed on unresolved or incomplete groups;
7. preserve an interrupted-overlay marker so a later no-Bluetooth process start can restore Standard rather than treating a Bluetooth overlay as the baseline.

This mutation path is implemented for device testing but is not considered accepted merely because it compiles. The next device round must prove that the repaired Standard bootstrap completes, programmatic Effects writes trigger live GMMP behavior, live Equalizer setters affect playback, and route transitions restore Standard correctly. After that evidence, only accepted bounded postconditions remain and development-only diagnostics can be retired.

## Acceptance requirements

Before this feature can be considered complete:

1. Active route changes must be detected correctly for the normal one-Bluetooth-output case and Standard/no-Bluetooth case.
2. The same physical Bluetooth output must not fragment into separate profiles merely because Android reports multiple Bluetooth audio-device types.
3. Standard must be bootstrapped from a real no-Bluetooth GMMP state even when the process initially starts with Bluetooth connected.
4. Native GMMP edits made while Standard is active must update Standard; native edits made while Bluetooth is active must remain temporary.
5. Equalizer inheritance must use Standard rather than a temporary live Bluetooth value.
6. Preset files must be imported read-only when a complete curve can be proven; otherwise a naturally selected native GMMP preset may be learned only through validated read-only state.
7. `Custom` must appear only after manual PRE/band editing; selecting a known named preset must restore that preset label and its complete native PRE/band shape.
8. Equalizer observation/apply boundaries must resolve semantically; tested obfuscated names may only be validated fast paths and ambiguous future mappings fail closed.
9. Equalizer override must apply the complete profile through proved native GMMP boundaries, including PRE/bands/enabled.
10. Each Effects override must apply its complete native card as one unit and device evidence must confirm live playback follows the programmatic native preference write.
11. Switching outputs must restore Standard first and then apply only the destination's saved overrides, including after an interrupted Bluetooth process.
12. Missing native/Standard/preset-shape values must fail closed rather than becoming persisted suggested defaults or name-only mutations.
13. The GMMP process must never write GoneSmart RemotePreferences; cross-process observations are persisted by the companion only.
14. The final accepted path must not retain broad preference probes, class inventories or polling.
15. The normal Build workflow must pass on the exact feature-branch head before merge.
