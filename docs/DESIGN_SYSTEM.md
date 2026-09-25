# GoneSmart design system — companion app, GMMP extensions and branding

**Status:** Maintainer-approved standing design direction, consolidated 2026-09-25 from older discussions, current code and the verified feature docs. **Read together with [AGENTS.md](../AGENTS.md)**. This document specifies what the team should preserve; it does **not** claim all still-experimental screens have passed device tests. The current implementation is the checked-out source, not an outdated screenshot or debug log.

## 1. Two related but intentionally different visual systems

**GoneSmart companion app:** Own, consistent **English-only dark Material design**, inspired by the accepted InstaEclipse reference. It has a fixed violet/lilac brand palette, large rounded cards, a rounded GMMP-inspired app icon with a GoneSmart sparkle, grouped settings and a fixed bottom navigation. **Do not repaint this app** to match the current GMMP album cover.

**GoneSmart-injected GMMP controls:** Look native to the **current GMMP theme, palette and selected view mode**, including the user's dynamic album-art colors. Use genuine native GMMP text, row layout, surfaces, spacing, ripple and menu/FAB controls. GoneSmart's **lilac two-star sparkle** identifies added GoneSmart actions without recoloring the entire native control. The **Now Playing Auto-DJ sparkle** is an explicit exception: its **green/red/absent** color encodes runtime status, not lilac branding.

The user wants a recognizable GoneSmart marker on **GoneSmart-added GMMP features/actions**, not on unrelated stock GMMP functionality. Do not cover every existing native button or replace native icons with logos.

## 2. Companion app: actual palette in MainActivity.kt

These are the **current source constants** as of 2026-09-25, not independently standardized Android Material tokens. When changing colors, update the real UI, supporting documentation and screenshots/visual checks together rather than duplicating arbitrary literals in each screen.

| Source constant | Hex | Existing purpose |
| --- | --- | --- |
| COLOR_BG | `#151419` | Main dark app background; system status bar |
| COLOR_SURFACE | `#1E1D23` | Main rounded card surfaces |
| COLOR_SURFACE_2 | `#25242B` | Secondary surface and header version badge |
| COLOR_NAV | `#202127` | Fixed bottom bar, system navigation |
| COLOR_TEXT | `#F4F2F7` | Main white/off-white text |
| COLOR_TEXT_SECONDARY | `#BEBAC5` | Card descriptions, secondary labels |
| COLOR_MUTED | `#77737E` | Muted metadata and less prominent text |
| COLOR_ACCENT | `#A39AFF` | GoneSmart lilac/violet identity, accent headings, active switch thumb, sliders, primary buttons and default feature sparkle |
| COLOR_ACCENT_DARK | `#34305F` | Selected bottom-nav pill and active switch track |
| COLOR_GREEN | `#4CC96A` | Positive/ready state |
| COLOR_RED | `#FF5C68` | Error/fallback state |
| COLOR_AMBER | `#FFC857` | Caution/fallback-related setting icon |

Other existing secondary source values: inactive switch track `#46434D`, inactive thumb `#C8C5CE`, divider `#343239`, outline button border `#5A5662`, selected primary-button text `#17151E`. Keep adequate contrast. **Green/red status colors do not replace the lilac feature-brand color** on ordinary added GMMP actions.

## 3. Companion app: actual hierarchy and appearance

Reference implementation: `MainActivity.kt`. Retain the agreed visual language as screens are added or rearranged.

