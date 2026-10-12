# GoneSmart — persistent coding and collaboration rules

**Purpose:** Canonical entry point for assistants and contributors. Read this file before changing code, then inspect the relevant source, current branch head, compatibility docs and exact-head CI.

**Last consolidated:** 2026-10-08. **Release line:** 0.4.1. **Accepted GMMP target:** 4.2.1.

## 1. Collaboration and repository discipline

- Current explicit maintainer instructions override this file.
- Communicate with the maintainer in **German**. Public repository documentation and the companion UI remain English.
- Work from the actual GitHub repository; never reconstruct current code from chat snippets alone.
- Check the current branch head before editing and do not overwrite commits that appeared during the task.
- **Do not create temporary branches unless the maintainer explicitly asks for one.** Work on the active feature branch. If one-off CI/helper infrastructure is unavoidable, remove it in the same task and leave no release-tree residue.
- Minimize device-test rounds. Mine existing logs fully, add host tests and bundle related fixes instead of shipping one probe APK per hypothesis.
- After code changes, the branch is not green until the normal Build workflow succeeds on the **exact head**: unit tests, Debug APK, unsigned Release smoke test and Debug APK upload.
- Do not merge to `main`, tag or publish a release unless explicitly requested.

## 2. Native-first is mandatory

GoneSmart extends GMMP; it does not replace existing GMMP behavior with parallel implementations.

- Reuse GMMP native writers, callbacks, adapters, transactions, resources and playback flows whenever they exist.
- Never write directly to GMMP SQL/Room tables when a native DAO/writer boundary is available.
- Obfuscated names are version evidence, not semantic identity.
- Resolver order: tested fast path → structural/semantic discovery → uniqueness → runtime/postcondition verification.
- Ambiguous state-changing boundaries fail closed. Never invoke a candidate merely to see what it does.
- Read-only observation may be broader than mutation discovery.
- One unavailable hook family must not disable unrelated GoneSmart features.

## 3. Compatibility workflow

Read `docs/GMMP_COMPATIBILITY_PLAYBOOK.md` before adapting to a new GMMP build.

For an unknown version, use one bounded read-only compatibility self-test covering the relevant feature families. Safe evidence includes hierarchy/signatures, runtime types, generated Room adapter SQL, native adapter ownership and independent Cursor/state correlation. Destructive actions are never invoked merely for discovery.

Lifecycle:

`unknown → passive evidence → semantic resolver → uniqueness → real native action → postcondition/device acceptance → retire probe`

Graduation retires **runtime cost**, not just logging. Accepted-version hot paths must not keep broad reflection, SQL/Cursor readbacks, full view-tree scans or model reconstruction running continuously.

## 4. Accepted GMMP 4.2.1 queue contract

- Queue DAO accessor: `GMDatabase_Impl.F(): sx3`; runtime DAO: `vx3`.
- Queue entity: `cy3`.
- Native synchronous reader: `sx3.H1(): ArrayList`.
- Generated `vx3` adapters prove `queue_table` ownership.
- Verified update writer: `O0(List)`.
- Delete may reflect as `Object[]`, but the implementation casts to `cy3[]`; pass a real typed runtime array.
- Independent Cursor/native state remains authoritative for queue identity/order/current postconditions.
- `qr.z(int)` is the native Auto-DJ **refill** boundary. It is never a current-position setter.
- Queue Current writing is learned passively from natural playback; observed 4.2.1 evidence is `dx3.c2(int)`, but the name itself is not proof.

Queue Flip and Play flipped are accepted on 4.2.1. Once a writer/readback has been proved for the live Auto-DJ instance, discovery becomes a cheap pass-through.

## 5. Track Auto-DJ

The 4.2.1 flow is accepted from normal playlists and large Smart Playlists.

