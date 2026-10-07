# GoneMAD Music Player forum copy

This directory keeps copy-ready BBCode for the GoneSmart thread in the GMMP General Discussion forum:

- `THREAD_SUBJECT.txt` — suggested thread subject.
- `THREAD_START_TEMPLATE.bbcode` — maintained source for the first post.
- `THREAD_START.bbcode` — generated first post with the current GoneSmart / tested-GMMP versions.
- `LATEST_RELEASE_REPLY.bbcode` — generated reply for the current `RELEASE_NOTES.md`.

Generate both copy-ready files with:

```bash
python3 scripts/generate_forum_posts.py
```

Do not hand-edit the generated `THREAD_START.bbcode` or `LATEST_RELEASE_REPLY.bbcode`; edit the template and/or `RELEASE_NOTES.md`, then regenerate.

## Pasting into ProBoards

Paste the generated `.bbcode` contents into the forum editor's **BBCode/source view**, not the visual/WYSIWYG view. The generated copy deliberately uses a conservative subset of forum markup: `[b]`, `[i]`, `[url]` and plain `•` bullet characters. It intentionally avoids `[list]`, `[*]`, `[ul]` and `[li]` list markup because the visual editor can rewrite pasted list structures and produce broken closing tags or empty list items.

The generator validates this conservative format, and the normal Build workflow checks that the generated files are current. The Release APK workflow regenerates them, uploads versioned BBCode copies as release assets, and on a manual release from `main` refreshes the generated files in the repository if necessary.

Forum: https://gonemadmusicplayer.proboards.com/board/5/general-discussion
