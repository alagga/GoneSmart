# Normal Auto-DJ: deferred cold-pool native refill (GMMP 4.2.1)

**Branch:** `feature/async-track-mix-playback`. **Status:** experimental;
device acceptance pending.

## Motivation and architecture

The 2026-10-30 trace showed a normal native `qr.z(int)` refill blocking a
playback transition for 23.74 seconds (about 19.4 seconds in an unproductive
ListenBrainz request). The actual native insertion took only 11 ms. The
separate Smart Playlist UI delays showed GMMP list queries rather than a
GoneSmart recommendation pipeline and are not modified by this experiment.

When a normal Auto-DJ refill encounters an empty smart recommendation pool,
GoneSmart now starts the **unchanged full-quality** recommendation search but
returns to GMMP immediately so its playback transition may complete. Once
the fill finishes (up to the existing 60-second bound), a worker invokes
the **same native `qr.z(int)` method** against the captured GMMP owner. Its
existing selection hook handles smart matches, native fallback and stops.

Only one pending native refill exists per session; duplicate requests merge
into the largest requested count. Before native retry, a fresh native queue
snapshot must prove the original queue session still exists with at least
one retained queue-entry identity. Manual enqueues and skips within that
session preserve pending work; wholly new playback/Track Mix and disabled
Smart DJ cancel it. No direct Room writer, separate playback engine, new
recommendation heuristics, Intent-based insertion or permanent polling is
introduced. The `PLAY_FILE`/`ENQUEUE_FILE` Track Mix start is unchanged.

The separate `fix/audio-profiles-device-identity` native single-flight guard
is retained after integration. It protects the short synchronous selection
and queue-writing window even when a pool is already warm. The deferred
session-request coalescer handles the long background waiting window instead;
both guards serve different stages and must remain in place.

### 2026-11-10 playback regression: joining an overlapping warm refill

Two 5-MiB Logcat snapshots from the exact tested `e678933` debug APK
independently caught the same race with an already-warm pool:

- At 17:33:41.356 GMMP's playback worker entered `qr.z(1)` while an early
  GoneSmart refill was selecting a pool hit. The native call was immediately
  coalesced, and `MusicService` cached `next audio source is null` at
  17:33:41.358. The correct `Only You` queue row was appended at 17:33:41.368,
  ten milliseconds **after** that failed lookup. At the following transition
  (17:37:40), the now-current `Only You` entry existed and another follower
  was inserted, but the 1.8/4.3-second audio probes reported inactive audio.
- At 21:51:57.425 the exact same overlap occurred during managed Track Mix
  continuation. Native `next audio source is null` followed at 21:51:57.426;
  the correct `We Found Love` row appeared at 21:51:57.504. The subsequent
  21:55:22 transition again showed valid Current/upcoming rows but inactive
  audio in both probes.

This is not recommendation scarcity, a failed queue write, or a stalled UI:
the existing single-flight guard let the *second*, playback-critical `qr.z`
call return before the *first* native enqueue committed. Correct later queue
postconditions could not retroactively prepare GMMP's cached AudioSource.

The candidate repair lets a coalesced off-main-thread native `qr.z` caller
**join the existing single-flight completion** (up to 1,500 ms, event-driven)
before allowing the original native caller to resume its next-source lookup.
It does not invoke a second refill, change provider searches, rewrite queue
rows, replay transport commands or delay the main thread. Same-thread
reentrancy is never joined; slow/cold fills time out instead of blocking the
existing 60-second provider search. The original fast native writer remains
the sole enqueue owner. Logs include `join=COMPLETED` / `TIMED_OUT` and
`waitMs` to distinguish verified coordination from another missed window.

**Device re-acceptance required:** repeat multiple natural transitions with
normal Auto-DJ and managed Track Mix when the pool is warm, including screen
off and rapid Next. The relevant overlap should now log `join=COMPLETED`,
the original native next-source lookup should return a real file, and the
following track must actually play. Also verify that long provider fills,
main-thread calls and new sessions remain responsive. A successful build or
queue verification alone does not prove audible playback.

### Extra Next presses at the empty tail

The first native Next still completes immediately, including when it starts
the cold fill. While that fill is pending, a second or later **actual GMMP
player Next UI click** may have nothing to advance to. GoneSmart passively
observes `View.performClick` for a narrow list of Next-named native resource
IDs and lets the original click execute. Only a successfully handled click
while the same fill is pending increments a bounded counter (maximum four).

After the verified native refill completes, a background worker checks the
queue session, original queue-entry identity, newly appended native entries,
current playback ownership and existence of an upcoming item. When all checks
hold, it dispatches GMMP's documented public `gonemad.gmmp.command.NEXT`
broadcast for each extra pending press; between consecutive commands, no more
than two delayed native-current readbacks establish the previous advance.
No GMMP decoder, position setter or queue writer is invoked by this replay.
New playback, missing native additions, missing/ambiguous Current and failed
advance cancel the remaining requests. Manual queue additions still remain
at their native position ahead of later generated rows.

