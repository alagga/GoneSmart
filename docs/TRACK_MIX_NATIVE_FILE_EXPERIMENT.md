# Experimental native-refill Track Mix (GMMP 4.2.1)

**Branch:** `feature/async-track-mix-playback`. **Status:** host CI and device
acceptance required; do not merge to the 0.5.0 fix branch yet.

**2026-11-11 device regression / revised candidate:** The initial smart-fill
experiment with **only** a late `qr.z(needed)` was **device-disproven**. On revision
`6b08760`, GMMP queried and cached `next audio source is null` at 13:35:19,
11 seconds before the prepared pool completed. At 13:35:30 the verified native
`qr.z(4)` appended just three valid GoneSmart matches, but the second song
did not play; the following native AUTO_DJ command immediately requested the
missing fourth slot and added a GMMP random fallback after another cold search.
Successful queue mutation did not repair the early missing decoder.

The previous correction restored `PLAY_FILE` plus individually verified native
`ENQUEUE_FILE` commands (revision `6bec6f7`), but this does not share the
standard Auto-DJ native refill. The **current, unaccepted candidate** instead
uses `PLAY_FILE` plus a single post-search native `qr.z(needed)` append, checks
every new queue-entry identity against the prepared GoneSmart pool, and observes
the first native `MusicService.E2(service)`/`hn3.h0` source assignment. If that
source was actually null and no later native non-null source has recovered it,
invoke the **APK-verified same native E2 calculation** exactly once after a
verified append; accept only a non-null setter result and unchanged full queue.
This is a native calculation, not a guessed AudioSource swap, replay, extra
refill, arbitrary delay or direct Room write. A request-local exact-session/
Current/entry-ID check continues to hold only immediate native AUTO_DJ padding
when there are *some but not enough* smart followers; it releases on a real
Current/queue transition. Zero suitable matches still use the verified native
`qr.z` fallback (when enabled) and normal ongoing refills remain GMMP-owned.
The existing non-null source proof is **not** proof that the second song is
audible: fresh exact-head device acceptance is mandatory.

**2026-11-10 latest correction:** The speculative `qr.z` refill workers
described in the historical November 1–3 entries below have been retired.
They collided with real native `qr.z` on the playback thread and produced
cached null AudioSources despite successful subsequent queue insertion. The
natural Current observer now only assists the existing Track Mix zero-pool
transition; normal and managed ongoing refills occur at GMMP's own `qr.z`
boundary, with one controlled deferred native completion after a cold pool
search. The former 1.5-second join is removed. Audible device re-acceptance
is still required. Older sections describe historical experiments, not the
current design.

## Native workflow

1. Resolve a unique GMMP library Track ID/file path from the clicked song.
   Restrict the new route to the known `qr` runtime and absolute file paths;
   preserve the old prepared-predecoder path otherwise.
2. Immediately broadcast GMMP's documented `PLAY_FILE` with `filename`.
   Verify that the host created one Current row at position 1.
3. In the background, run the **unchanged full** selected-seed recommendation
   pipeline. Retain the original queue entry ID as a request-local ownership
   anchor, even if the user later appends or reorders manual queue entries.
4. When the full ranked pool is available, re-check the original anchor,
   rebind the pool to the real queue session and invoke the same native
   `qr.z(needed)` used by normal Auto-DJ. Verify each newly materialized
   queue ID/Track ID against the prepared pool, preserving the original Current
   and manual entries exactly. Do not replace already-materialized Track IDs.
5. If the original native E2 source assignment was null and never recovered,
   recalculate CURRENT+1 once through the verified native E2 method, requiring
   a non-null native setter and identical Current/queue readback. Then release
   the refill hold and enable GMMP's own `AUTO_DJ` mode. For a genuine smart
   shortfall, suppress immediate automatic native random padding until Current
   changes; subsequent refills remain native.

No direct Room queue insertion, guessed native writer, **post-materialization**
Track-ID retarget, AudioSource replay or parallel player is introduced.

## Session safety

- A manual Enqueue/Play Next or in-session reordering during the recommendation
  search **does not** cancel the mix. Generated titles go behind the live tail.
- Device log 2026-10-29 revealed that GMMP 4.2.1's structural CURRENT marker
  may be ambiguous after native queue mutation, even when the verified native
  position writer is available. Both pool-to-session adoption and native
  refill verification use the same position-hinted queue read. The former
  `ENQUEUE_FILE` route proved four rows in 2026-10-30 (`090f432`); those
  results are *historical evidence*, not acceptance of this E2/`qr.z` route.
