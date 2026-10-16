# Bluetooth audio profiles

This document defines the development contract for output-specific Equalizer and Effects settings in GoneSmart.

## User model

GoneSmart has one **Standard (no Bluetooth)** audio state and one optional overlay per remembered Bluetooth output.

Standard is not another GoneSmart override profile. It is the user's normal GMMP Equalizer/Effects state when no Bluetooth output is active. Bluetooth profiles are temporary overlays on top of that baseline:

- **Equalizer** is one complete override. Enabled state, preset, PRE and all ten bands belong to that section.
- **Effects** are overridden per native GMMP card. Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier keep their switch and value controls together; Force mono and Auto pitch correction remain standalone overrides.
- Anything without an active Bluetooth override inherits the saved Standard value.

Changes made directly in GMMP while **Standard** is active become the new Standard baseline. Changes made directly in GMMP while a Bluetooth output is active are temporary session edits only: they do not rewrite Standard and they do not rewrite the saved Bluetooth profile. On the next output change, the destination is reconstructed from Standard plus that output's saved overrides.

### Route-transition lifecycle

1. With no Bluetooth output, GMMP's managed Equalizer/Effects state is **Standard**.
2. When Bluetooth output A becomes active, GoneSmart reconstructs the target from Standard and A's enabled overrides.
3. Direct GMMP edits while A is active may affect that session but do not redefine Standard or A's saved profile.
4. Switching to B reconstructs Standard + B; A's temporary edits disappear.
5. Disconnecting Bluetooth restores Standard.
6. Reconnecting A restores Standard + A's saved overrides.

Disabling a Bluetooth override returns that section to Standard without deleting dormant tuning. Resetting a Bluetooth profile removes its dormant values too.

## Standard bootstrap and cross-process persistence

GoneSmart distinguishes:

- `native.<field-id>`: last passively observed live GMMP value, which may be temporary on Bluetooth;
- `standard.<field-id>`: saved normal no-Bluetooth baseline;
- `value.<route-id>.<field-id>`: saved Bluetooth override value;
- `native_preset.<preset-id>.*`: a complete native PRE/band reference curve that was actually observed or imported.

Bluetooth inheritance and override seeding use `standard.*`, never a temporary Bluetooth `native.*` value. Missing Standard values fail closed instead of promoting suggested UI defaults.

A route change can leave native values numerically unchanged. Therefore, when the no-Bluetooth route becomes active, the observer performs one delayed bounded snapshot and force-republishes the complete known GMMP audio state with local-route identity. The Equalizer shape is included when the validated live reader/setter state is available.

### Acknowledged companion bridge

The hooked GMMP process cannot write GoneSmart's libxposed RemotePreferences: that object is read-only in the host process. Earlier development builds therefore sent observations only through package-targeted broadcasts. Device evidence on 2026-10-16 proved that this was not a sufficient persistence boundary: the GMMP-side observer captured a complete Standard snapshot (`17` native preference fields plus `11/11` Equalizer shape fields), yet the application module still saw the framework Standard baseline as incomplete after Bluetooth reconnected.

The current development path therefore uses a synchronous GoneSmart-owned `ContentProvider` bridge for route, native-value and native-preset observations. The provider validates the caller UID/package, writes through `GoneSmartAudioProfileRepository`, verifies the local write, and returns an acknowledgement. A fully explicit component broadcast with foreground/stopped-package flags remains a compatibility fallback rather than the primary success signal.

In addition, no-Bluetooth observations are mirrored into the GoneSmart-owned GMMP-private runtime store `gonesmart_audio_runtime_state`. Only observations attributed to the local route may populate `standard.*` there; Bluetooth observations can never seed Standard. The native application module resolves Standard from framework preferences first and then from this host-local mirror. This removes companion wake/service-sync timing from the native apply critical path without weakening the Standard-vs-Bluetooth isolation rule. Learned native preset curves receive the same host-local fallback.

The companion repository still mirrors acknowledged observations into its local store immediately and flushes pending keys to writable framework RemotePreferences when the Xposed service is available. The Audio Profiles UI therefore reads the same persisted data that the provider positively acknowledged.

## Equalizer

The Equalizer profile contains:

- enabled state;
- preset state;
- PRE;
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`Custom` is a state, not a selectable preset. Manually changing PRE or any band in a Bluetooth profile changes its stored preset state to `Custom`.

### Native Equalizer boundary

For GMMP 4.2.1, natural runtime calls prove one PRE setter, one indexed band setter and one enabled setter on the live Equalizer state owner. The observed fast-path names are `r61.r(double)`, `r61.z(int,double)` and `r61.d(boolean)`, but those obfuscated names are evidence only. Runtime resolution validates the structural owner before they are reused, and an unknown/ambiguous mapping fails closed.

The application module captures a real Equalizer owner only from natural GMMP calls. An actual requested known profile may then apply PRE, all ten bands and enabled state through those already-proved live setter roles. GoneSmart does not implement a parallel DSP.

### Presets

Writing GMMP's `dspSettings_activePreset` preference alone is **not** the native preset-load action. Device testing changed the stored name without loading the corresponding PRE/band curve, so GoneSmart must not pretend that a name-only write selected a preset.

A named preset is usable only when GoneSmart has a complete native PRE + ten-band reference shape. Reference curves can come from:

- a complete, unambiguous native GMMP preset file discovered read-only; or
- passive observation/readback after GMMP itself naturally loads a preset.

The PRE reader and ten-band state reader are accepted only after agreement with naturally observed setter calls. Unknown curves remain unavailable until the actual native preset-action boundary is semantically mapped or the preset is naturally observed in GMMP.

The Audio Profiles preset chooser now lists **only presets with a complete captured native curve**. It no longer offers hard-coded names that can only end in a “Preset curve not learned yet” error. If nothing has been learned yet, the UI explains that a preset should be opened once in GMMP while GoneSmart is running.

