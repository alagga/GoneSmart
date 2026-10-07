# GMMP compatibility playbook

This is GoneSmart's maintained version ledger and repeatable procedure for adapting to GoneMAD Music Player (GMMP) updates. GMMP internals are obfuscated and are not a stable public API, so semantic behavior and structural ownership matter more than R8 names.

Standing contributor rules live in `AGENTS.md`; detailed reverse-engineering chronology remains in `docs/NATIVE_GMMP_AUDIT.md` and the dated `GMMP_421_*` documents.

## 1. Version ledger

| GMMP version | State | Notes |
| --- | --- | --- |
| 4.2.0 | Accepted historical baseline | Original concrete mappings and device-tested behavior. Names are evidence only. |
| 4.2.1 | Accepted | Smart DJ, Playlist/Smart-Playlist features, Play flipped, Queue Flip and Track Auto-DJ are device-accepted. Broad compatibility probes and accepted-version hot-path diagnostics are retired/gated. |
| Any other version | Untested | Show amber Compatibility status and run the bounded compatibility workflow before claiming support. |

Source of truth for the companion status is `GmmpCompatibilityPolicy.TESTED_VERSION = "4.2.1"`.

## 2. Golden rule: resolve semantics, not names

Obfuscated names can be tried as tested fast paths, but they never establish identity alone.

A durable resolver should combine as many of these as appropriate:

1. owner/runtime type reached from a known live object;
2. method/field signature and hierarchy;
3. generated Room adapter SQL ownership;
4. independent read-only Cursor/native-state correlation;
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

Probe retirement also includes **runtime cost**. After a boundary is accepted, remove repeated reflection, Cursor/SQL readbacks, delayed discovery callbacks, model reconstruction and view-tree scans from normal render/playback hot paths. Logging less while still doing the work is not sufficient cleanup.

## 4. GMMP 4.2.1 accepted boundary map

### Smart DJ refill

- Runtime Auto-DJ owner is currently `qr`.
- Native refill boundary is currently `qr.z(int)`.
- Semantic role: **request N Auto-DJ tracks**.
- This method must never be reused as a generic current-position writer just because it is an `int -> void` method.

The selection/recommendation pipeline remains GoneSmart-owned, while GMMP owns insertion/playback. Native GMMP fallback remains valid when GoneSmart has no suitable local match or its configured fallback requires it.

### Read-only queue state

Queue state is read independently through GMMP's read-only Cursor path. Cursor values are authoritative for:
- `queue_id`;
- `queue_track_id`;
- `queue_position`;
- `queue_shuffle_position`;
- Current-row correlation and mutation postconditions.

### Queue DAO / entity / writers

Device/runtime proof for 4.2.1:

- generated database accessor: `GMDatabase_Impl.F(): sx3`;
- runtime DAO implementation: `vx3`;
- native Queue entity: `cy3`;
- synchronous native reader: `sx3.H1(): ArrayList`;
- generated DAO adapters owned by `vx3` name `queue_table` in their INSERT/DELETE/UPDATE SQL;
- verified update writer: `O0(List)` after ownership/entity proof;
- delete reflection may expose `Object[]`, but the implementation casts to `cy3[]`; GoneSmart must pass a real typed entity array.

The earlier `d85` candidate was rejected as Queue writer owner because its generated adapters write `tracks`, not `queue_table`. Reactive `W1/X1` candidates are not invoked during Queue discovery.

### Current-position writer / Queue Flip

Queue Flip is accepted on 4.2.1.

The writer is not selected by an obfuscated name. GoneSmart observes **natural GMMP playback calls**, compares them with an independent native current-position signal, and promotes a writer only when the observed transition reaches exactly the naturally requested queue position and that queue position is valid/unique. Candidate methods are never actively invoked just to discover them.

The observed 4.2.1 writer is `dx3.c2(int)`. Treat this name as version evidence only. `qr.z(int)` remains refill and is explicitly excluded.

Controlled Queue Flip then:

1. starts from an independently Cursor-verified Queue snapshot;
2. correlates native Queue entities 1:1;
3. reverses through proven native Queue writers;
4. writes Current through the passively verified native state writer when required;
5. re-reads the independent Cursor/native Current state;
6. requires reversed queue IDs plus the same Current queue identity;
7. rolls back when verification fails.

After a writer has been proved for the live Auto-DJ instance, passive writer discovery becomes a cheap pass-through. Generic getter scans, delayed readbacks and SQL correlation must not continue on every playback callback.

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

This has been verified from normal Playlist and large Smart-Playlist launch contexts. With Initial Size 5 and one seed, the accepted flow makes one native request for 4 tracks and verifies a final queue of exactly 5.

### Playlist / Smart-Playlist surfaces

