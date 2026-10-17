# Bluetooth audio profiles

Bluetooth Audio Profiles is the GoneSmart feature for automatically applying different GoneMAD Music Player Equalizer and Effects settings for different Bluetooth outputs.

This document describes the accepted GMMP 4.2.1 behavior and the implementation contract. The detailed acceptance/audit record lives in `docs/AUDIO_PROFILES_COMPLETION.md`.

## User model

GoneSmart keeps one **Standard** state and optional per-Bluetooth-output overrides.

- **Standard** is the user's normal GMMP Equalizer/Effects state while no Bluetooth output is active. It is a baseline, not another GoneSmart override profile.
- Each remembered Bluetooth output can override **Equalizer** as one complete section and can independently override the managed **Effects** cards.
- A disabled override inherits Standard.
- Direct GMMP edits while Standard is active may redefine Standard.
- Direct GMMP edits while a Bluetooth output is active are temporary session edits only. They do not silently rewrite Standard or the saved Bluetooth profile.
- Switching output reconstructs the destination from Standard plus that output's enabled overrides.

Disabling an override keeps its dormant values so it can be enabled again later. Resetting a Bluetooth profile removes its saved overrides.

## Managed controls

### Equalizer

The Equalizer override owns:

- enabled state;
- preset state;
- PRE;
- a canonical ten-point gain curve.

The canonical stored centers are `32`, `64`, `128`, `256`, `512` Hz and `1`, `2`, `4`, `8`, `16` kHz.

`Custom` is a GoneSmart profile state, not an invented GMMP preset. Manually editing PRE or a band changes the saved route state to `Custom`.

### Effects

GoneSmart manages these native GMMP controls:

- Balance: enabled + amount;
- Force mono: enabled;
- Limiter: enabled + attack + release;
- Bass boost: enabled + strength;
- Tempo: enabled + amount;
- Auto pitch correction: enabled;
- Virtualizer: enabled + strength;
- Amplifier: enabled + gain.

For cards with an Enabled switch, dependent controls remain visible but are disabled while either the Bluetooth Override or the native Enabled switch is off.

## Standard bootstrap and persistence

GoneSmart distinguishes four classes of stored audio data:

- `native.<field-id>`: the last passively observed live GMMP value;
- `standard.<field-id>`: the saved no-Bluetooth baseline;
- `value.<route-id>.<field-id>`: an explicit Bluetooth-route override value;
- `native_preset.<preset-id>.*`: a complete native Equalizer preset reference curve.

Bluetooth inheritance and override seeding use `standard.*`, never an arbitrary Bluetooth `native.*` value. Missing Standard data fails closed instead of being replaced by suggested UI defaults.

When Standard becomes active, the GMMP-side observer performs one delayed, bounded snapshot so unchanged native values are re-attributed to the local route. Standard application also has a GMMP-private runtime mirror (`gonesmart_audio_runtime_state`) so restoration does not depend on companion-process wake timing. Only observations attributed to the local route may populate that mirror's `standard.*` keys.

## Cross-process transport

The hooked GMMP process treats GoneSmart's libxposed RemotePreferences as read-only.

Native observations are therefore sent to the companion process through:

1. the GoneSmart `ContentProvider` bridge when Android allows GMMP to resolve it;
2. an explicit component receiver fallback when that provider is not visible.

The tested device cannot resolve the provider authority from the GMMP host, so the provider is probed once per process and the explicit receiver is then used for the remaining bounded events. The receiver accepts only GoneSmart's known audio observation actions and the repository normalizes/validates every field before storing it. The exported receiver exists only because the tested cross-package provider path is unavailable; replacing this fallback with caller-authenticated IPC is a future hardening opportunity, not a reason to guess at a different GMMP mutation boundary.

## Native-first application

Effects are applied through the same semantic GMMP SharedPreferences keys and native value types observed from GMMP's own UI. The native preference store is resolved from the audio keys it actually contains; a historical XML filename is not its identity. Writes are read back immediately and an ambiguous store fails closed.