## Effects

Managed native cards are:

- Balance: enabled + amount;
- Force mono: enabled;
- Limiter: enabled + attack + release;
- Bass boost: enabled + strength;
- Tempo: enabled + amount;
- Auto pitch correction: enabled;
- Virtualizer: enabled + strength;
- Amplifier: enabled + gain.

The application path writes complete groups through the same native GMMP SharedPreferences keys and native units observed from ordinary GMMP UI changes. Reverse conversions are explicit and host-tested: Balance and Tempo use native fractions, Bass boost and Virtualizer use GMMP's 0..1000 scale, and Amplifier uses hundredths of a dB. Preference commits are read back immediately. Whether those programmatic native preference writes update every live effect immediately remains a device-acceptance item; the latest test could not exercise it because the Standard gate blocked application first.

## Audio Profiles UI

The screen uses a Material toolbar with standard back navigation and an info action; long explanatory copy stays behind the info dialog.

The output selector shows the device/profile name without a redundant `Bluetooth` prefix. The currently active output is indicated by a small green dot and bold label rather than by the literal word `Active`. The same treatment is used in the chooser.

For an enabled Bluetooth Equalizer override, the `Enabled` switch remains interactive. If `Use the GMMP Equalizer for this output` is off, Preset, PRE and all ten band controls are disabled and visually muted because they cannot affect playback while the Equalizer is disabled. Re-enabling it restores those controls without discarding their stored values.

Re-rendering the same output/section after a switch, slider or preset action preserves the current vertical scroll position. Switching output or switching Equalizer/Effects starts the destination section at its own top.

When Standard is incomplete on the active Bluetooth route, one concise Snackbar explains that Bluetooth should be disconnected once, briefly, then reconnected.

## Route identity

GoneSmart observes output changes through Android audio-device APIs in both the hooked GMMP process and the companion screen. Bluetooth routes are canonicalized primarily by normalized product name, with type/address only as fallback when no name exists. This avoids the previously observed fragmentation of one physical output into multiple route IDs, at the cost that two different devices with exactly the same product name are not yet distinguishable.

## GMMP 4.2.1 evidence

Natural GMMP UI writes prove these semantic preference identities:

- Equalizer enabled: `player_eqEnabled`;
- active Equalizer preset: `dspSettings_activePreset`;
- Balance: `dspSettings_balanceEnabled`, `dspSettings_balance`;
- Force mono: `player_forceMono`;
- Limiter: `dspSettings_limiterEnabled`, `dspSettings_attack`, `dspSettings_release`;
- Bass boost: `dspSettings_bassBoostEnabled`, `dspSettings_bassBoostStrength`;
- Tempo: `player_tempoEnabled`, `player_tempo`;
- Auto pitch correction: `player_autoPitchCorrection`;
- Virtualizer: `dspSettings_virtualizerEnabled`, `dspSettings_virtualizerStrength`;
- Amplifier: `dspSettings_loudnessEnabled`, `dspSettings_loudnessGain`.

The 2026-10-16 crash evidence proved that GoneSmart RemotePreferences are read-only from the hooked GMMP process. A later test proved a complete local Standard source snapshot (`17` preference values plus `11/11` Equalizer shape values) while the framework-facing apply module nevertheless remained blocked after reconnect. That isolates the remaining Standard failure to cross-process persistence/visibility rather than GMMP source capture and motivates the acknowledged provider bridge plus host-local Standard fallback described above.

That same run revalidated the live Equalizer owner/setter structure and produced a complete observed current preset shape, but profile application itself never passed the incomplete-Standard guard. Therefore live Equalizer and Effects application are still pending device acceptance; they are not considered fixed merely because the new bridge compiles.

## Native-first application contract

1. Require a complete real Standard baseline before any Bluetooth mutation.
2. Resolve GMMP's native audio preference store semantically from known audio keys.
3. Reconstruct the destination from Standard plus only that Bluetooth output's enabled complete-group overrides.
4. Apply Effects only through observed GMMP native preference boundaries and verify persistence.
5. Apply Equalizer PRE/bands/enabled only through the semantically resolved live GMMP setter roles.
6. Load a named preset only from an exact learned native shape until the real native preset action is proved.
7. Keep temporary Bluetooth GMMP edits out of both Standard and saved Bluetooth profiles.
8. Use acknowledged companion persistence plus the local-route-only host mirror so transport timing cannot silently block or corrupt Standard.
9. Fail closed on missing values, ambiguous native owners or unproved mutations.
10. Keep runtime work event-driven; no polling or recurring broad class/preference scans.

## Acceptance requirements

Before Audio Profiles can be considered complete:

1. Standard bootstrap must be acknowledged and visible after disconnect/reconnect, including a process that originally starts on Bluetooth.
2. The same physical Bluetooth output must keep one route identity in the normal one-device case.
3. Bluetooth native edits must remain temporary; no Bluetooth observation may redefine Standard.
4. Equalizer inheritance must come from Standard, not the last Bluetooth live state.
5. Equalizer PRE/bands/enabled application must be device-verified to change the real live GMMP DSP and restore correctly on output switches.
6. Each Effects card must be device-verified to affect the same live GMMP behavior as its native UI action.
7. Only complete observed preset shapes may be offered/applied; unknown built-in names remain fail-closed until the native preset action is mapped.
8. `Custom` must remain a GoneSmart profile state, not an invented GMMP preset mutation.
9. Standard restoration after interrupted Bluetooth state must be device-verified.
10. Final accepted runtime must retire development-only probes and keep only bounded resolver/postcondition diagnostics.
11. The normal Build workflow must pass on the exact feature-branch head before merge.
