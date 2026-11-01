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
* `NvFOpen` serves `texdb/<db>/<db>.<installed family>.<ext>` when the engine
  asks for a family that was never published for that database (`player`,
  `playerhi` and `menu` only exist as `.pvr`). The `.toc` of both families have
  the same size, so the index layout is platform independent and each entry
  carries its own texture format - the published data is used as-is,
* the launcher gate accepts any installed `mobile.<family>.tmb` for the same
  reason, and only opens the installer when no family is present at all.

### Why connecting to a server used to end the session

The voice button drew `voiceactive`/`voicepassive` from the `samp` texture
database and dereferenced the result without checking it:

```cpp
renderer->drawImage(..., recording() ? m_texture_micro_on->raster
                                     : m_texture_micro_off->raster);
```

The database this client installs does not contain either name - the log says
`Texture (samp) not found in database (voiceactive)` - so both pointers were
null, and the first frame after a server accepted the client read `->raster` at
address 0. Every session died in `VoiceButton::draw()` right after connecting,
with `SIGSEGV fault=0x0`.

Now:

* `VoiceButton` draws itself like any other button (background, outline and its
  `TALK` caption) when the icon is missing, and tries the older
  `icon_micro_on`/`icon_micro_off` names first,
* the same null check is applied where the speaker icon, the AFK/micro icon, the
  voice plugin button and `Image` were dereferenced the same way,
* a missing icon degrades to a plain `MIC` button, so voice chat stays usable,
* a CI gate refuses a build where any of those guards disappears.

### Why spawning on a server used to end the session

`IS_ANIMATION_LOADED` is script command `0x04EE`. The engine resolves the block
name and then reads the "loaded" byte of the result without checking that the
lookup succeeded:

```asm
0x41d8ac  bl  CAnimManager::GetAnimationBlock
0x41d8b0  ldrb w1, [x0, #0x10]      ; SEGV at 0x10 when the name is unknown
```

The block table comes from `ANIMGRP.DAT`. Servers preload their animations by
sending every block name they know, and this client's `ANIMGRP.DAT` has no
`ROB_FLEECA` - so the moment the player spawned, the preloader asked about a
block that does not exist and the engine died reading address `+0x10`.

Now:

* `CGame::HasAnimationBlock()` asks the exported
  `CAnimManager::GetAnimationBlock` directly (resolved with `dlsym`, with the
  per-ABI offset as a fallback),
* `CGame::IsAnimationLoaded()` answers "not loaded" for a block that is not
  installed instead of asking the engine, and `RequestAnimation()` skips it,
* `CPlayerPed::ApplyAnimation()` returns early for such a block, logs it once,
  and no longer queues the same animation twice when the block is still loading,
* a CI gate fails the build if any of those guards disappears.

### The server address comes from settings.ini