- **Logo:** rounded GMMP-inspired music-player mark with GoneSmart's lilac **two-star sparkle**; no angular launcher icon or conspicuous black outline around the sparkle. Reuse `R.drawable.gonesmart_logo_round` for the header/launcher family rather than inventing unrelated logos in each section.
- **Header:** app logo at left, clear **GoneSmart** title and small rounded current-version badge. Existing header: 92 dp high, logo holder 50 dp, 48 dp logo, 29 sp title, 15 sp version badge on `COLOR_SURFACE_2`.
- **Fixed five-tab bottom navigation (current implementation):** **Home, Smart DJ, UI, Logs, Help**. Icon above caption; active icon/text use primary text color and the lilac-dark selected pill. Existing bar height 82 dp, icon 25 dp, caption 13 sp. Do not revert to an older four-tab mockup: **UI** was added and is established.
- **Content:** scrollable pages between header and bottom navigation. Existing page has 24 dp horizontal padding. Page titles are 31 sp white bold; uppercase section headings are 15 sp lilac bold with letter spacing.
- **Cards:** broad dark `COLOR_SURFACE` cards with **24 dp corner radius**, no exaggerated shadow/stroke. Clear hierarchy, not a dense developer preference screen. Text labels remain off-white; explanatory descriptions are subdued but readable. Existing info-card heading 17 sp bold and body 14 sp.
- **Primary buttons:** 58 dp high, rounded 24 dp, lilac fill with dark label. Secondary action buttons use a rounded outlined style, lilac text and subtle outline; existing shorter secondary actions are commonly 54 dp high. Reuse shared card/button helpers or centralize them further instead of hand-styling each new feature.
- **Status:** show honest Xposed/GMMP compatibility, GoneSmart readiness, updates and the actionable **Open GMMP**, **Restart GMMP**, **Add to Obtainium**, **Check for updates** and **GitHub releases** links. No manual "Apply" requirement for ordinary live settings.
- **Home stays general.** The maintainer explicitly asked not to overload Home with Smart-DJ tuning. Home is for module/player status, updates, feature overview and global live-settings guidance. Smart-DJ-specific controls and explanations belong on **Smart DJ**; GMMP UI tweaks belong on **UI**; event history belongs on **Logs**; user-facing feature details/FAQs belong on **Help**.

### 3.1. How options are displayed

Treat **`settingGroup` / `settingRow`** as the present design contract:

1. One rounded dark card groups related options under an uppercase colored section heading.
2. Each row has a **colored glyph inside a small rounded/tinted 46 × 46 dp tile** on the left; the glyph color may reflect a setting's semantic meaning (lilac/green/amber/red), but should not contradict live feature state.
3. The middle column has a **short English title (currently 16 sp bold)** and **explanatory English subtitle (currently 13 sp)**. Descriptions explain *effects and dependencies*, not just repeat the title.
4. The **Material switch is right-aligned**. Its enabled thumb is GoneSmart lilac, enabled track dark lilac; disabled thumb/track use neutral muted tones. The entire row is at least 76 dp high with comfortable 16/13/14/13 dp padding. Within a setting-group card, separators are subtle and indented so they start past the glyph — these **companion-app separators are intentional**; do **not** transplant them into GMMP's own playlist rows, where the user wants **no** extra separators.
5. For a nonboolean setting, use the corresponding full-width control: **Minimum rating** has a dedicated card, a 0–5-star slider in **0.5-star steps**, and a current lilac value label; 0 is **Off**. Prefer a clear interactive slider over burying numeric preferences in a yes/no switch.
6. Respect dependencies in both **logic and visible state**. **Rating fallback** is disabled, unchecked and visually dimmed when Minimum rating is 0 and Smart rating is off; it becomes available if **either** is active. Playlist-folder suboptions are disabled/dimmed while master Playlist folders is off. Do not leave controls visually active if their effect is unavailable.
7. Normal settings changes take effect **live** through the existing repository/remote-preferences path. Preserve state on tab switches and restarts. Restart GMMP after injecting a new module build or for troubleshooting, not on every ordinary toggle.
8. Group new settings with related existing ones; do not create one oversized card containing unrelated features, scatter controls over Home or introduce a visually different layout for each addition. Use an experimental/debug explanation only for genuinely unverified debug-only features.

### 3.2. Section and option map (current development app)

**Smart DJ**: GENERAL → Enable Smart DJ. MATCHING → Minimum rating, Smart rating, Rating fallback, Prefer higher-rated matches, Exclude 0.5-star tracks, Prefer studio over live, Match current music era, Favor recently added tracks, Prevent version duplicates. FALLBACK & STATUS → Fallback when no matches exist, Show status messages, plus the explanatory Offline behavior card.

**UI**: PLAYLISTS → Multi-playlist selection and **debug-only experimental** Playlist folders, Group external playlists and Group root playlists (master/suboption dependency). PLAYBACK & QUEUE → **Track Auto-DJ** and **Flip queue / Play flipped**; both are independently switchable. Do not put these switches under Smart DJ simply because their names contain playback-related terms.

**Logs**: concise recent high-level events, category totals and **Copy/Clear**; development-only reflection/stack dumps belong to tagged Logcat, not the companion's user-facing feed.

**Help**: how Smart DJ works, where recommendation providers come from, rating behavior and fallbacks, green/red/absent Auto-DJ badge meaning, live settings, compatibility and troubleshooting; add coverage for other completed features as they are finished.

## 4. The GoneSmart sparkle: shared mark with context-specific meaning

