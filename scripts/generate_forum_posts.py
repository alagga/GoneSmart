#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORUM_DIR = ROOT / "docs/forum"
THREAD_TEMPLATE = FORUM_DIR / "THREAD_START_TEMPLATE.bbcode"
THREAD_OUTPUT = FORUM_DIR / "THREAD_START.bbcode"
REPLY_OUTPUT = FORUM_DIR / "LATEST_RELEASE_REPLY.bbcode"


def read_version() -> str:
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        raise RuntimeError("Could not find versionName in app/build.gradle.kts")
    return match.group(1)


def read_tested_gmmp() -> str:
    text = (ROOT / "app/src/main/java/io/github/alagga/gonesmart/GmmpCompatibilityPolicy.kt").read_text(encoding="utf-8")
    match = re.search(r'TESTED_VERSION\s*=\s*"([^"]+)"', text)
    if not match:
        raise RuntimeError("Could not find GmmpCompatibilityPolicy.TESTED_VERSION")
    return match.group(1)


def inline_bbcode(text: str) -> str:
    text = re.sub(r'\[([^]]+)\]\((https?://[^)]+)\)', r'[url=\2]\1[/url]', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'[b]\1[/b]', text)
    text = re.sub(r'(?<!\*)\*([^*]+)\*(?!\*)', r'[i]\1[/i]', text)
    text = text.replace('`', '')
    return text


def markdown_release_to_bbcode(markdown: str) -> str:
    out: list[str] = []
    list_open = False
    skip_section = False

    def close_list() -> None:
        nonlocal list_open
        if list_open:
            out.append("[/list]")
            list_open = False

    for raw in markdown.splitlines():
        line = raw.rstrip()
        if line.startswith("# "):
            continue
        if line.startswith("## "):
            close_list()
            title = line[3:].strip()
            skip_section = title.lower() == "installation"
            if skip_section:
                continue
            out.extend(["", f"[size=4][b]{inline_bbcode(title)}[/b][/size]", ""])
            continue
        if skip_section:
            continue
        if line.startswith("### "):
            close_list()
            out.extend(["", f"[b]{inline_bbcode(line[4:].strip())}[/b]", ""])
            continue
        if line.startswith("- "):
            if not list_open:
                out.append("[list]")
                list_open = True
            out.append(f"[*]{inline_bbcode(line[2:].strip())}")
            continue
        close_list()
        if not line or line == "---":
            if out and out[-1] != "":
                out.append("")
            continue
        out.append(inline_bbcode(line))

    close_list()
    while out and out[-1] == "":
        out.pop()
    return "\n".join(out).strip()


def render() -> tuple[str, str]:
    version = read_version()
    tested_gmmp = read_tested_gmmp()
    release_url = f"https://github.com/alagga/GoneSmart/releases/tag/v{version}"

    thread = THREAD_TEMPLATE.read_text(encoding="utf-8")
    thread = (
        thread.replace("{{VERSION}}", version)
        .replace("{{TESTED_GMMP}}", tested_gmmp)
        .replace("{{RELEASE_URL}}", release_url)
    )

    notes = (ROOT / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    body = markdown_release_to_bbcode(notes)
    reply = f"""[size=5][b]GoneSmart v{version} released[/b][/size]

The new release is now available. I've updated the first post with the current compatibility information as well.

[b]GitHub release / APK:[/b]
[url={release_url}]{release_url}[/url]

{body}
"""
    return thread.rstrip() + "\n", reply.rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate copy-ready BBCode for the GMMP forum thread.")
    parser.add_argument("--check", action="store_true", help="Fail if committed generated files are stale.")
    args = parser.parse_args()

    thread, reply = render()
    expected = {THREAD_OUTPUT: thread, REPLY_OUTPUT: reply}

    if args.check:
        stale = []
        for path, content in expected.items():
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(path.relative_to(ROOT))
        if stale:
            print("Forum copy is stale. Run: python3 scripts/generate_forum_posts.py", file=sys.stderr)
            for path in stale:
                print(f" - {path}", file=sys.stderr)
            return 1
        print("Forum copy is up to date.")
        return 0

    for path, content in expected.items():
        path.write_text(content, encoding="utf-8")
        print(f"Wrote {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
