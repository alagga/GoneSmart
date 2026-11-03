# Experimental public-command Track Mix (GMMP 4.2.1)

**Branch:** `feature/async-track-mix-playback`. **Status:** host CI and device
acceptance required; do not merge to the 0.5.0 fix branch yet.

## Native workflow

1. Resolve a unique GMMP library Track ID/file path from the clicked song.
   Restrict the new route to the known `qr` runtime and absolute file paths;
   preserve the old prepared-predecoder path otherwise.
2. Immediately broadcast GMMP's documented `PLAY_FILE` with `filename`.
   Verify that the host created one Current row at position 1.
3. In the background, run the **unchanged full** selected-seed recommendation
   pipeline. Retain the original queue entry ID as a request-local ownership
   anchor, even if the user later appends or reorders manual queue entries.
4. When the full ranked pool is available, re-check the original anchor and
   rebind that pool to the real queue session. Ignore candidates already in
   the live queue; for each selected recommendation, broadcast `ENQUEUE_FILE`.
   Verify the newly materialized native Track/entry identity and contiguous
   positions before dispatching the next. Preserve manual entries and append
   recommendations after whatever the queue contains at that moment.
5. After confirming generated entries, release the native refill guard and
   enable GMMP's own `AUTO_DJ` mode for subsequent refills.

No direct Room queue insertion, guessed native writer, Track-ID retarget,
AudioSource replay or parallel player is introduced.

## Session safety

- A manual Enqueue/Play Next or in-session reordering during the recommendation
  search **does not** cancel the mix. Generated titles go behind the live tail.
- Device log 2026-10-29 revealed that GMMP 4.2.1's structural CURRENT marker
  is ambiguous after `ENQUEUE_FILE`, even when the verified native position
  writer is available. Both pool-to-session adoption and final generated-row
  commit now use the same position-hinted read as the Track Mix worker. This
  prevents false aborts after manual appends and false failures after four
  successfully materialized recommendations. Device log 2026-10-30
  (`090f432`) confirms two four-track commits with the verified writer hint;
  a newly started playback correctly aborted the intermediate search.
- Native `AUTO_DJ` can post a delayed GMMP status Toast after the async
  worker has completed. The short post-command suppression grace is now kept
  for this path too; GoneSmart's own confirmation remains visible. Missing
  GMMP `started` string now falls back to readable `Started` instead of `✓`.
  Both UI changes await device verification.
- A new playback source normally replaces GMMP's native queue and removes the
  original seed entry. Missing original entry, duplicate/ambiguous enqueue
  identity or noncontiguous queue positions aborts remaining insertions.
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
   candidates, queue reordering, and a rapid second explicit mix request.
6. Confirm subsequent native Auto-DJ continuation uses the maintained Smart
   pool, with at most one extra upcoming-track safety margin beyond GMMP's
   configured count (minimum 2) in sessions already proven through `qr.z`.
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