Equalizer PRE/bands/enabled are applied through the live GMMP Equalizer owner already proved by natural calls. GoneSmart does not implement a second DSP.

For GMMP 4.2.1, the accepted fast-path evidence is an Equalizer state owner observed as `r61` with PRE/band/enabled calls corresponding to `r(double)`, `z(int,double)` and `d(boolean)`. Those obfuscated names are evidence only. The structural resolver must still prove one unique semantic owner before the setters are shared through `GmmpEqualizerRuntimeBridge`.

Exactly one GoneSmart hook family owns those setter observations. The application module consumes the shared live owner; it does not install a competing setter hook.

## Live Equalizer band layouts

GMMP 4.2.1 allows 2 through 10 Equalizer bands. GoneSmart stores one canonical ten-point curve but adapts the UI and native apply path to the live count.

The device-verified center frequencies are:

| Bands | GMMP 4.2.1 centers |
| ---: | --- |
| 2 | 350 Hz, 15 kHz |
| 3 | 150 Hz, 1 kHz, 15 kHz |
| 4 | 70 Hz, 400 Hz, 2 kHz, 15 kHz |
| 5 | 64 Hz, 256 Hz, 1 kHz, 4 kHz, 16 kHz |
| 6 | 45 Hz, 150 Hz, 450 Hz, 1 kHz, 4 kHz, 15 kHz |
| 7 | 40 Hz, 100 Hz, 300 Hz, 750 Hz, 2 kHz, 5 kHz, 15 kHz |
| 8 | 35 Hz, 85 Hz, 200 Hz, 500 Hz, 1 kHz, 3 kHz, 6 kHz, 15 kHz |
| 9 | 35 Hz, 70 Hz, 150 Hz, 350 Hz, 700 Hz, 1 kHz, 3 kHz, 7 kHz, 15 kHz |
| 10 | 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, 16 kHz |

The live band count is accepted only from independently validated native configuration/layout evidence: the semantic band-count preference and the native DspManager creation boundary. **Never infer the live band count from the length of GMMP's internal Equalizer gain array.** Device evidence proved that smaller layouts may keep an extra internal element (for example six real bands with an array length of seven).

The live N-band curve is projected to/from the canonical ten-point curve using GoneSmart's frequency-aware mapping:

- exact shared center frequencies keep the exact gain;
- intermediate centers use linear interpolation in log-frequency space;
- targets outside the source range keep the nearest edge gain;
- every returned gain is normalized to GMMP/GoneSmart's 0.1 dB field/slider grid.

This is GoneSmart's deterministic remapping policy; it is not claimed to be GMMP's private internal preset-interpolation algorithm.

Only the active N band setters are invoked. Merely changing the visible GMMP band count does not mutate the canonical route curve. Editing a visible N-band slider expands the edited live curve back to canonical ten-point storage and changes the route preset state to `Custom`.

## Equalizer readback and fail-closed behavior

The observer may correlate read-only internal Equalizer state with natural setters, but internal storage layout is not assumed. On smaller GMMP layouts the internal gain array may not have the same length as the real band count, so direct array readback deliberately fails closed instead of guessing which entries are semantic bands.

Natural setter observations remain sufficient to reconstruct a complete live shape when every active band was observed. Standard snapshots use validated direct readback when available and otherwise the complete observed setter shape.

## Native Equalizer presets

Writing `dspSettings_activePreset` is metadata and is not treated as the native preset-load action.

GoneSmart imports complete GMMP-owned preset curves directly and read-only from the installed preset storage. GMMP organizes presets by band count; the current catalog prefers the complete ten-band family used by canonical GoneSmart storage and rejects malformed or explicitly different-band curves.

The accepted native file schema is:

```text
Preset name
PRE
band count
N band values
```

The parser also retains the already-tested compatible text/CSV/raw formats, but every accepted preset must resolve to one complete PRE + ten-band shape. Missing or ambiguous data fails closed.

On the accepted GMMP 4.2.1 device, startup discovery imports all 17 native ten-band presets without asking the user to open them one by one.

