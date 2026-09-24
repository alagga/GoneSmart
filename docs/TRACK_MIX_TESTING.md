# Track Auto-DJ — GMMP 4.2.0

**Feature status:** Complete in the v0.4.x development branch following the maintainer's report that the latest Track Auto-DJ build works on-device. The companion app uses English **Track Auto-DJ**, while GMMP's own menus retain native translations. The maintainer subsequently confirmed the corrected build works when launching Track Auto-DJ from an existing queue, with no repeat error observed. That targeted regression check is considered passed; this confirmation is based on the maintainer's device test, not an independent review of the new Logcat output.

**Earlier development diagnostics (24 September 2026):** The supplied phone log recorded six
verified five-track starts and one intermittent queue-isolation failure from a
queue-row Play action. Immediately after that Play action GMMP requested a
refill of its existing Auto-DJ queue. The original implementation broadcast
CLEAR_QUEUE and waited for the selected song to become the only queue entry.
Because both that command and GMMP's in-flight refill are asynchronous, the
verification could time out without proving which native callback won the race.

**Root-level implementation change:** The new code does not resend CLEAR_QUEUE
or retry it. After GMMP's native Play moves to the selected song, GoneSmart
resolves the selected entry by its unique native queue_id (not merely song ID),
checks that the native queue has not changed, then uses GMMP's native Room
transaction (ex3.c with xx3.O and xx3.O0) to remove only the other entries
and move that same current queue entry to position 1. GMMP's native next-position
allocator and playback pointer are synchronized to 1. The write is verified
BEFORE Auto-DJ is allowed to fill the new queue. An unexpected concurrent
queue change aborts safely rather than deleting a different queue.

The old pre-clear refill hook remains a safeguard against *new* old-session
refills, but a refill already running when the user taps Play cannot be canceled
retroactively. The direct native transaction eliminates repeated asynchronous
clear attempts and the failure mode they caused. **The maintainer has retested Track Auto-DJ from an existing queue using the
corrected build and observed no recurrence of the earlier failure.** This closes
the targeted regression check. The earlier log remains historical evidence of
the superseded implementation; the new run's Logcat was not independently
reviewed in this chat.

## User behavior

The song action appears after **Play next** in single-song menus (library,
queue, playlist details, search, file browser and shared tracks). It is
independently switchable in **GoneSmart → UI**, and can enable Smart DJ
when it was previously disabled. The chosen song plays as the first entry
of a fresh queue and GMMP Auto-DJ fills the rest to **Initial Size**.

The English-only GoneSmart companion app always displays **Track Auto-DJ**.
The menu name inside GMMP is built from the installed player's own translations
of its `track` and `auto_dj` resources in **every** player language.
For example, native German: **Titel Auto-DJ**; native English: **Track Auto-DJ**.
A localized native `started` resource is used for the one visible success
confirmation where available; otherwise GoneSmart uses a checkmark
rather than inventing a translation. If a GMMP resource is absent, an
available native term is used without inserting a made-up foreign word.

Only one user-visible success confirmation should appear. GMMP's own
Play/Clear/Auto-DJ status Toasts and Snackbars are suppressed during the
short bounded transition. A genuine error still shows one warning;
diagnostics go to **GoneSmart → Logs** and Logcat `GoneSmartTrackMix`.

## Regression result and future smoke tests

**Passed (maintainer report):** The corrected build was tested by starting
Track Auto-DJ from an existing queue. The earlier queue-isolation failure
did not recur. Other routine Track Auto-DJ starts were previously reported
working, including the six verified five-track starts in the earlier log.
No further targeted retest is required to close this issue.

For future GMMP updates or the bundled v0.4.x release, a normal smoke test
may cover both (1) a middle Queue row while Auto-DJ is already on and
(2) an ordinary Library track with Smart DJ initially off. The selected track
should be queue position 1, GMMP should fill to Initial Size, and one
confirmation should appear.

Useful new logs: `MIX ISOLATED` (unique row ID, number removed and native
transaction verification), `MIX ISOLATE FAILED` (concurrent queue change or
native write failure), `MIX VERIFIED` (new queue and selected song intact),
`MIX POPUP` (intermediate status suppressed). No repeated manual trial cycles
are necessary: if either case fails, send the filtered log once.
