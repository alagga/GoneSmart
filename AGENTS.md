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
- Isolate the exact native Queue entity through proven GMMP writers and verify that the seed remains Current.
- GMMP Initial Size includes the seed; request exactly the missing count through native `qr.z(count)`.
- Recommendation preparation must not strand playback on a one-row queue. Give Smart DJ only a short bounded head start; native GMMP may fill immediately while the same pool fill continues in the background.
- Track-Auto-DJ-owned normalized queues use the accepted event-driven continuation repair: on a verified natural CURRENT transition, read remaining rows once and invoke `qr.z(deficit)` only when GMMP's configured upcoming count is not satisfied. No polling and no guessed append allocator.

## 6. Playlist / Smart-Playlist / UI rules

- Playlist and Smart-Playlist folders preserve GMMP native rows, styling, parsing, actions and writers wherever those exist.
- Playlist Link is portable/fail-closed. Disabling it leaves old Smart Playlists openable and GoneSmart-only semantics inert.
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

- Normal GMMP playlists are a first-class local recommendation signal: use read-only indexed playlist identities, cache membership by file fingerprint, weight focused playlists more strongly than huge catch-all lists, and reward independent playlist co-occurrence. Smart Playlists are not part of this signal unless a future explicitly weaker opt-in policy is implemented.
- Smart recommendation mode is the default and owns conservative stable-provider enablement/weights. Custom mode may expose provider enablement, bounded ranking weights and playlist-size weighting, but provider request order is not a user preference.
- Independent network providers should run in parallel inside one deterministic pool-fill pipeline when this reduces latency. Weight/ranking priority must not be implemented by serially delaying one enabled provider behind another.
- Provider corroboration is evidence: after provider/seed deduplication, independent providers confirming the same logical track may receive a bounded bonus. A local playlist signal must never bypass the normal local-library quality/rating/duplicate gates.
- Final selection may add controlled variety only inside bounded near-equal score buckets. Do not add global random score noise that can promote a clearly weaker candidate over a stronger one; Smart mode uses the balanced policy and Custom may expose Off/Balanced/High.
- Revalidate large-library caches with cheap read-only revision signals before repeating an expensive full-library scan; retain a bounded hard refresh for metadata changes the revision signal may miss.
- Recommendation-side GMMP reads must not require one obfuscated TrackDao field or method. Tested R8 names may remain optional fast paths, but the structural read-only Room/Cursor resolver and semantic playlist-catalogue discovery must remain sufficient and fail closed on ambiguity.
- Keep the recommendation pool ahead of GMMP's visible queue through background prewarm/low-water top-up, including local-only prewarm when the network is unavailable.
- Provider backoff applies to **unproductive** fills, not successful ones.
- Native queue insertion remains owned by GMMP's verified Auto-DJ refill path.
- Latency diagnostics distinguish queue-read-before, pool hit/fill, provider rounds/playlist affinity, native refill, queue-read-after and total time.
- Reuse the already passively proved current-position hint for queue snapshots; do not restart broad getter discovery on transient CURRENT ambiguity.
- Pre-action Queue/Room race barriers run off GMMP's main thread.

## 10. Logging and diagnostics

GoneSmart has two deliberate diagnostic layers and every feature audit must review both:

- **Companion Logs tab:** bounded, copyable, high-signal support history. Record meaningful state changes, verified user actions, fallbacks/degraded states, feature-unavailable conditions and failures with enough context to identify the broad failure class. Routine per-track/per-refill success, candidate lists, reflection inventories and other hot-path detail do not belong here. Keep categories, duplicate suppression, retention, Copy/Clear behavior, Help copy and the displayed Logcat-filter guidance current as features evolve.
- **Android Logcat:** detailed developer diagnostics. Normal logs record meaningful runtime decisions, resolver success/failure, verified mutation/rollback, Track Auto-DJ phases, provider/fallback decisions and useful latency summaries. Accepted-version normal logs use compact summaries rather than per-candidate recommendation/result dumps. Stable GoneSmart-family tags should make feature-specific filtering practical; whenever tags are added/renamed/retired, update the companion Logs/Help guidance in the same change.

Broad class/method/recycler inventories and detailed candidate/result dumps are gated to unknown/failing compatibility or explicit debug boundaries. Do not hide native GMMP tags such as `w6`; remove GoneSmart-caused unnecessary work instead.

Accepted actions may retain one bounded postcondition check, not discovery-era polling loops. The companion Logs timeline is the first support artifact for bug triage; filtered Logcat is the second-level artifact when the high-level event history is insufficient.

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
- `docs/FEATURE_AUDIT_CHECKLIST.md`: mandatory feature-closeout checklist. Run it before calling a feature complete or integration-ready; every feature audit must explicitly review companion Logs + Logcat diagnostics and record its Localization/I18N outcome, including **no new translations required** when applicable.
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
10. the feature-closeout code-hygiene pass removed obsolete probes/debug dumps/dead helpers/stale comments and left no temporary infrastructure in the release tree;
11. the companion Logs/support timeline and Logcat diagnostics were audited for signal, noise, failure coverage, current categories/tags and up-to-date filter/help text;
12. the merged `main` head is green before tagging/publishing;
13. GMMP forum copy is current: update `THREAD_START_TEMPLATE.bbcode` when public features/compatibility change, regenerate via `python3 scripts/generate_forum_posts.py`, and review the generated first-post and release-reply BBCode;
14. the release workflow publishes versioned forum BBCode assets and keeps the copy-ready `docs/forum` outputs synchronized for manual main releases.

After 0.4.0, new features should start on a normal named feature branch from the chosen clean `main` integration point.

- Provider artist fallbacks are a rescue stage, not a default per-provider tax: first evaluate Playlist Affinity plus direct track/recording recommendations locally, then run artist-level provider expansion only when unique local coverage is below a reserve scaled from GMMP Initial Size / Upcoming Tracks and a bounded fraction of the internal pool target. Prefer one sufficiently rich first pass over repeated closely spaced provider rounds.
- A partially populated GoneSmart pool is valid. If GMMP requests more tracks than GoneSmart currently has suitable candidates, return only the suitable GoneSmart rows and never pad the remainder with untouched/native random rows. Treat a partial successful fill as scarcity: suppress low-water background provider top-up while useful partial-pool tracks remain, then allow a fresh refill after they are consumed. Native fallback for recommendation scarcity is reserved for the zero-suitable-track case according to the fallback policy.
- `Prevent Version Duplicates` groups the classic base-version family (Original/Radio/Extended/Club) while leaving remixes independent. `Prevent Remix Duplicates` is a separate opt-in setting, default off, that blocks remix-vs-other-version conflicts for the same normalized artist + base title in either direction.