- Dispatch GMMP's exact native Play action for the clicked row.
- Treat the first CURRENT from the generic track menu as provisional during the bounded Smart-Playlist completion guard.
- If the first candidate survives the native rebuild exactly once, preserve it even if CURRENT temporarily drifts.
- If that provisional candidate disappears completely **inside the guard** and a different playback identity is independently observed, retarget and restart settling.
- Outside the guard, fail closed rather than following unrelated/manual playback.
- A successful native menu dispatch is not playback identity. Reusing an unchanged CURRENT queue entry requires an independent native playback/queue-update signal; otherwise wait for a real playback-identity change or fail closed.
- Legacy 4.2.0 may isolate the seed through its accepted native `ex3` transaction. **GMMP 4.2.1 Track Auto-DJ must not use `CLEAR_QUEUE` for startup.** r28 device evidence proved the command itself removes `current+1` early enough for MusicService to cache a null next AudioSource before any post-clear refill can run.
- On GMMP 4.2.1 Track Auto-DJ, resolve the clicked Track **before native Play** from bounded semantic evidence in the native context-menu callback graph and the read-only GMMP library cache. Require a unique strong match (exact path, ID plus corroborating text, or unique title+artist); never guess from an obfuscated field name or a bare numeric coincidence.
- Prepare the selected-track recommendation IDs before native Play. Around the first native `qr.z(...)` refill whose Current independently verifies the resolved Track ID, pass GMMP through its normal refill but replace the native `kr.F1(int)` selection objects with the immutable prepared GoneSmart Track IDs **before** GMMP materializes queue rows / preloads `current+1`. Native rows remain only for a genuine prepared-pool shortfall.
- Do **not** post-retarget Track IDs of a materialized 4.2.1 `current+1`: r28 proved GMMP can already have an AudioSource/decoder for that old Track. After r30 bootstrap materialization, Queue DAO mutation may only prune surplus rows while preserving the already-correct first upcoming Track IDs, queue IDs and positions.
- Prepared GoneSmart Track IDs occupy the first upcoming slots; existing native Track IDs remain only for a genuine prepared-pool shortfall. Do not perform a second Initial-Size refill after recommendations are ready.
- Send native `AUTO_DJ` only after the in-place retarget is independently verified. Hold its delayed natural refill through the bounded command window, then resume event-driven continuation refill on verified natural CURRENT transitions.
- A same-position `dx3.c2(int)` refresh is not a playback repair; r27 proved it can verify while Track 2 still has no decoder.

## 6. Playlist / Smart-Playlist / UI rules

- Playlist and Smart-Playlist folders preserve GMMP native rows, styling, parsing, actions and writers wherever those exist.
- Playlist Link is portable/fail-closed. Disabling it leaves old Smart Playlists openable and GoneSmart-only semantics inert.
- Playlist Link must share semantic/runtime GMMP boundaries with the accepted Playlist/Smart-Playlist stack. Historical 4.2.0 obfuscated names are fast-path evidence only; the leaf rule, presenter, parser, query builder, playlist DAO/model and Smart writer must be shape-validated and ambiguity must fail closed.
- A remapped Playlist Link UI action is not proven by an old obfuscated method name plus a matching signature. Correlate the real user-triggered native call passively (for example from the resulting native dialog/event stack) before promoting an intercept boundary.
- GMMP 4.2.1 Playlist Link is now passively correlated at the naturally triggered `as4$g.accept(...)` callback (`MaterialDialog.show()` stack). The lone `as4.h2(boolean)` candidate must **not** be treated as the old link action merely because its signature matches 4.2.0.
- When GMMP's native Smart-rule evaluator already owns operator semantics, prefer compiling an accepted neutral native rule through that evaluator over separately mapping an ambiguous obfuscated query-helper operator. Do not guess among structurally indistinguishable helpers merely to manufacture a fail-closed predicate.
- Playlist Link must not depend on recovering an R8-renamed `track_uri` constant. On GMMP 4.2.1, source playlist file paths are resolved to library Track IDs through the already verified read-only GMMP database bridge. The native Track-ID query-field object is recovered from the already compiled fail-closed native Track-ID predicate when uniquely inspectable; if that object remains opaque, the native Smart-rule evaluator supplies bounded Track-ID equality predicates instead. Historical query-field holder names remain diagnostic evidence only, ambiguity fails closed, and optional OR grouping stays lazy.
- Playlist Link dispatch must not rely solely on an after-inflate `MenuItem` listener because GMMP may replace it later. GMMP 4.2.0 keeps the accepted exact `ds4.g2(boolean)` boundary; GMMP 4.2.1 intercepts the proven chooser callback and resumes that exact captured callback/payload for the native Smart-Playlist option instead of guessing an upstream obfuscated method.
- Playlist Link native chooser callbacks must honor the host dialog ABI. If the callback return type is erased, derive the concrete return singleton from the native event constructor's retained generic callback signature when available; accepted-version class aliases are fallback evidence only and must still satisfy the singleton shape. Never substitute an unrelated Kotlin/obfuscated singleton merely because it has the same static-self pattern.
- Playlist Link is not accepted on a new GMMP mapping until one bundled device pass verifies **add → save → reopen/edit → evaluate/play** and then changes the linked source playlist once to prove that membership remains live rather than copied.
- Multi-selection changes only the intended add/move/create action.
- UI extensions follow GMMP's live theme/navigation surfaces rather than fixed assumptions where possible.
- Attached but offscreen ViewPager pages are effectively idle.
- Prefer native adapter/scroll/layout events over polling.
- Coalesce visible-row synchronization to at most one posted animation-frame update per burst.
- Full decor/view-tree scans are bounded recovery paths, not normal layout work.
- Temporary `isShown == false` during a pager transition does not invalidate a verified native anchor; detach/replacement/semantic mismatch does.
- Cache class-level reflection and drawable/glyph analysis used by persistent badges.

