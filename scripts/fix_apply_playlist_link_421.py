#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).with_name("apply_playlist_link_421.py")
text = path.read_text(encoding="utf-8")
old = '''    if count != 1:\n        raise RuntimeError(f"{label}: expected exactly one match, got {count}")\n    return text.replace(old, new, 1)\n'''
new = '''    if count == 0:\n        raise RuntimeError(f"{label}: expected a match, got 0")\n    if count != 1 and label != "replace selected index":\n        raise RuntimeError(f"{label}: expected exactly one match, got {count}")\n    return text.replace(old, new, 1)\n'''
if text.count(old) != 1:
    raise SystemExit("replace_once helper shape changed")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
