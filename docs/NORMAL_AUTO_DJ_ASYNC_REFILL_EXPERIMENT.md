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

## Real-device tests required

1. With a newly made two-track queue and cold pool, skip to the second/last
   track. Playback should advance *before* the provider search completes,
   followed by native Auto-DJ queue additions with no duplicate tracks.
2. Verify natural transition and next track decoder with screen on/off;
   inspect for cached `next audio source is null` even when queue looks good.
3. Press Next once more while already on the last song and the pool is still
   loading. GMMP might have no next decoder until late refill; record whether
   it waits, pauses, or needs another Next. No guessed decoder replay is used.
4. Manually enqueue during search (manual tracks retained, smart appended).
   Start a new playback during search (old recommendations discarded).
5. Confirm fallback enabled/disabled, offline, repeated refill requests and
   Track Mix regression behavior.

## Audit

- Native first: unchanged `qr.z(int)` and selection hook; no new writer.
- Performance: no cold-pool provider wait on GMMP playback call stack; only
  an event-triggered one-shot worker. Match scoring and pool sizing unchanged.
- Diagnostics: existing `GoneSmart` Logcat tag logs deferred scheduling,
  coalescing, cancellation and native retry with wait time. No new tag/filter
  or routine companion events; existing companion fallback events remain.
- Localization: **no new translations required**; no new GMMP UI strings.
- Device validation and exact-head CI still required; not release-ready.