## 7. User-facing notification contract

- A popup/Toast should exist only when it confirms an action, communicates a failure/fallback, or prevents an otherwise confusing native result.
- Prefer GMMP's complete localized native phrase when one exists.
- GoneSmart-owned notices may use concise English fallbacks.
- **Never use a checkmark, warning icon or other symbol as the entire fallback message.** Success/failure must remain understandable as text (for example `Track Auto-DJ started`, `Playlist moved`, `Error`).
- 0.4.0 acceptance: Track Auto-DJ confirmation uses GMMP's native `started` phrase when available and readable `started` fallback otherwise; Queue Flip success is `Queue reversed`. Checkmark glyphs in `MainActivity` are visual UI decoration, not popup fallbacks.
- Suppression of native Toasts/Snackbars must be narrowly scoped, one-shot/bounded and limited to known duplicate or misleading transitional messages.
- Do not suppress unrelated GMMP notifications.

## 8. Status UI contract

The Home status UI is one large Status card. Aggregate health uses the worst state (red > amber > green), followed by four full-width sections:

- GMMP: missing red / installed-not-running amber / running green.
- Xposed: unavailable red / unsupported API amber / supported API green.
- GoneSmart: stopped/unavailable red / degraded amber / healthy green.
- Compatibility: missing/unknown red / installed untested amber / tested 4.2.1 green.

`GmmpCompatibilityPolicy.TESTED_VERSION` is the only tested-version source of truth.

## 9. Smart Auto-DJ performance invariants

- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up.
- Provider backoff applies to **unproductive** fills, not successful ones.
- Native queue insertion remains owned by GMMP's verified Auto-DJ refill path.
- Latency diagnostics distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time.
- Reuse the already passively proved current-position hint for queue snapshots; do not restart broad getter discovery on transient CURRENT ambiguity.
- Pre-action Queue/Room race barriers run off GMMP's main thread.

## 10. Logging and diagnostics

Normal logs record meaningful runtime decisions, resolver success/failure, verified mutation/rollback, Track Auto-DJ phases, provider/fallback decisions and user-visible failures.

Every device-debug build exposes the exact local Git revision through `BuildConfig.GIT_REVISION`; local uncommitted source is suffixed `-dirty`. Startup and feature-local Playlist Link diagnostics include that revision. Before interpreting a device failure or asking for another probe build, verify that a revision-bearing boundary reached by the captured flow matches the intended branch head. Package-filtered Logcat can miss early startup lines, so a later editor/presenter/dispatch marker is equally authoritative. An actual mismatch, or the absence of any revision marker from a boundary the flow demonstrably reached, leaves build identity unverified.