Natural preset actions remain a fallback/validation source. They do not overwrite the exact ten-band catalog while GMMP is running a smaller live band layout.

## Route identity

GoneSmart observes Bluetooth outputs through Android audio-device APIs in both the GMMP host and the companion screen.

Route identity is intentionally stabilized primarily by normalized product name, with type/address only as fallback when no usable name exists. This avoids one physical device fragmenting into several profiles when Android exposes inconsistent addresses/types.

Known limitation: two different Bluetooth devices with exactly the same product name currently share one profile identity. If several Bluetooth outputs are simultaneously exposed as sinks, Android's output-device enumeration does not provide a perfect active-media-route oracle on every API level; GoneSmart therefore uses the most recently added matching Bluetooth output and otherwise the final Bluetooth sink in the current list. The common one-active-output case is device-accepted.

## UI contract

Audio Profiles is opened from **Companion → UI → Audio → Configure audio profiles** and uses a dedicated dark Material sub-screen with back navigation.

- Output selector: Standard plus remembered Bluetooth outputs.
- Equalizer and Effects are separate tabs.
- Standard is read-only in GoneSmart because native GMMP is the source of truth for Standard edits.
- Bluetooth sections expose an explicit Override switch.
- The active output uses a small positive-state indicator rather than redundant `Active` copy.
- Equalizer labels follow the current GMMP 2–10-band layout.
- Re-rendering the same route/tab preserves vertical scroll position; changing route/tab starts at that destination's top.
- If Standard is incomplete while Bluetooth is active, one concise Snackbar asks the user to disconnect Bluetooth once briefly so the baseline can be captured.

## Accepted GMMP 4.2.1 evidence

The completed device rounds prove:

- complete Standard capture and restoration across Bluetooth transitions;
- live native Effects mutation from GoneSmart;
- one stable Bluetooth profile identity for the tested output;
- the semantically resolved live Equalizer owner and shared apply target;
- real Equalizer application at 2, 6, 8 and 10 bands, including switching layouts without a crash;
- normalized 0.1 dB projected values on the GoneSmart apply path;
- direct import of 17 native ten-band GMMP presets;
- disabling the Equalizer override restores the Standard Equalizer state;
- no FATAL EXCEPTION or ANR in the final acceptance log.

The latest accepted debug build used commit `9f4729065f34780b484c9049f1902dd2f1d0914d`; subsequent audit-only cleanup must still pass the exact-head Build workflow before merge.

## Native semantic keys proved on GMMP 4.2.1

- Equalizer enabled: `player_eqEnabled`;
- active Equalizer preset: `dspSettings_activePreset`;
- Equalizer band count: `dspSettings_bandCount`;
- Balance: `dspSettings_balanceEnabled`, `dspSettings_balance`;
- Force mono: `player_forceMono`;
- Limiter: `dspSettings_limiterEnabled`, `dspSettings_attack`, `dspSettings_release`;
- Bass boost: `dspSettings_bassBoostEnabled`, `dspSettings_bassBoostStrength`;
- Tempo: `player_tempoEnabled`, `player_tempo`;
- Auto pitch correction: `player_autoPitchCorrection`;
- Virtualizer: `dspSettings_virtualizerEnabled`, `dspSettings_virtualizerStrength`;
- Amplifier: `dspSettings_loudnessEnabled`, `dspSettings_loudnessGain`.

These names are semantic preference IDs written by GMMP itself, unlike obfuscated class/method names.

## Compatibility contract for future GMMP versions

For a new GMMP version, do not assume the accepted 4.2.1 Equalizer layout table or internal owner still matches.

Revalidate, in one bounded pass:

1. semantic native preference store and managed keys;
2. Equalizer owner/setter uniqueness;
3. native DspManager band-count boundary;
4. actual 2–10 center-frequency layouts if GMMP still exposes them;
5. direct native preset storage/schema;
6. Standard → Bluetooth → Standard restoration;
7. exact-head host tests and device acceptance.

Unknown or ambiguous state-changing boundaries fail closed. Broad discovery is temporary compatibility work, not an accepted hot path.
