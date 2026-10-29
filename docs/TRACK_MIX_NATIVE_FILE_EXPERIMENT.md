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
- A new playback source normally replaces GMMP's native queue and removes the
  original seed entry. Missing original entry, duplicate/ambiguous enqueue
  identity or noncontiguous queue positions aborts remaining insertions.
- If "remove after played" removes the original seed during the wait, we
  conservatively cancel instead of assuming this is still the same session.
- If recommendations are unavailable, only the configured native GMMP
  fallback may be enabled.

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
