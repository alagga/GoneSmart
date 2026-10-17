> **Retired by r45:** device evidence showed queue/decoder divergence and false-success fallback semantics. Keep this file as historical evidence only; do not re-enable its post-materialization current+1 retarget design.

# GMMP 4.2.1 Queue Compatibility — r44

Source repair commit: `ab139568db9df78f49929e6621ca7c222c5fe359`.
The install head is defined by the subsequent exact-head CI build and may include documentation-only pin commits.

## Evidence

The 2026-10-14 BUNT. log reached a visually correct five-row queue but exposed a playback-state break earlier in the transition: after source isolation left only CURRENT, GMMP queried position 2 and cached `next audio source is null`. Four native rows were inserted only afterwards. Replaying the previously observed `MusicService(Int)->void` position-2 boundary did not restore decoder continuity: Track 2 became CURRENT in metadata while no decoder opened, and a subsequent Avicii Track Mix inherited stale playback state.

## Rule

A 4.2.1 Track Mix transition may never create a moment where the playing CURRENT lacks current+1 while MusicService can preload. The passively observed current+1 boundary is not an invocation API.

## r44 native fallback

When provider/local matching returns zero and native fallback is allowed:

1. Before native Play, invoke only the already verified native Auto-DJ selection DAO and collect the complete Initial-Size remainder as Track IDs. No queue row is written at this stage.
2. Once native Play independently proves the target CURRENT and the natural current+1 MusicService boundary is observed, retarget only the existing current+1 native entity to fallback ID #1 before the original boundary proceeds. Verify queue_id, CURRENT and replacement Track ID.
3. Let GMMP perform its normal source rebuild. After exact CURRENT rebind/settling, retarget rebuilt upcoming rows to the same ordered fallback IDs. Slot 2 must equal the already preloaded ID #1. Rewrite first, verify, then delete surplus source rows.
4. Compact the final queue to 1..Initial Size through the passively verified position writer.
5. Enable Auto-DJ only after the complete final queue exists. Suppress transitional refill while the Track Mix hold is active. Do not replay MusicService playback methods.
6. Any ambiguity before priming fails without source mutation. Any later failure performs best-effort same-ID current+1 reconciliation before returning.

The prepared GoneSmart recommendation path is unchanged and does not use this fallback priming path.