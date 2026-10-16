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

### Companion transport and host-local fallback

The hooked GMMP process cannot write GoneSmart's libxposed RemotePreferences: that object is read-only in the host process. Native observations therefore leave the host process rather than editing GoneSmart RemotePreferences directly.

A synchronous GoneSmart-owned `ContentProvider` bridge exists as the preferred acknowledged transport when Android allows GMMP to resolve the companion provider. Device testing on 2026-10-17 showed that this provider is not visible from the tested GMMP host (`Failed to find provider info`). GoneSmart now probes that route only once per GMMP process and then uses the fully explicit component receiver fallback, avoiding one PackageManager error per mirrored value.

Standard application does not depend on companion wake timing. No-Bluetooth observations are also mirrored into the GoneSmart-owned GMMP-private runtime store `gonesmart_audio_runtime_state`. Only observations attributed to the local route may populate `standard.*` there; Bluetooth observations can never seed Standard. The native application path resolves Standard from framework preferences first and this host-local mirror second.

The 2026-10-17 device round proved that this repaired Standard path is usable: after disconnecting Bluetooth, all `17` managed preference values plus the complete `11/11` Equalizer shape were captured as Standard, Bluetooth overrides could then be enabled, and Effects could be applied live.

The companion repository still persists received observations locally and flushes pending keys to writable framework RemotePreferences when the Xposed service is available. The host-local mirror is an apply-critical fallback, not a second user-editable profile database.

## Equalizer

The Equalizer profile contains:

- enabled state;
- preset state;
- PRE;
- 32 Hz, 64 Hz, 128 Hz, 256 Hz, 512 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz and 16 kHz.

`Custom` is a state, not a selectable preset. Manually changing PRE or any band in a Bluetooth profile atomically stores the changed shape value and changes that route's preset state to `Custom`.

### Native Equalizer boundary

For GMMP 4.2.1, natural runtime calls prove one PRE setter, one indexed band setter and one enabled setter on the live Equalizer state owner. The observed fast-path names are `r61.r(double)`, `r61.z(int,double)` and `r61.d(boolean)`, but those obfuscated names are evidence only. Runtime resolution validates the structural owner before they are reused, and an unknown/ambiguous mapping fails closed.

An earlier 2026-10-17 device log isolated an architectural bug in the first application implementation: the passive observation module saw the real `r61` calls, while a second set of hooks installed by the application module never captured a live owner. Every requested Equalizer profile therefore remained `eq=shape-deferred` and no `EQ SHAPE APPLIED` event occurred.

The single-hook-owner architecture fixed that duplicate-hook problem but the following device round exposed a second, narrower target-readiness bug. The observer successfully registered the shared apply bridge and naturally observed PRE plus all ten band setters, yet `EQ APPLY TARGET READY` never appeared and every apply still remained `shape-deferred`. The natural GMMP Equalizer initialization was running on a worker thread without a `Looper`; the first bridge implementation therefore stored the live owner but never produced the required `Handler`.

The runtime bridge now treats the proven setter owner and its execution dispatcher separately. A natural call on a thread with a `Looper` uses that Looper. If the first real Equalizer owner is observed on a non-Looper worker, the bridge retains that exact native owner and uses GMMP's main Looper only as the serialized dispatch target for later requested profile application. A later natural call on another real Looper upgrades the dispatcher. No second Equalizer hook family is installed.

A requested profile applies PRE, all ten bands and enabled state through those already-proved live setter roles. GoneSmart does not implement a parallel DSP. The route profile itself stores a complete explicit shape; choosing a named preset copies that exact curve into the route values first, so runtime application never needs a second preset lookup to reconstruct the requested curve.

### Presets

Writing GMMP's `dspSettings_activePreset` preference alone is **not** the native preset-load action. Device testing changed the stored name without loading the corresponding PRE/band curve, so GoneSmart must not pretend that a name-only write selected a preset.

The 2026-10-17 logs also proved that an active preset name observed at process startup is not safe evidence for the next Equalizer setter burst: GMMP started with a stored preset label and then initialized PRE plus all ten bands independently. The old learner could therefore associate the wrong shape with that name. Startup preset labels are metadata only and never arm preset learning.

The normal GoneSmart workflow must **not** require the user to select every GMMP preset once. Built-in preset discovery is read-only and direct from GMMP-owned preset data. GMMP supports band-count-specific preset families, while the current live player instance reports ten Equalizer bands. The catalog therefore searches GMMP's preset roots recursively, recognizes numeric/band-labelled child directories, prefers the 10-band family, ignores other explicitly scoped band counts, and still supports flat/unscoped storage for compatibility.

A named preset is usable only when GoneSmart has a complete native PRE + ten-band reference shape. Direct file import is the primary setup path. The strict parser accepts only complete curves: structured PRE/frequency or indexed-band layouts, compact PRE + ten-band lists, an optional leading `10` band-count marker, supported CSV/text layouts, or the already-supported exact raw 11-value binary layouts. Missing, ambiguous or out-of-range curves fail closed. Natural GMMP preset actions remain a fallback/validation source rather than a required one-by-one setup step.

The latest pre-fix device log reported `EQ PRESET CATALOG | imported=0 | candidates=0 | rejected=0`. Combined with the observed GMMP preset directory containing band-count subdirectories, that result identified a discovery bug rather than proof that no presets existed: the old catalog only inspected files directly under each candidate root. The recursive band-family reader is intended to make those real nested 10-band files visible on startup without touching the preset UI.

