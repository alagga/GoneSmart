# Track Mix / Titel-Mix — GMMP 4.2.0

**Development status (24 September 2026):** The supplied phone log records six successfully verified five-track mixes and one intermittent queue-isolation failure. The failure occurred after using Track Mix from a Queue row: GMMP requested another Auto-DJ track during the native Play/Clear transition (00:18:17), and GoneSmart could not establish that the selected song was isolated before its timeout (00:18:23). The log does not independently prove exactly which GMMP callback blocked clearing.

The current feature branch addresses this with a **temporary hold on native GMMP Auto-DJ refills before the new seed has been isolated**, one bounded retry of native Clear Queue, clearer failure diagnostics, and queue verification before declaring success. Only one GoneSmart confirmation is now scheduled after verification. These latest changes have passed source-level tests and must still be confirmed on GMMP itself.

## What the feature does

The item appears immediately after Play next on individual-song context menus in the library, queue, playlist detail, search and file browser. It is independently switchable in **GoneSmart → UI → Track Mix**, enabled by default for existing users. When selected, it plays the selected song, keeps only that song as the initial queue entry, enables Smart DJ if needed and lets GMMP Auto-DJ fill the queue up to GMMP's Initial Size (including the selected song). It leaves the real playlist files alone.

The native GMMP CLEAR_QUEUE command retains the currently selected song. Native pre-clear automatic refills are deferred while Track Mix is starting, so a previous Auto-DJ session does not refill the old queue during clearing. Once isolation is confirmed, GoneSmart sends the native AUTO_DJ command and verifies the new queue. If GMMP refills instantly, GoneSmart can accept a first-position seed plus **newly generated** entries, but never mistake the old remaining queue for a cleared queue. An incomplete mix shows a warning rather than claiming success.

## Pop-ups and languages

The user should see **one short confirmation after successful verification**, not the intermediate GMMP Play, Clear and Auto-DJ notifications. During the short startup window, only native toasts/snackbars generated inside GMMP's process are suppressed; other activity outside the window behaves normally. GoneSmart's own confirmation and genuine error warnings remain visible. Each outcome is also recorded in **GoneSmart → Logs → Track Mix**.

GMMP translates its own track and Auto-DJ words, not GoneSmart's invented Mix name. German: **Titel-Mix**; English: **Track Mix**. Other languages use the native GMMP words separated by a dot; confirmations use a native “started” resource if available or a universal checkmark. We deliberately do not maintain a custom translation catalog for this new feature.

## One optional combined regression check

After installing the latest green feature-branch APK, restart GMMP once. With a disposable queue, try Track Mix from (1) a Queue row while Auto-DJ is already active and (2) an ordinary Library track, ideally when Smart DJ is disabled so it must be enabled. For either one, the chosen song should continue at queue position 1 and the total queue should reach GMMP's Initial Size. Only **one** successful Track Mix notification should appear. The UI-tab switch should hide the entry when off and reveal it again when on without another GMMP restart.

If something fails, one Logcat extract filtered by `GoneSmartTrackMix` suffices. Particularly relevant entries: `MIX AUTO-DJ HOLD` (old refill deferred), `MIX CLEAR RETRY` (overlap occurred), `MIX CLEAR DIAG` (still unable to isolate), `MIX VERIFIED` (queue contains requested tracks), `MIX POPUP` (intermediate notification suppressed) and `MIX FAILED`. Do not repeat earlier multi-screen tests unless a new regression appears.