Playlist folders, Smart-Playlist folders, multi-selection, Playlist Link and native Play-flipped behavior are accepted on 4.2.1. Resolvers should continue to prefer semantic adapter/model/writer ownership and native actions; historical class names are only fast paths.

Performance contract for these surfaces:
- native adapter/scroll/layout events are preferred over periodic polling;
- attached but offscreen ViewPager pages must not do row reflection/model scanning merely because they still draw/layout;
- foreground pre-draw fallback must be bounded and contain only work that genuinely needs frame timing;
- expensive visible-row alignment/interaction work is coalesced from scroll/refresh events;
- accepted-version diagnostics are not allowed to recreate the discovery-time runtime cost.

## 5. Accepted-version performance audit

The final 4.2.1 cleanup found four important classes of accidental overhead:

### Queue writer discovery

A generic same-host fallback could invoke several zero-arg GMMP getters repeatedly while trying to correlate a natural position transition. Some of those getters perform Queue SQL internally. In device logs this appeared as hundreds of `w6` queue queries per second and severe frame loss.

Final policy:
- prefer the cheap independently verified native position signal first;
- cache structural writer-candidate membership;
- allow broad same-host fallback at most once per candidate per process;
- delayed checks use the cheap signal only;
- once a writer is verified, all discovery hooks pass through immediately.

### Playlist/Smart-Playlist folder rendering

The folder overlays previously retained some row/style/reflection work in `OnPreDraw`, including while native ViewPager pages remained attached but offscreen.

Final policy:
- offscreen pages are idle apart from the minimal geometry needed to keep an overlay hidden;
- Smart-folder visible-row title/interaction sync is posted/coalesced from native scroll and refresh events;
- normal Playlist periodic model-refresh fallback runs only on the foreground page and at a slow interval; native adapter notifications remain the primary refresh path.

### Player / navigation badges

The Now Playing Auto-DJ badge and Playlist/Smart-Playlist navigation badges are persistent UI extensions, so discovery cost matters even when no feature action is running. The former could repeatedly rescan the full decor tree and rasterize the playback-mode drawable; the latter could rescan every `TextView` and rediscover `getAdapter()` reflection during layout waves.

Final policy:
- cache weak references to already proven native UI anchors;
- on layout/global-layout callbacks, reuse those anchors while they remain attached, visible and semantically valid;
- fall back to structural full-tree discovery only when a cached target disappears or is rebound;
- cache class-level reflection such as adapter getters instead of rediscovering it per ViewGroup;
- cache glyph analysis by drawable identity/state and use only a bounded safety recheck;
- reuse render objects/shaders/paths where possible instead of allocating them on every draw;
- normal accepted-version badge state changes do not emit repetitive info logs.

### Play-flipped postcondition verification

The native 4.2.1 Play-flipped interception is device-accepted. Its old success verifier could poll Queue state up to twelve times at 250 ms intervals after every launch, including a legacy reflective fallback.

Final policy:
- one delayed independent Queue postcondition read is sufficient during normal accepted-version playback;
- at most one legacy structural fallback may run if the primary read is inconclusive;
- there is no retry/polling loop in the normal path;
- repeated diagnostics belong only to an unknown/failing compatibility investigation.

Do not hide GMMP's native `w6` logger to make logs look clean. Remove GoneSmart-caused unnecessary queries. Native GMMP queries performed legitimately when a library tab is opened/refocused may remain visible.

## 6. Probe ledger / retirement rules

The 4.2.1 investigation used successive compatibility probe revisions to discover remapped Playlist/Smart UI, Room ownership, Queue entity bindings and Current-state relationships. Detailed chronology is retained in the dated 4.2.1 docs.

Final policy:
- keep the reusable compatibility self-test infrastructure;
- keep targeted failure diagnostics near semantic resolvers;
- retire continuous broad class/recycler/holder inventories for the tested 4.2.1 build;
- retire the runtime cost of graduated passive probes as well as their logs;
- re-enable/expand diagnostics only for an unknown GMMP version or an unresolved boundary;
- every new probe must state its scope, trigger, safety bound and retirement condition.

## 7. Status UI contract

The companion has one large Status card. The top overall-status section is colored from aggregate health (red > amber > green), followed by exactly one divider and four full-width colored sections in the same card:

- GMMP: red missing / amber installed-not-running / green running;
- Xposed: red service missing / amber unsupported libxposed API / green supported API;
- GoneSmart state: red unavailable/stopped / amber degraded or fallback / green healthy normal/idle or Smart runtime;
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
- [ ] graduated probes no longer impose obvious persistent runtime cost;
- [ ] companion Compatibility source-of-truth updated;
- [ ] `AGENTS.md`, `docs/GMMP_421_COMPLETION.md` and this playbook updated;
- [ ] exact-head CI fully green.
