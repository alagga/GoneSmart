from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    path.write_text(text.replace(old, new, 1))


agents = Path("AGENTS.md")
replace_once(
    agents,
    """- Coalesce visible-row synchronization to at most one posted animation-frame update per scroll/refresh burst.
""",
    """- Coalesce visible-row synchronization to at most one posted animation-frame update per scroll/refresh burst.
- Long-lived `OnGlobalLayout`/layout listeners must fast-path already proven native anchors. A full decor/view-tree scan is a recovery path for detached, hidden or semantically rebound targets, not normal work during pager animation.
- Periodic visual diagnostics (for example drawable/glyph raster analysis) must be invalidation/state-driven and cached. A bounded safety recheck is acceptable; rebuilding bitmaps, shaders or paths every monitor tick/frame is not.
""",
    "AGENTS UI hot-path rules",
)
replace_once(
    agents,
    """Do not “fix” noisy native GMMP tags such as `w6` by hiding their logger. If GoneSmart caused unnecessary native SQL, remove the repeated work. Native queries that GMMP itself legitimately performs while opening/refocusing a library tab may remain visible in logcat.
""",
    """Do not “fix” noisy native GMMP tags such as `w6` by hiding their logger. If GoneSmart caused unnecessary native SQL, remove the repeated work. Native queries that GMMP itself legitimately performs while opening/refocusing a library tab may remain visible in logcat.

Accepted user actions may keep a bounded postcondition check, but must not retain discovery-era polling loops. Repeated Cursor/Queue reads after a device-accepted native action are reserved for an unknown/failing compatibility boundary, not ordinary runtime verification.
""",
    "AGENTS bounded postconditions",
)

playbook = Path("docs/GMMP_COMPATIBILITY_PLAYBOOK.md")
replace_once(
    playbook,
    """## 5. Accepted-version performance audit

The final 4.2.1 cleanup found two important classes of accidental overhead:
""",
    """## 5. Accepted-version performance audit

The final 4.2.1 cleanup found four important classes of accidental overhead:
""",
    "playbook audit count",
)
replace_once(
    playbook,
    """Final policy:
- offscreen pages are idle apart from the minimal geometry needed to keep an overlay hidden;
- Smart-folder visible-row title/interaction sync is posted/coalesced from native scroll and refresh events;
- normal Playlist periodic model-refresh fallback runs only on the foreground page and at a slow interval; native adapter notifications remain the primary refresh path.

Do not hide GMMP's native `w6` logger to make logs look clean. Remove GoneSmart-caused unnecessary queries. Native GMMP queries performed legitimately when a library tab is opened/refocused may remain visible.
""",
    """Final policy:
- offscreen pages are idle apart from the minimal geometry needed to keep an overlay hidden;
- Smart-folder visible-row title/interaction sync is posted/coalesced from native scroll and refresh events;
- normal Playlist periodic model-refresh fallback runs only on the foreground page and at a slow interval; native adapter notifications remain the primary refresh path.

### Player / navigation badges

The Now Playing Auto-DJ badge and Playlist/Smart-Playlist navigation badges are persistent UI extensions, so discovery cost matters even when no feature action is running. The former could repeatedly rescan the full decor tree and rasterize the playback-mode drawable; the latter could rescan every `TextView` and rediscover `getAdapter()` reflection during layout waves.

Final policy:
- cache weak references to already proven native UI anchors;
- on layout/global-layout callbacks, reuse those anchors while they remain attached, visible and semantically valid;
- fall back to structural full-tree discovery only when a cached target disappears or is rebound;
- cache class-level reflection such as adapter getters instead of rediscovering it per ViewGroup;
- cache glyph analysis by drawable identity/state and use only a bounded safety recheck;
- reuse render objects/shaders/paths where possible instead of allocating them on every draw;
- normal accepted-version badge state changes do not emit repetitive info logs.

### Play-flipped postcondition verification

The native 4.2.1 Play-flipped interception is device-accepted. Its old success verifier could poll Queue state up to twelve times at 250 ms intervals after every launch, including a legacy reflective fallback.

Final policy:
- one delayed independent Queue postcondition read is sufficient during normal accepted-version playback;
- at most one legacy structural fallback may run if the primary read is inconclusive;
- there is no retry/polling loop in the normal path;
- repeated diagnostics belong only to an unknown/failing compatibility investigation.

Do not hide GMMP's native `w6` logger to make logs look clean. Remove GoneSmart-caused unnecessary queries. Native GMMP queries performed legitimately when a library tab is opened/refocused may remain visible.
""",
    "playbook new performance classes",
)

