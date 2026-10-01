# GMMP compatibility playbook

This document is the maintained compatibility ledger and update procedure for GoneSmart's integration with GoneMAD Music Player (GMMP).

It exists because GoneSmart deliberately reuses GMMP internals to preserve native behavior, UI, localization and data transactions. Those internals are obfuscated and are **not a stable API**. A small GMMP release can therefore keep the same visible behavior while R8 renames classes, methods, fields, constructors or generated Room DAO implementations.

Use this file for the **current cross-version map and repeatable update strategy**. Keep detailed reverse-engineering chronology in `docs/NATIVE_GMMP_AUDIT.md`; keep standing contributor rules in `AGENTS.md`. Do not duplicate long investigation histories into AGENTS.

## Compatibility states

- **Accepted** — maintainer device testing has verified the relevant GoneSmart feature boundaries on that exact GMMP version.
- **Observed / unaccepted** — GoneSmart has run on the version and diagnostics exist, but one or more native boundaries are still unresolved or untested.
- **Unsupported / unknown** — no reliable runtime evidence exists.

The companion Home status must warn for every installed GMMP version other than the explicit tested version. The warning belongs on the **Compatibility row background**, not on the overall module/injection card: module injection can be healthy while one or more GMMP-native boundaries are unverified.

## Current version ledger

| GMMP | State | Evidence / notes |
|---|---|---|
| 4.2.0 | **Accepted baseline** | Current v0.4 development features were implemented and device-tested against this version. |
| 4.2.1 | **Observed / unaccepted** | Install version code 473 observed on 2026-10-01. The public update is small, but the runtime shows a broad R8 remap across Smart DJ, Playlist UI, Playlist Link, Smart folders, Flip and Track Auto-DJ queue observers. |

Do **not** move 4.2.1 to Accepted until every feature intended to ship has either been remapped and device-tested or explicitly classified as unavailable for that version.

## 4.2.0 → 4.2.1 mapping ledger

Status meanings in this table:
- **Verified** = runtime evidence is sufficient to use the boundary.
- **Observed** = diagnostic evidence exists, but the replacement is not yet proven safe.
- **Unresolved** = old boundary is known broken; no safe replacement is selected yet.
- **Stable** = the 4.2.0 boundary still installed/worked in the observed 4.2.1 run.