- Native `AUTO_DJ` can post a delayed GMMP status Toast after the async
  worker has completed. The short post-command suppression grace is now kept
  for this path too; GoneSmart's own confirmation remains visible. Missing
  GMMP `started` string now falls back to readable `Started` instead of `✓`.
  Both UI changes await device verification.
- A new playback source normally replaces GMMP's native queue and removes the
  original seed entry. Missing original entry or ambiguous queue/Current
  identity aborts the native refill before invocation; inconsistent native
  postconditions abort further Track Mix actions without rewriting rows.
- If "remove after played" removes the original seed during the wait, we
  conservatively cancel instead of assuming this is still the same session.
- If recommendations are unavailable, only the configured native GMMP
  fallback may be enabled.

## 2026-11-01 device log follow-up

- `18:36` Re-Style bootleg: Last.fm supplied 24 remix-artist candidates,
  but only one recording matched the local library. The original-artist
  **seed** was still allowed into the local artist fallback, explaining
  why an unrelated Armin van Buuren recording could follow hardstyle.
  Local artist rescue now uses the explicitly parsed remixer identity. If
  exact local tracks are scarce, Last.fm's *artist community genre tags*
  also seed bounded `tag.getTopArtists` lookups, expanded into matching
  **local** tracks. ID3/MP3 genre tags are not trusted for this rescue.
- `21:35` playback stall: native Auto-DJ requested its next track only
  after reaching the last Current row (`upcoming=0`). GoneSmart supplied
  `Re-Style - Feel Alive` within 54 ms and GMMP reported a valid next
  AudioSource, but no subsequent audio start was observed. The accepted
  GMMP `qr.z(int)` refill is now requested *earlier*, off the playback
  thread, when a previously proven native Auto-DJ queue reaches fewer
  than two upcoming tracks. This safety margin does not alter the native
  player position, create a placeholder or force an audio restart.
- Both paths remain **device-test-required**: the recorded source is not
  proof the decoder started, and external genre API coverage may vary.

## Device acceptance (must be tested on GMMP 4.2.1)

1. Verify exact-head `BuildConfig.GIT_REVISION`; trigger Track Mix from Search,
   Queue, file browser and playlist contexts. The selected song must become
   audible *before* network/provider search finishes.
2. Wait for the **second** generated song to become audible at natural end;
   repeat with Skip and screen off. A visually correct native queue is not
   sufficient: specifically check for cached `next audio source is null`.
3. During search, manually Enqueue and Play Next one or more songs; confirm
   none are removed and generated songs appear later in the live queue.
4. During search, start an unrelated song with native Play/PLAY_FILE; confirm
   late GoneSmart recommendations are discarded, not inserted in the new queue.
5. Check shortage/zero matches, fallback enabled/disabled, duplicate
   candidates, queue reordering, a rapid second explicit mix request, and
   a GMMP-native `kr.F1` selection with fewer carrier rows than requested.
6. Confirm subsequent GMMP-owned native Auto-DJ continuation uses the
   maintained Smart pool and only the configured upcoming-track count. No
   passive CURRENT event may invoke its own speculative `qr.z`.
7. For Re-Style and other remixes, confirm `SMART DJ REMIX GENRE` and
   `SMART DJ REMIX GENRE LOCAL` log external style discovery and local artist
   matches. Confirm originals do not dominate the remixer's result set.
8. On the penultimate Current track of an established Auto-DJ session,
   confirm `SMART DJ WARM REFILL` appears before end-of-file, then verify
   actual audio starts on the following track after natural transition.

Until this is device-accepted, the experimental branch is not release-ready.
Native public file commands are documented by GMMP but a *late* native enqueue
refreshing a previously null next AudioSource has not yet been verified on
the maintainer's 4.2.1 installation.

## Feature audit

- Native first: PLAY_FILE/ENQUEUE_FILE/AUTO_DJ broadcasts own playback and
  insertion. No new low-level GMMP mutation bindings.
- Ranking quality: all normal providers, scoring, pool sizing and thresholds
  remain unchanged; surplus ranked candidates cover user-manual queue entries.
- Performance: one explicit worker request and bounded queue postconditions;
  no permanent background sampling or enlarged Logcat buffer.
