<div align="center">
  <img src="assets/logo.png" alt="GoneSmart" width="120" />
  <h1>GoneSmart</h1>
  <p>Smart Auto-DJ, playlist tools and quality-of-life extensions for GoneMAD Music Player.</p>

  <p>
    <a href="https://github.com/alagga/GoneSmart/releases/latest"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/alagga/GoneSmart?style=for-the-badge&logo=github&color=5f57b8&labelColor=151419"/></a>
    <a href="https://github.com/alagga/GoneSmart/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/alagga/GoneSmart/build.yml?branch=main&style=for-the-badge&logo=githubactions&label=build&color=5f57b8&labelColor=151419"/></a>
    <a href="https://github.com/alagga/GoneSmart/releases/latest"><img alt="Downloads" src="https://img.shields.io/github/downloads/alagga/GoneSmart/total?style=for-the-badge&logo=github&color=5f57b8&labelColor=151419"/></a>
    <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/alagga/GoneSmart?style=for-the-badge&color=5f57b8&labelColor=151419"/></a>
    <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%3Furl%3Dhttps%253A%252F%252Fgithub.com%252Falagga%252FGoneSmart"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to-Obtainium-5f57b8?style=for-the-badge&logo=android&logoColor=white&labelColor=151419"/></a>
  </p>

  <p>
    <a href="#what-is-gonesmart">What is it</a> •
    <a href="#features">Features</a> •
    <a href="#how-it-works">How it works</a> •
    <a href="#feature-details">Feature details</a> •
    <a href="#compatibility">Compatibility</a> •
    <a href="#installation">Installation</a> •
    <a href="#faq">FAQ</a> •
    <a href="#roadmap">Roadmap</a>
  </p>
</div>

---

## What is GoneSmart?