| Feature / boundary | 4.2.0 baseline | 4.2.1 observation | Status / rule |
|---|---|---|---|
| Auto-DJ refill hook | existing verified refill boundary | installs successfully | **Stable** |
| Player Auto-DJ badge | existing verified player hook | installs successfully | **Stable** |
| Auto-DJ selection | `kr.F1(int): List` | `F1` absent; only concrete `kr.B1(int): List` candidate observed | **Observed**, do not hard-code `B1`; resolve only from a unique concrete int→List contract plus runtime postconditions |
| Auto-DJ TrackDao field | `qr.r` → TrackDao | still yields runtime class `kr` | **Verified** for the observed build |
| Auto-DJ GMDatabase field | `qr.t` → GMDatabase | `qr.t` now yields `ur`; unique database-like field `qr.s` yields `GMDatabase_Impl` | **Verified structurally**; prefer legacy field only if database-like, otherwise require exactly one Room/GMDatabase candidate |
| TrackDao library query | `kr.g2(rawQuery): List` | `g2` absent; hierarchy `kr > gr > ys3`; inherited `ys3.Q(qp4): List` is abstract; no concrete RawQuery method resolved yet; fields include `u → GMDatabase_Impl`, `v → kr$a`, `w → kr$c` | **Unresolved**; never invoke the abstract contract; inspect concrete delegate fields / implementation structure |
| ArtistDao accessor | `GMDatabase.y()` | succeeds once the correct GMDatabase field is used and returns runtime ArtistDao class `fn` | **Verified** |
| Artist RawQuery method | `ArtistDao.R1(tp4)` | `R1` absent; `fn` exposes several `qp4` candidates including List-returning methods | **Unresolved**; derive/verify by semantic contract, not method name alone |
| Artist RawQuery wrapper | `tp4(String,Object[])` | old `tp4` constructor absent; 4.2.1 DAO candidates use `qp4` | **Observed**; derive wrapper from the verified DAO method parameter |
| Add-to-Playlist multi-selection lifecycle | `bo3.I3()` plus `k2()/D1()` view getters | `bo3.I3()` absent; later getters were not reached by the old all-or-nothing installer | **Unresolved**; resolve each sub-hook independently and diagnose no-arg methods by return type/lifecycle |
| Playlist Link rule model | `ft4(int,int,String,int)` and related `ft4.z(...)` | constructor and evaluation method no longer match | **Unresolved** |
| Playlist Link editor presenter | `ds4(Context,Bundle)`, `ds4.g2(boolean)`, `ds4$g` | constructor/method/inner class no longer match | **Unresolved** |
| Playlist Link native label hook | `os2.U(gt4)` | method no longer matches | **Unresolved** |
| Smart-Playlist model | `ws4(String,int,int,int,ArrayList,int)` | constructor no longer matches | **Unresolved** |
| Smart presenter capture | `ss4.P1(fo2)` | method absent | **Unresolved** |
| Smart list submit | `os4.j2(List)` | method absent | **Unresolved** |
| Smart create callback | `ss4$b.invoke` | inner class absent | **Unresolved** |
| Smart context Move | `nt4.c(Context,zn0,MenuItem)` | method absent | **Unresolved** |
| Native Smart ActionMode | `n3` ActionMode callbacks | still install | **Stable** |
| Smart writer | `ws4.t(File)` | method absent | **Unresolved** |
| Flip queue capture | `ex3.D()` fallback capture | method absent | **Unresolved**; constructor capture may still be usable independently |
| Track Auto-DJ queue write observer | `ex3.w(List)` | no matching method observed by old resolver | **Unresolved** |
| Queue position observer | `ex3.b2(int)` | method absent | **Unresolved** |
| Native playlist Play / Flip | `MusicService.w1(int,Object,List)` | method absent | **Unresolved** |
| Flip / Track Auto-DJ menu inflaters | verified native menu inflater hooks | still install | **Stable** |

## Lessons from the 4.2.1 update

### 1. Treat obfuscated names as a fast path, never as identity

A symbol such as `kr.F1`, `qr.t`, `ws4.t` or `ex3.b2` is evidence for one exact tested build, not the semantic identity of a GMMP function.

Preferred resolver order:

1. **Verified legacy fast path** for the accepted GMMP version.
2. **Structural resolver** using parameter count/types, return type, modifiers, declaring-class relationships, constructor shape and field declared/runtime types.
3. **Semantic/postcondition validation** before GoneSmart changes state.
4. If the candidate is not unique or validation is impossible, **fail closed and log bounded diagnostics**.

Never pick a replacement solely because its name or one signature “looks likely”.

### 2. Reject abstract/interface contracts as callable implementations

The first 4.2.1 TrackDao experiment found `ys3.Q(qp4): List`. Its signature looked correct, but it was an abstract inherited contract and invocation produced `AbstractMethodError`.

Every structural method resolver must check at least:
- method is not abstract when GoneSmart plans to invoke it;
- parameter types match the semantic boundary;
- return type is compatible;
- if a bridge/delegate method returns `Object`, validate the runtime result before use;
- owner/hierarchy makes sense for the expected native component.

### 3. Resolve fields by type relationships, not letters

The 4.2.0 database field `qr.t` became unrelated `ur` in 4.2.1, while `qr.s` became the real `GMDatabase_Impl`.

For fields:
- first try the accepted-version field;
- validate the value against the expected declared/runtime type;
- otherwise enumerate non-static instance fields;
- accept a fallback only when exactly one candidate satisfies the required type relationship;
- log field name + declared/runtime type only, never private user data.

### 4. Generated Room DAO layers can add abstract contracts and delegates

A Room DAO runtime object can inherit abstract query contracts while concrete work is delegated through generated classes or fields. A missing direct method does not prove the query disappeared.

When a DAO mapping breaks:
- record class hierarchy and interfaces;
- inspect concrete vs abstract methods separately;
- inspect non-static delegate fields and their runtime types;
- derive RawQuery wrapper types from the verified native DAO method instead of hard-coding obfuscated query-class names;
- validate returned collection/row shapes before caching the mapping.