- Diagnostics: compact `MIX NATIVE FILE` Logcat phases and companion status
  on completion/abort; no extra hot-path candidate dumps.
- Localization: no new GMMP-facing UI labels; companion messages remain
  English-only, as in the rest of that app.


## 2026-11-03 cold native fallback / warm continuity (device re-acceptance)

- The 2026-11-02 16:35 log captured `next audio source is null` before
  deferred native fallback inserted four GMMP tracks. If a verified
  `PLAY_FILE` seed produced **zero** GoneSmart candidates, call the
  already-verified native `qr.z` once on the worker **before** sending
  `AUTO_DJ`; do not invoke a second provider search in that exact
  native-fallback callback. Require a matching receiver, proven seed
  queue-entry and empty prepared pool. Check the appended queue afterward.
- Natural-track transitions in the 19:46 and 21:44 logs already reported
  `Marking as played` before the previous one-following-song prefill.
  During established/proven native Auto-DJ sessions, keep at least three
  upcoming real tracks when possible, refilling via the verified GMMP
  `qr.z` callback on the next natural Current event. Do not synthesize
  placeholders, replay audio or force Next.
- **Device acceptance is still necessary.** A present next AudioSource in
  Logcat is not proof that ExoPlayer started playback. Check whether the
  natural transition is actually audible and that native fallback creates
  tracks before any `next audio source is null` event.

## 2026-11-03 Smart DJ continuity correction

- The R83 log (`abfbef61`) shows a *cold GoneSmart pool* when a real
  `qr.z(1)` request arrived at 10:19:52. The request was deferred and GMMP
  recorded `next audio source is null` 3 ms later, 10.4 seconds before
  GoneSmart's network/provider search returned 20 suitable **smart** tracks.
  The later queue insertion alone cannot prove the decoder repaired its
  previously null next source.
- Commit `7386809` tried to let GMMP supply native fallback tracks at
  this boundary. **That behavior was explicitly rejected by the maintainer
  and is removed.** The Smart DJ remains a GoneSmart-ranked recommendation
  system, and GMMP native fallback is retained *only* when no suitable
  GoneSmart matches are found and the fallback setting permits it.
- Use the already verified natural-position callback to start the existing
  GoneSmart pool preparation before the final transition, while a previous
  song is still playing. Coalesce per-session background searches; never
  block the GMMP player thread, inject a placeholder, force Next or
  select a random GMMP track just because the pool is still cold.
- The 900 ms delayed warm refill from R84 remains guarded by a live native
  Current/session/queue check; it is not itself a replacement for early
  recommendation preparation.
- Device acceptance required: check real audio progression on multiple
  natural transitions, verify `SMART DJ INITIAL POOL PREWARM` occurs before
  the first cold `qr.z`, and confirm only GoneSmart `SMART DJ SELECT`
  tracks appear. Cold playback at the very first load may still require
  independent GMMP next-source refresh diagnosis.

## 2026-11-03 native tail-only refill correction (maintainer requirements)

The 10:19:52 GMMP request was `qr.z(1)`, with `initial=5` and
`upcoming=1`. The existing deferred worker correctly waited for one
GoneSmart provider fill (~10.4 s, 20 real matched candidates) before
executing the **same native qr.z(1) selection hook**. Subsequent
experiments introduced unwanted changes: prewarming providers after every
natural Current change (`acd65d4`) and maintaining three upcoming
tracks rather than GMMP's configured one (`afac4cf` onwards).

Maintain the original on-demand provider policy and single-flight deferred
worker. Observe GMMP's proven natural Current transition passively, but
request a refill ONLY once the last queue item becomes Current (not while
one or more followers remain). The requested native amount is exactly
`autoDj_upcomingTracks` (minimum 1), with no bonus buffering, synthetic
placeholders, random native GMMP selection, playback restart or forced Next
while audio remains active. On a cold smart pool the GMMP native refill
callback returns promptly; one in-flight GoneSmart pool search continues
in the background and the matching native refill resumes after it completes.
Any explicit native fallback remains restricted to the existing verified
zero-matches policy. Ordinary `PLAY_FILE/ENQUEUE_FILE` Track Mix is unchanged.

On-device acceptance remains required: while the last song begins playing
normally, the delayed refill should add `upcoming` GoneSmart-selected tracks.
If GMMP had already cached a null next AudioSource, also verify that the
next song actually starts; the queue alone is not proof of playback.

## 2026-11-03 queue postcondition follow-up

