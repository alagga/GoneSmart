from pathlib import Path
import re

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one exact match, got {count}')
    write(path, text.replace(old, new, 1))

module = 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
replace_once(
    module,
    '''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r34"''',
    '''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r35"'''
)
replace_once(
    module,
    '''                val originalList = chain.getArg(listIndex) as? List<*>\n                val trackMixWindow = originalList?.let { source ->\n                    trackMixController.windowNativePlaybackForTrackMix(\n                        action,\n                        source\n                    )\n                }\n                val effectiveList = trackMixWindow ?: originalList\n                val reversed = queueFlipController\n                    .consumeReversePlaylistForNativePlay(\n                        action,\n                        effectiveList\n                    )\n                val replacementList: List<*>? =\n                    reversed?.tracks ?: trackMixWindow''',
    '''                val originalList = chain.getArg(listIndex) as? List<*>\n                val reversed = queueFlipController\n                    .consumeReversePlaylistForNativePlay(\n                        action,\n                        originalList\n                    )\n                val replacementList: List<*>? = reversed?.tracks'''
)

for dead in [
    'app/src/main/java/io/github/alagga/gonesmart/TrackMixNativePlayWindowPolicy.kt',
    'app/src/test/java/io/github/alagga/gonesmart/TrackMixNativePlayWindowPolicyTest.kt'
]:
    p = ROOT / dead
    if not p.exists():
        raise SystemExit(f'{dead}: expected r34 file missing')
    p.unlink()

append = '''\n\n## GMMP 4.2.1 r35 Track Mix CURRENT identity\n- r34 device evidence proved the BUNT. context-track Play does not traverse the `MusicService.w1(action=0, List, ...)` list boundary: `prepared=0` was observed but `MIX NATIVE PLAY WINDOW` never fired. That source-window experiment is retired and must not be revived as a Track Mix dependency.\n- On 4.2.1 a Smart-Playlist rebuild may replace the selected row's native `queue_id` while the already-proven selected audio keeps playing. After Track Mix has first proven CURRENT by native queue entry, and only inside the existing Smart-source completion guard, a vanished entry hint may rebind CURRENT to the pre-resolved exact `track_uri` iff that exact path occurs exactly once in the rebuilt native queue.\n- The exact-path rebind is request-scoped, one-shot, case-sensitive, and non-normalizing. Zero or multiple exact path matches fail closed. Track ID alone never authorizes the rebind. Once accepted, the replacement native `queue_id` becomes the new pinned identity for settle/prune/Auto-DJ verification.\n- The accepted r34 Smart-Playlist row-label behavior remains invariant: preserve GMMP's native styled `CharSequence` and replace only the localized prefix; never mutate row gravity, text size, typeface, or layout.\n'''
for doc in ['AGENTS.md', 'docs/GMMP_COMPATIBILITY_PLAYBOOK.md', 'docs/GMMP_421_COMPLETION.md']:
    text = read(doc)
    if '## GMMP 4.2.1 r35 Track Mix CURRENT identity' not in text:
        write(doc, text.rstrip() + append + '\n')

check = 'docs/GMMP_421_REACCEPTANCE_CHECKLIST.md'
text = read(check)
text = text.replace(
    'current **r34** Playlist Link / Track Mix repair candidate',
    'current **r35** Playlist Link / Track Mix repair candidate',
    1
)
text = re.sub(
    r'Repair source commit: `[^`]+`\.',
    'Repair source commit: `R35_REPAIR_SOURCE_PENDING`.',
    text,
    count=1
)
r35 = '''\n\n## r35 regression focus\n- The Smart-Playlist rule label is accepted from r34: it must remain **Smart Playlist: <name>** with native typography/alignment unchanged.\n- Re-run the zero-provider BUNT. Track Mix. The retired `MIX NATIVE PLAY WINDOW` marker must be absent. After the initially proven BUNT. queue entry disappears during Smart-Playlist materialization, expect exactly one `GMMP QUEUE CURRENT | track-mix path rebind ... exact=true` followed by `MIX CURRENT REBIND ... source=exact-path | verified=true`.\n- The rebind must adopt the replacement native `queue_id`, then reach `MIX PLAY VERIFIED`, `MIX CURRENT PIN`, the existing prune/Auto-DJ path, and finally `MIX AUTO-DJ` / `MIX VERIFIED`. Zero or duplicate exact path matches must not rebind.\n- Re-run Avicii `Levels (Radio Edit)` as regression. Its prepared recommendation pool must continue through the accepted controller-owned `qr` bootstrap; no exact-path rebind should be needed in the normal successful path, and the natural Track-2 decoder/audio transition must remain intact.\n- Safety invariants remain: no 4.2.1 `CLEAR_QUEUE`, no same-position playback refresh, no post-materialization Track-ID retarget, and no current selection inferred from track ID alone.\n'''
if '## r35 regression focus' not in text:
    text = text.rstrip() + r35 + '\n'
write(check, text)

print('r35 correction applied')
