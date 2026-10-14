from pathlib import Path

p = Path('docs/GMMP_421_REACCEPTANCE_CHECKLIST.md')
t = p.read_text(encoding='utf-8')

def replace_once(old, new, label):
    global t
    c = t.count(old)
    if c != 1:
        raise SystemExit(f'{label}: expected 1 match, got {c}')
    t = t.replace(old, new, 1)

replace_once(
    'Repair source commit: `abfee93177808f13e1611e943551b6d796a99240`.',
    'Repair source commit: `ab139568db9df78f49929e6621ca7c222c5fe359`.',
    'source pin'
)
replace_once(
    "7. If providers genuinely yield zero recommendations, Track Mix must preserve only the exact pinned selected row from the source queue, rebase it to position 1, and let GMMP's native Auto-DJ refill create the Initial-Size followers. Source Smart-Playlist followers must not be retained as a substitute recommendation set.\n8. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR` and no post-materialization Smart Track-ID retarget. Prepared-recommendation queue mutation after bootstrap remains prune-only; the zero-provider fallback may delete source rows and compact native queue positions, but it must never rewrite follower Track IDs.",
    "7. If providers genuinely yield zero recommendations and native fallback is allowed, Track Mix must preselect the complete native GMMP fallback remainder **before native Play**. The first preselected fallback Track ID must be written only into the already existing temporary current+1 row before GMMP's natural position-2 AudioSource read, then preserved as final queue slot 2 through any Smart-Playlist source rebuild. Only after that continuity is verified may rebuilt future rows be reconciled to the remaining already-preselected native fallback IDs, surplus source rows pruned, and positions compacted to 1..Initial Size.\n8. Startup must contain no 4.2.1 `CLEAR_QUEUE` / `TRACK MIX NATIVE CLEAR`, no seed-only interval with missing current+1, and no manual replay/invocation of the observed MusicService next-source boundary. Prepared SMART recommendations retain the accepted pre-materialization bootstrap/prune path. Native-fallback follower Track-ID writes are allowed only as same-ID reconciliation to the **already preselected** fallback IDs, with final slot 2 identical to the Track ID that GMMP naturally preloaded before the source rebuild.",
    'generic track mix rules'
)

anchor = '## r36 regression focus\n'
if anchor in t and 'Historical note: r36-r39' not in t:
    t = t.replace(
        anchor,
        '## Historical note: r36-r39 zero-provider mechanics are retired\n\nThe sections below document device experiments that led to r44. Their seed-isolation, explicit native-refill and `MIX NEXT SOURCE REFRESH` expectations are **historical evidence only and must not be used as current acceptance criteria**. r44 supersedes those mechanics because the 2026-10-14 device log proved the temporary missing-current+1 interval cached a null AudioSource and manual MusicService replay did not restore decoder continuity.\n\n' + anchor,
        1
    )

if 'Repair source commit: `ab139568db9df78f49929e6621ca7c222c5fe359`.' not in t:
    raise SystemExit('new source pin missing')
if 'no seed-only interval with missing current+1' not in t:
    raise SystemExit('new continuity invariant missing')
if 'Historical note: r36-r39' not in t:
    raise SystemExit('historical retirement marker missing')

p.write_text(t, encoding='utf-8')
print('r44 docs pin applied')
