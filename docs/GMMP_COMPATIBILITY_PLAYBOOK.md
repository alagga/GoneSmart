# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in the dated GMMP evidence files and feature completion docs.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted current target** | Smart DJ, Playlist/Smart-Playlist features, Flip, Track Auto-DJ and compatibility/performance cleanup are accepted. Bluetooth Audio Profiles are also device-accepted on their feature branch for 0.5.0 integration. |
| Any other version | Untested | Show amber Compatibility status and run the bounded workflow before claiming support. |

Source of truth: `GmmpCompatibilityPolicy.TESTED_VERSION = "4.2.1"`.

## 2. Resolve semantics, not names

A durable resolver combines, as applicable:

1. owner/runtime type reached from a known live object;
2. signature/hierarchy;
3. generated Room adapter SQL ownership;
4. independent Cursor/native-state correlation;
5. uniqueness among candidates;
6. postcondition after the real native action;
7. rollback/fail-closed behavior for mutation.

Tested obfuscated names may be fast paths. They are never proof by themselves. If ambiguity remains, stop rather than invoking candidates experimentally.

This rule applies to audio internals too. An Equalizer class or method observed as `r61`, `r`, `z`, `d`, etc. on one build may be used only as a fast lookup after its structural role is revalidated. Audio observation/application code uses the same tested-fast-path → structural/semantic fallback → uniqueness → verification lifecycle as queue, playlist and other GMMP-facing features.

## 3. Workflow for a new GMMP version

### Detect

Compare the installed GMMP version with the tested version. Missing/unknown versions are never green Compatibility.

### Run one bounded read-only self-test

Cover the feature families that depend on internals in one pass. Safe evidence includes class hierarchy/signatures, runtime types, generated Room SQL, RecyclerView adapter/holder ownership, native call-chain existence without destructive invocation, Cursor/state correlation, and read-only audio-state/preset-storage structure.

### Graduate each boundary

- encode semantic resolution in a shared resolver/policy;
- add JVM tests for uniqueness and false positives;
- validate the real native action once on device;
- retain a bounded independent postcondition;
- retire the broad probe.

For Equalizer observation/application specifically, discovery may hook natural GMMP calls only after a unique structural owner is identified; it must not invoke an unknown setter to learn its meaning. Once PRE/band/enabled roles are proved from natural calls, a requested profile may reuse those same roles on the captured live owner. Read-only state helpers may be used only after their outputs correlate with natural setter calls. Native preset files may be parsed read-only only when their complete shape is provable.

### Retire runtime cost

Accepted versions must not continuously perform discovery-time reflection, SQL/Cursor readbacks, model reconstruction, delayed probes or full view-tree scans. Logging less while keeping the same expensive work is not cleanup.

## 4. GMMP 4.2.1 accepted boundary map

### Auto-DJ refill

- Runtime owner observed as `qr`.
- Native refill boundary observed as `qr.z(int)`.
- Semantic role: request N Auto-DJ tracks.
- It is explicitly excluded from current-position writer discovery.

GoneSmart owns recommendation selection; GMMP owns insertion/playback. Native fallback remains valid when no suitable local smart candidate is available.

### Queue read / DAO / entity

- database accessor: `GMDatabase_Impl.F(): sx3`;
- runtime DAO: `vx3`;
- queue entity: `cy3`;
- synchronous reader: `sx3.H1(): ArrayList`;
- generated adapters naming `queue_table` establish ownership;
- verified update writer: `O0(List)`;
- delete bridge must receive a real typed `cy3[]` even when reflection exposes `Object[]`.

Independent Cursor/native state remains the oracle for queue IDs, track IDs, positions, shuffle positions and mutation postconditions.

### Current-position writer / Queue Flip

The writer is learned passively from **natural GMMP playback**, correlated with an independent current-position signal and promoted only after exact agreement. No candidate is invoked merely to test it.

Observed 4.2.1 evidence is `dx3.c2(int)`. Treat the name as evidence only.

