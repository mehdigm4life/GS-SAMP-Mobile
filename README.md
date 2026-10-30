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

4. The game only starts after every file has been verified. If a download is
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
Texture packs are filtered per GPU family (`.dxt` / `.etc` / `.pvr`) so a device
downloads only the variant it can render. The family is chosen by probing the
same GL extensions libGTASA probes (`GL_EXT_texture_compression_s3tc`, then
`GL_IMG_texture_compression_pvrtc`, then ETC1) - not by the renderer name. A
vendor string such as "PowerVR" says nothing about which formats the driver can
actually sample: the GE8320 advertises S3TC, so the game loads
`texdb/mobile/mobile.dxt.tmb`. Before starting the game the launcher also checks
that exact file exists, so a cache built for the wrong family opens the installer
instead of crashing in `TextureDatabaseRuntime::SortEntries`.

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