Broad class/method/recycler inventories are gated to unknown/failing compatibility boundaries. Do not hide native GMMP tags such as `w6`; remove GoneSmart-caused unnecessary work instead.

Accepted actions may retain one bounded postcondition check, not discovery-era polling loops.

## 11. Mutation safety

- Correlate native rows 1:1 with independent read state before mutation.
- Re-read immediately before a transaction when concurrent playback can change state.
- Use native Room writers and verify afterward.
- Retain rollback data until postconditions succeed.
- Never infer writer ownership from a historical class/field name alone.

## 12. Documentation and public communication

- `AGENTS.md`: standing collaboration/architecture rules.
- `docs/GMMP_COMPATIBILITY_PLAYBOOK.md`: version ledger and future-update procedure.
- `docs/GMMP_421_COMPLETION.md`: concise final 4.2.1 / 0.4.0 acceptance state.
- `docs/NATIVE_GMMP_AUDIT.md` and dated `GMMP_421_*` files: historical reverse-engineering evidence.
- `docs/ROADMAP.md`: public planned direction for upcoming releases; keep it concise and user-facing.
- `docs/forum/`: copy-ready GMMP forum thread/release BBCode. `THREAD_START_TEMPLATE.bbcode` is maintained source; `THREAD_START.bbcode` and `LATEST_RELEASE_REPLY.bbcode` are generated by `scripts/generate_forum_posts.py` and must not be hand-edited.
- Feature docs: detailed user/developer behavior.

Public release notes, the README and roadmap should explain **what a feature does and what the user gains**. Keep implementation detail at a high level (for example, "more dynamic hooks improve robustness for future GMMP versions"). Obfuscated names, resolver internals, queue-writer mechanics and similar reverse-engineering detail belong in the compatibility/audit/development docs, not the GitHub Release body unless required to explain a limitation.

Public-facing compatibility/support wording must remain accurate:

- GoneSmart is an **independent project** and is not affiliated with GoneMAD Software.
- Before a suspected GMMP bug is reported upstream, users should disable GoneSmart and verify that the issue is reproducible without GoneSmart. Issues that only occur with GoneSmart enabled belong in GoneSmart's issue/support channels.
- LSPatch is currently an **experimental, untested-by-the-maintainer** path. Do not soften this to "less tested" unless real maintainer testing has actually happened.
- Names such as Spotify, YouTube and YouTube Music are only **examples of possible future recommendation-provider directions** until feasibility is evaluated and a provider is explicitly accepted for implementation. Do not present them as committed/planned integrations.
- A small patch following a major feature release should say so at the top of its release notes and prominently point users to the preceding major release, so the major feature set remains visible on GitHub's Latest Release page.

Keep chronology out of `AGENTS.md`; preserve it in the audit/evidence docs.

## 13. Release completion

A release candidate is complete only when:

1. intended behavior is device-accepted or a limitation is explicitly documented;
2. exact-head normal CI is fully green;
3. temporary probes/workflows/scripts are absent from the release tree;
4. tests cover durable resolver/policy behavior;
5. `AGENTS.md`, the compatibility playbook, completion matrix, README and release notes agree;
6. release-facing docs describe the accepted/tested version and shipping state rather than stale development-branch or pre-acceptance wording;
7. public release notes prioritize features, benefits and user-visible improvements over implementation internals;
8. no enabled unsafe guessed writer remains;
9. accepted-version diagnostics impose no obvious persistent hot-path cost;
10. the merged `main` head is green before tagging/publishing;
11. GMMP forum copy is current: update `THREAD_START_TEMPLATE.bbcode` when public features/compatibility change, regenerate via `python3 scripts/generate_forum_posts.py`, and review the generated first-post and release-reply BBCode;
12. the release workflow publishes versioned forum BBCode assets and keeps the copy-ready `docs/forum` outputs synchronized for manual main releases.

