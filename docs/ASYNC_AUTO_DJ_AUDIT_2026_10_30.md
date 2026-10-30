# Async Track Mix / normal Auto-DJ: feature audit (2026-10-30)

**Scope:** current `feature/async-track-mix-playback` compared with `AGENTS.md`,
`GMMP_COMPATIBILITY_PLAYBOOK.md`, and `FEATURE_AUDIT_CHECKLIST.md`. Integration
baseline: `95a568d0`, containing `fix/audio-profiles-device-identity` and its
native single-flight recommendation fix. **Status: pending exact-head CI and
device re-acceptance for extra Next replay.** This is not a release audit.

| Check | Result / evidence |
| --- | --- |
| Behavior / ranking | Track Mix uses the existing native `PLAY_FILE` and `ENQUEUE_FILE` intents; normal cold refill starts the original full-quality pool search asynchronously and then invokes native `qr.z(int)`. No early match cut-off or changed score/provider selection. |
| Concurrent search / duplicates | Session coalescing and the merged normal Auto-DJ `AutoDjRefillSingleFlight` remain distinct. Track Mix's native bootstrap is explicitly excluded from the normal single-flight guard. Existing queue-entry/Track-ID duplicate postconditions retained. |
| Native mutation / fail closed | No new GMMP Queue/Room writer. The deferred retry reuses the same already-hooked native `Method` rather than independently resolving an R8 name. Extra UI Next uses the documented public GMMP broadcast and only after native additions, same-session/current/next checks. An unrecognized View ID, missing native addition or session change does not trigger replay. |
| Compatibility / dynamic boundaries | Framework `View.performClick`/resource lookup is not R8-name-dependent. The native `qr.z(int)` and `kr.F1(int)` fast paths are **tested GMMP 4.2.1 aliases**, now validated for their expected method shapes; selection has a unique structural fallback. No safe semantic proof for an arbitrary future renamed refill is available: it **must fail closed** until re-proved on that GMMP version. The Next view ID also needs device confirmation; unknown names are skipped. Future-proof operation cannot be guaranteed across every GMMP release. |
| Playback safety | The first native Next transition is never blocked. Extra Next replay is bounded to four user UI clicks, with at most two Current reads between replay commands. Any changed queue session, lost Current or no upcoming item stops further replay. No decoder/native Current-position method replay or new source rewrite. |
| Cost / hot paths | No recurring recommendation poll or global view-tree scan. Existing background pool fill remains one per session. The new View hook performs one cheap pending-request check on ordinary clicks; resource classification happens only during an active cold fill. Native queue reads for replay happen off-thread, only for user-demanded extra Next events. |
| Localization / UI | **No new translations required.** No new user-visible GMMP or companion text; no Toast suppression changes. Existing native localization and textual Track Mix Started confirmation untouched. |
| Companion Logs / Logcat | Existing bounded Logs-tab runtime reports and failure/fallback events preserved. Added one-off deferred Next queued/replay/cancel markers under the existing `GoneSmart` Logcat tag, with no new tag family or filter/help/category updates. No routine per-track companion Log entry. |
| Hygiene | No temporary branches, workflows, probes, direct SQL writes or new Settings/Help surfaces. New pure policy and focused JVM tests cover click-name gating, bounded counters, native-addition evidence and session cancellation. |
| Documentation | `AGENTS.md` standing invariant, compatibility ledger and normal Auto-DJ experiment doc updated. README, forum copy and release notes remain unchanged because this branch is not device-accepted or released. |
| Validation | Integration baseline `95a568d0` passed exact-head Build before this change. Local Gradle cannot create `/root/.gradle` wrapper lock in the sandbox; new head requires GitHub Build (unit tests, Debug APK, unsigned Release smoke, artifact). Device tests remain necessary for real Next view identity, replay semantics, manual-queue race and screen-off transitions. |

## Remaining device scenarios

1. Start a cold normal Auto-DJ pool on the last track. Verify first Next
   releases native playback promptly; press Next again before pool fill ends.
   Check `SMART DJ DEFERRED NEXT | queued=1` then `replay=1/1`; exactly one
   real player transition after native additions, no duplicate generated rows.
2. Two/three extra GMMP player Next clicks: one transition per click, stop if
   no upcoming rows remain. Test screen off and headset Next separately;
   currently not covered by UI-only identification.
3. Manually enqueue while the same pool is loading: retain user tracks;
   clicking Next to a manual track must not cause an extra automatic skip.
4. Start a wholly new playback during the pending window: no old generated
   rows or stored Next presses may apply to the new session.
5. Track Mix start/zero-provider native continuation, Smart Playlist play,
   ordinary warm-pool refill and repeated normal refill remain unchanged.

The prior `2026-10-30 16:35:56` device trace confirmed cold refill returning
in approximately 33 ms and native additions approximately 1.8 seconds later;
it was collected **before** this Next replay code and does not validate the
new input-observer path.
