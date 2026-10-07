# GMMP 4.2.1 Queue compatibility — r42 queue continuity

## Device evidence

The 2026-10-07 device pass confirms the preceding Track Auto-DJ CURRENT fix and the navigation performance fix: the maintainer reports the localized Track Auto-DJ action working again and tab swiping smooth.

The same pass exposes one older 4.2.0 invariant that was lost in the 4.2.1 port:

- native Play materializes a 5904-row source queue;
- Track Auto-DJ correctly deletes 5903 stale rows and preserves exactly one seed;
- after refill, GMMP continues using absolute `queue_position` values around 5905+ instead of a fresh `1..N` basis;
- GMMP later asks for positions around 5912–5918 while only a handful of rows are live;
- the Queue UI consequently reports an impossible current index/count and can omit the actual current row.

The accepted 4.2.0 implementation explicitly reset both the playback pointer and `ex3.p`, GMMP's then-proven next-position allocator. The 4.2.1 port remapped the playback pointer but did not establish a behaviorally proven replacement for that separate allocator.

The log also shows why Smart DJ remains slow: post-refill queue reads repeatedly lose CURRENT, generated rows are not retained as generated session context, and `QueueSessionTracker` opens a new session/pool on later skips. There are no steady-state pool hits. The initial Track Auto-DJ refill can additionally wait more than ten seconds for provider/local matching while the queue contains only the seed, so Next is unavailable during that interval.

## r42 correction

1. Every Smart-DJ queue read now reuses the already passively verified native current-position readback when one exists. Generic structural CURRENT discovery remains only the compatibility fallback before such proof exists.
2. Track Auto-DJ owns a narrow 4.2.1 queue-continuity repair for the session it created. After native refill, if Queue DAO positions are sparse, the already-proven native Queue update writer rebases them to `1..N` while preserving queue IDs/order. If CURRENT must move numerically, only the already passively verified current-position writer is allowed. The independent Cursor must then prove contiguous positions and the same current queue entry. Any ambiguity fails closed and rollback uses the same native writers.
3. The unknown 4.2.1 append allocator is **not guessed**. The rebase is a compatibility fallback to retire when the real native allocator is passively identified.
4. The explicit Track-Auto-DJ Initial-Size refill waits only 1500 ms for the smart recommendation pool. If the pool is not ready, it is left running in the background and native GMMP fills Initial Size immediately, restoring a usable Next action without throwing away the smart work already in progress.
5. A native full-list Play outside a pending Track Auto-DJ action disarms the scoped rebase so ordinary GMMP queues are never normalized globally.

## Automated gate

`QueuePositionNormalizationPolicyTest` proves sparse 5900-range positions become `1..N` without changing queue order, that CURRENT maps to the corresponding new position, that an already-contiguous queue is a no-op, and ambiguous native queue identity fails closed.

The normal branch build remains the final exact-head gate after integration.

## One bundled device pass

One Track Auto-DJ start from the same large source is sufficient. Then skip several titles quickly and open Queue once.

Expected evidence:

- `QUEUE POSITION NORMALIZE ... verified=true` after a native refill when GMMP appended with a stale absolute basis;
- sane Queue position/count and a visible current row;
- Next becomes available after the short initial smart wait rather than after a multi-second provider pipeline;
- later refills remain on the same queue session and begin producing pool hits/background top-up instead of repeatedly resetting `SMART DJ POOL`.
