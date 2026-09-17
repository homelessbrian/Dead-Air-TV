<div align="center">

# 📺 Dead Air

**A lean-back Android TV client for CyTube channels — Channel-Z and 420 Grindhouse, switchable from the menu.**

Fullscreen player · chat as subtitles · schedule · movie details · built for the remote

![Platform](https://img.shields.io/badge/platform-Android%20TV%20%7C%20Fire%20TV%20%7C%20Android-1654E3)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
[![Build APKs](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

</div>

---

## What it does

Dead Air turns a CyTube channel into a proper TV app. Open it and the current stream is already playing, in sync with everyone else in the room. It ships with two channels — [Channel-Z](https://cytu.be/r/Channel-Z) and [420 Grindhouse](https://cytu.be/r/420Grindhouse) — and you flip between them from the side menu. Everything else — chat, what's coming up next, details about the movie — lives in overlays you pull up with the remote and dismiss just as fast, so the video always stays front and center.

- **Always in sync** — connects straight to the CyTube room and follows its playlist, seeks and pauses in real time
- **Chat as subtitles** — room chat renders at the bottom of the picture like captions; toggle it with one button, tune size, opacity, and auto-hide in Settings
- **Left navigation rail** — a collapsible side menu (Now Playing, Schedule, Movie Details, Chat, Channel, Settings) that slides over the video
- **Channel switcher** — jump between Channel-Z and 420 Grindhouse without leaving the player; your pick is remembered
- **Now Playing HUD** — title, progress, viewer count, and the next three items in the queue
- **Up Next schedule** — the full upcoming queue with start times, in 12- or 24-hour clock
- **Movie details & trivia** — poster, synopsis, and IMDb trivia for whatever's on
- **Four color themes** and a TV-safe-zone option
- **In-app updates** — checks this repo for new releases and offers to install them
- **Picture-in-picture** on phones

## Editions

| Edition | For | Notes |
| --- | --- | --- |
| **Light** — `dead-air-light.apk` | Android TV, Google TV, Fire TV | D-pad only. Chat is read-only (subtitles). |
| **Full** — `dead-air-full.apk` | Phones & tablets | Adds chat login and a message composer. |

## Remote controls

| Button | Action |
| --- | --- |
| **◄ LEFT** | Open the side menu |
| **▲ UP** | Show Now Playing HUD |
| **▼ DOWN** | Toggle chat subtitles (or close the HUD if it's showing) |
| **► RIGHT** | Show Up Next schedule |
| **OK** | Play / pause |
| **MENU** | Settings |
| **BACK** | Close overlay · exit app |

Inside the side menu: **▲▼** move, **OK** select, **►** or **BACK** close. It hides itself after a few seconds.

## Installing on a TV

1. Grab `dead-air-light.apk` from the [latest release](../../releases/latest).
2. On the TV, install **Downloader** from the app store and allow it to install unknown apps (Settings → Apps → Security & restrictions on Google TV; Settings → My Fire TV → Developer options on Fire TV).
3. In Downloader, enter the APK's download URL, install, and launch.

Or from a computer with ADB: `adb connect <tv-ip>` then `adb install dead-air-light.apk`.

Updates: the app checks for new versions on launch and can download them itself; you can also just reinstall the newer APK the same way.

## Building

You don't need Android Studio. Every push to `main` builds both APKs on GitHub Actions:

- **Actions** tab → latest *Build APKs* run → download the `dead-air-apks` artifact
- Tag a commit as `vX.Y.Z` (or draft a release with a new tag) and the APKs are attached to the release automatically

To build locally: open `android/` in Android Studio, pick the `light` or `full` flavor, and *Build → Build APK(s)*.

### Releasing a new version

Create a tag — that's it. On GitHub: **Releases → Draft a new release → Choose a tag → type `v1.0.1` → Create new tag → Publish release**. The workflow then:

1. builds both APKs with the version taken from the tag (`v1.0.1` → version name `1.0.1`, version code `10001`)
2. attaches them to the release
3. rewrites `version.json` on `main` so installed apps see the update on next launch

Nothing to edit by hand: the version lives only in the tag, and the updater's download URLs are generated from this repository's name automatically.

## Project layout

```
android/app/src/main/java/com/example/
├── data/
│   ├── socket/        CyTube socket.io client, playlist & chat sync
│   ├── scraper/       schedule fallbacks
│   ├── movie/         movie metadata & trivia
│   ├── repository/    settings persistence
│   └── update/        in-app update check
├── player/            ExoPlayer / WebView playback + view model
└── ui/
    ├── nav/           left navigation rail
    ├── player/        main screen
    ├── chat/          subtitle chat overlay
    ├── metadata/      Now Playing HUD, movie details, trivia
    ├── queue/         Up Next schedule
    ├── settings/      settings menu
    └── theme/         color palettes
```

Adding another CyTube room to the switcher is one line: add it to `KnownChannels` in `data/model/CyTubeModels.kt`.

## Credits & license

Dead Air is a fork of [Mikes 420 Grindhouse App](https://github.com/kburna243/mikes-420grindhouse-app) by Fried (@kburna243) and Mike, extended with a channel switcher, a side menu, and new branding. All of the sync engine, player, and overlay work is theirs. See [NOTICE.md](NOTICE.md).

Licensed under the [GNU GPL v3](LICENSE). Unofficial, non-commercial community project — not affiliated with CyTube or the channel operators.
