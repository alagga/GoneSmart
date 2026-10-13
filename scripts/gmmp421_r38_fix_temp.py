from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing expected block in {path}: {old[:120]!r}")
    text2 = text.replace(old, new, 1)
    p.write_text(text2)

# Runtime: state-writer hooks receive qr child/state hosts (dx3 on GMMP 4.2.1).
# Do not let that child receiver overwrite the independently captured qr owner.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    '''                    if (receiver != null) {\n                        if (receiver.javaClass.name == "qr") {\n                            trackMixAutoDj = WeakReference(receiver)\n                        }\n                        trackMixController.captureNativeAutoDj(receiver)\n                        queueFlipController.captureNativeAutoDj(receiver)\n                    }\n                    queueFlipController.aroundNativeStatePositionCommand(''',
    '''                    // stateWriterCandidates are methods on Auto-DJ child/state hosts.\n                    // Their receiver is not the controller-owned Auto-DJ instance and must\n                    // never replace the independently captured refill/service owner.\n                    queueFlipController.aroundNativeStatePositionCommand('''
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r37"',
    'private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r38"'
)

# Regression test: structural state-writer discovery yields child-host methods.
replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/NativeQueuePlaybackDiagnosticsTest.kt",
    '''        assertEquals(1, methods.size)\n        assertEquals("setPosition", methods.single().name)\n''',
    '''        assertEquals(1, methods.size)\n        assertEquals("setPosition", methods.single().name)\n        assertEquals(StateHost::class.java, methods.single().declaringClass)\n'''
)

# Standing architecture rule (no chronology in AGENTS.md).
replace_once(
    "AGENTS.md",
    '''- A Track-Mix-only Current rebase boundary may use a native zero-based state coordinate while `queue_table.queue_position` is one-based. Infer a request-scoped 0/1 argument/read offset only from a naturally observed structural state-writer call plus the later exact pinned CURRENT; ambiguity, stale evidence or a wider offset fails closed. Never generalize that weaker mapping into Queue Flip's global position-writer proof.\n''',
    '''- A Track-Mix-only Current rebase boundary may use a native zero-based state coordinate while `queue_table.queue_position` is one-based. Infer a request-scoped 0/1 argument/read offset only from a naturally observed structural state-writer call plus the later exact pinned CURRENT; ambiguity, stale evidence or a wider offset fails closed. Never generalize that weaker mapping into Queue Flip's global position-writer proof.\n- Passive hooks installed on Auto-DJ child/state hosts must never replace the independently captured Auto-DJ owner. Child receivers supply state-writer evidence only; controller-owned refill/service identity remains authoritative for queue reads, writer correlation and Track Mix.\n'''
)

# Durable compatibility notes.
for path, paragraph in {
    "docs/GMMP_COMPATIBILITY_PLAYBOOK.md": '''\n### GMMP 4.2.1 Track Mix owner retention\n\nState-writer hooks are installed on child/state-host methods discovered from fields of the native Auto-DJ owner. The hook receiver is therefore evidence for the writer boundary, not a replacement Auto-DJ owner. Preserve the independently captured controller-owned Auto-DJ instance across child callbacks; otherwise request-scoped Current-writer correlation will inspect the child as though it were the owner and fail closed even when the natural writer call was observed.\n''',
    "docs/GMMP_421_COMPLETION.md": '''\n### r38 Track Mix owner wiring\n\nThe prepared-pool Levels regression is accepted from r37. The remaining zero-provider Smart-Playlist failure was traced to state-child ownership: the passive `dx3.c2(int)` hook replaced the captured `qr` owner immediately before writer correlation. r38 keeps child receivers observational only so the existing request-scoped 0/1 Current mapping can correlate against the real Auto-DJ owner. No recommendation ranking, prepared bootstrap, exact-path rebind, queue isolation or next-source boundary changed.\n'''
}.items():
    p = Path(path)
    text = p.read_text()
    if paragraph.strip() not in text:
        p.write_text(text.rstrip() + "\n" + paragraph)

# Re-acceptance: r38 is the current single device candidate.
p = Path("docs/GMMP_421_REACCEPTANCE_CHECKLIST.md")
text = p.read_text()
text = text.replace(
    "current **r37** Playlist Link / Track Mix repair candidate",
    "current **r38** Playlist Link / Track Mix repair candidate",
    1
)
text = re.sub(
    r"Repair source commit: `[^`]+`\.",
    "Repair source commit: `R38_REPAIR_SOURCE_PENDING`.",
    text,
    count=1
)
r38 = '''\n## r38 regression focus\n\n- Avicii `Levels (Radio Edit)` is accepted from the r37 device run: all prepared GoneSmart slots materialized correctly. Re-run it once only as a regression; the zero-provider path must remain inactive.\n- Re-run zero-provider BUNT. After the natural child-state call, expect `QUEUE POSITION TRACK MIX CANDIDATE` while the retained Auto-DJ owner remains the controller-owned refill/service instance. A child/state receiver must never replace that owner.\n- After the accepted exact-path rebind and `MIX CURRENT PIN`, expect `QUEUE POSITION TRACK MIX MAPPING` with only a 0/1 request-scoped offset, then seed isolation/rebase to queue position 1, four native Auto-DJ followers, compact positions 1..5 and the already accepted next-source refresh for position 2.\n- BUNT. must finish at **1 of 5** and transition naturally into the newly generated Track 2.\n- Safety invariants remain: no `CLEAR_QUEUE`, no materialized Track-ID rewrite, no same-position CURRENT refresh, no hard-coded state/next-source method identity, and ambiguous/stale owner or coordinate evidence fails closed.\n'''
if "## r38 regression focus" not in text:
    text = text.rstrip() + "\n" + r38
p.write_text(text)
