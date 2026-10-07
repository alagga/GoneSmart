# GMMP 4.2.1 queue continuity — r43

## Evidence

The r42 DAO rebase fixed the visible sparse queue-position problem but did not reset GMMP's hidden queue-end/append state. A Track Auto-DJ run created four native fallback rows, then GoneSmart verified `old=1,5905,5906,5907,5908 -> new=1..5`. Playback advanced through the normalized queue. At the tail GMMP queried `queue_position = 6`, returned `next source null`, and never invoked another native Auto-DJ refill.

The recommendation pool itself was already ready before the tail transition, so provider latency was not the reason continuation stopped.

## r43 contract

- Keep the r42 native DAO position normalization; it fixes the user-visible/current-row corruption.
- Do not mutate or guess the unknown 4.2.1 append/end allocator.
- Only while the Track Auto-DJ-owned 4.2.1 session is armed, observe GMMP's already-verified natural CURRENT-position signal.
- On a new verified position, perform one queue read, derive remaining rows, and compare them with GMMP's own `autoDj_upcomingTracks` setting.
- If there is a deficit, invoke the already-verified native `qr.z(deficit)` refill boundary. The normal Smart DJ interception/pool-selection path still owns replacement.
- Dedupe repeated observer callbacks for the same position, allow one refill in flight, and never poll.
- Any unrelated native list Play disarms the managed continuation.
