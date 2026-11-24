# ReelsPlayer LAN

English | [简体中文](README.zh-CN.md)

An Android player for downloaded Reels / Shorts / TikTok videos — swipe vertically like you are
still in the feed — plus the thing the original does not do: **stream them straight from a NAS or
PC share over SMB (LAN)**.

Pick a local folder, or pick a folder on your NAS; either way you get the same full-screen vertical
feed.

> This project is a derivative of [ReelsPlayer](https://github.com/ctyfrank/reelsplayer) (MIT).
> It keeps the original swipe UI and local playback, and replaces the "local files only" data layer
> with one that also speaks SMB. See [Credits](#credits) for every project it builds on.

## Features

**Playback**

- Full-screen vertical swipe feed, tap to pause, long-press the right side for 2x speed
- Thin progress bar and `current / total` counter
- **Resume** — the video and the position you stopped at are remembered; after a restart the landing
  screen offers **Resume (N)** and continues from there
- **Play order** — sequential, or shuffled for every session
- **End of a video** — loop it, pause on the last frame, or play the next one
- **Clean exit** — back (or the button in the top-left corner) leaves the feed and returns to the
  landing screen with the playlist kept for **Resume**; at the landing screen, a first back press
  warns and a second one closes the app

**Local files**

- Pick a folder (SAF); every video in it and in its subfolders is played
- Swipe order follows the relative path, so videos of one folder stay together
- Scan progress while walking large trees

**LAN / SMB**

- **Multiple saved servers**, each with its own host, port, share, domain, username and password
- **Guest / anonymous** access for shares that allow it, and an **SMB1** switch for old devices
- **Encrypted credentials**: the server list is sealed with an AES-256-GCM key generated inside the
  Android keystore, so copying the app's preferences does not reveal a single password
- **Folder browser**: walk the share level by level; each server remembers the last folder played
- **Recursive scan** of the chosen share folder into the swipe feed

**Diagnostics**

- **Built-in log** — playback failures, SMB connect/list/stream details and scan results are recorded
  with their full cause chain (including the SMB NT status), not just a toast
- **One-tap export** — the log screen writes a single report file (device, app version, network,
  saved servers without passwords) and opens the share sheet, so it can be sent for analysis
- **Crash capture** — an uncaught exception is written to the same log before the process dies

**Streaming quality**

- Seekable SMB data source with a 1 MiB read-ahead window (works with MP4/MKV whose indexes sit at
  the end of the file)
- 512 MB LRU disk cache, so re-watching and scrubbing are served from local storage
- Head of the clip after the next one is prefetched (skipped on metered networks, and it never
  fights the player for an SMB file that is already open)
- ExoPlayer load control tuned for a LAN (1.5 s to start, up to 180 s buffered)
- Player instances are pooled instead of rebuilt on every swipe

## Usage

### Local folder

Landing screen → **Pick folder** → choose the folder → swipe.

### NAS / PC share

1. Landing screen → **LAN servers**.
2. Tap **+**: host (`192.168.1.10` or `nas.local`), optional port and share, username/password — or
   tick **Guest / anonymous**. **Test** checks the login without saving it.
3. Tap the server, browse into the folder with the videos, tap **Play this folder**.

Long-press a server in the list to edit, test or delete it.

### When a video will not play

A playback failure shows a snackbar with **View log**. The log screen lists what happened — which
server, which file, the ExoPlayer error code and the underlying SMB status. **Export** saves the
whole report to a file and opens the share sheet; **Copy** puts it on the clipboard. Turn on
**Verbose logging** first if you want per-folder scan and per-item details as well.

The log is also mirrored to `filesDir/reelsplayer-lan.log` (rotated at 2 MB), so a crash report from
a previous run is still there after a restart.

Notes:

- SMB2/SMB3 is negotiated by default. Tick **Legacy SMB1 device** only for routers/NAS boxes that
  still speak SMB1.
- Passwords are encrypted with a device-bound keystore key and are only ever sent to the server you
  configured.
- The stream cache lives in the app cache directory; clearing the app cache clears it.

## Requirements

- Android 8.0+ (API 26)
- Container/codec support follows ExoPlayer (MP4/H.264, WebM, MKV, …)
- LAN: an SMB server (Windows share, Samba, Synology/QNAP, router USB share, …)

## Building

Requires **JDK 17** (AGP 8.2) and an Android SDK with `platforms;android-34` and
`build-tools;34.0.0`.

macOS / Linux:

```bash
git clone https://github.com/ctyfrank/reelsplayer-lan.git
cd reelsplayer-lan
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

Windows (PowerShell) — `\` is an escape character inside `.properties`, use forward slashes:

```powershell
git clone https://github.com/ctyfrank/reelsplayer-lan.git
cd reelsplayer-lan
Set-Content local.properties "sdk.dir=E:/Android/Sdk"
.\gradlew.bat assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Signed release: drop `reelsplayer-lan-release.jks` in the repository root and export
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, then `./gradlew assembleRelease`.

## Tech stack

Kotlin, AndroidX ViewPager2, Media3 ExoPlayer 1.2.0, jcifs-ng 2.1.10 (SMB), Android Keystore
(AES-256-GCM). compileSdk 34, minSdk 26, targetSdk 34, JDK 17.

## Credits

This project stands on the following work. It is a modified derivative, not the original.

| Project | Author | License | What this project uses |
|---|---|---|---|
| [ReelsPlayer](https://github.com/ctyfrank/reelsplayer) | underwear | MIT | Base app: vertical swipe feed, local folder playback, UI. `MainActivity`, `VideoAdapter` and the layouts started here and were modified. |
| [CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider) | wa2c | MIT | Reference for jcifs-ng usage: SMB version negotiation, NTLM / guest / anonymous contexts, timeouts. No source files were copied. |
| [jcifs-ng](https://github.com/AgNO3/jcifs-ng) | AgNO3 GmbH & Co. KG | LGPL-2.1 | SMB2/SMB3 (and SMB1) client library used at runtime. |
| [Bouncy Castle](https://www.bouncycastle.org/java.html) (`bcprov-jdk18on`) | The Legion of the Bouncy Castle | MIT-style | Crypto provider required by jcifs-ng. |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Google / AndroidX | Apache-2.0 | Playback engine, `DataSource` API, disk cache (`SimpleCache`). |
| [AndroidX AppCompat / Material / ViewPager2 / DocumentFile](https://developer.android.com/jetpack/androidx) | Google / AndroidX | Apache-2.0 | UI toolkit, swipe container, SAF helpers. |
| [SLF4J](https://www.slf4j.org/) | QOS.ch | MIT | Logging facade used by jcifs-ng (`slf4j-nop` binding). |

Upstream MIT notices are reproduced in [CREDITS.md](CREDITS.md). jcifs-ng is LGPL-2.1: this project
links it unmodified as a separate library; its source is available at the link above.

## License

MIT — see [LICENSE](LICENSE). Portions are derived from ReelsPlayer (MIT, © 2026 underwear).