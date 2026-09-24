<div align="center">
  <img src="assets/logo.png" alt="GoneSmart" width="120" />
  <h1>GoneSmart</h1>
  <p>Smart extensions for GoneMAD Music Player — currently focused on a session-aware Auto-DJ that works with the music you actually keep in your local library.</p>

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
    <a href="#tech-stack--architecture">Tech stack</a> •
    <a href="#compatibility">Compatibility</a> •
    <a href="#installation">Installation</a> •
    <a href="#faq">FAQ</a> •
    <a href="#credits">Credits</a>
  </p>
</div>

---

## What is GoneSmart?

GoneSmart is a modern libxposed module and companion app that extends [GoneMAD Music Player](https://gonemadmusicplayer.blogspot.com/) with **smart and quality-of-life features**. The current main focus — and the first major feature — is **Smart Auto-DJ**: GMMP stays in charge of playback, queue management and Auto-DJ timing, while GoneSmart replaces the actual track-selection step with session-aware recommendations.

The project is intentionally broader than Auto-DJ. GoneSmart now also includes optional GMMP quality-of-life extensions such as multi-playlist selection, while keeping the same core idea: keep GMMP as the player and add focused features around it.

For Smart Auto-DJ, GoneSmart asks [ListenBrainz](https://listenbrainz.org/) and [Last.fm](https://www.last.fm/) for similar music, merges those recommendation signals, and then matches them against **your local GMMP library**. It never turns an external recommendation into a stream: the selected file must already exist on your device and in GMMP's database.

The matching pipeline is currently **especially tuned for electronic-music libraries**, where Original / Radio Edit / Extended Mix / Club Mix / Remix variants, featured artists, aliases and inconsistent release naming are common. GoneSmart is not limited to electronic music, though — matching feedback and real-world examples from rock, metal, hip-hop, pop, classical and other libraries are very welcome through [feature requests](https://github.com/alagga/GoneSmart/issues/new/choose).

> [!NOTE]
> GoneSmart is an independent project. It is not affiliated with GoneMAD Software, Last.fm, MusicBrainz, MetaBrainz or ListenBrainz.

---

## Features

<details open>
<summary><b>✨ Smart Auto-DJ</b></summary>

<br/>

### Recommendation pipeline

| Feature | What it does |
|---|---|
| Session-aware recommendations | Uses the current and recent user-selected tracks as musical context |
| Multiple providers | Combines ListenBrainz and Last.fm instead of relying on one source |
| Local-library matching | Only selects tracks that actually exist in your GMMP library |
| Recommendation pool | Prepares multiple tracks at once so every Auto-DJ refill does not require another network round |
| Broad second pass | If the normal search finds zero usable local candidates, exactly one wider provider search is attempted |
| Artist fallback | Can use another local track from a strongly related/seed artist when the exact recommended recording is unavailable |
| Drift control | User-selected session context stays stronger than GoneSmart's own previous picks |
| Native fallback | Can hand selection back to regular GMMP Auto-DJ when smart selection cannot supply a track |

### Matching and ranking controls

| Feature | What it does |
|---|---|
| Minimum rating | Hard 0–5 star minimum in 0.5-star steps |
| Smart rating | Uses the median rating of the current recommendation context as a dynamic minimum |
| Rating fallback | Optional retry without Minimum/Smart rating when hard limits eliminate everything; the switch is disabled unless one of those limits is active |
| Prefer higher-rated matches | Uses ratings as a small ranking bonus after hard filters |
| Exclude 0.5-star tracks | Completely blocks half-star tracks, including during Rating fallback |
| Match current music era | Gives a modest bonus to music from a similar release period |
| Favor recently added tracks | Uses GMMP's date-added signal when the current session is also recent |
| Prefer studio over live | Can penalize live versions unless the session itself is live-oriented |
| Version duplicate prevention | Prevents Original/Radio/Extended/Club versions of the same song family from appearing back-to-back |

> The current metadata/version heuristics are particularly tuned for electronic music. If another genre exposes bad matches or missing normalization rules, please open a feature request with a few representative artist/title examples.

</details>

<details>
<summary><b>🎧 GMMP integration</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Native queue lifecycle | GMMP still decides when Auto-DJ needs another track |
| Dynamic pool sizing | Internal pool sizing follows GMMP's Auto-DJ queue settings without mirroring the visible queue 1:1 |
| Player indicator | A sparkle overlays GMMP's Auto-DJ headphones icon: green = ready, red = fallback/problem, none = Auto-DJ inactive |
| Offline behavior | A valid pool can continue offline, but a new queue never reuses the previous session's pool |
| Restart GMMP | Companion-app shortcut to force-stop and relaunch GMMP when hooks need a clean restart |

</details>

<details>
<summary><b>🎛️ UI & quality-of-life</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Multi-playlist selection | Long-press a destination in GMMP's Add to Playlist dialog, select multiple playlists, then confirm once |
| Native playlist writes | Reuses GMMP's own playlist-add operation rather than editing playlist files directly |
| Native look & language | Selection colors follow GMMP's dynamic theme and user-facing selection/result strings reuse GMMP's localized resources |
| Safe scrolling | Selection is keyed to the real playlist path so RecyclerView row reuse does not move highlights to other playlists |
| One completion message | Multiple native result messages are combined into one “X files / Y playlists” summary |
| Independent UI toggle | The feature has its own switch in GoneSmart's **UI** tab and does not require Smart DJ to be enabled |

</details>

<details>
<summary><b>🧰 Companion app</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Module status | Shows whether the Xposed service and GMMP target process are available |
| Live settings | Recommendation settings are pushed to the running target without a normal restart |
| Runtime logs | Keeps a compact high-level GoneSmart event log inside the app |
| Compatibility status | Shows the installed GMMP version and the currently tested version |
| Update status | Checks GitHub Releases when the companion app starts and shows whether the installed version is current |
| Obtainium hand-off | Opens GoneSmart directly in Obtainium so Obtainium can handle APK updates |
| UI tab | Keeps optional GMMP interface/QoL extensions separate from Smart DJ settings |
| Built-in FAQ | Explains providers, rating rules, pool behavior, indicator states and fallback behavior |

</details>

---

## How it works

1. **GMMP asks for Auto-DJ tracks.** GoneSmart hooks that selection point but leaves the rest of GMMP's queue logic intact.
2. **GoneSmart builds session context.** Up to five representative seed tracks are used for provider requests, with the current/recent tracks weighted most strongly.
3. **ListenBrainz + Last.fm are queried.** Their similar-track signals are normalized and merged.
4. **Everything is matched locally.** Exact track matches are preferred; carefully limited local artist fallbacks can fill gaps.
5. **Local preferences are applied.** Ratings, era, date added, live/studio preference and song-family duplicate rules adjust or filter candidates.
6. **A local pool is created.** GMMP can take multiple future selections from it without repeating the full network pipeline for every song.
7. **If nothing local matches, GoneSmart broadens once.** The second pass asks for a wider provider result set, then stops. It does not loop indefinitely.

### Recommendation providers

- **[ListenBrainz](https://listenbrainz.org/)** — MusicBrainz-backed recording lookup and similar-recording datasets.
- **[Last.fm](https://www.last.fm/)** — similar-track data via the Last.fm API.

GoneSmart sends seed metadata needed for recommendation lookups. The full GMMP library stays on-device and is matched locally.

---

## Tech stack & architecture

| Area | Current implementation |
|---|---|
| **Primary language** | Kotlin |
| **Platform** | Native Android app + libxposed module |
| **Hooking API** | libxposed API 102 |
| **UI** | AndroidX AppCompat + Material Components |
| **Build system** | Gradle Kotlin DSL / Android Gradle Plugin |
| **Recommendation providers** | ListenBrainz + Last.fm over HTTP/JSON |
| **Local selection** | GMMP queue/library readers, metadata normalization, local matching and preference ranking |
| **State/settings** | Session-aware recommendation pool + libxposed remote preferences |
| **CI / releases** | GitHub Actions, signed APK release workflow |

At runtime, the module is loaded into GMMP's process and hooks the Auto-DJ selection path. The companion app stays separate and handles settings, status, logs and user-facing controls. Provider responses are treated only as recommendation signals; the final playable track is resolved locally against GMMP.

A more detailed component overview lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Compatibility

| | |
|---|---|
| **Latest tested GMMP version** | `4.2.0` |
| **Android** | Android 8.0+ (`minSdk 26`) |
| **Module API** | libxposed API `102` |
| **Rooted framework** | [JingMatrix Vector](https://github.com/JingMatrix/Vector) `v2.2+` recommended |
| **No-root path** | [JingMatrix LSPatch](https://github.com/JingMatrix/LSPatch) `v1.2` — experimental / less tested |
| **Target package** | `gonemad.gmmp` |

GoneSmart currently hooks obfuscated GMMP internals. That means a future GMMP update can change the classes or methods GoneSmart expects even if the public GMMP UI looks unchanged. Versions other than the tested one should be treated as unverified until checked.

---

## Installation

Install the latest GoneSmart APK from [**Releases**](https://github.com/alagga/GoneSmart/releases/latest), or add the repository to [**Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%3Furl%3Dhttps%253A%252F%252Fgithub.com%252Falagga%252FGoneSmart) to let Obtainium track future GitHub releases. GoneSmart itself only checks for a newer published version; it does not download or install APK updates.

### Rooted — Vector / modern LSPosed

> GoneSmart targets **libxposed API 102**. [Vector v2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2) introduced API 102 support and is the recommended rooted setup.

1. Install the GoneSmart APK.
2. Install/enable Vector using its official instructions for your root setup.
3. Open the Vector/LSPosed manager and enable **GoneSmart**.
4. Scope GoneSmart to **GoneMAD Music Player** (`gonemad.gmmp`).
5. Force stop GMMP and open it again.
6. Open the GoneSmart companion app and confirm that the module/target status is active.
7. In GMMP, enable its normal **Auto-DJ** playback mode. GoneSmart takes over the track-selection part while Auto-DJ is active.

### No root — LSPatch (experimental)

> [!WARNING]
> LSPatch support is an **experimental path** for GoneSmart and is not yet validated as thoroughly as Vector. Patching changes the target APK signature and can affect app updates, licensing or other integrity checks. Only patch an APK you are allowed to modify and keep a backup of your original setup.

1. Install GoneSmart.
2. Install [JingMatrix LSPatch v1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2).
3. In LSPatch, patch your GMMP APK / installed app using **Local Patch Mode** and **Inject loader dex**.
4. Install the patched GMMP APK produced by LSPatch.
5. In LSPatch → Manage → GMMP → Modules, enable GoneSmart for the patched GMMP instance.
6. Open the GoneSmart companion app, then force stop/reopen GMMP if needed.
7. Enable normal GMMP Auto-DJ and verify the GoneSmart player indicator.

See [docs/INSTALLATION.md](docs/INSTALLATION.md) for troubleshooting and more detail.

---

## Flip Queue / Play Flipped

GoneSmart adds an optional **Flip queue / Play flipped** switch under
**UI → Playback & Queue**, independent of Smart DJ. It adds a native-looking
menu entry with localized Queue/Play text, two spaced arrows and the lilac
GoneSmart sparkle in the Queue, Playlist and Smart Playlist menus.

- **Flip existing queue:** Reverse *every* entry, including already played
  tracks, without changing the currently playing/paused track or its progress.
  Its queue position moves along with it.
- **Play playlist flipped:** Play a playlist from its original **last** track
  to its **first**, starting playback at the top of the reversed queue.
- **Play Smart Playlist flipped:** Apply the same reversal *after* GMMP
  evaluates the Smart Playlist's current ordered track list.

All three native operations were exercised on GMMP **4.2.0** on an actual
device (31-track queue, 17-track playlist, and Smart Playlists of 31 and
140 tracks); the queue position, first/last track identities, and native
verification logs matched the expected order. Other GMMP versions and
rare concurrent queue changes have not been exhaustively validated. The
setting is off by default; no .m3u playlists are modified.

See [Flip feature testing and compatibility](docs/QUEUE_FLIP_TESTING.md)
for current behavior, diagnostics and limitations.

## Track Auto-DJ — Auto-DJ from any song

The **Track Auto-DJ** action appears directly after **Play next** in the
three-dot context menu for individual songs in the library, queue,
playlist detail pages, search results and file browser. In German its
label is **Titel Auto-DJ**. Like the other GoneSmart menu actions, it has
the centered lilac two-star sparkle without enlarging GMMP's menu rows.
It does not appear in whole-album, artist or playlist context menus. You can enable or disable this independent feature under **GoneSmart → UI → Track Auto-DJ**; it remains enabled for existing users until they switch it off.

Choosing Track Auto-DJ plays **that selected song**, retains it as the seed
for a new queue, enables GoneSmart's Smart DJ if necessary, and uses
GMMP's own **Auto-DJ playback mode**. GMMP's configured **Initial Size**
determines the target queue length (including the selected seed song);
GoneSmart supplies its usual matching local recommendations for the
remaining positions. The normal upcoming-track setting continues to
control later refills. No playlist files are changed.

The action uses GMMP's native Play callback followed by a native Room transaction that removes other queue entries by their unique IDs (without an asynchronous Clear Queue broadcast), then enables GMMP Auto-DJ. Its high-level start,
verification and failure outcomes appear in the GoneSmart app's
**Logs → Track Auto-DJ** category; details are in Android Logcat under
`GoneSmartTrackMix`. A verified mix shows **one concise confirmation**. During the bounded Track Auto-DJ startup only, GoneSmart suppresses GMMP's intermediate Play/Auto-DJ Toasts and Snackbars, including delayed Auto-DJ-rules-changed status UI. Actual errors still show one warning.

**Languages:** GoneSmart's companion app stays in English and always calls this feature **Track Auto-DJ**, including in Settings, Logs and Help. The action inside GMMP composes its own menu label from the player's localized **track** and **Auto-DJ** strings (for example, German **Titel Auto-DJ** or English **Track Auto-DJ**). Confirmations reuse GMMP's translated **started** string if available, otherwise a neutral checkmark. No copied translation table is required.

**Status:** Feature complete in the v0.4.x development branch; the maintainer reports Track Auto-DJ working on-device with GMMP 4.2.0. The 24 September development log showed six successful five-song starts and one earlier intermittent queue-isolation failure during an old-queue refill. That older clearing path has been replaced with native atomic isolation by unique queue-entry ID. The maintainer subsequently retested the queue-row Track Auto-DJ flow with the corrected build and reported no recurrence of the failure; this targeted device regression is accepted as passed. Other GMMP versions remain unverified. See [Track Auto-DJ test and notes](docs/TRACK_MIX_TESTING.md).

## In development: Playlist folders

The next v0.4.x feature introduces optional physical playlist folder
navigation in GMMP's Playlists tab and Add to Playlist picker, with nested
folders. Two **independent** options control whether external playlists and
playlists directly in GMMP's main root appear inside the virtual **Other
Locations** folder or as loose items after folder rows. The GMMP 4.2.0
GMMP 4.2.0 has now been tested with playlists several physical folder
levels deep: normal listing/opening, native Add to Playlist and GoneSmart
multi-destination add all passed. A native GMMP write also converted an
existing relative M3U entry to an absolute path. Moving playlist files
externally still leaves stale GMMP database records until cleanup, so
GoneSmart's future Move action requires a verified native per-playlist path
update. A read-only `FOLDER DISCOVERY` diagnostic is now staged to map
the normal Playlists tab against the Add picker. Native folder UI and
file-moving actions are **not yet implemented**. See
[the design and on-device findings](docs/PLAYLIST_FOLDERS.md).

## Companion UI and player indicator

The GoneSmart companion app has separate **Home**, **Smart DJ**, **UI**, **Logs** and **Help** tabs. **Home** gives a balanced overview of Smart DJ and UI tweaks, plus module, update and shared live-settings status. **Smart DJ** uses a compact headphones icon and controls music recommendations; **UI** independently controls extensions such as multi-playlist selection, Flip Queue and Track Auto-DJ. **Home → Settings** explains that both types of settings apply live without restarting GMMP (a restart is still recommended after module updates).

**Logs** collects recent, high-level activity across **Smart DJ**,
**multi-playlist selection**, **Flip Queue / Play Flipped**,
**Track Auto-DJ**, and UI/System events. Successful Flip events are recorded only after native verification;
failures and recovery attempts have their own entries. These additional
events do **not** replace the separate Auto-DJ readiness/fallback status
on Home. For developer diagnostics, filter Android Logcat by `GoneSmart`,
`GoneSmartPlaylist`, or `GoneSmartFlip`.

When GMMP is in Auto-DJ mode, GoneSmart adds a small sparkle to the headphones/playback-mode icon:

- 🟢 **Green sparkle** — GoneSmart is ready for the current session, either online or with a still-valid cached pool.
- 🔴 **Red sparkle** — GoneSmart cannot currently provide smart selection, an error/no-match condition occurred, or regular GMMP Auto-DJ fallback is active.
- **No sparkle** — GoneSmart is disabled, or GMMP is using Normal/Shuffle instead of Auto-DJ.

The indicator intentionally reflects the state relevant to Auto-DJ selection; it does not continuously poll every possible future network failure before GMMP needs another track.

---

## FAQ

**Does GoneSmart upload my music library?**  
No. Provider requests use the seed metadata needed to find similar music. Matching against your GMMP library happens locally on the device.

**Why only five provider seeds instead of the whole queue?**  
A small representative seed set keeps requests bounded and reacts better to recent musical direction. The wider session can still influence local context and ranking without multiplying network requests.

**What happens if no recommended track exists locally?**  
GoneSmart performs one broader provider search. If that still produces no usable local candidate, configured rating/native fallbacks take over.

**Why did GoneSmart ignore my rating limit once?**  
**Rating fallback** is available only while Minimum rating is above zero or Smart rating is enabled. If both are off, its switch is dimmed, disabled and reset to off. When enabled, it may retry the same candidates without those hard thresholds; `Exclude 0.5-star tracks` remains active.

**Does changing a setting require restarting GMMP?**  
Normally no. Recommendation-affecting settings invalidate the current pool and apply to the next refill. Restart GMMP is mainly for hook/module updates or troubleshooting.

**Does GoneSmart work offline?**  
A valid pool from the current session can continue offline. A brand-new queue never inherits an old pool. Once no suitable cached track remains, native GMMP fallback can take over if enabled.

More detail: [docs/FAQ.md](docs/FAQ.md).

---

## Last.fm API key handling

GoneSmart uses Last.fm's `track.getSimilar` endpoint. That endpoint requires an **API key** but does **not** require Last.fm user authentication. GoneSmart does **not** use or ship a Last.fm shared secret. The application API key is supplied at build time:

- Local builds: put `LASTFM_API_KEY=...` in `local.properties`.
- GitHub releases: store the key as the repository secret `LASTFM_API_KEY`.
- Never commit `local.properties`, a shared secret, keystores or signing passwords.

A client-side application API key can ultimately be extracted from an APK; using a GitHub secret prevents accidental source-control disclosure, not reverse engineering of the installed client. If Last.fm changes its client-key policy, GoneSmart should revisit this setup before the next release.

The API key should belong to a registered Last.fm API application for GoneSmart. Last.fm's current [API Terms of Service](https://www.last.fm/api/tos) require attribution and contain additional restrictions around public/commercial use; review the current terms before distributing a public build. GoneSmart credits and links Last.fm here and in the companion app. More provider notes are in [docs/PROVIDERS.md](docs/PROVIDERS.md).

---

## Building

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

Release signing can be supplied through `keystore.properties` locally or the environment variables documented in [docs/BUILDING.md](docs/BUILDING.md).

---

## Releases and CI

- The companion app performs one lightweight GitHub Releases check on startup and reports whether the installed version is current.
- GoneSmart does **not** self-update; use Obtainium or GitHub Releases for installation.
- The README's **Add to Obtainium** button uses Obtainium's documented deep-link flow.
- Normal pushes/PRs run the GitHub build workflow.
- The **Release APK** workflow builds a signed release APK and publishes/updates the matching `v<versionName>` GitHub Release.
- Release builds expect the Last.fm API-key secret plus Android signing secrets. See [docs/BUILDING.md](docs/BUILDING.md).
- Maintainer workflow, stable releases and prereleases are documented in [docs/RELEASING.md](docs/RELEASING.md).
- The current release notes live in [RELEASE_NOTES.md](RELEASE_NOTES.md).

---

## Contributing

Bug reports, reproducible compatibility findings and focused pull requests are welcome.

- Found a bug? Open a [bug report](https://github.com/alagga/GoneSmart/issues/new/choose).
- Have an idea or a smart GMMP feature that would fit GoneSmart? Open a [feature request](https://github.com/alagga/GoneSmart/issues/new/choose).
- Testing another GMMP/Vector/LSPatch version? Include exact version numbers and relevant GoneSmart logs.
- Using a non-electronic library? Genre-specific matching feedback is particularly useful. Include a few representative artist/title/version examples and what you expected GoneSmart to treat as equivalent or different.

Please do not include API keys, keystores, account data or other secrets in issues or logs.

---

## Credits

<div align="center">

### Maintainer

<a href="https://github.com/alagga">
  <img src="https://github.com/alagga.png" width="80" alt="alagga" style="border-radius:50%"/><br/>
  <b>alagga</b>
</a>

</div>

GoneSmart builds on and integrates with work from the wider Android and music-metadata ecosystem:

- **[GoneMAD Music Player](https://gonemadmusicplayer.blogspot.com/)** by GoneMAD Software — the player GoneSmart extends.
- **[libxposed / Vector](https://github.com/JingMatrix/Vector)** and **[LSPatch](https://github.com/JingMatrix/LSPatch)** — module/runtime infrastructure.
- **[ListenBrainz](https://listenbrainz.org/)** / **[MetaBrainz](https://metabrainz.org/)** / **[MusicBrainz](https://musicbrainz.org/)** — open music metadata and recommendation infrastructure.
- **[Last.fm](https://www.last.fm/)** — similar-track recommendation data used by Smart Auto-DJ.
- **OpenAI ChatGPT** — used as an AI coding assistant for implementation support, debugging, code review and documentation.

GoneSmart is an **AI-assisted coding project**, not an autonomous one: product direction, requirements, testing, compatibility decisions and releases are driven by the maintainer.

See [CREDITS.md](CREDITS.md) for the longer attribution/acknowledgement list.

---

## License

GoneSmart is licensed under the [MIT License](LICENSE).

---

<div align="center">
  <sub>Smart extensions for local music libraries and GoneMAD Music Player.</sub><br/>
  <sub>Not affiliated with GoneMAD Software, Last.fm, MusicBrainz, MetaBrainz or ListenBrainz.</sub>
</div>
