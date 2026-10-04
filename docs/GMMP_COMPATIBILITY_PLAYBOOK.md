# GMMP compatibility playbook

This is GoneSmart's maintained version ledger and repeatable procedure for adapting to GoneMAD Music Player (GMMP) updates. GMMP internals are obfuscated and are not a stable public API, so semantic behavior and structural ownership matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed reverse-engineering chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` documents.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original concrete mappings and device-tested behavior. Names are evidence only. |
| 4.2.1 | Accepted for current core/features, with one explicit open boundary | Smart DJ, Playlist/Smart-Playlist features, Play flipped and Track Auto-DJ are accepted. Queue-menu `Flip queue` still lacks a proven current-position writer when the reversal changes Current position. |
| Any other version | Untested | Show amber Compatibility status and run the bounded compatibility workflow before claiming support. |

Source of truth for the companion status is `GmmpCompatibilityPolicy.TESTED_VERSION = "4.2.1"`.

## 2. Golden rule: resolve semantics, not names

Obfuscated names can be tried as tested fast paths, but they never establish identity alone.

A durable resolver should combine as many of these as appropriate:

1. owner/runtime type reached from a known live object;
2. method/field signature and hierarchy;
3. generated Room adapter SQL ownership;
4. independent read-only Cursor correlation;
5. uniqueness among candidates;
6. postcondition after the real native action;
7. rollback/fail-closed behavior for mutation.

If several candidates remain, stop. Do not invoke them merely to see what happens.

## 3. One-shot update workflow for a new GMMP version

### Phase A — detect

The companion compares the installed GMMP version with the tested version. Unknown/missing GMMP must never be presented as green Compatibility.

### Phase B — bundled read-only self-test

On an unknown version, run one bounded compatibility self-test covering the feature families that depend on GMMP internals. Prefer passive evidence and structural inspection over a sequence of tiny probe APKs.

Safe examples:
- class hierarchy/signatures;
- live field runtime types;
- Room generated-adapter SQL strings;
- RecyclerView adapter/holder ownership on a relevant surface;
- native call-chain existence without invoking a destructive final action;
- Cursor schema/order/current correlation.

Do not mutate playlists, queue rows or playback merely for discovery.

### Phase C — graduate

Once a semantic shape is proven:
- encode it in a shared resolver/policy;
- add JVM tests for uniqueness and false positives;
- validate the real native action once on device;
- keep independent postcondition verification;
- retire the broad probe.

### Phase D — clean

Accepted versions should not continuously emit full reflection inventories. Normal logs keep compact mapping/result markers. Deep inventories are re-enabled only for an unknown version or the failing unresolved boundary.

## 4. GMMP 4.2.1 accepted boundary map

### Smart DJ refill

- Runtime Auto-DJ owner is currently `qr`.
- Native refill boundary is currently `qr.z(int)`.
- Semantic role: **request N Auto-DJ tracks**.
- This method must never be reused as a generic current-position writer just because it is an `int -> void` method.

The selection/recommendation pipeline remains GoneSmart-owned, while GMMP owns insertion/playback. Native GMMP fallback remains valid when GoneSmart has no suitable local match or its configured fallback requires it.

### Read-only queue state

Queue state is read independently through GMMP's read-only Cursor path. The current marker is correlated with the native position signal reached through `qr.t -> ur.b()` on the tested build.

Cursor values are authoritative for:
- `queue_id`;
- `queue_track_id`;
- `queue_position`;
- `queue_shuffle_position`;
- Current-row correlation.

### Queue DAO / entity / writers

Device/runtime proof for 4.2.1:

- generated database accessor: `GMDatabase_Impl.F(): sx3`;
- runtime DAO implementation: `vx3`;
- native Queue entity: `cy3`;
- synchronous native reader: `sx3.H1(): ArrayList`;
- generated DAO adapters owned by `vx3` name `queue_table` in their INSERT/DELETE/UPDATE SQL;
- verified update writer: `O0(List)` after ownership/entity proof;
- delete reflection may expose `Object[]`, but the implementation casts to `cy3[]`; GoneSmart must pass a real typed entity array.

The earlier `d85` candidate was rejected as Queue writer owner because its generated adapters write `tracks`, not `queue_table`. This is the canonical example of why historical field/name assumptions are insufficient.