After 0.4.0, new features should start on a normal named feature branch from the chosen clean `main` integration point.


## r31 device-proven follow-up rules

- GMMP 4.2.1's accepted direct read-only Room query carrier is parameter-free unless non-zero argument-count ownership is uniquely proved. Playlist Link resolves ordinary-playlist file membership by building a bounded cached `song_id`/`track_uri` index with a parameter-free read and matching canonical paths in GoneSmart. Never guess R8 argument-count/binder fields.
- Track Auto-DJ pre-Play preparation must not assume `qr` was constructed after GoneSmart hooks installed. Recover the live accepted `qr` passively from natural verified state-writer calls and, if needed, from the bounded context-menu object graph. Discovery never sends a playback/Auto-DJ command; zero or multiple candidates fail closed.
- Smart-editor labels distinguish native Smart-Playlist references from GoneSmart ordinary-Playlist bridge references: native Smart references use the host Smart-Playlist noun; bridge references use the host Playlist noun.


## r32 device-proven follow-up rules

- GMMP 4.2.1 invokes Playlist Link Smart-rule compilation on the main thread. That synchronous evaluator must never perform a Room/Cursor query. Resolve ordinary-playlist membership only against GoneSmart's already prewarmed immutable GMMP library snapshot; if that snapshot is unavailable, fail closed without blocking the UI.
- A Track Auto-DJ refill is owned by the exact `qr` instance already verified/captured by `TrackMixController`. Pass that receiver explicitly into `qr.z(int)`. Natural child/state-writer callbacks such as `dx3` must never overwrite the module's Auto-DJ reference or become a refill receiver.
- Linked-rule label formatting is read-only UI behavior. On accepted 4.2.x mappings, discover all shape-valid `SmartRule -> String` formatter methods across the bounded known formatter families rather than trusting one obfuscated method name; only rewrite the returned linked-rule label and leave GMMP's rule model untouched.

- r32 performance guard: the complete `track_uri -> song_id` index is constructed together with `GmmpLibraryReader`'s startup/cache refresh off the UI thread. Smart-rule compilation may only consume the finished immutable index; it must not scan/canonicalize the whole library synchronously.


### GMMP 4.2.1 r33 Track Mix current identity + Smart rule row labels
- r32 device evidence proves the selected song can already be playing/decoded while the Smart-Playlist native list is still materializing. Once Track Mix has uniquely observed the selected native `queue_id`, that immutable queue-entry identity is the authoritative temporary CURRENT hint through list completion, initial `qr.z(int)` refill, prune and final verification. Mutable `queue_position`/R8 state remains secondary evidence; a missing or duplicated queue entry never permits guessing by track ID alone.
- The 4.2.1 Track Mix queue mutation remains prune-only after native materialization: preserve the verified CURRENT row and `Initial Size - 1` earliest upcoming rows, including `current+1`; never use `CLEAR_QUEUE`, never post-retarget a materialized upcoming Track ID, and never restore the retired same-position refresh without new playback evidence.
- Smart editor type labels are presentation-only at the exact host `smartRuleListRecyclerView` row-bind boundary. A row may be relabeled from the host `Playlist: <name>` to the host-localized `Smart Playlist: <name>` only when the bound model is uniquely proven to be a native Smart-Playlist reference. GoneSmart ordinary Playlist bridge references always retain `Playlist: <name>`. Do not install a global TextView rewrite or guess another R8 formatter alias.


