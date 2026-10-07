from pathlib import Path
p = Path('AGENTS.md')
s = p.read_text()
anchor = '- Full view-tree scans may remain as a bounded recovery path when a cached native target is detached/replaced, but must not run continuously during normal pager/layout waves.\n'
if anchor not in s:
    if not s.endswith('\n'):
        s += '\n'
    s += '\n## Runtime hot-path invariants\n\n' + anchor
p.write_text(s)