The R83 10:23:34 trace placed the next-source lookup ~714 ms after
natural Current, so the R85 900-ms warm-refill sleep could race **after**
GMMP had already cached a null next AudioSource. The extra sleep is removed;
the refill remains on its worker thread after the native position callback.

A successful `qr.z(int)` reflection invocation is also not proof of
successful queue insertion: while a cold recommendation pool runs, it can
return immediately without adding a row. Both ordinary warm and managed
Track Mix continuation now require a read-only queue postcondition before
marking that Current as refilled: preserved current-entry identity,
nonduplicated/contiguous queue positions, preserved future entries, and at
least one newly appended GMMP row (no more than requested). The managed
worker also rechecks the verified native Current writer to reject stale
position hints before invoking `qr.z`.

None of these checks insert placeholders, restart playback, add provider
calls, change GMMP's `upcoming` setting, or mutate the queue directly.
Queue verification is not decoder/device acceptance. On-device testing
must demonstrate actual audible playback and non-null next AudioSource.

## 2026-11-03 native GMMP fallback audit

A successful `qr.z` invocation is **not** proof that GMMP appended a valid
queue or that the player successfully prepared the next AudioSource.
The no-matches ordinary Smart DJ fallback and the single-seed
`PLAY_FILE` Track Mix native fallback now independently report a
read-only postcondition: stable Current entry and track ID, unique
queue-entry IDs, contiguous positions, unchanged existing future rows,
and exactly the requested number of newly appended GMMP tracks.
The exact returned Track IDs and the reason for any failed/partial
postcondition are logged. The previously unavailable queue-mapping
path is explicitly labelled **unverifiable**, never success.

For a device log inspect `SMART DJ NATIVE FALLBACK VERIFY` (ordinary
native fallback) or `MIX NATIVE FILE FALLBACK PREFILL` (single-file
Track Mix). Check `verified`, `status`/`audit`, `requested`,
`added` and `currentBefore/After`. The fallback's actual use remains
restricted to the pre-existing eligible paths; this audit does not
perform a provider query, select a track, insert or remove any song,
seek/restart playback, or attempt a synthetic transition.

A verified native queue still does not prove decoder/source continuity;
an on-device natural-next transition remains necessary.


## 2026-11-03 17:25 device hang: native queue correct, audio still stalled

Captured `GoneSmart-last-5min-20261103-172506-171.zip` (GMMP 4.2.1,
GoneSmart `198e8f2`). The exported five-minute snapshot contains only
17:24:36.544–17:25:06.039 (30 seconds of retained host Logcat).
At 17:24:36.544 GMMP logged `DspProcessor: Finishing process thread`.
At 17:24:37.026 GMMP logged `Marking as played`.
The managed native refill requested exactly one follower at 17:24:37.049;
the existing smart pool was a hit, and `kr.F1` selected local Track 6601.
Native `qr.z` completed in ~53 ms. Both `SMART DJ SELECT QUEUE VERIFY`
and `MIX CONTINUITY QUEUE VERIFY` passed; position 7 contains exactly
that smart Track. At 17:24:37.997 MusicService independently resolved
a **non-null next AudioSource** for Track 6601. Therefore the observed
hang is **not** shown to be a provider latency, stale queue ID,
incorrect smart match or null next-source failure. Nor does a non-null
next AudioSource prove that the new song's decoder or audio output started.
No definitive player-start marker is present in the retained Logcat.

To distinguish the remaining possibilities without changing working
Auto-DJ behavior, add one passive tail-transition check after 1.8 s
(`SMART DJ AUDIO TRANSITION PROBE`), and only when the native Current
entry remains unchanged and `AudioManager.isMusicActive` is false,
repeat once after 2.5 s. Both checks run on a daemon executor, read only
the native queue and Android audio activity, and never start providers,
insert songs, seek, restart or invoke `qr.z`. This activity flag is a
*heuristic*, not proof of decoder state; a persistent inactive flag
alongside stable Current is a stronger diagnostic clue.

Device re-acceptance is required. If the new symptom persists, capture a
recording started **before** the transition, ideally including at least a
few seconds before the previous song ends; the snapshot buffer retained
only ~30 seconds in this incident. No localization changes required.


### Additional maintainer observation: displayed next track, previous progress frozen

