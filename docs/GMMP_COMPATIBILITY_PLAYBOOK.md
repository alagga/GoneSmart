# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted / 0.4.0 target** | Smart DJ, Playlist/Smart-Playlist features, Flip, Track Auto-DJ and compatibility/performance cleanup device-accepted. Audio Profiles remain feature-scoped development until their full route/EQ path is device-accepted. |
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

This rule applies to audio internals too. An Equalizer class or method observed as `r61`, `r`, `z`, `d`, etc. on one build may be used only as a fast lookup after its structural role is revalidated. Audio observation/application code must have the same tested-fast-path → structural/semantic fallback → uniqueness → verification lifecycle as queue, playlist and other GMMP-facing features.

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

For Equalizer observation/application specifically, discovery may hook natural GMMP calls only after a unique structural owner is identified; it must not invoke an unknown setter to learn its meaning. Once the PRE/band/enabled setter roles have been proved from natural GMMP calls, an actual requested profile may reuse those same roles on the captured live owner. Read-only state helpers may be used only after their outputs have been correlated with those natural setter calls. Native preset files may be parsed read-only only when their complete shape is provable.

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

Queue Flip then correlates native entities 1:1, reverses through proven native writers, updates Current only through the proved writer when necessary, verifies the independent postcondition and retains rollback data until success.

### Track Auto-DJ

Accepted from ordinary playlists and large Smart Playlists:

1. dispatch GMMP's exact native Play action;
2. use the bounded generic-track completion guard while native list playback settles;
3. preserve the clicked/provisional target if it remains uniquely identifiable through a large rebuild;
4. if the first provisional CURRENT disappears completely inside the guard, retarget to the independently observed live CURRENT and restart settling;
5. outside the guard, fail closed on competing/manual playback;
6. isolate the exact native Queue entity through proven writers;
7. verify the seed is sole Current;
8. request exactly `Initial Size - seed size` through native refill;
9. verify exact Initial Size and preserved seed.

#### 4.2.1 queue-root continuity (r42–r43)

Seed isolation changes the logical queue root. Device evidence showed that rebasing Queue DAO row positions does not prove GMMP's hidden append/end allocator was reset. GoneSmart therefore does **not** guess that allocator.

For a Track-Auto-DJ-owned normalized queue only:

- use the already verified natural CURRENT transition as the event trigger;
- read the actual remaining queue once;
- compare it with GMMP's configured upcoming count;
- invoke the existing native `qr.z(deficit)` refill boundary only for a real deficit;
- deduplicate by verified queue position and allow only one managed refill in flight;
- disarm on unrelated native list playback.

This continuation path is device-accepted for 0.4.0.

### Audio profiles / Equalizer and Effects

The Audio Profiles feature remains under development on 4.2.1, but the 2026-10-17 device round materially narrowed the remaining work.

Current proved and implemented boundaries:

