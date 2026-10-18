#!/usr/bin/env python3
"""Update the Shields endpoint with actual GoneSmart GitHub APK downloads.

Only .apk release assets are counted; forum BBCode downloads are excluded.
Called by the scheduled GitHub Actions job. The endpoint is deliberately
generated within this repository rather than using the unreliable Shields
GitHub all-releases downloads endpoint.
"""
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

REPO = os.environ.get("GITHUB_REPOSITORY", "alagga/GoneSmart")
OUTPUT = Path(__file__).resolve().parents[1] / "assets/badges/downloads.json"


def fetch_apk_downloads() -> int:
    url = f"https://api.github.com/repos/{REPO}/releases?per_page=100"
    token = os.environ.get("GH_TOKEN", "")
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "GoneSmart-download-badge",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    total = 0
    while url:
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            releases = json.load(response)
            if not isinstance(releases, list):
                raise ValueError("Expected an array of GitHub releases")
            for release in releases:
                if release.get("draft", False):
                    continue
                for asset in release.get("assets", []):
                    if asset.get("name", "").lower().endswith(".apk"):
                        total += int(asset["download_count"])

            # Follow GitHub pagination so the lifetime count remains accurate.
            next_url = None
            for part in response.headers.get("Link", "").split(","):
                if 'rel="next"' in part:
                    next_url = part.split(";", 1)[0].strip().strip("<>")
                    break
            url = next_url
    return total


def main() -> None:
    total = fetch_apk_downloads()
    badge = {
        "schemaVersion": 1,
        "label": "Downloads",
        "message": str(total),
        "color": "5f57b8",
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(badge, indent=2) + "\n", encoding="utf-8")
    print(f"Total GoneSmart APK downloads: {total}")


if __name__ == "__main__":
    main()