Learned/imported preset storage is versioned. Schema 2 invalidates curves produced by the unsafe startup-label association. Existing route PRE/band values are preserved, but old named route labels are migrated to `Custom` until a complete current-schema curve is available. Standard's native preset label is retained as observed metadata, but it is not promoted into the selectable catalog merely because the label exists.

The Audio Profiles preset chooser lists only presets for which a complete current-schema native curve is available. An empty list now means the GMMP preset storage was not readable or no complete 10-band curves were found; it no longer instructs the user to open every preset manually.

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

The application path writes complete groups through the same native GMMP SharedPreferences keys and native units observed from ordinary GMMP UI changes. Reverse conversions are explicit and host-tested: Balance and Tempo use native fractions, Bass boost and Virtualizer use GMMP's 0..1000 scale, and Amplifier uses hundredths of a dB. Preference commits are read back immediately.

The 2026-10-17 device round moved Effects past the earlier Standard gate. Live changes were confirmed from GoneSmart, with the log directly showing applied native writes for Balance, Limiter, Bass boost and Tempo while the maintainer confirmed the managed Effects were changing playback correctly. Effects preference mutation is therefore device-proven on the current GMMP 4.2.1 feature branch; route-transition restoration still remains part of final feature acceptance.

## Audio Profiles UI

The screen uses a Material toolbar with standard back navigation and an info action; long explanatory copy stays behind the info dialog.

The output selector shows the device/profile name without a redundant `Bluetooth` prefix. The currently active output is indicated by a small green dot and bold label rather than by the literal word `Active`. The same treatment is used in the chooser.

Cards with a leading `Enabled` switch and dependent controls use two gates. The Bluetooth `Override` must be enabled first, and the card's own `Enabled` switch must also be on before dependent sliders/fields become interactive. When the effect itself is off, its dependent controls stay visible but are disabled and visually muted. This applies to Equalizer, Balance, Limiter, Bass boost, Tempo, Virtualizer and Amplifier. Standalone switches such as Force mono and Auto pitch correction have no dependent controls and remain directly usable whenever their override is active.

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

The 2026-10-16 crash evidence proved that GoneSmart RemotePreferences are read-only from the hooked GMMP process. The next device rounds proved complete local Standard source capture and then the host-local fallback that lets Bluetooth Effects apply even when companion/framework propagation is not yet visible in the GMMP process.

The 2026-10-17 rounds proved live Effects preference mutation, the companion-provider fallback behavior, the validated 10-band live Equalizer owner/setters, and the fact that the shared apply target still remained unavailable when its real owner was first observed on a non-Looper worker. The latest log also reported zero preset-file candidates while the physical GMMP preset tree was known to be nested by band count, motivating the recursive 10-band catalog path rather than a manual-preset workflow.

## Native-first application contract

1. Require a complete real Standard baseline before any Bluetooth mutation.
2. Resolve GMMP's native audio preference store semantically from known audio keys.
3. Reconstruct the destination from Standard plus only that Bluetooth output's enabled complete-group overrides.
4. Apply Effects only through observed GMMP native preference boundaries and verify persistence.
5. Resolve and hook the Equalizer owner once in the passive observer; share that exact live owner with the application path and use a bounded dispatcher fallback when the natural owner was observed on a non-Looper worker.
6. Apply Equalizer PRE/bands/enabled only through the semantically resolved live GMMP setter roles.
7. Discover complete built-in preset curves directly from GMMP-owned storage for the active band count; a startup label or name-only preference write is never curve proof, and manual one-by-one preset selection is not the normal setup path.
8. Keep temporary Bluetooth GMMP edits out of both Standard and saved Bluetooth profiles.
9. Use framework persistence plus the local-route-only host mirror so transport timing cannot silently block or corrupt Standard.
10. Fail closed on missing values, ambiguous native owners or unproved mutations.
11. Keep runtime work event-driven; no polling or recurring broad class/preference scans.

## Acceptance requirements

Before Audio Profiles can be considered complete:

1. Standard bootstrap must remain correct after disconnect/reconnect and after process restarts that begin on Bluetooth. **Current device evidence: working.**
2. The same physical Bluetooth output must keep one route identity in the normal one-device case. **Current device evidence: working for the tested SOUNDPEATS route.**
3. Bluetooth native edits must remain temporary; no Bluetooth observation may redefine Standard.
4. Equalizer inheritance must come from Standard, not the last Bluetooth live state.
5. Equalizer PRE/bands/enabled application through the shared live target must be device-verified to change the real GMMP DSP and restore correctly on output switches. **Pending next device round after non-Looper target fix.**
6. Effects cards must affect the same live GMMP behavior as their native UI actions. **Current 4.2.1 device round: live application confirmed; route restoration still to verify.**
7. Direct GMMP preset discovery must enumerate and import the complete 10-band preset family without requiring every preset to be opened manually; malformed/other-band curves remain fail-closed. **Pending next device round.**
8. `Custom` must remain a GoneSmart profile state, not an invented GMMP preset mutation.
9. Standard restoration after interrupted Bluetooth state must be device-verified.
10. Final accepted runtime must retire development-only probes and keep only bounded resolver/postcondition diagnostics.
11. The normal Build workflow must pass on the exact feature-branch head before merge.