- Stable semantic preference IDs are observed from ordinary GMMP UI writes for Equalizer enabled/active preset and every managed Effects control documented in `docs/AUDIO_PROFILES.md`.
- The native GMMP preference store is resolved by the number of those semantic keys it actually contains. A historical XML file name is never the identity; no unique semantic store means no preference mutation.
- A complete Standard baseline is mandatory before any Bluetooth mutation. Starting on Bluetooth may collect live observations, but those observations can never be promoted to Standard.
- When the local route becomes active, one delayed bounded snapshot force-republishes unchanged native values with local-route identity. Device evidence now proves this capture reaches all `17` managed preference values plus all `11` Equalizer PRE/band shape fields.
- The GMMP-private `gonesmart_audio_runtime_state` mirror is the apply-critical fallback for Standard. Only the local route may populate its `standard.*` keys. The 2026-10-17 round proved that Standard is now available after disconnect/reconnect and no longer blocks Bluetooth profile setup.
- A GoneSmart-owned exported `ContentProvider` remains an acknowledged GMMP→companion transport when Android permits it, but the tested GMMP host cannot resolve that provider authority. The runtime now probes it once per process and then uses the explicit component receiver fallback rather than generating one PackageManager error per mirrored field.
- Effects are reconstructed as complete native cards and written in the exact native types/units observed from GMMP. Preference commits are read back immediately. The 2026-10-17 device round confirmed live behavior from GoneSmart; the log directly exercised Balance, Limiter, Bass boost and Tempo, and the maintainer confirmed the managed Effects respond correctly. Full output-switch restoration is still part of final feature acceptance.
- The accepted 4.2.1 Equalizer state owner is observed as `r61`, with natural PRE/band/enabled calls exposed as `r(double)`, `z(int,double)` and `d(boolean)`. Those names are evidence only.
- Runtime resolution validates the Equalizer state shape first: a unique PRE-like `void(double)` setter, a unique band-like `void(int,double)` setter, a unique enabled-like `void(boolean)` setter, unique gain-array state and supporting Equalizer structure.
- `r61` is therefore only a validated 4.2.1 fast path. If the name changes, a bounded startup-only Dex scan searches GMMP implementation classes for the same structural contract and accepts only one unique highest-confidence owner. Ambiguity disables that boundary instead of guessing.
- The 2026-10-17 log isolated why Equalizer profile edits still did nothing: the passive observer received every natural `r61` call, but the application module's second independent hook set never captured a live owner. Every apply attempt remained `eq=shape-deferred` and no `EQ SHAPE APPLIED` marker appeared.
- The current architecture therefore has **one Equalizer hook owner**. The passive observer registers the already-proved setters and publishes the naturally observed live owner + Looper/Handler through `GmmpEqualizerRuntimeBridge`. The application module consumes that process-local target and no longer installs competing hooks merely to capture `thisObject`. This architecture is implemented and host-tested by CI but still needs the next device acceptance round.
- A Bluetooth Equalizer profile stores a complete explicit route shape. Choosing a learned named preset copies its exact PRE/band curve into those route values before apply; manual PRE/band edits atomically set the route preset state to `Custom`. Runtime application therefore uses the route's complete explicit curve rather than performing another preset lookup.
- Device evidence disproves the assumption that writing `dspSettings_activePreset` is itself the native preset-loading action: the stored name changes without necessarily loading the live curve.
- The latest log additionally disproved startup-label learning: GMMP started with `dspSettings_activePreset=Dance` and then initialized PRE plus all ten bands to zero, which the old code incorrectly learned as the Dance curve. Startup labels are now metadata only and never arm learning.
- Learned preset storage is versioned. Preset schema 2 clears unsafe schema-1 `native_preset.*` curves and migrates old Bluetooth named-preset route labels to `Custom` while preserving their stored PRE/bands. Standard's active preset name is not treated as a learned curve.
- Natural preset selections in GMMP may be learned only from the real GMMP preset-action call chain plus a complete fresh setter shape (or already-validated readback). A recent complete setter burst handles the case where GMMP writes the preset label after applying the curve; otherwise the learner clears its working shape and waits for a new complete setter burst.
- A small GoneSmart-owned runtime marker records whether GMMP was left in Bluetooth-overlay state. The application module can restore Standard on a later local-route process start once the remaining Equalizer path is device-accepted.

The next device round is intentionally narrow: verify that the shared Equalizer runtime target produces `EQ APPLY TARGET READY` followed by `EQ SHAPE APPLIED` when a GoneSmart band is changed, then naturally open one or two presets in GMMP and confirm only their actual curves become selectable in GoneSmart. Effects do not need another broad discovery round unless output-switch restoration reveals a regression.

### Recommendation-pool latency

- successful fills may top up again as soon as low-water requires it;
- the long provider backoff applies only after an unproductive fill;
- startup/session prewarm is asynchronous;
- Track Auto-DJ's explicit Initial-Size path gives smart preparation only a short bounded head start and never cancels the still-useful background fill when native GMMP must proceed immediately.

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

## 5. Accepted-version performance contract