GoneSmart is a **libxposed module and companion app for [GoneMAD Music Player](https://gonemadmusicplayer.blogspot.com/)**. It extends GMMP in two main areas:

- **Smart Auto-DJ** — session-aware music recommendations that are matched against the tracks you already keep in your local GMMP library.
- **UI and workflow extensions** — optional playlist, Smart-Playlist and playback tools that add capabilities such as folders, multi-selection, Playlist Link, Queue Flip and Track Auto-DJ.

The goal is to keep **GMMP as the player** while adding focused features around it. GoneSmart does not replace GMMP's library or playback engine.

For Smart Auto-DJ, GoneSmart currently uses [ListenBrainz](https://listenbrainz.org/) and [Last.fm](https://www.last.fm/) as recommendation sources. Their suggestions are matched locally against your GMMP library, so an externally recommended track is only playable when that track already exists on your device and in GMMP.

The matching system is especially tuned for electronic-music libraries, where Original / Radio Edit / Extended Mix / Club Mix / Remix variants, featured artists, aliases and inconsistent release naming are common. GoneSmart is not limited to electronic music, though, and feedback from other genres is welcome.

> [!NOTE]
> GoneSmart is an independent project. It is not affiliated with GoneMAD Software, Last.fm, MusicBrainz, MetaBrainz or ListenBrainz.

> [!IMPORTANT]
> If something looks like a GMMP bug, disable GoneSmart first and verify that the issue is still reproducible with normal GMMP before reporting it to the GMMP developer. Problems that only occur with GoneSmart enabled should be reported to GoneSmart instead.

---

## Features

This section is the short overview. Each major feature links directly to a more detailed explanation further down the page.

| Area | Highlights |
|---|---|
| **[Smart Auto-DJ](#smart-auto-dj)** | Session-aware recommendations, local-library matching, multiple providers, recommendation pool, rating and matching controls, GMMP fallback and player status indicator |
| **[Playlist tools](#playlist--smart-playlist-tools)** | Playlist folders, Smart-Playlist folders, multi-selection and Playlist Link |
| **[Playback tools](#playback-tools)** | Queue Flip / Play flipped and Track Auto-DJ from an individual song |
| **[Companion app](#companion-app)** | Status, settings, logs, compatibility information, update checks and built-in help |

<details open>
<summary><b>✨ Smart Auto-DJ at a glance</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Session-aware recommendations | Uses the current and recent user-selected tracks as musical context |
| Multiple providers | Combines ListenBrainz and Last.fm recommendation signals |
| Local-library matching | Only selects tracks that actually exist in your GMMP library |
| Recommendation pool | Prepares several future candidates in the background for faster refills |
| Broad second pass | Can widen the provider search once if the normal search finds no usable local result |
| Artist fallback | Can use another local track from a strongly related artist when the exact recording is unavailable |
| Drift control | Keeps user-selected session context stronger than GoneSmart's own previous picks |
| Rating controls | Supports minimum rating, Smart Rating, rating fallback and higher-rated-match preference |
| Matching preferences | Can consider era, recently added music, live/studio preference and version duplicates |
| GMMP integration | Keeps GMMP in charge of playback and Auto-DJ while GoneSmart supplies smarter track choices |
| Player indicator | Shows GoneSmart's Auto-DJ readiness directly on GMMP's playback-mode icon |
| Native fallback | Can hand selection back to regular GMMP Auto-DJ when GoneSmart cannot provide a suitable track |
| Offline pool | A still-valid pool from the current session can continue to supply tracks without a new provider request |

</details>

<details>
<summary><b>🎛️ UI & quality-of-life at a glance</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Playlist multi-selection | Add tracks to several playlists with one confirmation |
| Playlist folders | Browse, create and manage nested playlist folders |
| Smart-Playlist folders | Organize Smart Playlists in folders and move several entries together |
| Playlist Link | Use a normal playlist as a live source inside a Smart Playlist |
| Flip queue / Play flipped | Reverse the current queue or play a Playlist / Smart Playlist from end to start |
| Track Auto-DJ | Start a fresh Smart Auto-DJ session directly from an individual song |
| GMMP-style UI | Added actions follow GMMP's active look, theme and available native wording where possible |
| Independent controls | UI extensions can be enabled separately from the normal Smart Auto-DJ settings |

</details>

---

## How it works

### Smart Auto-DJ

1. **GMMP requests another Auto-DJ track.** GoneSmart takes over the recommendation step while GMMP remains responsible for playback and queue behavior.
2. **GoneSmart builds musical context.** The current and recent user-selected tracks form the strongest part of the session context.
3. **ListenBrainz and Last.fm are queried.** Their recommendation results are combined into a broader candidate set.
4. **Candidates are matched against your local library.** External providers suggest music; GoneSmart only plays files that already exist in GMMP.
5. **Your preferences are applied.** Rating, era, recency, live/studio and version-duplicate options can influence which local candidate wins.
6. **Several recommendations are prepared ahead of time.** This reduces the need for a new network round every time GMMP needs another track.
7. **Fallback remains available.** If GoneSmart cannot provide a suitable local match, regular GMMP Auto-DJ can take over when enabled.

#### Recommendation providers

- **[ListenBrainz](https://listenbrainz.org/)** — MusicBrainz-backed recording lookup and recommendation data.
- **[Last.fm](https://www.last.fm/)** — similar-track data through the Last.fm API.

GoneSmart only sends the seed metadata needed for recommendation lookups. Your full GMMP library remains on-device and is matched locally.

### UI and playback extensions

GoneSmart's UI and playback features are optional extensions to existing GMMP workflows. They add controls and actions where they are useful — for example inside playlist views, Smart-Playlist views, track menus and the queue — while keeping GMMP's own library, playback and theme as the foundation.

These features are configured independently in the companion app's **UI** section. Most of them do not require Smart Auto-DJ to be enabled; **Track Auto-DJ** is the main exception because it intentionally starts a new Smart Auto-DJ session from the selected song.

---

## Feature details

### Smart Auto-DJ

Smart Auto-DJ is GoneSmart's recommendation system for local music libraries. It uses your current listening direction as context, asks recommendation providers for related music and then searches for the best usable matches already present in GMMP.

#### Matching and ranking controls

| Setting | Effect |
|---|---|
| Minimum rating | Requires a configurable minimum rating |
| Smart Rating | Uses the current session's rating context as a dynamic threshold |
| Rating fallback | Can retry without the hard rating threshold when it would otherwise eliminate every result |
| Prefer higher-rated matches | Gives higher-rated local matches a small ranking advantage |
| Exclude 0.5-star tracks | Completely excludes half-star tracks |
| Match current music era | Gives a modest preference to music from a similar release period |
| Favor recently added tracks | Can prefer newer additions when the current session is also recent |
| Prefer studio over live | Can penalize live versions unless the session itself is live-oriented |
| Version duplicate prevention | Helps avoid back-to-back Original / Radio / Extended / Club variants of the same song family |

The current metadata/version heuristics are particularly tuned for electronic music. If another genre exposes bad matches or missing normalization rules, please open a [feature request](https://github.com/alagga/GoneSmart/issues/new/choose) with a few representative artist/title examples.

When GMMP is in Auto-DJ mode, GoneSmart adds a small sparkle to the playback-mode icon:

- 🟢 **Green sparkle** — GoneSmart is ready for the current session.
- 🔴 **Red sparkle** — GoneSmart cannot currently provide smart selection, or GMMP fallback is active.
- **No sparkle** — GoneSmart is disabled, or GMMP is not in Auto-DJ mode.

More technical provider and architecture information is available in [docs/PROVIDERS.md](docs/PROVIDERS.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Playlist & Smart-Playlist tools

#### Playlist folders

Enable **UI → Playlists → Folders** to organize normal GMMP playlists in nested folders. GoneSmart adds folder navigation to the Playlists tab and the Add to Playlist picker, supports creating/deleting folders and lets you move one or several playlists to another folder.

Optional grouping controls can collect external or root playlists under **Other Locations**.

See [docs/PLAYLIST_FOLDERS.md](docs/PLAYLIST_FOLDERS.md) for the detailed behavior and current limitations.

#### Smart-Playlist folders

Enable **UI → Smart-Playlists → Folders** to organize Smart Playlists in physical folders as well. Multi-selection can be enabled separately so several Smart Playlists can be selected and moved together.

See [docs/SMART_PLAYLIST_FOLDERS.md](docs/SMART_PLAYLIST_FOLDERS.md).

#### Multi-selection

GoneSmart extends supported playlist workflows with multi-selection:

- In **Add to Playlist**, long-press a destination and select additional playlists before confirming once.
- In supported Playlist and Smart-Playlist management views, select several entries before moving them.

#### Playlist Link

**Playlist Link** extends Smart Playlists so a normal playlist can be used as a live source. When the normal playlist changes, its current membership can be reflected the next time the Smart Playlist is evaluated.

See [docs/SMART_PLAYLIST_LINKS.md](docs/SMART_PLAYLIST_LINKS.md).

### Playback tools

#### Flip queue / Play flipped

Enable **UI → Playback & Queue → Flip queue / Play flipped** to:

- reverse the current queue while keeping the currently playing/paused track and its progress;
- play a normal playlist from its last track to its first;
- play a Smart Playlist in its evaluated reverse order.

The setting is independent of Smart Auto-DJ and does not modify playlist files.

See [docs/QUEUE_FLIP_TESTING.md](docs/QUEUE_FLIP_TESTING.md) for detailed behavior and compatibility notes.

#### Track Auto-DJ

**Track Auto-DJ** appears in the context menu for individual songs. Selecting it starts playback from that song and creates a fresh Smart Auto-DJ session around it.

GMMP's configured Auto-DJ queue settings continue to determine how much music is kept around the current track, while GoneSmart supplies the smart local recommendations.

See [docs/TRACK_MIX_TESTING.md](docs/TRACK_MIX_TESTING.md) for detailed behavior and compatibility notes.

### Companion app

The GoneSmart companion app separates the main areas into **Home**, **Smart DJ**, **UI**, **Logs** and **Help**:

| Area | Purpose |
|---|---|
| Home | Module, GMMP, compatibility and update status plus a quick overview |
| Smart DJ | Recommendation, matching and fallback settings |
| UI | Playlist, Smart-Playlist and playback extensions |
| Logs | Compact high-level GoneSmart activity and failures |
| Help | Built-in explanations for common features and settings |

Most normal setting changes apply live. A GMMP restart is mainly useful after module updates or while troubleshooting.

---

## Compatibility

| | |
|---|---|
| **Latest tested GMMP version** | `4.2.1` |
| **Android** | Android 8.0+ (`minSdk 26`) |
| **Module API** | libxposed API `102` |
| **Rooted framework** | [JingMatrix Vector](https://github.com/JingMatrix/Vector) `v2.2+` recommended |
| **No-root path** | [JingMatrix LSPatch](https://github.com/JingMatrix/LSPatch) `v1.2` — experimental / **not tested by the maintainer** |
| **Target package** | `gonemad.gmmp` |

GoneSmart depends on GMMP internals, so future GMMP updates can require compatibility work. Many hooks and lookups are intentionally dynamic to improve robustness across future versions, but versions other than the tested one should still be treated as unverified until checked.

> [!IMPORTANT]
> We recommend disabling automatic Play Store updates for GMMP and checking GoneSmart's tested GMMP version before updating GMMP manually. GoneSmart 0.4.1 also warns when it detects a GMMP version that has not already been acknowledged in the companion app.

Developer-facing compatibility details live in [docs/GMMP_COMPATIBILITY_PLAYBOOK.md](docs/GMMP_COMPATIBILITY_PLAYBOOK.md).

---

## Installation

Install the latest GoneSmart APK from [**Releases**](https://github.com/alagga/GoneSmart/releases/latest), or add the repository to [**Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%3Furl%3Dhttps%253A%252F%252Fgithub.com%252Falagga%252FGoneSmart) so Obtainium can track future GitHub releases.

### Rooted — Vector / modern LSPosed

> GoneSmart targets **libxposed API 102**. [Vector v2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2) introduced API 102 support and is the recommended rooted setup.

1. Install the GoneSmart APK.
2. Install/enable Vector using its official instructions for your root setup.
3. Open the Vector/LSPosed manager and enable **GoneSmart**.
4. Scope GoneSmart to **GoneMAD Music Player** (`gonemad.gmmp`).
5. Force-stop GMMP and open it again.
6. Open the GoneSmart companion app and confirm that the module/target status is active.
7. Enable GMMP's normal **Auto-DJ** mode if you want to use Smart Auto-DJ.

### No root — LSPatch (experimental / untested)

> [!WARNING]
> LSPatch is documented as a possible no-root setup, but it has **not been tested by the GoneSmart maintainer**. Patching changes the target APK signature and can affect app updates, licensing or integrity checks.

1. Install GoneSmart.
2. Install [JingMatrix LSPatch v1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2).
3. Patch GMMP using **Local Patch Mode** and **Inject loader dex**.
4. Install the patched GMMP APK.
5. In LSPatch → Manage → GMMP → Modules, enable GoneSmart.
6. Open GoneSmart, then force-stop/reopen GMMP if needed.

See [docs/INSTALLATION.md](docs/INSTALLATION.md) for troubleshooting and more detail.

---

## FAQ

**Does GoneSmart upload my music library?**  
No. Provider requests use the seed metadata needed to find similar music. Matching against your GMMP library happens locally on the device.

**What happens if no recommended track exists locally?**  
GoneSmart can perform one broader provider search. If that still produces no usable local candidate, the configured fallback behavior takes over.

**Does changing a setting require restarting GMMP?**  
Normally no. Most settings apply live. Restarting GMMP is mainly useful after module updates or for troubleshooting.

**Does GoneSmart work offline?**  
A still-valid recommendation pool from the current session can continue offline. Once no suitable cached track remains, regular GMMP fallback can take over if enabled.

**Can I use the playlist/UI features without Smart Auto-DJ?**  
Yes. Playlist folders, Smart-Playlist folders, multi-selection, Playlist Link and Queue Flip are independent features. Track Auto-DJ intentionally starts a Smart Auto-DJ session.

More detail: [docs/FAQ.md](docs/FAQ.md).

---

## Roadmap

### Ideas for 0.5.0

- **More recommendation providers:** additional recommendation sources are being explored. Services such as Spotify, YouTube and YouTube Music are examples of possible directions only; the exact providers and feasibility still need to be evaluated.
- **Bluetooth device audio profiles:** automatically use different GMMP equalizer and effects settings depending on which Bluetooth headphones, speakers, car audio system or other playback device is connected.

These are current directions and may change during development. See [docs/ROADMAP.md](docs/ROADMAP.md) for the current roadmap.

---

## Development

### Tech stack

| Area | Current implementation |
|---|---|
| **Primary language** | Kotlin |
| **Platform** | Native Android app + libxposed module |
| **Hooking API** | libxposed API 102 |
| **UI** | AndroidX AppCompat + Material Components |
| **Build system** | Gradle Kotlin DSL / Android Gradle Plugin |
| **Recommendation providers** | ListenBrainz + Last.fm over HTTP/JSON |
| **CI / releases** | GitHub Actions, signed APK release workflow |

A more detailed component overview lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Building

The project currently targets:

- Android Gradle Plugin `9.4.1`
- Gradle `9.6.0`
- JDK `17`
- compile/target SDK `37`
- libxposed API/service `102.0.0`

Create a local `local.properties` in the repository root:

```properties
sdk.dir=/path/to/Android/Sdk
LASTFM_API_KEY=your_lastfm_api_key
```

Then build with Android Studio or:

```bash
./gradlew :app:assembleDebug
```

Release signing is documented in [docs/BUILDING.md](docs/BUILDING.md). Release workflow details are in [docs/RELEASING.md](docs/RELEASING.md).

### Maintainer and contributor notes

Project-specific development rules, GMMP compatibility procedures and persistent maintainer conventions live in [AGENTS.md](AGENTS.md) and the linked developer documentation. They are intentionally kept out of the user-facing introduction above.

Bug reports, reproducible compatibility findings and focused pull requests are welcome:

- [Open a bug report or feature request](https://github.com/alagga/GoneSmart/issues/new/choose).
- When reporting compatibility problems, include exact GMMP / Vector / LSPatch versions and relevant GoneSmart logs.
- Before reporting a suspected GMMP bug upstream, disable GoneSmart and verify that the problem is reproducible without it.
- Do not include API keys, keystores, account data or other secrets in issues or logs.

---

## Credits

GoneSmart builds on and integrates with work from the wider Android and music-metadata ecosystem:

- **[GoneMAD Music Player](https://gonemadmusicplayer.blogspot.com/)** by GoneMAD Software — the player GoneSmart extends.
- **[libxposed / Vector](https://github.com/JingMatrix/Vector)** and **[LSPatch](https://github.com/JingMatrix/LSPatch)** — module/runtime infrastructure.
- **[ListenBrainz](https://listenbrainz.org/)** / **[MetaBrainz](https://metabrainz.org/)** / **[MusicBrainz](https://musicbrainz.org/)** — open music metadata and recommendation infrastructure.
- **[Last.fm](https://www.last.fm/)** — similar-track recommendation data used by Smart Auto-DJ.
- **OpenAI ChatGPT** — used as an AI-assisted development tool during design, debugging, documentation and compatibility work.

Maintained by [**alagga**](https://github.com/alagga).

---

## License

See [LICENSE](LICENSE).
