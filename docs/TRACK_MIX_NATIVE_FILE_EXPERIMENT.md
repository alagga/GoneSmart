# Experimental public-command Track Mix (GMMP 4.2.1)

**Branch:** `feature/async-track-mix-playback`. **Status:** host CI and device
acceptance required; do not merge to the 0.5.0 fix branch yet.

## Native workflow

1. Resolve a unique GMMP library Track ID/file path from the clicked song.
   Restrict the new route to the known `qr` runtime and absolute file paths;
   preserve the old prepared-predecoder path otherwise.
2. Immediately broadcast GMMP's documented `PLAY_FILE` with `filename`.
   Verify that the host created Current at position 1. GMMP 4.2.1 may ask
   for `current+1` within milliseconds: at the already accepted natural
   `MusicService.I2(int)` boundary, independently verify the exact one-row
   Current and synchronously let native `qr.z(1)` materialize **one**
   continuity follower *before* the original I2 proceeds. Its integer
   argument is opaque and never used as a queue coordinate. Verify that
   the native refill preserved the original queue entry and created exactly
   one follower at position 2. Any ambiguity fails closed.
3. In the background, run the **unchanged full** selected-seed recommendation
   pipeline. Retain the original queue entry ID as a request-local ownership
   anchor, even if the user later appends or reorders manual queue entries.
4. When the full ranked pool is available, re-check the original anchor and
   rebind that pool to the real queue session. Ignore candidates already in
   the live queue; for each selected recommendation, broadcast `ENQUEUE_FILE`.
   Verify the newly materialized native Track/entry identity and contiguous
   positions before dispatching the next. Preserve manual entries and append
   recommendations after whatever the queue contains at that moment. Reserve
   one of the configured initial slots for the protected native continuity
   follower when it exists; never overwrite or delete that already predecoded
   Track ID. Other than this one native continuity slot, candidates keep the
   existing quality ranking and natural GMMP enqueue semantics.
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
- The 2026-11-01 log (`1c09704ce6a2`) **disproves** the original assumption
  that a late ENQUEUE_FILE repairs an absent next decoder: `qr.z(4)` was held
  at 17:07:46.433, `MusicService.I2` cached `next audio source is null` at
  17:07:46.489, four native fallback rows were added only at 17:07:54, and
  the natural transition around 17:11:23 did not correctly play Track 2.
  The predecoder single-slot native fill above prevents this specific race,
  while allowing the remaining recommended songs to arrive asynchronously.

## Device acceptance (must be tested on GMMP 4.2.1)

1. Verify exact-head `BuildConfig.GIT_REVISION`; trigger Track Mix from Search,
   Queue, file browser and playlist contexts. The selected song must become
   audible *before* network/provider search finishes.
2. Wait for the **second** generated song to become audible at natural end;
   repeat with Skip and screen off. The first follower may be a GMMP-native
   continuity song, with the remaining followers from GoneSmart. A visually
   correct native queue is not sufficient: verify a non-null `next audio
   source` at initial Play and the real audio decoder after natural transition.
3. During search, manually Enqueue and Play Next one or more songs; confirm
   none are removed and generated songs appear later in the live queue.
4. During search, start an unrelated song with native Play/PLAY_FILE; confirm
   late GoneSmart recommendations are discarded, not inserted in the new queue.
5. Check shortage/zero matches, fallback enabled/disabled, duplicate
   candidates, queue reordering, and a rapid second explicit mix request.
6. Confirm subsequent native Auto-DJ continuation respects GMMP's configured
   upcoming count and only uses the maintained Smart pool.

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