- Offscreen ViewPager pages do not perform row/model/style work.
- Native adapter/scroll/layout events are preferred over periodic polling.
- Visible-row synchronization is coalesced.
- Persistent UI helpers cache weak references to proven native anchors.
- `isShown == false` during pager transitions does not invalidate anchor identity.
- Full view-tree discovery is bounded recovery only.
- Drawable/glyph analysis and class-level reflection are cached.
- Queue-writer discovery becomes pass-through after proof.
- Play-flipped uses one delayed independent postcondition read plus at most one legacy fallback, not a retry loop.
- Track Auto-DJ pre-action Queue/Room barriers never block GMMP's main thread.
- Audio's structural Dex fallback is startup-only and runs only when the validated tested fast path is unavailable; it must never become a recurring hot-path scan.
- Audio profile writes are event-driven by output/profile changes and are debounced; no periodic DSP polling is permitted.
- Standard bootstrap and native preset readback are bounded route/action-triggered snapshots, not polling loops.
- The audio companion provider is probed at most once after it is found unavailable in a GMMP process; subsequent bounded events go directly to the explicit receiver fallback instead of repeating failed provider lookups.
- Equalizer observation/application shares one native hook family. Do not install duplicate interceptors solely to recover a live owner for another GoneSmart subsystem.

Do not hide `w6` or other native GMMP logging to make logs look clean. Remove GoneSmart-caused unnecessary queries instead.

## 6. Notification / host-UI discipline

- Prefer complete GMMP localized phrases when available.
- GoneSmart-owned notices may use concise English fallback text.
- Symbol-only status (`✓`, `⚠`) is not an acceptable entire popup fallback.
- Suppress native Toasts only in a bounded, exact scope when they are known duplicates or misleading transitional results.
- First-time Audio Profiles setup may use one concise Snackbar to explain that Bluetooth must be disconnected once for Standard capture; long background explanations belong behind the toolbar info action.

## 7. Probe retirement ledger

4.2.1's broad class/recycler/Room probes are retired or gated. Reusable diagnostics remain available for unknown versions, but accepted runtime keeps only concise resolver/result/failure markers.

The Audio Profiles feature may retain bounded development evidence while its programmatic native application path is being accepted. Once accepted, broad audio preference/preset diagnostics must be removed; semantic resolvers, event-driven application and bounded postconditions remain.

The dated `GMMP_421_*` files retain the investigation chronology. Do not move that chronology back into hot runtime code or `AGENTS.md`.

## 8. Acceptance checklist for a future version

- [ ] bundled read-only self-test completed;
- [ ] every enabled mutation has a proven native action/writer;
- [ ] independent read/postcondition mapping verified;
- [ ] JVM tests cover ambiguity and failure;
- [ ] changed semantic boundaries device-accepted;
- [ ] failure paths fail closed;
- [ ] broad diagnostics and runtime discovery cost retired;
- [ ] Compatibility source-of-truth updated;
- [ ] `AGENTS.md`, completion matrix, README and this playbook agree;
- [ ] exact-head CI fully green.

For audio-capable versions, the checklist additionally requires that the Equalizer owner and GMMP audio preference store resolve semantically, complete Standard/override route transitions are device-verified, the local-route-only Standard mirror cannot be contaminated by Bluetooth, and no duplicate GoneSmart hook family competes for the live Equalizer owner. A built-in preset may be loaded programmatically only after its actual native action is semantically proved or its exact native curve has already been safely learned from a natural GMMP action; a startup/stored preset-name preference alone is insufficient.

## 9. 0.4.0 closure

GMMP 4.2.1 is the accepted target for GoneSmart 0.4.0. The final migration includes the queue/entity remap, passive Current writer proof, Track Auto-DJ r42–r44 continuity/settling corrections, recommendation-pool latency cleanup and ViewPager/badge hot-path cleanup. No previously accepted 4.2.1 feature depends on an unsafe guessed state-changing writer. Audio Profiles remain feature-scoped development until their remaining Equalizer and route-restoration acceptance passes the device checks described above.
