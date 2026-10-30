# SA-MP 2.10 (Android)

Modern **SA-MP 2.10** client for **Grand Theft Auto: San Andreas (Android)**.

> **Status:** Active Development 🚧

## Features

* ✅ Android **ARM32 and ARM64** support
* ✅ ShadowHook integration
* ✅ Support for modern Android devices
* ✅ Game cache location:

```text
/storage/emulated/0/GTA/
```

## Requirements

* Android device
* ARM32 or ARM64 processor

## Installation

1. Install the APK and open it.
2. Grant the "All files access" permission when the app asks for it.
3. Tap **Install files**: the app downloads the game cache itself, straight into
   `/storage/emulated/0/GTA/`. No browser and no manual unzipping are involved.

```text
client_config.json  ->  file list  ->  filter for this device's GPU
                    ->  verify what is on disk (size + checksum)
                    ->  download only the files that are missing or corrupt
                    ->  verify again, repeat until the tree matches the server
```

4. The game only starts after the whole tree has been compared with the server
   manifest: every entry must exist, have the published length, and - when the
   server publishes a checksum - the same digest. Files are matched against a
   receipt (length, timestamp, MD5) recorded when they were downloaded, so an
   edit that keeps the same length is still caught. Delete one file by hand and
   the next launch re-downloads exactly that file before the game opens. If a download is
   interrupted, the next run continues from the bytes already on disk (HTTP
   `Range`), so nothing is downloaded twice.
5. If the game data is later damaged or removed, the same page offers an update
   / repair run that fetches only what no longer matches.

### Cache downloader

| Feature | Behaviour |
| --- | --- |
| Internal download | Files are streamed by the app itself; the browser is never opened |
| Pause / Resume | The partial file is kept as `<name>.part` and resumed with an HTTP Range request |
| Cancel | Stops the transfer and removes the partial file |
| Verification | Every file is checked against the server manifest (size, plus MD5/SHA1/CRC32 when published) |
| Repair | Only missing or corrupted files are fetched, then the whole tree is verified again |
| Diagnostics | Progress and failures are appended to `/storage/emulated/0/GS-SAMP/logcat.txt` |
| Unit tested | Resume, pause, cancel and checksum handling are covered by JVM tests against a local HTTP server |

The manifest is read from the project's own endpoint
(`https://samp-mobile.shop/client_config.json`), which points at the file list.

**Every texture family the server publishes is installed** (`.dxt`, `.pvr`,
`.etc`, `.unc`, `.360`, `.ps3`). Filtering them by the detected GPU is what broke
the game: `TextureDatabaseRuntime::Load()` re-selects the format internally after
the caller passes one and ignores the value it was given, so on a PowerVR device
the launcher asked for PVR while the engine opened `texdb/player/player.dxt.tmb`
and then `texdb/mobile/mobile.dxt.tmb`. Those indices were filtered out of the
cache, and `Load()` dereferenced the null database in
`TextureDatabaseRuntime::SortEntries` (SIGSEGV) right after the loading screen.
The server is the authority for the flavour the build expects, so the client
downloads all of them and the installer logs every `texdb/` entry it found.

The requested format is not honoured either. `TextureDatabaseRuntime::Load()`
stores it at `[db+0xa0]`, hands it to the format selection at `0x220700`, and
that call rewrites it - on the PowerVR Rogue GE8320 (Xiaomi M2006C3MG) every
database ends up as DXT, which is why the engine asked for
`texdb/player/player.dxt.tmb` even though the launcher had passed `DF_PVR`. The
crash itself is a null database dereference in
`TextureDatabaseRuntime::SortEntries` (SIGSEGV `0x6f1` / `0x133f`) as soon as the
index the engine opens is missing. `libsamp` therefore keeps two safety nets:

* `TextureDatabaseRuntime::ResolveFormatForDevice()` opens only a family whose
  `texdb/<db>/<db>.<family>.tmb` really exists on disk (requested family first,
  then pvr, dxt, etc, unc, 360, ps3),
* `TextureDatabaseRuntime::IsTextureDatabaseInstalled()` skips a database that
  was never installed instead of letting `Load()` crash on it,
* both decisions are appended to `/storage/emulated/0/GS-SAMP/logcat.txt`, so the
  family the game really opened is verifiable from the diagnostics file,
* the launcher gate accepts any installed `mobile.<family>.tmb` for the same
  reason, and only opens the installer when no family is present at all.

## Building

Clone the `GTA-2.10` branch:

```bash
git clone --branch GTA-2.10 --single-branch https://github.com/kuzia15/SAMP-Mobile.git
```

Open the project in Android Studio and build it using the appropriate ARM32 or ARM64 configuration.

## Project Status

This project is under active development. New fixes and improvements may be added over time.

## License

This project is provided for educational and research purposes only.

Grand Theft Auto, Grand Theft Auto: San Andreas and SA-MP are trademarks of their respective owners. This project is not affiliated with or endorsed by Rockstar Games, Take-Two Interactive or the original SA-MP development team.