**Standing requirement:** Add a recognizable, consistent GoneSmart **lilac/purple two-star sparkle** to newly injected GMMP features/actions so users can distinguish them from untouched GMMP functionality. Use the existing **`PlayerAutoDjBadgeController.SparkleBadgeDrawable`** geometry or a shared brand asset rather than drawing a different star for each screen.

**Normal branding color:** `#A39AFF`. It is intentional that ordinary feature sparkles stay primarily **lilac even when GMMP dynamically changes accent colors**; otherwise they become indistinguishable from stock theme icons. The sparkle has a lighter inner highlight, small white central core and soft radial glow, **without a black outline**. Make it readable on light and dark themes. If the current native FAB/background itself becomes similar to lilac and the mark loses contrast, choose a *distinct live native palette slot* as the **fallback for contrast only**; return to brand lilac when contrast permits. This is the existing multi-select FAB behavior, not a reason to replace all normal lilac sparkles with album-art colors.

**Where it already exists in code:**

| Added GMMP feature/control | Marker | Important details |
| --- | --- | --- |
| **Track Auto-DJ** native context-menu action | Lilac sparkle after localized native menu label | Existing `TrackMixController.lilacSparkleTitle`: centered 28 dp replacement span, scale 1.85. Do not expand native menu-row height. |
| **Flip Queue / Play Flipped** native actions | Existing typographic reversal arrows followed by lilac two-star sparkle | `QueueFlipController.brandedMenuTitle`: 28 dp, scale 1.85; preserve arrowheads, modest tracking and the original menu baseline/row height. |
| **Multi-playlist selection confirmation FAB** | Existing larger sparkle next to/on the native confirm tick | `PlaylistMultiSelectController`: shared drawable at scale 2 with `playlistPlacement=true`. The main star sits **below-right** of the white check/tick, small star remains near its tip. Selection bar/row/FAB use native GMMP Aesthetic colors, not static GoneSmart lilac. |
| **Full Now Playing Auto-DJ indicator** | **Green / red / absent** compact sparkle over GMMP's real headphones/Auto-DJ mode button | `PlayerAutoDjBadgeController` only on the verified Now Playing `npMediaBtn10` marker near `npMediaBtn4` / headphones. Green `#4CC96A` = available/ready, including a valid current-session offline pool. Red `#FF5C68` = no smart track/error/no usable cache/native fallback. Absent when GoneSmart/Auto-DJ is inactive or GMMP uses Shuffle/Normal. **Never** show this status sparkle on mini-player, Queue, unrelated context menus or other pages. |
| **Playlist folders** (in development) | Thin native-tinted folder outline for navigation | Folder rows are organizational navigation, not an excuse to stamp a giant purple star on every playlist. If an extra GoneSmart marker is introduced for a new folder-only action, keep it small and separate from the native playlist headline and test legibility. Existing folder icon preference is a **filigree outline**, not a chunky filled white icon. |

**For future GMMP extensions:** plan the sparkle **at the feature's new entry point** — its added menu action, new FAB mode, small contextual icon or status control, as appropriate. Preserve the native base label, icon silhouette and click target. Use native translations for text. A sparkle is a brand mark, not a reason to recolor an entire native list row, overwhelm the menu or introduce a permanent marker on every stock GMMP element. If the action has a **different meaning for the sparkle** (the Auto-DJ traffic-light state), document and test that exception explicitly.

**Do not** add a white decorative sparkle to the companion Smart DJ headphones setting icon just because the full Now Playing indicator has a status sparkle. The companion uses its own established icon and brand logo, and the real colored state indicator lives on the player's actual button.

## 5. Native GMMP visual parity is separate from companion branding

New UI *inside* GMMP must follow the **real current** installed player's palette and view mode, not companion-app `#151419` surfaces or `#A39AFF` everywhere. Where available, reuse GMMP's own Aesthetic view/layout/icon/color observables. Live adaptation includes album-art-driven accent/FAB and text/background/surface changes. Do not sample a single theme color on startup and freeze it.

Match native playlist title **font size and typeface**, title/metadata distinction, margins, item height, overflow/ripple, row spacing and background. **Prefer the actual bound GMMP View and CharSequence as the source of truth:** copy its base TextView metrics **and** MetricAffecting/TextAppearance spans, Typeface, letterSpacing, textScaleX, line spacing, includeFontPadding, maxLines/ellipsize and padding. Android font scale, device density and a different GMMP skin must therefore flow through automatically. Do **not** convert a native 30 px measurement to a guessed 45 px, or define a custom sp size merely to make one screenshot look right, when GMMP already supplies the rendered style. Do not add artificial dividing lines to native Playlists/Add rows. Do not infer the whole UI from a single GMMP skin: verify the current theme, another accent palette and a structurally different view mode before calling broad skin parity complete.

