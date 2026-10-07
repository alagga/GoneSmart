# GMMP 4.2.1 queue compatibility — r39 Track Mix Initial Size

Date: 2026-10-04

## Device evidence

The r38 ordinary-playlist Track Mix run proved that queue isolation is correct but the initial Auto-DJ fill was incomplete:

- the selected playlist row was isolated through `vx3` / `cy3` to one verified seed;
- GoneSmart read GMMP `Initial Size = 5` and `Upcoming = 1`;
- after the Auto-DJ command GMMP invoked its normal `qr.z(int)` refill with `requested=1`;
- GoneSmart prepared a valid recommendation pool, replaced exactly that one requested native row and GMMP appended exactly one track;
- the verified queue therefore remained at two rows and Track Mix ended with `MIX INCOMPLETE | initial=5 | actual=2`.

This proves that the count passed to the first normal refill after enabling Auto-DJ is not a guarantee that GMMP will populate its configured Initial Size. In this run it matched the normal Upcoming count instead.

## r39 rule

Track Mix owns the postcondition that the newly created mix reaches GMMP's configured Initial Size. It must nevertheless preserve GMMP's native refill transaction and GoneSmart's normal recommendation pipeline.

The fill sequence is therefore:

1. isolate the selected seed using the already verified native queue writer;
2. enable Auto-DJ normally;
3. observe the native `qr.z(count)` request and accumulate its requested count;
4. if a native refill is already running, do **not** start another refill concurrently;
5. wait until the independently read queue proves that the observed request has materialized;
6. recompute `missing = Initial Size - actual queue size` from the verified live queue;
7. only when `missing > 0`, invoke the same native `qr.z(missing)` boundary once more;
8. verify the final queue contains at least Initial Size rows while preserving the selected current track.

For the observed `Initial Size=5`, one isolated seed and native `requested=1` case, the intended sequence is therefore `1 seed + 1 native refill + 3 supplemental = 5 total`.

If an observed native refill does not materialize within the bounded wait, Track Mix fails closed instead of issuing a concurrent second `qr.z(...)` call. This avoids duplicate recommendation-pool selection and overfilling races.

## Runtime markers

r39 adds/extends:

- `MIX NATIVE REFILL | requested=<n> | totalRequested=<n>`
- `MIX FILL WAIT | nativeRequested=<n> | expectedAfterNative=<n> | actual=<n>`
- `MIX FILL SUPPLEMENT | nativeRequested=<n> | actual=<n> | missing=<n>`
- existing `MIX REQUEST REFILL | requested=<n>`
- final success remains `MIX VERIFIED | initial=<n> | actual=<n>`.

## Separate open issue

The r38 log also still shows a distinct Smart-Playlist timing problem: a very large Smart Playlist can continue rebuilding its native queue after a short quiet period and overwrite the isolated seed. That is **not** treated as the Initial Size bug and remains a separate compatibility problem. Do not weaken the r39 sequential refill rule to compensate for it.