Reactive `W1/X1` candidates are not invoked during Queue discovery.

### Current-position state

- Proven read boundary: `ur.b()` through the current-position host reached from `qr.t`.
- No safe current-position writer has yet been proven on 4.2.1.
- `qr.z(int)` is refill and is explicitly excluded from state-writer discovery.

This does not block Track Auto-DJ when the pointer already targets position 1, but it does remain relevant to Queue-menu `Flip queue` when a reversal must move Current to a different queue position.

### Track Auto-DJ

Accepted device flow:

1. invoke GMMP's exact native Play action for the clicked track;
2. retain the originally selected track through any asynchronous Smart-Playlist queue rebuild;
3. wait for bounded queue quiescence/completion;
4. isolate the exact native Queue entity via proven GMMP writers;
5. verify the selected track is the sole Current seed;
6. keep the refill hold active while enabling Auto-DJ;
7. suppress the transitional ordinary `upcoming` refill;
8. call the same native refill boundary once with `Initial Size - seed size`;
9. verify **exact Initial Size** and preserved Current seed before releasing normal refill behavior.

This has been verified from normal Playlist and large Smart-Playlist launch contexts. With Initial Size 5 and one seed, the final accepted log shows one native request for 4 tracks and a final queue of exactly 5.

### Playlist / Smart-Playlist surfaces

Playlist folders, Smart-Playlist folders, multi-selection, Playlist Link and native Play-flipped behavior are accepted on 4.2.1. Resolvers should continue to prefer semantic adapter/model/writer ownership and native actions; historical class names are only fast paths.

## 5. Queue Flip open boundary

There are two separate features that must not be conflated:

- **Play flipped** (playlist context): accepted on 4.2.1 through the structurally resolved native `MusicService` playback boundary.
- **Flip queue** (current Queue menu): reversing queue positions while preserving the same current track can require writing a new current queue position.

The second operation must remain fail-closed until a native current-position writer is semantically identified and verified. A valid solution must:

1. start from an independently Cursor-verified Queue snapshot;
2. prove the native state-writer boundary without signature guessing;
3. perform the native queue update;
4. write the corresponding current position through GMMP's native state path;
5. re-read the Cursor and confirm reversed queue IDs + identical Current queue ID;
6. roll back both row positions and current state if verification fails.

Do not close this item by calling `qr.z(int)`.

## 6. Probe ledger / retirement rules

The 4.2.1 investigation used successive compatibility probe revisions to discover remapped Playlist/Smart UI, Room ownership, Queue entity bindings and Current-state relationships. Detailed chronology is retained in the dated 4.2.1 docs.

Final policy:
- keep the reusable compatibility self-test infrastructure;
- keep targeted failure diagnostics near semantic resolvers;
- retire continuous broad class/recycler/holder inventories for the tested 4.2.1 build;
- re-enable/expand them only for an unknown GMMP version or an unresolved boundary;
- every new probe must state its scope, trigger, safety bound and retirement condition.

## 7. Status UI contract

The companion's Status card is neutral. It has one divider below the overall status and then independently colored health rows:

- GMMP: red missing / amber installed-not-running / green running;
- Xposed: red service missing / amber unsupported libxposed API / green supported API;
- GoneSmart state: red unavailable/stopped / amber inactive or fallback/degraded / green healthy runtime;
- Compatibility: red unknown/missing / amber untested version / green tested 4.2.1.

Compatibility warning color must not be blended with a green parent background, and there is no extra divider dedicated to Compatibility.

## 8. Evidence and acceptance checklist

Before promoting a new GMMP version to tested:

- [ ] bundled read-only self-test completed;
- [ ] every enabled state-changing feature has a proven native writer/action;
- [ ] Cursor/read-only mappings independently verified;
- [ ] JVM resolver tests cover ambiguity/failure;
- [ ] one device flow per changed semantic boundary succeeds;
- [ ] failure paths fail closed and do not corrupt native state;
- [ ] broad discovery logs retired or gated;
- [ ] companion Compatibility source-of-truth updated;
- [ ] `AGENTS.md` and this playbook updated;
- [ ] exact-head CI fully green.

A version can be documented as accepted with an explicit isolated limitation only if that limitation remains disabled/fail-closed and does not affect the accepted feature families.
