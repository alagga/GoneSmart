# GMMP 4.2.0 native playlist creation — APK investigation (2026-09-25)

Status: **reverse engineering in progress; no write hook enabled**. This document describes observations from the GMMP APK supplied privately by the maintainer. Do not commit or redistribute the proprietary APK.

## Input and scope

SHA-256 of supplied `base(1).apk`: `3299c96a558e10ed421dadcfce032442b6758a896d824f43996d72a68148cce8`. It contains three main DEX files. The following class and method observations are from **classes3.dex**. These are obfuscated and tied to this exact supplied APK; verify at runtime before hooking.

## Confirmed call-path pieces

| Class/method | APK observation | Interpretation and limitation |
| --- | --- | --- |
| `tp3` / `PlaylistListPresenter.kt`, `y2(): void` | Subscribes to a Kotlin method reference with metadata strings `onAddNewPlaylist()V` and `onAddNewPlaylist`, and constructs event/subscription objects. | Confirmed main playlist presenter has a dedicated create event. Its event subscriber and ultimate file/DB writer are **not yet mapped**. Do not hook `y2` as the create writer; it sets up subscriptions. |
| `bo3` / `PlaylistAddToFragment.kt`, `I3(): void` | Instantiates `go3` / `PlaylistAddToPresenter.kt`. Its native FAB is exposed through `bo3.k2()`. | The picker and main tab use related presenters, but identical creation paths are not established. |
| `go3` / `PlaylistAddToPresenter.kt`, `y2(): void` | Registers its own events, including `io3` / `PlaylistAddToSelectionBehavior`. | The existing native add-to-playlist writer `io3.r(Context,ie0)` remains separate from creation and must not regress. |
| `hp3` / `PlaylistFile.kt`, `d(): boolean` | Checks file-write access, creates parent directories via `File.getParentFile().mkdirs()`, opens an output stream with `yx4.h(Context, File)`, and writes playlist contents. Handles failure. | There is a native file-save primitive that supports a file inside a directory, but this **alone is not proof** of the new-playlist creation transaction or DB registration. |
| `x6.b(Context, File): boolean` | Calls `yx4.f(Context,File)` and, on success, publishes via `gc1.f(Object)`. | A file-registration/notification *candidate*. Verify its actual behavior, transaction ordering and return value before treating it as a safe create path. |
| `zp3.M(Context, wp3): void` | Handles actions on an **existing** native playlist; invokes `x6.b(Context,File)` and passes `playlist_file_uri` and `playlist_file_display_name` in a Bundle. | Existing-playlist action only; **not** evidence that this is the new-playlist create callback. |
| `oo3` / `PlaylistDao_Impl.kt` | Generated SQL includes `INSERT OR ABORT`, `INSERT OR REPLACE` and `UPDATE OR ABORT` for `playlist_file_table`. | GMMP owns a URI/display-name/id DB row. Independently moving a newly created M3U can leave stale rows; don't emulate native creation by moving. |

These observations were obtained by parsing DEX string/type/method/class tables and inspecting method-code references in the supplied APK. A code-unit scan is useful for mapping candidates but is not a complete control-flow decompilation; conclusions about method *purpose* beyond concrete invocations must be tested.

## Next implementation gate

1. Trace the `tp3.onAddNewPlaylist` event subscriber through the actual user-entered filename and creation callback. Trace the picker FAB create callback separately, and establish whether both delegate to one native create primitive.
2. Identify the real configured playlist root and exact destination parameter in the create path. Distinguish file writing (`hp3.d`), DB registration/refresh (possible `x6.b`) and success UI. Do not inject an unverified guessed method or write GMMP DB rows directly.
3. Add a **version-guarded** hook that substitutes the current validated physical folder *before* native filename validation/file construction, so original native creation handles both file and DB. Capture the destination when the dialog opens; cancel if the folder or Group root setting changes before confirmation.
4. Keep only `menuAdd` hidden in forbidden locations; all other overflow items remain. Show it in physical folders **only when the redirect is active and verified**. Picker FAB always confirms selected destinations before considering creation.
5. Regression-test root and virtual Other Locations both ways, physical folders at multiple depths, duplicate filename, invalid/vanished destination, picker and tab create, database row identity and list refresh. Device verification is required; unit/CI green alone cannot certify GMMP internals.

The current branch intentionally retains the physical-creation guard and the working root flow. Do not enable subfolder creation based on `hp3.d` or `x6.b` alone.