Queue Flip correlates native entities 1:1, reverses through proven native writers, updates Current only through the proved writer when necessary, verifies the independent postcondition and retains rollback data until success.

### Track Auto-DJ

The accepted architecture dispatches GMMP's real native Play path, settles on the clicked/live target through bounded identity guards, isolates the exact native Queue entity through proven writers, verifies that the seed is Current, requests only the Initial-Size deficit through the native Auto-DJ refill boundary, and verifies the final seed/size postcondition.

Queue-root continuity must never guess GMMP's hidden append allocator. GoneSmart reacts to verified native Current transitions, reads the actual remaining queue, computes a real configured-upcoming deficit and invokes the already-proven refill boundary only when required.

The playlist/Track-Auto-DJ compatibility branch contains the newest 4.2.1 reacceptance evidence; keep its accepted rules when integrating 0.5.0.

### Playlist / Smart-Playlist surfaces

Accepted on 4.2.1:

- Playlist folders;
- Smart-Playlist folders;
- ordinary and Smart multi-selection;
- Playlist Link;
- Play flipped;
- Queue Flip;
- navigation/player sparkle badges and Status/Compatibility UI.

Resolvers prefer semantic adapter/model/writer ownership. Historical class names remain fast paths only where useful and safe.

### Audio Profiles / Equalizer and Effects

Bluetooth Audio Profiles are device-accepted on GMMP 4.2.1 on `feature/bluetooth-audio-profiles`. The authoritative feature contract and audit are `docs/AUDIO_PROFILES.md` and `docs/AUDIO_PROFILES_COMPLETION.md`.

Accepted boundaries:

- Stable semantic preference IDs come from normal GMMP UI writes for Equalizer enabled/active preset/band count and every managed Effects control.
- The native preference store is resolved by those semantic keys. A historical XML filename is never the identity; ambiguity disables preference mutation.
- Standard is the no-Bluetooth GMMP baseline. Only local-route observations may redefine it; Bluetooth observations cannot contaminate it.
- A delayed bounded local-route snapshot republishes unchanged Standard values. A GMMP-private runtime mirror preserves the same local-only Standard data when companion/framework timing is unavailable.
- Effects are reconstructed as complete native cards, converted to GMMP's real native units, committed to the native preference store and read back.
- The accepted Equalizer state owner is structurally resolved. `r61` and its observed PRE/band/enabled names are 4.2.1 fast-path evidence only.
- There is one GoneSmart hook owner for Equalizer PRE/band/enabled. `GmmpEqualizerRuntimeBridge` shares the naturally observed live owner with the apply path.
- A first natural owner call may happen on a non-Looper worker. The bridge retains that exact owner and uses an observed Looper when available, otherwise GMMP's main Looper as the serialized dispatch target. It never installs a duplicate setter hook merely to recover a Handler.
- GMMP 4.2.1 supports live 2–10-band Equalizer layouts. The band count is taken from the semantically resolved `dspSettings_bandCount` and/or the passively proved DspManager creation boundary.
- **Never infer the semantic band count from the internal Equalizer gain-array length.** Device evidence proved six real bands can coexist with a seven-element internal array.
- GoneSmart stores a canonical PRE + ten-band curve. Live N-band layouts are projected by the device-verified center-frequency table, with exact anchors preserved, log-frequency interpolation between centers, edge clamping and 0.1 dB normalization.
- Only the active N band setters are invoked. Changing layout does not mutate canonical storage; editing a live N-band curve expands back to canonical storage and marks the route `Custom`.
- Direct internal array readback fails closed when its shape does not exactly match the semantic live layout. Complete natural setter observations remain a safe read source.
- `dspSettings_activePreset` is metadata, not proof that GMMP loaded a curve.
- Native preset discovery is direct, bounded and read-only. The accepted device imports all 17 complete ten-band preset files without requiring manual preset-by-preset selection.
- Natural preset learning remains fallback/validation only and cannot replace the exact ten-band catalog while GMMP uses a smaller live layout.
- The tested host cannot resolve the GoneSmart provider authority, so the provider is probed once and then an explicit component receiver transports bounded observations to the companion. Field normalization remains fail-closed.
- The normal one-active-Bluetooth-output route case is accepted. Same-name device identity collision and multiple-sink ambiguity are documented non-blocking limitations.

