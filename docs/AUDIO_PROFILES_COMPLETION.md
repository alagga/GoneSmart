# Bluetooth Audio Profiles completion audit

**Feature branch:** `feature/bluetooth-audio-profiles`
**Accepted GMMP target:** `4.2.1`
**Device acceptance date:** 2026-10-17
**Integrated release candidate:** `0.5.0-rc.1`

This file records the feature-specific completion state required by `AGENTS.md`. The accepted feature is now combined with Auto-DJ Providers and the GMMP 4.2.1 compatibility work in the `0.5.0-rc.1` release candidate.

## Acceptance result

Bluetooth Audio Profiles is **device-accepted on GMMP 4.2.1**.

The maintainer confirmed the final feature behavior after testing live profile edits, Standard inheritance/restoration, native presets and Equalizer band-count changes. The last accepted debug build before audit-only cleanup was commit:

`9f4729065f34780b484c9049f1902dd2f1d0914d`

Its normal Build workflow (#1388 / run `39058119200`) passed unit tests, debug APK, unsigned release smoke and debug artifact upload. The audited code/documentation state then passed the same normal Build workflow on commit `1952c4b91a7d7bc806c6892c57ead311468175f9` in Build #1395 / run `39060652936` before this closure-only documentation commit. The closure commit itself is required to pass exact-head CI before merge, preserving the repository's exact-head rule.

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

A follow-up 0.5.0 RC fix makes the exposed Android Bluetooth address the first-choice route identity, so two same-name devices can have separate profiles; a hidden address retains the name-based fallback. The companion route selection and repository persistence retain the observer's ID rather than regenerating a name hash. The maintainer device-tested and confirmed that the two Esinkin adapters are now recognized separately. Previously saved name-based entries remain to protect user overrides. A subsequent RC fix adds explicit deletion for **disconnected** saved Bluetooth routes, including route metadata and overrides, with Standard and current active route protected. Deletion is synchronized to framework preferences through atomic local tombstones and has JVM regression coverage. **Device acceptance confirmed by the maintainer on 2026-10-18:** the separate Esinkin identities and the saved-profile deletion/duplicate cleanup work correctly on the device. Both follow-up fixes are accepted; no further device fixes are currently known for this feature.

### 2026-10-18 follow-up regression acceptance

- **Status:** PASS — maintainer tested separate same-name Esinkin Bluetooth receivers and removal of legacy/duplicate profiles; confirmed both fixes work.
- **Accepted feature branch:** `fix/audio-profiles-device-identity` (implementation/documentation head `6aa7a92d24be49aeb4759e2df0d901db160711db` before this acceptance-only update).
- **Exact implementation-head CI:** [Build #1426](https://github.com/alagga/GoneSmart/actions/runs/39139257967) succeeded: generated forum copy, JVM unit tests, Debug APK, unsigned Release APK smoke test and Debug APK upload.
- **Release handling:** Device acceptance does not itself merge the fix branch into `main` or publish a new pre-release; carry it into the next planned `0.5.0-rc.2` integration.

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

## 0.5.0 integration closeout

Bluetooth Audio Profiles is integrated into the same `0.5.0-rc.1` tree as Auto-DJ Providers and the accepted GMMP 4.2.1 Playlist Link / Track Auto-DJ work. Shared integration surfaces were reconciled semantically rather than choosing one branch wholesale: the Xposed initializer retains all three required module entries, `MainActivity` retains both provider/settings and Audio Profiles UI, and the compatibility/audit rules preserve the union of accepted boundaries.

### Localization and support audit

Audio Profiles adds no new GMMP-facing wording. Its configuration UI is part of the intentionally English-only GoneSmart companion app; native mutation changes GMMP audio state without injecting an Audio Profiles menu into the player. No additional GMMP locale table is therefore required for this feature.

Normal release diagnostics remain event-driven and bounded. Detailed support Logcat uses `GoneSmartAudioProfiles` and `GoneSmartAudioApply`; the companion Logs/Help guidance includes those tags without exposing the debug-only `GoneSmartAudioProbe` path as a normal support filter.

### Combined release gate

Feature-level device acceptance is complete. Promotion of this integrated candidate still requires the repository-wide exact-head release gate: generated forum copy current, normal Build green on the exact RC head, temporary integration helpers removed, merged `main` green, then prerelease publication and maintainer testing before stable `0.5.0`.