**Newest requested Playlist UI parity (implementation under device review, 2026-09-25):** use a separately fixed, horizontally scrollable breadcrumb bar as in GMMP's native Files tab, with every ancestor independently tappable and its actual native `quickNavRecyclerView` typography sampled when available. At root, display no breadcrumb and reserve no header height; once inside a folder, use GMMP's native localized `storage` string as the first tappable segment. Do not use the previous single full-path Back pseudo-row. The normal Playlists tab must also preserve the **native `rvContextMenu` three-dot button on each actual playlist** and delegate all menu actions to that exact bound native playlist; folders and the Add picker must not acquire fake per-playlist overflow controls. The dot icon, its native Aesthetic tint, ripple, context content and accessible native label should follow the current GMMP theme. Visual/runtime parity is not yet verified on-device, even though the screenshot and verified APK resources guided the implementation.

**Current experimental note, 2026-09-25:** the maintainer has now confirmed the thin outline folders, correct dark Add-picker surface, native-colored selection, visible picker FAB and two-destination multi-add on device. The proportional **30→45 px** title correction is explicitly rejected as an end-state because the injected text still looks smaller than native GMMP and would not be portable. The next implementation copies the bound native title's styled CharSequence/TextAppearance and exact runtime metrics instead. Two navigation polish items remain: avoid the brief flash of the original playlist list when opening details, and restore the originating folder when returning from playlist details. The latest attached log for this pass contains no FATAL EXCEPTION.

## 6. Documentation: GitHub and the overall GoneSmart app

The maintainer explicitly wants **both**, not one substituted for the other:

- **Developer rules and design:** update `AGENTS.md` when a standing rule changes and **this design-system document** when layout, tokens, icon geometry, sparkle semantics, options UX or brand decisions change. Keep links in `README.md` and `CONTRIBUTING.md`.
- **Public GitHub docs:** new/finished features update the README feature overview, their dedicated docs under `docs/`, `docs/FAQ.md` if applicable and **`RELEASE_NOTES.md`** for the corresponding release. Mark experimental and debug-only behavior as such; do not advertise an untested folder feature as shipped. Document option names, defaults, interdependencies, native GMMP version limits and practical behavior.
- **GoneSmart companion Help/FAQ:** the **in-app Help tab** should describe every completed publicly available feature in short, plain **English**, including when/where the feature appears in GMMP, how to enable/use it, settings dependencies, green/red/absent indicator, its distinctive GoneSmart sparkle where relevant and known limitations. Keep Help and README/FAQ factually consistent. Update Home's general **Features** overview only to name/highlight completed features; place detailed controls in Smart DJ or UI and detailed explanations in Help.
- **Code & docs in one change:** update the corresponding docs and in-app Help **in the same feature-completion work**, before calling a feature done. For purely experimental debugging, note progress in feature docs/AGENTS.md without repeatedly shipping half-working instructions into the public Help/README.
- **Proof/visual examples:** representative screenshots of at least the current native GMMP theme, one changed dynamic/accent palette and a structurally different GMMP view mode can accompany final feature docs. Screenshots are test evidence, not proof of every possible skin. Avoid user-identifying file paths, private artwork and copyrighted album art in public documentation.

## 7. Change review checklist for visual/UI features

- Companion: does it still match the existing dark/lilac Material visual language, rounded cards, fixed 5-tab navigation and English-only copy? Are grouped options, short explanatory subtitles and dependencies accurately represented?
- GMMP: can users distinguish newly added GoneSmart features by a subtle shared sparkle without changing native functionality? Are translations sourced from GMMP's real currently installed resources?
- Do actual font family/size, native row height, background, text state, Aesthetic selection highlight, ripple, colors and all FAB modes follow the active GMMP theme? Does a dynamic album cover/color update work **without** a forced restart?
- Is the Now Playing green/red status badge present **only** on the genuine Auto-DJ headphones control, not on mini-player/Queue/Normal/Shuffle?
- Are the original drawer, Now Playing, playlist detail, picker and mini-player interactive? Are overlays detached safely without blocking another screen, crashing FragmentManager or leaving selection highlights stuck?
- Were GitHub docs, dedicated feature docs, in-app Help and release notes updated at the appropriate verified completion stage?
- Were related UI/logic fixes combined into a single CI-tested device build where safe, rather than producing numerous one-line APKs?

**Use the repository code to verify currently implemented details, and [AGENTS.md](../AGENTS.md) for cross-feature collaboration rules.**