The maintainer confirmed that after the final song ends, GMMP displays
the *next track's metadata*, but the progress indication remains stuck on
the previous song, no audio starts, and the player controls appear frozen
until GMMP is force-stopped and restarted. This is stronger evidence of a
**failed playback-state handoff** than of a provider/queue mismatch.
The 17:24:54 device trace also proves the GMMP main thread was still
dispatching touch events and updating playback metadata 17 s after the
transition; a total main-thread deadlock is not established.

In addition to the bounded audio-activity checks, each diagnostic sample
therefore posts one asynchronous main-looper acknowledgement and logs
`SMART DJ UI LOOP PROBE` with latency or a 1.5 s threshold failure.
This is passive and lightweight: no UI traversal, player call, queue
mutation, provider request, or periodic polling. An acknowledged looper
does not itself prove that GMMP's playback controls work, but distinguishes
a blocked UI event loop from a player-state transition failure. Confirmation
of the root cause still requires an on-device capture containing the
corresponding audio and UI probe logs.

## 2026-11-04 continuous trace: cold normal Smart DJ cached null before refill

The continuous recording `GoneSmart-recording-20261104-115346-299.zip`
identifies a distinct, now proven failure path in GMMP 4.2.1. At
11:49:39.920 the native AudioPlayer switched to Bosse - Sunnyside. The
ordinary native `qr.z(1)` request then found no GoneSmart pool and
deferred **the same single provider pipeline** at 11:49:40.125. At
11:49:40.127, before it returned any match, GMMP queried queue position
22 and logged `next audio source is null`. The pool completed 7.594 s
later with 20 ranked local matches, and native `qr.z(1)` appended the
selected Lityway - Für dich (ID 15872) at position 22, independently
verified at 11:49:47.773. At Sunnyside's natural end (11:52:54), GMMP
selected that correct Track ID but did not start output. Both passive
audio probes observed inactive output while the main looper acknowledged
normally. The later non-null source for position 23 does **not** repair
the earlier null source cached for position 22.

Candidate correction: only when this exact cold deferred request is
still within 1.5 s of its start, the original song already has active
audio, and the accepted `MusicService.I2(int)` pre-source callback is
invoked off the UI/deferred worker, temporarily hold that callback for
at most 12 s while the existing provider and native `qr.z` worker run.
Release on **verified native queue materialization** (preserved Current,
unique/contiguous positions, real newly appended GMMP rows), rejection
or timeout. Never invoke/replay `I2`, alter its opaque argument, add a
provider query, change GMMP's configured upcoming count, inject a native
random/placeholder row, or seek/skip/restart the player. On timeout the
original callback proceeds unchanged. No persistent polling is added.

This is a targeted **device-test candidate**, not accepted decoder
repair. The on-device trace must confirm `SMART DJ NEXT SOURCE HOLD`
before the natural source lookup, `verified=true` after the *same* pool
completes, followed by a non-null `next audio source` for the exact
appended Track ID **and** audible natural playback at the following EOF.
The earlier 2026-11-03 non-null-source hang remains a separate diagnostic
possibility. No new translations required.

## 2026-11-04 12:45 device re-test: I2 gate did not protect normal refill

The exact `b6430689e607` build in
`GoneSmart-recording-20261104-124525-615.zip` reproduced the fault.
GMMP started Cro - long nights at 12:41:39.413 with the queue at position
23. Normal `qr.z(1)` deferred its cold recommendation pool at
12:41:40.112; GoneSmart logged `native transition released` at .113.
GMMP read queue position 24 at .115 and cached `next audio source is null`
at .116. The provider/local-match pass completed at 12:41:48.297;
the verified native refill appended Danju/Cro - Touch the Sky (ID 15902,
position 24) at 12:41:48.398. When long nights ended at 12:44:40.855,
GMMP selected the correct metadata for Touch the Sky but produced no
audio; the two audio probes at +1.8 s and +4.3 s were inactive while the
main looper acknowledged. The first follow-up source at position 25 was
non-null, but this could not repair the cached null at position 24.

Critically, no `SMART DJ NEXT SOURCE HOLD` start/end appeared at all
between native refill deferral and the null source lookup. The R83 I2
gate was therefore not exercised on this observed ordinary refill path;
we must not assume the passive alias catches every next-source read.

