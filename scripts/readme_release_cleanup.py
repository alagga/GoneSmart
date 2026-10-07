from pathlib import Path

path = Path("README.md")
text = path.read_text()

replacements = {
    "Confirmations reuse GMMP's translated **started** string if available, otherwise a neutral checkmark. No copied translation table is required.":
        "Confirmations reuse GMMP's translated **started** string if available; otherwise GoneSmart uses the readable English fallback **started**. No copied translation table is required.",
    "**Status:** Feature complete in the v0.4.x development branch; the maintainer reports Track Auto-DJ working on-device with GMMP 4.2.0. The 24 September development log showed six successful five-song starts and one earlier intermittent queue-isolation failure during an old-queue refill. That older clearing path has been replaced with native atomic isolation by unique queue-entry ID. The maintainer subsequently retested the queue-row Track Auto-DJ flow with the corrected build and reported no recurrence of the failure; this targeted device regression is accepted as passed. Other GMMP versions remain unverified. See [Track Auto-DJ test and notes](docs/TRACK_MIX_TESTING.md).":
        "**Status:** Accepted for GoneSmart 0.4.0 on GMMP 4.2.1. The final device pass covers ordinary and large Smart-Playlist starts, exact seed preservation/isolation, Initial Size refill, continued Auto-DJ playback and the bounded provisional-CURRENT hand-off used while GMMP rebuilds a Smart-Playlist queue. Other GMMP versions remain unverified. See [Track Auto-DJ test and notes](docs/TRACK_MIX_TESTING.md).",
    "## Playlist folders (GMMP 4.2.0 — feature-complete on tested setup)":
        "## Playlist folders (GMMP 4.2.1 — accepted for 0.4.0)",
    "Enable **UI → Playlist folders** (available in both debug and future release builds on this development branch) to browse nested physical playlist folders in":
        "Enable **UI → Playlist folders** to browse nested physical playlist folders in",
    "resource; the installed GMMP 4.2.0 language inventory is covered in both.":
        "resource; the maintained GMMP language inventory is covered in both.",
    "The maintainer accepted the complete current folder flow on the tested\nGMMP 4.2.0 device on 28 September 2026. Other GMMP versions, alternative\nskins, and independent native-speaker review of all Move translations remain\nseparate compatibility/release-hardening work; this development-branch\nacceptance is **not** a newly published release. See":
        "The maintainer accepted the complete current folder flow again on the tested\nGMMP 4.2.1 setup during the final 0.4.0 device pass. Other GMMP versions,\nalternative skins, and independent native-speaker review of all Move translations\nremain separate compatibility work. See",
    "## Smart-Playlist folders (GMMP 4.2.0 — feature-complete on tested setup)":
        "## Smart-Playlist folders (GMMP 4.2.1 — accepted for 0.4.0)",
    "The maintainer accepted the current Smart-folder navigation, scrolling/overscroll, creation, deletion, single/multi Move, drawer badge and native-dialog behavior on the tested GMMP 4.2.0 setup by 30 September 2026. Other GMMP versions and untested skins remain compatibility work. See [Smart-Playlist folders](docs/SMART_PLAYLIST_FOLDERS.md).":
        "The maintainer accepted Smart-folder navigation, scrolling/overscroll, creation, deletion, single/multi Move, drawer badge and native-dialog behavior on the tested GMMP 4.2.1 setup during the final 0.4.0 device pass. Other GMMP versions and untested skins remain compatibility work. See [Smart-Playlist folders](docs/SMART_PLAYLIST_FOLDERS.md).",
    "Playlist Link rules have been device-tested on GMMP 4.2.0 for add/save/reopen/edit, normal Smart-Playlist display/playback and dynamic source membership changes.":
        "Playlist Link rules have been device-tested on GMMP 4.2.1 for add/save/reopen/edit, normal Smart-Playlist display/playback and dynamic source membership changes.",
    "Playlist Link is now installed in both debug and release build variants on the v0.4 development branch; the old reverse-engineering reader/query probes are not part of the shipping path.":
        "Playlist Link ships in GoneSmart 0.4.0 in both debug and release build variants; the old reverse-engineering reader/query probes are not part of the shipping path.",
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"README release text not found: {old[:100]!r}")
    text = text.replace(old, new, 1)

for forbidden in ("GMMP 4.2.0", "neutral checkmark", "development branch", "future release builds"):
    if forbidden in text:
        raise SystemExit(f"stale README release wording remains: {forbidden}")

path.write_text(text)