completion = Path("docs/GMMP_421_COMPLETION.md")
replace_once(
    completion,
    """| Accepted-version performance cleanup | Accepted implementation | Queue-writer discovery retires from the playback hot path after proof; Smart-folder row reflection is scroll/refresh-coalesced; Playlist/Smart folder pre-draw fallback work is foreground-only. |
""",
    """| Accepted-version performance cleanup | Accepted implementation | Queue-writer discovery retires after proof; folder row work is event-coalesced/foreground-only; persistent player/navigation badges reuse verified native anchors; Play-flipped verification is a single bounded postcondition read. |
""",
    "completion matrix performance row",
)
replace_once(
    completion,
    """- [x] Smart-folder visible-row reflection is coalesced from native scroll/refresh events instead of every pre-draw.
- [x] Temporary cleanup workflow/script removed from the repository.
""",
    """- [x] Smart-folder visible-row reflection is coalesced from native scroll/refresh events instead of every pre-draw.
- [x] Player/Now-Playing badge no longer rescans the decor tree or rerasterizes the glyph on every monitor/layout tick.
- [x] Playlist/Smart navigation badges reuse semantically validated native TextViews during layout waves and fall back to discovery only when needed.
- [x] Play-flipped postcondition verification no longer polls Queue state repeatedly after an accepted native launch.
- [x] Temporary cleanup workflow/script removed from the repository.
""",
    "completion checklist performance items",
)
replace_once(
    completion,
    """## Performance audit result

The final 4.2.1 log review found two GoneSmart-side hot paths worth retiring:

1. Queue current-position writer discovery could repeatedly invoke generic zero-arg state getters. Some GMMP getters perform queue SQL internally, producing hundreds of `w6` queries per second and visible frame loss. The accepted path now prefers the cheap verified native position signal, bounds generic fallback once per candidate, uses cheap-only delayed checks and becomes a no-op after proof.
2. Playlist/Smart-Playlist folder overlays retained some pre-draw work even on attached offscreen ViewPager pages. Expensive visible-row reflection/alignment is now coalesced from scroll/refresh events and periodic fallback refreshes run only on the foreground page.

The remaining `w6` lines emitted when GMMP itself opens/refocuses native library tabs are native queries; GoneSmart should not suppress GMMP's logger. The goal is to stop causing unnecessary queries, not hide them.
""",
    """## Performance audit result

The final 4.2.1 log/code review found four GoneSmart-side hot-path classes worth retiring:

1. Queue current-position writer discovery could repeatedly invoke generic zero-arg state getters. Some GMMP getters perform queue SQL internally, producing hundreds of `w6` queries per second and visible frame loss. The accepted path now prefers the cheap verified native position signal, bounds generic fallback once per candidate, uses cheap-only delayed checks and becomes a no-op after proof.
2. Playlist/Smart-Playlist folder overlays retained some pre-draw work even on attached offscreen ViewPager pages. Expensive visible-row reflection/alignment is now coalesced from scroll/refresh events and periodic fallback refreshes run only on the foreground page.
3. Persistent player/navigation badge helpers could repeat full view-tree discovery during layout waves. The player badge now caches its native Now-Playing/playback-mode anchors, caches drawable analysis and reuses drawing objects; the Playlist/Smart navigation badge keeps weak semantically validated target references and caches adapter-getter reflection. Structural discovery remains the recovery path when native views change.
4. Device-accepted Play-flipped playback still ran a discovery-era postcondition polling loop. Normal runtime now performs one delayed Queue verification plus at most one legacy fallback, with no repeated polling.

The supplied pre-cleanup log also showed that GoneSmart's own explicit log noise was concentrated in the player-badge diagnostics; those accepted-version target/glyph/mode/hidden info lines are now retired. The remaining `w6` lines emitted when GMMP itself legitimately opens/refocuses native library tabs may remain visible. The goal is to stop causing unnecessary queries, not hide them.
""",
    "completion performance audit expansion",
)
replace_once(
    completion,
    """8. Passive discovery is not free: after a boundary graduates, remove both diagnostic noise and repeated reflection/SQL work from normal runtime hot paths.
""",
    """8. Passive discovery is not free: after a boundary graduates, remove both diagnostic noise and repeated reflection/SQL work from normal runtime hot paths.
9. UI observation is not free either: cache semantically proven native anchors and make full view-tree scans a recovery path, especially from `OnGlobalLayout`/pager callbacks.
10. Device-accepted actions still need verification, but that verification should be one bounded postcondition check rather than a discovery-era polling loop.
""",
    "completion durable lessons",
)

print("final performance audit documentation updated")