The next candidate protects the **actually observed `qr.z` return** for
this same cold request: on the already-audible, non-main playback worker,
temporarily defer its return (maximum 63 s: the established 60 s smart
preparation window plus a 3 s verified-native-append margin) until the pool and
verified native append finish. First release GoneSmart's normal-refill
single-flight lease so its own deferred worker can re-enter `qr.z`;
without that release the attempted fix would coalesce the only append
and deadlock itself. Continue immediately for main-thread, inactive,
aged-out, canceled or special Track-Mix requests. No extra provider
queries, native placeholder, seek or replay is introduced. Retain the
earlier I2 gate for a separately observed source callback. Distinct
`SMART DJ NATIVE RETURN HOLD` start/skipped/end logs allow the next
device trace to prove eligibility, duration and queue verification.

This remains a **device-test candidate**. Exact-head CI and a natural
audible transition are required before calling the decoder issue fixed.
There are no new user-facing strings or translations.

## 2026-11-04 15:13 device follow-up: headless GMMP skipped the hold

`GoneSmart-recording-20261104-151351-359.zip` confirms the preceding
native-return gate works in one GMMP process: at 13:08:15 it held the
return for 9050 ms, native `qr.z` appended and verified the next queue
row, and GMMP then logged a **non-null** next AudioSource. Subsequent
transitions were audible and the passive audio probes were active.

After GMMP restarted in background process 3612 (without MainActivity
resume), the same tested build `bf2cd01bf173` again saw a cold pool at
Sunshine (current position 35) at 15:09:41.729. The new explicit skip
diagnostic was decisive: `SMART DJ NATIVE RETURN HOLD | skipped=true |
ageMs=1 | audioActive=null | mainThread=false | deferredWorker=false |
trackMix=false`. The adjacent EOF check also recorded `context=false`:
`gmmpAppContext` was populated **only** in MainActivity.onResume, so
normal background-only playback had no Context from which to query
AudioManager.isMusicActive. GMMP cached `next audio source is null` for
position 36 at 15:09:41.738, before the existing provider pipeline
finished 12.5 s later. GoneSmart appended Up & Down (ID 15973) at
position 36 at 15:09:54.302 and independently verified the queue. At
15:13:12.816 GMMP selected Up & Down's metadata but did not start audio.

Correction: capture a GMMP-only **Android Application.attach(Context)**
callback as the process Context during normal application binding, which
also precedes headless MusicService playback. Keep the existing Activity
onResume capture as an independent later refresh. This is a passive
Android lifecycle read, not another GMMP player resolver, provider call
or playback operation. Its single startup log proves whether the Context
was acquired on device. The existing `audioActive == true` eligibility
remains fail-closed when Android audio state really is unavailable; no
unproved assumption about audible playback is introduced.

Device acceptance: confirm `GMMP APP CONTEXT | source=Application.attach`
after a background-only GMMP restart, and at the next cold Smart-DJ tail
confirm a successful `SMART DJ NATIVE RETURN HOLD` start/end, verified
native queue insertion, a non-null CURRENT+1 audio source, and audible
natural playback. No translations required.

## 2026-11-04 15:52 regression: synchronous source holds delay native Next

The exact `1f240d6b7435` device capture
`GoneSmart-recording-20261104-155204-437.zip` invalidates the assumption
that holding `qr.z` after a Current update cannot delay track switching.
At 15:49:17.025 a normal cold refill of BoyWithUke - Sick of U held the
native service call for **17,189 ms**. Only at 15:49:34.213, after the
existing search completed and a native queue row was verified, could
GMMP process the subsequent Next; GMMP recorded a skip and started The
Shirelles - Mama Said. A second cold refill held the same service path
at 15:50:01.211 for **14,152 ms**; native `onTransitionComplete` was
not logged until 15:50:16.664. The new Application.attach capture itself
was verified in the second process, so the wait was now enabled as
designed; the threading premise was wrong.

**R86 supersedes both synchronous normal Smart-DJ source holds.** Remove
the `qr.z` return barrier **and** the dormant I2 blocking observer, along
with their one-shot latch/policy/tests. Do not block either of these GMMP
transport/service call paths for the provider pipeline, regardless of
whether audio was active before the call. Restore the exact nonblocking
flow: native Next/current transition returns immediately, one existing
provider pipeline continues in the background, and after its results are
ready the same native `qr.z` writer appends and verifies the smart row.
No additional provider calls, altered search timeout, player method
replay, native placeholder or random fallback is introduced. Preserve
the new headless Application.attach Context capture for unrelated audio
diagnostics and safe deferred EOF checks.