### 5. Hook installation must be feature-isolated

One version-specific failure must not abort independent features. 4.2.1 initially made GoneSmart look completely dead because one Smart-DJ reflection failure stopped later hook registration.

Rules:
- install each independent hook family inside its own guarded boundary;
- split multi-hook families further when one missing sub-hook should not prevent other safe observers from installing;
- report degraded capability precisely;
- never convert “module injected” into “all native hooks compatible”.

### 6. Deterministic mapping failures must not retry in a hot loop

The original startup prewarm retried the same missing reflection mapping every 400 ms. Once a missing/ambiguous native boundary is deterministic for the process, cache that state and stop retrying it.

Retry only conditions that can genuinely change during the process lifecycle (for example native database readiness), not missing methods/classes/constructors in a fixed APK.

### 7. Diagnostics should be bounded, structural and privacy-safe

Useful diagnostics:
- class hierarchy and implemented interfaces;
- method name, declaring class, modifiers, parameter types and return type;
- constructor parameter types;
- field name, declared type and runtime type;
- candidate count and why a candidate was rejected.

Do not log:
- full library contents;
- playlist/track titles unless explicitly necessary for a targeted test;
- filesystem paths in compatibility inventories;
- unbounded method/field dumps.

### 8. Cache resolved native bindings once per process/version

After a resolver proves a boundary, retain the resolved `Method`, `Field` or `Constructor` in the feature's binding object. Do not rediscover mappings from layout passes, scroll events, list refreshes or repeated Auto-DJ calls.

A future cleanup should centralize these contracts into version-aware/native-semantic binding objects instead of scattering string literals throughout feature controllers.

### 9. Prefer stable Android/GMMP semantics around the obfuscated core

When possible, anchor discovery to:
- stable Android / AndroidX types;
- GMMP resource IDs/names and actual localized UI ownership;
- RoomDatabase inheritance;
- Java collection/primitive signatures;
- native model relationships already proven by GMMP itself;
- actual postconditions (queue order changed, list committed, file written, native adapter contains expected model).

This does **not** authorize synthetic substitutes where AGENTS requires native-first reuse.

## Update procedure for every new GMMP version

1. **Do not change the tested-version constant immediately.** Let the companion mark the new version untested.
2. Capture one clean startup log with GoneSmart enabled and the normal development options.
3. Compare the log against this ledger and classify every native boundary as stable, remapped or unresolved.
4. Fix the earliest shared/core boundaries first (database/DAO, queue, Smart models), because many features depend on them.
5. Prefer structural resolvers over adding a new obfuscated name table.
6. Add bounded diagnostics for unresolved boundaries in the same test build so one device pass can answer several questions.
7. Keep failures isolated by feature and stop deterministic retry loops.
8. Run unit tests + debug APK + unsigned release APK + artifact upload on the exact head.
9. Device-test only the affected native boundaries and their immediate regressions; do not repeat unrelated accepted flows.
10. Update this ledger with **observed evidence**, marking mappings Verified only after the relevant runtime behavior is confirmed.
11. Update `docs/NATIVE_GMMP_AUDIT.md` with detailed investigation notes when useful.
12. Only after the intended feature matrix passes should the tested-version constant/README compatibility claim move to the new GMMP version.

## Longer-term hardening backlog

- Introduce semantic binding/resolver objects per native domain (Auto-DJ/Room, Playlist picker, Smart-Playlist model/editor, queue/playback) so feature code does not own raw obfuscated names.
- Give each binding a legacy fast path, structural fallback, validation function and compact diagnostic description.
- Unit-test resolvers with small fake class hierarchies covering renamed methods, abstract contracts, bridge methods, ambiguous candidates and moved fields.
- Track per-feature compatibility capability rather than one binary “module works” flag; surface degraded feature groups in logs/status without turning the overall injection state red.
- Consider persisting only the **GMMP version + resolved capability summary**, never reflected members or user library data.
- Keep an explicit device acceptance matrix for every GMMP version that GoneSmart publicly claims to support.