### End-of-queue continuation on a cold pool (2026-10-30)

The `b8bebcc29445` device snapshot recorded `End of queue reached` at
20:08:32 and a deferred native `qr.z(4)` in ~31 ms. At 20:08:46, after
14.75 seconds of full-quality recommendation preparation, GMMP inserted
four correctly selected rows, but playback had already ended and did not
start the new queue position 2. This is a playback-continuation race, not
a failure to find or insert recommendations.

The worker may now emit **one** public GMMP `NEXT` only for a refill that
began with no upcoming queue entry and inactive audio, then still has the
same exact Current queue entry, unchanged recommendation session, verified
new native queue rows and an upcoming song after refill. A 250 ms grace
period allows GMMP to resume normally; any new active audio, changed Current,
missing host Context, new session or separately recorded manual Next cancels
the synthetic continuation. No native decoder, Current writer or SQL writer
is called. AudioManager's audio-inactive signal is only a conservative guard;
paused playback and external output routes need device validation.

### Deferred native invocation lock discipline (2026-11-01)

Native GMMP `qr.z(int)` is now invoked **outside** GoneSmart's global deferred
refill lock. Previously the asynchronous worker held that lock while executing
GMMP code, which could in principle contend with an on-screen native Next
callback taking the same lock. Ownership/requested-count is captured while
locked, native work runs unlocked, then the pending identity is checked again
before considering any replay. The actual deadlock is **not proven** by the
new device snapshot: its retained Logcat covers only 13:58:25–13:58:45 and
shows successful `current+1` refill at 13:58:27 (65 ms), not the reported hang.
If the issue recurs, start continuous recording before playback stalls; the
absence of the problematic moment is not evidence that the host was healthy.

**Acceptance limitation:** the exact resource ID of the player's Next view
has not yet been observed on a test device. Only recognized visible GMMP
player buttons are covered; Bluetooth/headset/notification Next and unknown
view IDs currently follow GMMP without deferred replay. A broad global
key-event/control-method guess is intentionally not introduced. The next
device log must confirm `SMART DJ DEFERRED NEXT | queued=...` then `replay=...`.

## Real-device tests required

1. With a newly made two-track queue and cold pool, skip to the second/last
   track. Playback should advance *before* the provider search completes,
   followed by native Auto-DJ queue additions with no duplicate tracks.
2. Verify natural transition and next track decoder with screen on/off;
   inspect for cached `next audio source is null` even when queue looks good.
3. Press the actual GMMP player Next control once more while already on the
   last song and the pool is still loading. Expect exactly one queued replay
   after native additions; test two rapid extra presses, upper bound and
   missing native next rows. Capture resource name and actual decoder change.
4. Manually enqueue during search (manual tracks retained, smart appended).
   Press Next after a manual enqueue: a click that already advances to that
   manual track must **not** be replayed. Start a new playback during search
   (old recommendations and pending Next presses discarded).
5. Confirm fallback enabled/disabled, offline, repeated refill requests and
   Track Mix regression behavior. Verify hardware/headset Next remains native.
6. Let a cold final song end naturally, then verify the `SMART DJ DEFERRED EOF`
   candidate/resume/currentAdvanced/audioActive markers and actual decoder.
   Pause the final track, start new playback, enqueue manually, and re-test
   screen-off behavior: none may cause a spurious automatic skip.

## Audit

- Native first: unchanged accepted `qr.z(int)` selection/insertion hook; no new
  writer. The delayed invocation now reuses the *already hooked `Method`*,
  rather than performing a second hardcoded `getDeclaredMethod("z")` lookup.
- Hook compatibility: `View.performClick` and the public Next broadcast are
  stable framework/GMMP contracts. The Next view resource-name allowlist is
  only a conservative tested-shape candidate, **not a future-version proof**;
  unknown names fail closed. The original native `qr.z` installation still
  requires a verified binding for each new GMMP version; R8 names are not
  semantic guarantees. No claim is made for untested GMMP releases.
- Performance: no cold-pool provider wait on GMMP playback call stack; only
  an event-triggered one-shot worker. Pending click detection is a cheap null
  check for normal UI calls; native refill never holds the GoneSmart deferred
  global lock. Limited readbacks occur only after requested
  replay. Match scoring and pool sizing unchanged.
- Diagnostics: existing `GoneSmart` Logcat tag logs deferred scheduling,
  coalescing, cancellation, native retry with wait time, queued Next and
  replay/abort. No new tag/filter or routine companion events; existing
  companion fallback events remain, and failures still use the existing
  fallback/runtime reporter path. No new Help filter strings or categories.
- Localization: **no new translations required**; no new GMMP UI strings.
- Hygiene: no temporary branch, runtime scan, poller or alternate queue writer.
- Device validation and exact-head CI still required; not release-ready.
