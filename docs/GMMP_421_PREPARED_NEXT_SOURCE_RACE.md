# GMMP 4.2.1 — prepared Queue Track Mix next-source race

**Status:** fix awaiting device re-acceptance. **Branch:** `fix/audio-profiles-device-identity`.

## Device evidence (2026-10-28)

GoneSmart 0.5.0-rc.1 (`ace58ab`), Samsung Android 13, GMMP 4.2.1,
`GoneSmart-last-5min-20261028-181243-088.zip`:

- 18:08:30.113: Queue-context Track Mix dispatched the exact native Play.
- 18:08:30.751 and .922: native `MusicService.I2(int)` read the old
  `queue_position=11` and cached `next source null` while the selected row
  remained at position 10. `ux1` confirmed both null next sources.
- 18:08:30.929–18:08:31.267: GoneSmart isolated Current to position 1,
  materialized four prepared Smart recommendations through native `qr.z`,
  and verified the five-row native queue.
- 18:08:32.096: the queue-only Track Mix postcondition passed. A valid queue
  postcondition **does not** prove the already-cached next AudioSource was
  rebuilt. A later transition advanced GMMP's track metadata without a
  corresponding second-track decoder initialization in the captured log.
- The playback hang occurred without a contemporaneous Bluetooth route
  change. The cached-null/decoder connection is strongly suspected, not yet
  independently proven by a device A/B test.

## Narrow correction

For the accepted natural `MusicService.I2(int)` event only, while an explicit
GMMP 4.2.1 **prepared Queue-context** Track Mix is starting, postpone the
original native event behind a **request-local, bounded** wait for the existing
worker's strict Current/queue-size/contiguous-position postcondition.

The hook does **not** invoke playback methods, rewrite GMMP's opaque I2
argument, manufacture next AudioSources, modify a materialized Track ID, or
repeat the native refill. The normal GMMP call proceeds once the exact queue
is verified. On error, timeout (3 seconds), an unsafe UI/self-thread wait, or
the end of the request, it proceeds without claiming success and emits a
compact warning. No background service, polling task, or persistent memory
buffer is introduced. Zero-pool and non-Queue Track Mix remain unchanged.

## Required device re-acceptance

1. Start prepared Queue-context Track Mix from a non-first/last row with
   screen on and off. Observe `MIX PREPARED NEXT SOURCE | verified=true`
   with a bounded wait before `MusicService`'s corresponding next source.
2. Confirm the **second** selected Auto-DJ song is actually decoded and
   audible, not merely present in `queue_table`. Listen through natural
   completion and skip transitions. Confirm Current and five native rows.
3. Confirm no timeout, unsafe-thread warning, duplicate rows, or stale
   AudioSource. Check zero-provider and non-Queue prepared starts still
   follow their accepted native boundaries.

## Feature audit

- Native-first: the proven GMMP I2/qr.z and queue-reader/writer ownership is
  unchanged; only the order of the existing native read is bounded.
- Fail-closed: no new mutation path. A failed barrier never asserts a verified
  decoder, and never uses a guessed method name as mutation authority.
- Performance: one short wait only during the explicit Track Mix request;
  no accepted-version polling or ongoing queue introspection.
- Companion Logs: existing Track Mix success and failure history unchanged;
  the low-level race timing stays in Logcat under `GoneSmartTrackMix`.
- Localization/I18N: **no new translations required**; no user-visible GMMP
  string was added or changed.
- Code hygiene: a single request-local helper and host race tests; no
  temporary hooks/branches or release changes.
- Validation: host tests and exact-head GitHub Build required; runtime
  acceptance remains pending until the maintainer reproduces or validates
  gapless track transitions on the device.
