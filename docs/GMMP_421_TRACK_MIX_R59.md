# GMMP 4.2.1 Track Auto-DJ — R59 4.2.0 semantic restoration

## Device evidence from R58

R58 correctly restored the selected seed row to `queue_position=1`, wrote the passively verified GMMP Current position to `1`, and forced a fresh GoneSmart recommendation session for every explicit Track Auto-DJ invocation. The remaining mismatch was the hidden GMMP 4.2.1 append/end basis: after the one-row seed, native `qr.z(4)` still materialized followers at the old absolute tail (`7215..7218`) instead of `2..5`. A second explicit Track Auto-DJ correctly used a fresh non-BUNT. recommendation context, proving that the remaining fault was queue-coordinate continuity rather than recommendation-session leakage.

## 4.2.0 comparison

The accepted 4.2.0 implementation reset three pieces of state before native Auto-DJ refill: the selected queue row to position 1, the playback Current pointer to 1, and the separately proven `ex3.p` append-position allocator to 1. That third operation is why native follower rows naturally started at position 2.

GMMP 4.2.1 still has no behaviorally proven direct equivalent of `ex3.p`. R59 therefore does not invent one and does not change Track Auto-DJ selection/recommendation behavior.

## R59 compatibility shim

- Arm the already-existing managed 4.2.1 Track-Auto-DJ session immediately after the exact seed and fresh recommendation session are verified, before native `AUTO_DJ` / `qr.z` refill.
- After GMMP's original `qr.z` returns, compact only that Track-Auto-DJ-owned queue through the verified native Queue DAO so the same native queue IDs/order become contiguous around the verified Current position. For the initial position-1 seed this yields exactly `1..InitialSize`. Track IDs are never rewritten by this shim.
- Keep the managed session only after exact Initial-Size verification. A failed/incomplete start disarms it.
- On later passively verified natural Current transitions, the existing managed continuation compares live remaining rows with GMMP's own `upcoming` preference and invokes only the native `qr.z(deficit)` boundary when needed. The same post-refill normalizer then closes any new hidden-allocator gap.
- Any unrelated native list Play disarms the managed compatibility session.

This reproduces the observable 4.2.0 queue-coordinate/continuation contract without guessing a 4.2.1 mutable allocator field and without reviving the R46-R56 follower-ID/predecoder rewrites.

## Expected device proof

With Initial Size 5, an explicit Track Auto-DJ must verify `Current=1`, exactly five rows, and positions `1,2,3,4,5`. A second explicit Track Auto-DJ must start a new recommendation session with only the newly selected track as user anchor. Advancing playback to the tail must trigger managed native refill when GMMP's configured upcoming count requires it, while queue positions remain contiguous.
