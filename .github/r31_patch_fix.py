from pathlib import Path

path = Path("app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt")
text = path.read_text()
old = "        private const val LIBRARY_PATH_INDEX_MAX_AGE_MS = 2L * 60L * 1000L\n"
new = (
    "        private const val MAX_IN_VALUES = 800\n"
    "        private const val LIBRARY_PATH_INDEX_MAX_AGE_MS = 2L * 60L * 1000L\n"
)
if old not in text:
    raise SystemExit("r31 library index constant insertion point missing")
path.write_text(text.replace(old, new, 1))