`[client] host` and `[client] port` in the app-private `settings.ini`
(`files/settings.ini` under Android's private app data directory) are the address
the client dials. This file is shared by the launcher and native client; it is
not read from the shared GTA folder, external app files, or cache. Android
removes it on uninstall or Clear data, and the app seeds it again from the
bundled defaults on next launch.

Both settings used to be ignored: `DoInitStuff()` constructed `CNetGame` with a
literal `"94.23.168.153", 2305`, so no server configured in the file could ever
be reached.

Now:

* the address is read on every start, and `net: configured server <host>:<port>
  (from app-private settings.ini)` names it in the log,
* `host` accepts `1.2.3.4`, `1.2.3.4:7777`, `host.name`, quotes, spaces and a
  `samp://`/`udp://` prefix; a port written inside `host` wins over the port key,
* `RakClient::Connect()` tests the host with `inet_pton` instead of
  `host[0] < '0' || host[0] > '2'` - that test sent every literal address that
  did not start with 0, 1 or 2 to DNS, and a failed lookup replaced the host with
  a null pointer,
* a lookup that fails now returns `false` (the caller retries) instead of
  connecting to nothing, and the resolved address is logged,
* every attempt and result is logged: `net: connect attempt <host>:<port>`,
  `net: no response from ...`, `net: connection accepted by ...`,
* `SettingsIni.resolve()` is the single path helper used by the server browser,
  the settings screen and `ConfigValidator`; it creates the private file from
  APK-bundled defaults when missing, and the game receives that same path before
  native settings initialization.

### Diagnosing a start that dies

A session once ended right after `Loading script.img..` with nothing at all in
the diagnostics file: no `SIGSEGV`, no `SIGABRT`. The crash reporting itself was
the problem. The handlers chained to the previous handler (debuggerd) *first* and
only then called `FLog()`, which needs `fopen`/`fprintf`/`malloc` - none of them
async-signal-safe. When the fault happened inside the allocator the handler
deadlocked, and when the chained handler terminated the process the report was
never written.

Now:

* `ReportFatal()` writes `signal`, fault address, `si_code`, `pc`, `lr`, `sp` and
  the current stage to `/storage/emulated/0/GS-SAMP/crash.txt` using only
  `open`/`write`/`fsync`, then restores the default action and re-raises so the
  system still produces its own tombstone. `SIGILL`, `SIGTERM` and `SIGQUIT` (the
  signal the system sends to a stuck app) are handled too.
* `GameStage()` records how far the startup got in
  `/storage/emulated/0/GS-SAMP/stage.txt` - the stage name on the first line,
  `free=`/`rss=` on the second - and logs RSS, peak RSS and the system's
  `MemAvailable` with every stage: `libsamp loaded`, `renderware`,
  `streaming images`, `scene load`, `game initialise`, `ui ready`,
  `game loop running` and `clean exit`.
* An `atexit` reporter appends `=== EXIT atexit stage=...` to `crash.txt` when the
  engine's own shutdown path calls `exit()`, which tears the process down with no
  signal at all. A genuine clean exit is skipped, so the file only names
  surprises.
* A `SIGKILL` (low-memory killer, task removed) cannot be caught, so it is
  recognised on the *next* start instead: `stage.txt` newer than `crash.txt`
  means the previous session went away without reporting, and the launcher logs
  that together with the memory numbers recorded at that stage.
* The launcher prints how the previous session ended before starting the next
  one, and drops what it can from the Java heap first - the game runs in the same
  process, so the installer's memory is still resident while the game maps a
  gigabyte of texture databases. It also waits for headroom (three
  `ActivityManager.getMemoryInfo()` checks with a `gc` in between) before it
  starts the game.

The overlay font is part of the same contract. `libsamp` loads
`SAMP/fonts/arial_bold.ttf` while the UI starts up, and
`ImFontAtlas::AddFontFromFileTTF()` calls `abort()` when the file is missing -
which killed the game with `SIGABRT` inside `UI::initialize()` right after the
loading screen. The APK now ships the font
(`assets/SAMP/fonts/arial_bold.ttf`) and the launcher seeds it into
`SAMP/fonts/` on the first start. As a second line of defence,
`ImGuiWrapper::initialize()` checks that the file is readable before calling into
dear imgui, tries the configured name plus the other known locations, and falls
back to the built-in `ProggyClean` font - it never aborts.

### Why the character stayed frozen and the buttons were missing

Three separate reports, one build.

**Animations.** The client used to hand every animation to the engine as a
script task (`apply_animation`, opcode 0x0812) without knowing whether the
library was loaded or whether the animation name existed in it. A task built
around an animation that is not there never finishes: the ped keeps it and
stands frozen for the rest of the session. The animation is now applied the same
way the engine's own `CTaskSimpleRunAnim` applies it -
`CAnimManager::GetAnimation(name, block)` followed by
`CAnimManager::BlendAnimation(clump, hierarchy, flags, rate)`, with the flags
`PlayAnimScriptCommand` computes (partial, looped, translations, last frame) - so
it is known *before* the ped is touched whether there is anything to play.
Requests for a library that is still streaming are queued and applied on the
frame its data lands, `"null"` is treated as the preload it is, and
`ClearAnimations` now actually stops the animation instead of only moving the
ped. Libraries this install does not carry (only `CRACK` in practice) are
skipped, and library names are logged once instead of on every request.

**The TALK control.** The texture database has no microphone artwork, so the
button fell back to the word MIC in the chat font. It is now drawn: rounded
background, accent outline, pulsing ring while the microphone is open and the
microphone glyph from `gui/MicGlyph.h` - the same glyph the voice window uses
for its icon button.

**The action bar.** The `>>` / `<<` controls are restored: it starts collapsed
and can be opened and closed. The previous always-visible change misunderstood
the report: native GTA sprint/attack widgets are separate from this SA-MP bar.
The uniform UI scale and drawn microphone are retained.

**Spawn-time rendering investigation (0.8.4.1).** `crash-3.txt` identifies
`libGTASA.so+0x2530e0` (`EmuShader::AssignShader`) for one failure; two others
report unknown modules and cannot yet be symbolicated. `logcat-13.txt` ends
with textdraw creation. Review found and fixed concrete hazards in that path:
uninitialised texture lookup, duplicate texture release, slot exhaustion and
reuse, unchecked RPC lengths/IDs, and passing a texture where the engine expects
a sprite pointer holder. Database registration is now balanced on lookup failure.
These fixes are not proof that all three device crashes have the same cause.
`python3 tests/test_textdraw_safety.py` exercises actual slot/lifecycle functions
with AddressSanitizer and UndefinedBehaviorSanitizer. Device spawn/respawn and
native widget verification are still required.

**No more aborts.** `RLEDecompress` threw `std::runtime_error` from inside a
streaming hook; nothing catches there, so `std::terminate()` ended the process
(the last line of such a session was an `rled` error). It never throws now: it
stops at the end of its source, zero-fills the rest of the destination so the
caller gets a defined buffer, and reports the truncation. The same treatment was
given to the other throwing paths reached from the network thread -
`CVehicle`/`CActor` ("Model not loaded", which closed the game when a server
spawned something this install cannot stream), `string_format` and the voice
channel constructor.

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
