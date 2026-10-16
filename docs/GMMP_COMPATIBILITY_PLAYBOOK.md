# GMMP compatibility playbook

This file is GoneSmart's maintained version ledger and repeatable update procedure for GoneMAD Music Player (GMMP). GMMP internals are obfuscated, so semantic ownership and observed behavior matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` evidence files.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original 0.3.x/early-0.4 mappings and device evidence. |
| 4.2.1 | **Accepted / 0.4.0 target** | Smart DJ, Playlist/Smart-Playlist features, Flip, Track Auto-DJ and compatibility/performance cleanup device-accepted. Audio Profiles remain feature-scoped development until their mutation path is device-accepted. |
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

The Audio Profiles feature remains under development even on 4.2.1. Its currently proved and implemented boundaries are:

- Stable semantic preference IDs are observed from ordinary GMMP UI writes for Equalizer enabled/active preset and every managed Effects control documented in `docs/AUDIO_PROFILES.md`.
- The native GMMP preference store is resolved by the number of those semantic keys it actually contains. A historical XML file name is never the identity; no unique semantic store means no preference mutation.
- A complete Standard baseline is mandatory before any Bluetooth mutation. Starting on Bluetooth may collect live observations, but those observations can never be promoted to Standard.
- When the local route becomes active, one delayed bounded snapshot force-republishes unchanged native values with local-route identity. Device evidence on 2026-10-16 proved this source capture reaches all `17` managed preference values plus all `11` Equalizer PRE/band shape fields.
- That same device run then reconnected Bluetooth and still saw the framework-facing apply module report `Standard baseline incomplete`. Therefore the remaining bootstrap failure was isolated to cross-process persistence/visibility, not missing GMMP source values.
- The repaired persistence path uses a GoneSmart-owned exported `ContentProvider` call as the primary synchronous GMMP→companion bridge. The provider validates the caller UID/package, writes through `GoneSmartAudioProfileRepository`, verifies the local write, and returns an acknowledgement. An explicit component broadcast remains a bounded fallback instead of being treated as proof of persistence merely because `sendBroadcast()` returned.
- No-Bluetooth observations are additionally mirrored into the GoneSmart-owned GMMP-private runtime store. Only the local route may populate its `standard.*` keys. The native apply module reads framework Standard first and this host-local mirror second, so companion wake/service-sync timing cannot block an otherwise proved local Standard capture or allow Bluetooth state to seed the baseline.
- Learned native preset curves use the same framework-first/host-local fallback. The preset chooser exposes only curves that are actually complete; hard-coded names are not user-selectable merely because GMMP is known to have built-ins.
- Effects are reconstructed as complete native cards and written in the exact native types/units observed from GMMP. Preference commits are read back immediately. Live DSP acceptance remains device-test pending because the last run never passed the Standard gate.
- The accepted 4.2.1 Equalizer state owner was observed as `r61`, with natural PRE/band/enabled calls exposed as `r(double)`, `z(int,double)` and `d(boolean)`. Those names are evidence only.
- Runtime resolution validates the Equalizer state shape first: a unique PRE-like `void(double)` setter, a unique band-like `void(int,double)` setter, a unique enabled-like `void(boolean)` setter, unique gain-array state and supporting Equalizer structure.
- `r61` is therefore only a validated 4.2.1 fast path. If the name changes, a bounded startup-only Dex scan searches GMMP implementation classes for the same structural contract and accepts only one unique highest-confidence owner. Ambiguity disables that boundary instead of guessing.
- Discovery itself is passive. The application path captures a real owner from natural GMMP calls; only then may a requested Standard/Custom/known-preset shape invoke the already-proved PRE/band/enabled setter roles.
- Named preset reference curves are imported read-only from actual installed preset files when a complete PRE + ten-band file can be parsed safely. A 4.2.1 device run with zero file candidates showed that built-ins cannot depend on loose-file discovery alone.
- Device evidence disproved the assumption that writing `dspSettings_activePreset` is itself the native preset-loading action: the stored name changed while no live PRE/band curve was loaded. Unknown named presets therefore fail closed instead of performing a name-only mutation.
- Natural preset selections in GMMP may be learned without guessed coefficients through validated read-only state/setter observation. Until the actual native preset action is mapped, an unknown built-in must be loaded naturally by GMMP once before GoneSmart can safely reuse its curve.
- A small GoneSmart-owned runtime marker records whether GMMP was left in Bluetooth-overlay state. The application module is initialized before passive audio observation so a completed Standard baseline can restore an interrupted Bluetooth session.

The programmatic mutation path remains **implemented but not yet device-accepted**. The next device round must first verify the acknowledged Standard bridge (or the host-local fallback), then verify live Effects behavior, live Equalizer setter application and Standard restoration. The native built-in preset action still needs semantic mapping before arbitrary unknown built-ins can be loaded directly from GoneSmart.

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
- The acknowledged audio companion bridge is event-driven. It may synchronously persist a bounded observation/action event, but it must never become a polling transport.

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

For audio-capable versions, the checklist additionally requires that the Equalizer owner and GMMP audio preference store resolve semantically, complete Standard/override route transitions are device-verified, cross-process Standard persistence has an acknowledged postcondition, and no temporary Bluetooth state can silently redefine Standard. A built-in preset may be loaded programmatically only after its actual native action is semantically proved or its exact native curve has already been safely learned; a stored preset-name preference alone is insufficient.

## 9. 0.4.0 closure

GMMP 4.2.1 is the accepted target for GoneSmart 0.4.0. The final migration includes the queue/entity remap, passive Current writer proof, Track Auto-DJ r42–r44 continuity/settling corrections, recommendation-pool latency cleanup and ViewPager/badge hot-path cleanup. No previously accepted 4.2.1 feature depends on an unsafe guessed state-changing writer. Audio Profiles remain feature-scoped development until their native application path passes the device acceptance described above.