The earlier **cached-null CURRENT+1 source remains unresolved** under
this nonblocking constraint. Do not claim queue verification proves
playable next audio or reintroduce any synchronous hold. A future repair
requires separately proving a safe nonblocking native next-source
invalidation/refresh or an equivalent real GMMP callback, without
replaying unverified obfuscated player methods or delaying current Play.
Device regression acceptance: a manual Next onto the final queue row
must start that row immediately while a cold search is still running;
only the following queue append waits for matching. No new translations.

## 2026-11-04 targeted native source refresh candidate (device test pending)

The original GMMP 4.2.1 APK (not a guessed R8 map) was inspected at the
actual `next audio source is ` logging statement. It resides inside static
`MusicService.E2(MusicService):void`. Its native path reads the next queue
entry, builds the `sq` AudioSource and passes it to `hn3.h0(sq,boolean)`.
The same helper is called by several native transitions, including
`hn3.V0()` after `onTransitionComplete`. This is distinct from the
`I2(int)` event whose R83 hold did not run on the ordinary cold-refill path.

The new candidate passively wraps this exact E2/source-setter pair only on
the shape-validated, tested GMMP 4.2.1 mapping. It never holds the original
native call. If native E2 actually sets a **null** source while the last
song's one existing GoneSmart recommendation fill is pending, it retains
that service instance for this request only. After the **same native qr.z**
worker appends a real next row and passes the independent queue postcondition,
one off-transport invocation of the **now APK-proven E2 source-calculation
helper** is eligible only with unchanged Current identity, a nonempty
upcoming queue, still-active audio, no pending manual Next, current request,
no intervening native non-null source recovery, and exactly GMMP 4.2.1.
It does not invoke I2, select another track, seek, restart, mutate the queue
or request more providers.

Logs: `SMART DJ NEXT SOURCE NULL OBSERVED`, then after the native queue
verification either `SMART DJ NEXT SOURCE REFRESH | skipped=true` or
`requested=true` followed by the actual native setter's
`nativeSourceNonNull=true/false`. One-time source recalculation is a new
**device-test candidate**, not an accepted audio/decoder fix. There is no
12/63-second synchronous hold. The 60-second existing provider preparation
limit remains unchanged. No translations or companion UI changes required.

Acceptance must verify (1) immediate manual Next onto the final queue song
with a cold pool, (2) original `E2` records a null source without delaying
the transition, (3) native refill verifies the exact smart row, (4) the
one-shot E2 refresh reports a **non-null** native source, and (5) the
following natural transition really starts audio. Also test Stop After
Each Track, canceled/new sessions and manual skips; a native non-null
AudioSource alone is not sufficient proof of audible playback.

## 2026-11-10 manual Next while the cold recommendation pool is empty

The improved 5-minute capture from 10:14 shows three user Next attempts
at 10:12:45.364, 10:12:45.745 and 10:12:49.058. Each reached GMMP's
`End of queue reached` and its normal Auto-DJ refill. GoneSmart coalesced
all three requests into **one** ongoing recommendation search, completed
after 23.57 s, and verified a native 1-row append at 10:13:09.025.
There is no `SMART DJ DEFERRED NEXT` record: the existing
`View.performClick` observer did not recognize these real GMMP Next
inputs. The native queue itself was not lost or inconsistent.

DEX inspection of the tested GMMP 4.2.1 APK finds the native
`MusicService.l1(m63)` command handler calling its
`MusicService.B1(boolean)` end-of-queue boundary. The revised candidate
passively scopes this native command and its end-of-queue invocation;
only when the same native `qr.z` request then actually defers a cold
refill with no upcoming tracks does GoneSmart remember one manual Next.
The original GMMP command and its native refill return immediately.
Further Next presses coalesce to **one** pending action (never 2–4);
the existing queue/session/entry validation and one verified native
`gonemad.gmmp.command.NEXT` replay after append remain unchanged.
The proven UI `performClick` path can also feed this same single-slot
behavior without accumulating duplicates. Show one short, nonmodal
GoneSmart-owned Toast confirming that recommendations are being searched
and the requested Next will follow. Respect `showStatusMessages`.

This is a targeted input-recognition/UX candidate and requires device
confirmation that the native command scope indeed recognizes Next even
when GMMP dispatches the button without `performClick`, that one
delayed Next resumes only within the same session, and that subsequent
manual playback cancels the pending action. It must never reintroduce
transport blocking or extra recommendation requests.
