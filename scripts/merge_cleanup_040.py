from pathlib import Path

module = Path("app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt")
text = module.read_text()
duplicate = """            if (key == GoneSmartSettingsKeys.KEY_MULTI_PLAYLIST) {
                playlistController.setEnabled(options.multiPlaylistEnabled)
            }

"""
if duplicate not in text:
    raise SystemExit("early duplicate multi-playlist listener block not found")
text = text.replace(duplicate, "", 1)
module.write_text(text)

badge = Path("app/src/main/java/io/github/alagga/gonesmart/PlayerAutoDjBadgeController.kt")
text = badge.read_text()
stale = """        /**
         * GMMP can change colorAccent without recreating the playlist FAB.
         * Update the existing overlay in place so its position, bounds,
         * and click handling remain unchanged during a track transition.
         */
        fun updateColor(color: Int) {
            if (badgeColor == color) return
            badgeColor = color
            fillPaint.color = color
            highlightPaint.color = lighten(color, 0.58f)
            invalidateSelf()
        }

"""
if stale not in text:
    raise SystemExit("stale duplicate updateColor block not found")
text = text.replace(stale, "", 1)
if text.count("fun updateColor(color: Int)") != 1:
    raise SystemExit("expected exactly one updateColor implementation")
badge.write_text(text)

dead = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistDiagnosticReporter.kt")
if dead.exists():
    dead.unlink()
