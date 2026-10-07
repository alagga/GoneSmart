from pathlib import Path

path = Path("app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt")
text = path.read_text()
old = '''    private fun buildUiPage(): View {
        val container = pageContainer()
        container.addView(pageTitle("UI"))
        container.addView(sectionTitle("PLAYLISTS"))
        container.addView(settingGroup(listOf(
            SettingSpec(
                GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST,
                "✓",
                "Multi-playlist selection",
                "Long-press a playlist in GMMP's Add to Playlist dialog, select " +
                    "multiple destinations, then confirm once. Uses GMMP's " +
                    "native playlist writer, theme colors and translations.",
                COLOR_ACCENT
            )
        )))
        container.addView(verticalGap(16))
        container.addView(infoCard(
            title = "How to use",
            body = "In GoneMAD Music Player, choose Add to Playlist for " +
                "one or more tracks. Long-press the first destination, " +
                "tap other playlists to select or deselect them, then tap " +
                "the checkmark to add the same tracks to every selected " +
                "playlist. Back cancels selection without closing the picker."
        ))
        container.addView(verticalGap(12))
        container.addView(infoCard(
            title = "Independent of Smart DJ",
            body = "This feature is optional and works even when Smart DJ " +
                "is disabled. The normal single-playlist tap and the " +
                "plus button for creating a playlist are unchanged."
        ))
        refreshSettingsSwitches()
        return scrollPage(container)
    }

'''
if text.count("private fun buildUiPage(): View") != 2:
    raise SystemExit("expected exactly two buildUiPage implementations before cleanup")
if old not in text:
    raise SystemExit("stale buildUiPage implementation not found")
text = text.replace(old, "", 1)
if text.count("private fun buildUiPage(): View") != 1:
    raise SystemExit("expected exactly one buildUiPage implementation after cleanup")
path.write_text(text)
