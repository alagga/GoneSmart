from pathlib import Path

track = Path("app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt")
text = track.read_text()
old = '                toast(request.context, request.menuLabel + " ✓")'
new = '                toast(request.context, request.confirmation)'
if old not in text:
    raise SystemExit("Track Mix checkmark toast not found")
track.write_text(text.replace(old, new, 1))

flip = Path("app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt")
text = flip.read_text()
old = '''                    if (count > 1) {
                        (nativeString(context, "queue") ?: "").trim()
                            .takeIf(String::isNotBlank)?.plus(" ✓") ?: "✓"
                    } else {
'''
new = '''                    if (count > 1) {
                        "Queue reversed"
                    } else {
'''
if old not in text:
    raise SystemExit("Queue Flip checkmark toast not found")
flip.write_text(text.replace(old, new, 1))
