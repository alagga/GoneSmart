from pathlib import Path
p = Path('app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt')
t = p.read_text()
old = '''        val displayName = PlaylistBridgePolicy.nativeSmartPlaylistDisplayName(value)\n            ?.trim()?.takeUnless(String::isBlank) ?: return false'''
new = '''        val displayName = value.substringAfter('|', "")\n            .trim().takeUnless(String::isBlank) ?: return false'''
if old not in t:
    raise SystemExit('r33 correction guard failed')
p.write_text(t.replace(old, new, 1))
print('r33 correction applied')