### GMMP 4.2.1 r34 native label parity + zero-pool Track Mix source window
- Smart-rule relabeling at `smartRuleListRecyclerView` must preserve GMMP's original `CharSequence` spans. Replace only the proven `Playlist` prefix inside a copied `SpannableStringBuilder`; never replace the whole styled label with a plain `String`, and never mutate TextView gravity, text size, typeface or layout parameters.
- r33 device evidence proves a Smart-Playlist Play may start the selected audio on a short-lived queue entry and then delete/recreate that `queue_id` while materializing the full Smart Playlist. A disappearing selected `queue_id` is therefore not proof that playback failed.
- For GMMP 4.2.1 Track Mix requests with a genuinely empty pre-Play GoneSmart recommendation pool, the accepted `MusicService.w1(action=0)` source-list boundary may be bounded *before queue materialization* to the uniquely pre-resolved selected track plus at most `Initial Size - 1` following native source rows. Apply this only while the exact request is `WAIT_PLAY`, only when the native source is larger than Initial Size, and only when native `getId()` maps the target exactly once. Missing/duplicate target or unsupported row shape leaves the native source untouched (fail closed).
- A request with any prepared GoneSmart Track IDs must not use the r34 source window; it retains the device-accepted r33 bootstrap/refill path. The r34 source window is pre-materialization source shaping, not a post-materialization queue Track-ID retarget. `CLEAR_QUEUE`, post-retarget of `current+1`, and the retired same-position playback refresh remain forbidden.

## GMMP 4.2.1 r35 Track Mix CURRENT identity
- r34 device evidence proved the BUNT. context-track Play does not traverse the `MusicService.w1(action=0, List, ...)` list boundary: `prepared=0` was observed but `MIX NATIVE PLAY WINDOW` never fired. That source-window experiment is retired and must not be revived as a Track Mix dependency.
- On 4.2.1 a Smart-Playlist rebuild may replace the selected row's native `queue_id` while the already-proven selected audio keeps playing. After Track Mix has first proven CURRENT by native queue entry, and only inside the existing Smart-source completion guard, a vanished entry hint may rebind CURRENT to the pre-resolved exact `track_uri` iff that exact path occurs exactly once in the rebuilt native queue.
- The exact-path rebind is request-scoped, one-shot, case-sensitive, and non-normalizing. Zero or multiple exact path matches fail closed. Track ID alone never authorizes the rebind. Once accepted, the replacement native `queue_id` becomes the new pinned identity for settle/prune/Auto-DJ verification.
- The accepted r34 Smart-Playlist row-label behavior remains invariant: preserve GMMP's native styled `CharSequence` and replace only the localized prefix; never mutate row gravity, text size, typeface, or layout.

## GMMP 4.2.1 r36 zero-provider Track Mix fallback
- r35 device evidence accepted the exact-path CURRENT rebind: BUNT. moved from temporary `queue_id=5680869` to rebuilt `queue_id=5680876`, then reached `MIX PLAY VERIFIED`, `MIX CURRENT PIN`, `MIX AUTO-DJ` and `MIX VERIFIED`. The remaining defect was downstream: pruning 7,214 source rows to five retained absolute CURRENT position 6 (`6 of 5`) and retained four source Smart-Playlist followers because the prepared GoneSmart pool was empty.
- A genuine `prepared=0` Track Mix must not treat arbitrary source-list followers as its four Initial-Size Auto-DJ tracks. After the rebuilt exact CURRENT is stable, r36 keeps only that pinned native row, rebases it to position 1 through a Track-Mix-only position writer that was first observed during natural GMMP state calls, then lets the native `qr.z(count)` refill own the replacement Track IDs. Prepared GoneSmart bootstrap paths are unchanged.
- A same-value natural state-writer call is only a provisional Track Mix candidate. Queue Flip does not consume it. The first controlled rebase write must independently verify both GMMP's native position signal and a unique queue row at the new position before the writer is promoted. Ambiguity fails closed.
- r36 does not guess an obfuscated next-source method. During the natural zero-provider start it records a bounded set of `MusicService(Int)->void` calls; once the transient selected CURRENT is independently known, exactly one call may be promoted iff its observed argument equals `currentQueuePosition + 1`. After native refill and position compaction, that same naturally observed boundary is invoked for position 2 so GMMP rebuilds `current+1` from the new native Auto-DJ row instead of retaining the already-cached Smart-Playlist AudioSource. Multiple/stale candidates fail closed.
- `CLEAR_QUEUE`, post-materialization Track-ID rewriting, and the retired same-position CURRENT refresh remain forbidden.

