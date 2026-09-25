# Contributing

Thanks for helping improve GoneSmart.

**Read [AGENTS.md](AGENTS.md) before editing, and [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) for companion-app layout, exact colors, the branded sparkle and native GMMP theme parity.** It is the maintained source of truth for the project's coding conventions, English companion versus native GMMP translations, dynamic GMMP styling, branch/CI/device-test workflow and current feature handoff. When a lasting rule is agreed or changed, update AGENTS.md in the same development commit along with relevant tests and docs.

## Bug reports

Please include:

- GoneSmart version
- exact GMMP version
- Vector/LSPosed or LSPatch version
- Android version
- what you expected and what happened
- relevant GoneSmart in-app logs or Logcat excerpts
- whether the issue reproduces with default GoneSmart settings

Do not include API keys, signing material, passwords or private account data.

## Pull requests

Keep changes focused and explain how they were tested. For GMMP-hook changes, document the exact GMMP build used for reverse engineering/testing.

## Compatibility reports

Reports that a different GMMP, Vector or LSPatch version works are useful. Please include exact version numbers and which areas you exercised (queue refill, settings, indicator, fallback, etc.).


## Music / genre matching feedback

GoneSmart's current normalization and version matching is especially tuned for electronic music. Feedback from other genres is useful for making the matching rules more general without breaking existing behavior.

For a matching feature request, please include a few concrete examples:

- artist as stored in GMMP
- title as stored in GMMP
- which version/release relationship you expect (same song family, distinct track, live version, remix, etc.)
- what GoneSmart did instead

Do not upload copyrighted audio. Metadata examples and relevant GoneSmart logs are enough.