Final device evidence includes successful Equalizer application at 2, 6, 8 and 10 bands, direct 17-preset import, Effects mutation, Standard restoration and no FATAL/ANR in the acceptance log.

## 5. Accepted-version performance contract

- Offscreen ViewPager pages do not perform row/model/style work.
- Native adapter/scroll/layout events are preferred over periodic polling.
- Visible-row synchronization is coalesced.
- Persistent UI helpers cache weak references to proven native anchors.
- Full view-tree discovery is bounded recovery only.
- Drawable/glyph analysis and class-level reflection are cached.
- Queue-writer discovery becomes pass-through after proof.
- Play-flipped uses one delayed independent postcondition read plus at most one legacy fallback, not a retry loop.
- Track Auto-DJ pre-action Queue/Room barriers never block GMMP's main thread.
- Audio's structural Dex fallback is startup-only and runs only when the validated tested fast path is unavailable.
- Audio profile writes are event-driven by output/profile/layout changes and are debounced; no periodic DSP polling is permitted.
- Standard bootstrap and native preset readback are bounded route/action-triggered snapshots, not polling loops.
- Native preset storage discovery is bounded and startup-only.
- The unavailable audio companion provider is probed at most once per GMMP process before using the explicit receiver fallback.
- Equalizer observation/application shares one native setter hook family.

Do not hide native GMMP logging to make logs look clean. Remove GoneSmart-caused unnecessary work instead.

## 6. Notification / host-UI discipline

- Prefer complete GMMP localized phrases when available.
- GoneSmart-owned notices may use concise English fallback text.
- Symbol-only status is not an acceptable entire popup fallback.
- Suppress native Toasts only in a bounded exact scope when they are known duplicates or misleading transitional results.
- First-time Audio Profiles setup may use one concise Snackbar explaining that Bluetooth must be disconnected once for Standard capture; long explanations belong behind the toolbar info action.

## 7. Probe retirement ledger

4.2.1's broad class/recycler/Room probes are retired or gated. Reusable diagnostics remain for unknown versions, but accepted runtime keeps resolver/result/failure markers rather than development-time discovery loops.

Audio Profiles has completed its device acceptance. The standalone duplicate Equalizer layout module and unused audio helper abstractions were removed in the completion audit. Release builds do not execute debug-only per-call audio call-site logging. Bounded preset discovery, semantic resolvers and postcondition/readback markers remain because they are part of the accepted architecture.

## 8. Acceptance checklist for a future version

- [ ] bundled read-only self-test completed;
- [ ] every enabled mutation has a proven native action/writer;
- [ ] independent read/postcondition mapping verified;
- [ ] JVM tests cover ambiguity and failure;
- [ ] changed semantic boundaries device-accepted;
- [ ] failure paths fail closed;
- [ ] broad diagnostics and runtime discovery cost retired;
- [ ] Compatibility source of truth updated;
- [ ] `AGENTS.md`, completion docs, README and this playbook agree;
- [ ] exact-head CI fully green.

For audio-capable versions also revalidate the native audio preference store, Equalizer owner/setters, band-count evidence, actual center-frequency layouts, direct preset storage, Standard/Bluetooth restoration and the single-hook-owner invariant. Do not carry the 4.2.1 center table forward by assumption.

## 9. Release/integration state

GMMP 4.2.1 remains the accepted compatibility target for the current public 0.4.x line.

For 0.5.0 development, Bluetooth Audio Profiles are feature-complete and device-accepted on their branch. The provider work and Playlist Link/Track Auto-DJ compatibility work remain separate integration inputs. Final README, release notes, forum copy, version bump, merged-tree CI and pre-release creation belong to the combined integration step, not to this feature branch in isolation.
