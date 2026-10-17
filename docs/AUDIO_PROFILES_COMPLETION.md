# Bluetooth Audio Profiles completion audit

**Feature branch:** `feature/bluetooth-audio-profiles`  
**Accepted GMMP target:** `4.2.1`  
**Device acceptance date:** 2026-10-17  
**Target integration release:** `0.5.0` pre-release after the feature branches are combined

This file records the feature-specific completion state required by `AGENTS.md`. It is intentionally separate from aggregate release notes because `feature/auto-dj-providers` and `fix/playlist-link-gmmp-4.2.1` still need to be integrated before the 0.5.0 pre-release is assembled.

## Acceptance result

Bluetooth Audio Profiles is **device-accepted on GMMP 4.2.1**.

The maintainer confirmed the final feature behavior after testing live profile edits, Standard inheritance/restoration, native presets and Equalizer band-count changes. The last accepted debug build before audit-only cleanup was commit:

`9f4729065f34780b484c9049f1902dd2f1d0914d`

Its normal Build workflow (#1388 / run `39047687150`) passed unit tests, debug APK, unsigned release smoke and debug artifact upload. The audited code/documentation state then passed the same normal Build workflow on commit `1952c4b91a7d7bc806c6892c57ead311468175f9` in Build #1395 / run `39060652936` before this closure-only documentation commit. The closure commit itself is required to pass exact-head CI before merge, preserving the repository's exact-head rule.

## Final device evidence

The final acceptance log shows:

- no `FATAL EXCEPTION`;
- no `AndroidRuntime` crash;
- no ANR;
- GMMP created a six-band Equalizer and GoneSmart resolved the same six-band runtime layout;
- GoneSmart applied exactly six normalized active-band values;
- later switches to 2, 8 and 10 bands also completed with matching `EQ BAND LAYOUT` and `EQ SHAPE APPLIED` events;
- the direct native preset catalog imported all 17 ten-band GMMP presets;
- disabling the Bluetooth Equalizer override restored Standard and applied the Standard enabled state/shape.

One `Skipped 59 frames` message occurs during the broad GMMP startup/library initialization window, alongside unrelated GMMP index/database work. The accepted audio path is event-driven and there is no evidence of periodic audio polling or a repeated audio-triggered main-thread stall.

## Code audit

### Native-first mutation

- Effects use the same semantic GMMP preference keys and native units observed from GMMP's own UI.
- The native preference store is selected by the audio keys it contains, not by a hard-coded XML filename.
- Preference writes are read back and ambiguous stores fail closed.
- Equalizer PRE/band/enabled apply through the already-proved live GMMP setter roles.
- The passive observer is the single hook owner for those Equalizer setters; the apply module consumes `GmmpEqualizerRuntimeBridge` instead of installing a duplicate setter hook family.
- The DspManager layout boundary is observed passively. Unknown candidate methods are never invoked merely to discover semantics.
- A changed DspManager layout invalidates the last Equalizer fingerprint and schedules one bounded reapply after the native rebuild.

### Standard / Bluetooth isolation

- Standard is the no-Bluetooth GMMP baseline, not another route override.
- Only local-route observations may redefine `standard.*`.
- Bluetooth observations cannot seed Standard.
- A complete Standard baseline is required before Bluetooth mutation.
- Standard can be restored from framework preferences or the local-route-only GMMP-private runtime mirror after an interrupted Bluetooth session.
- `AudioProfileValueResolver` is retained because the live apply path uses it to normalize the framework Standard value first and then fall back to the host-local Standard mirror when the framework value is missing/invalid. Its JVM test covers that fallback and unknown-field fail-closed behavior.
- Enabling an override seeds a complete group rather than creating a partial hidden override.

### Equalizer band layouts

- Canonical persistence remains PRE + ten bands.
- Live GMMP layouts from 2 through 10 bands are supported.
- The exact GMMP 4.2.1 center-frequency table is device-verified and documented in `docs/AUDIO_PROFILES.md`.
- Projection is frequency-aware in log-frequency space, keeps exact shared anchors and clamps only outside the source range.
- Every projected gain is normalized to the real 0.1 dB field/Material-slider step grid.
- The native apply path invokes exactly the current N band setters.
- Changing the live band count alone does not rewrite the canonical route curve.
- Editing an N-band curve expands it back to canonical ten-point storage and marks the route preset `Custom`.
- Live band count is never inferred from GMMP's internal gain-array length. Device evidence proved that the array may contain an extra element for smaller layouts.

### Presets

- Startup/stored preset names are metadata only and never prove a curve.
- Direct read-only GMMP preset discovery is the primary source.
- The nested ten-band preset family is discovered without asking the user to select every preset.
- The accepted native parser covers the real name/PRE/count/N-values format plus the already-tested compatible formats.
- Only complete current-schema PRE + ten-band shapes become selectable GoneSmart presets.
- Natural preset learning is a fallback/validation path and is disabled for smaller live layouts so an interpolated shape cannot replace the exact ten-band catalog.

### UI and persistence

- Standard is read-only in GoneSmart; GMMP remains the Standard editor/source of truth.
- Bluetooth groups use explicit Override state plus native Enabled gating for dependent controls.
- Equalizer UI labels follow the live 2–10-band layout.
- The activity reads visible values through canonical→N projection and writes edited N-band values through N→canonical projection; relabelled UI field objects are not used as raw canonical persistence indices.
- Re-rendering the same output/tab preserves scroll position.
- Repository writes are local-first and pending keys are flushed to writable framework preferences when available.

### Runtime/performance

- No polling loop was introduced for audio profiles.
- Native preset discovery is bounded and startup-only.
- The expensive structural Equalizer fallback scan runs only when the validated 4.2.1 fast path is unavailable.
- Audio profile writes are route/profile/band-layout event driven and debounced.
- The unavailable companion provider is probed once per GMMP process, then the explicit receiver fallback is used without repeating the failed PackageManager lookup for every mirrored value.
- Per-call verbose audio diagnostics are debug-build-only; accepted release behavior retains concise resolver/result/failure markers.

### Retired development code

The final audit removed code left behind by superseded approaches:

- `GoneSmartEqualizerBandLayoutModule` — duplicate standalone layout observer; the accepted layout observation lives inside the already-loaded apply module.
- `AudioProfileMutationScope` — unused marker from an abandoned preference-write discrimination path.

The audit initially also removed `AudioProfileValueResolver`, but the exact-head compiler check correctly exposed its live use in Standard framework→runtime fallback resolution. The helper and its test were restored immediately; this is precisely the kind of hidden dependency the exact-head CI gate is intended to catch.

The Xposed initializer list remains intentionally limited to:

- `GoneSmartModule`;
- `GoneSmartAudioProfileApplyModule`;
- `GoneSmartAudioModule`.

## Failure behavior

The feature fails closed when any state-changing boundary is not proved:

- no complete Standard → no Bluetooth mutation;
- ambiguous native preference store → no Effects preference mutation;
- unresolved/ambiguous Equalizer owner → no Equalizer setter application;
- unsupported band count → no projection/application;
- incomplete preset shape → preset is not selectable;
- smaller-layout direct array readback with incompatible internal shape → no guessed readback; complete natural setter observations are used when available.

Unavailable auxiliary observation does not disable unrelated GoneSmart features.

## Known non-blocking limitations

### Same-name Bluetooth devices

Route identity is stabilized primarily by normalized product name. Two distinct devices exposing exactly the same product name currently share one GoneSmart audio-profile identity.

### Multiple simultaneous Bluetooth sinks

Android output-device enumeration is not a perfect active-media-route oracle on every supported API. GoneSmart prefers the most recently added matching Bluetooth sink and otherwise the final Bluetooth sink in the current output list. The normal one-active-Bluetooth-output case is accepted.

### Receiver fallback hardening

The tested GMMP host cannot resolve the exported companion provider, so an explicit cross-package receiver is required as fallback. The receiver surface is limited to known audio observation actions and all stored values are normalized/validated, but pre-Android-14 BroadcastReceiver APIs do not expose a universally usable caller identity for this path. A caller-authenticated bound IPC transport remains a possible future hardening improvement. This is not used as justification for adding speculative native hooks or weakening field validation.

### GMMP-version scope

The 2–10 center-frequency table is explicitly verified for GMMP 4.2.1. A future GMMP version must revalidate the layout instead of inheriting the table by assumption.

## Test coverage audited

Host tests cover:

- audio field/group contract and normalization;
- override/control dependency policy;
- Standard/Bluetooth runtime policy;
- framework-first / runtime-fallback Standard value resolution;
- semantic native observation and native mutation conversion;
- Equalizer owner resolution and ambiguity rejection;
- Equalizer DspManager layout resolution;
- semantic band-count preference resolution;
- every GMMP 4.2.1 band count from 2 through 10;
- exact frequency layouts and labels;
- canonical/live projection in both directions;
- 0.1 dB slider-step normalization;
- preset catalog family selection;
- real GMMP preset-file parsing and malformed-input rejection.

The accepted device rounds provide the runtime oracle for the Android/Xposed-only path that cannot be meaningfully reproduced in pure JVM tests.

## Merge preparation for 0.5.0

All three current development branches started from the same current `main` base (`b52b5dc3d534c6befc7a346440ae75278db2fa52` at audit time), so the final integration can be done as a normal three-way merge rather than by transplanting isolated commits.

Known shared integration surfaces:

- `AGENTS.md` — changed by all active feature/fix work; preserve the union of standing rules rather than choosing one branch wholesale.
- `MainActivity.kt` — Audio Profiles adds the companion UI entry point; the provider branch adds provider/settings UI. Preserve both.
- `app/src/main/resources/META-INF/xposed/java_init.list` — the Audio Profiles branch intentionally adds the two audio modules. A conflict must keep `GoneSmartModule`, `GoneSmartAudioProfileApplyModule` and `GoneSmartAudioModule`.
- `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` — both playlist/Track-Auto-DJ compatibility work and audio acceptance contribute independent accepted boundaries; merge semantically.
- `README.md`, `RELEASE_NOTES.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md` and forum copy — aggregate release documentation must be reconciled only after all feature branches are present so 0.5.0 describes the combined result once.

The Audio Profiles branch deliberately does **not** bump the app version, tag, create a release or rewrite final 0.5.0 release/forum copy. Those are integration/release actions after all branches are combined and the merged tree passes CI.

## Final branch checklist

Before declaring this feature branch merge-ready:

- [x] Maintainer device acceptance on GMMP 4.2.1.
- [x] Standard → Bluetooth → Standard behavior accepted.
- [x] Effects native application accepted.
- [x] Equalizer native application accepted.
- [x] 2–10 band-count adaptation accepted.
- [x] Native preset catalog accepted.
- [x] Crash regression fixed and re-tested.
- [x] Dead development helpers removed without removing live fallback logic.
- [x] Dedicated feature contract updated to accepted behavior.
- [x] Merge-conflict surfaces with the other two 0.5.0 branches identified.
- [x] Normal Build green after audit cleanup; final closure commit must also pass exact-head CI before merge.

After the closure commit's exact-head Build is green, this branch is ready for the combined 0.5.0 integration step.
