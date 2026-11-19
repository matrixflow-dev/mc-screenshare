# mc-screenshare

Share your desktop with other players on a Minecraft server.
The sharer presses a key, picks a monitor or a single window from a live preview, and anyone who wants to watch sees it inside the game.

It has two parts:

| Part | Where it runs | Folder |
|---|---|---|
| Client mod (Fabric) | Every player who shares or watches | `fabric/` |
| Server plugin (Paper) | The server | `paper/` |

Target: Minecraft Java Edition 26.3, Fabric Loader 0.20.1, Fabric API 0.166.2+26.3, Paper 26.3.

![Self-test: the client shares a test pattern and watches it back through a local Paper server](docs/selftest.png)

## Background

We run a company Minecraft server as a virtual office (Paper 26.3 on AWS, with Simple Voice Chat for voice).
Voice worked, but people also wanted to show each other their screens without leaving the game.

In November 2026 we looked at the existing mods:

| Mod | Why we could not use it |
|---|---|
| StreamCraft Live | Supports 26.3, but must also run on the server as a Fabric mod. Our server runs Paper, and moving it to Fabric would mean rebuilding it, including Bedrock (Geyser) access. |
| SVC Screen Share (Simple Voice Chat addon) | Only released for 26.2. Its license is "All Rights Reserved" and no source is published, so we could not port it to 26.3 ourselves. |

Asking the SVC Screen Share author for a 26.3 build was possible, but there was no sign of an update after September 2026.
So we wrote our own, from scratch.
No code from either mod was used; this project only talks to vanilla Minecraft, Fabric API and the Paper API.

The design aims at "good enough for showing a slide or a terminal", not video:
about one frame per second, JPEG, no audio.

## How it works

1. The sharer's client lists the monitors and windows and takes screenshots with the OS's own tools, because Minecraft runs with `java.awt.headless=true` and `java.awt.Robot` is unavailable.
   - macOS: `screencapture` (`-D` for a monitor, `-l` for a window); the list comes from `osascript` (JavaScript for Automation)
   - Windows: one long-running PowerShell process using `System.Drawing` for monitors and `PrintWindow` for windows
   - Linux X11: ImageMagick `import`; monitors from `xrandr`, windows from `wmctrl` (optional)
   - Linux Wayland: `grim`, whole desktop only (Wayland does not let other programs capture single windows)
2. The image is scaled to at most 1280 px wide and encoded as JPEG.
3. The JPEG is cut into 30,000-byte chunks, because client-to-server custom payloads are capped at 32,767 bytes.
4. The Paper plugin checks each chunk's header and forwards the bytes, unchanged, to every player who asked to watch. It never decodes images.
5. The watcher's client reassembles the newest frame, decodes it on a background thread and draws it on the HUD.

Plugin channels: `screenshare:frame_up`, `screenshare:frame_down`, `screenshare:control`, `screenshare:state`.
The byte layout is documented in `fabric/src/main/java/io/github/tdual/screenshare/net/` and `paper/src/main/java/io/github/tdual/screenshare/paper/Wire.java`.

## Install

### Server

Put `screenshare-paper-<version>.jar` in `plugins/` and restart.
A `plugins/ScreenShare/config.yml` is created:

| Key | Default | Meaning |
|---|---|---|
| `max-sharers` | 3 | How many people may share at the same time |
| `max-kilobytes-per-second` | 600 | Upload limit per sharer; chunks above it are dropped |
| `idle-timeout-seconds` | 10 | A share ends when no frame arrives for this long |

Permissions: `screenshare.share` and `screenshare.watch` (everyone by default), `screenshare.admin` (ops).

### Client

Put these in the `mods` folder of a Fabric 26.3 profile:

- Fabric API
- `screenshare-fabric-<version>.jar`

On macOS, the first share triggers a Screen Recording permission request for the launcher (for example Prism Launcher).
Allow it in System Settings → Privacy & Security → Screen Recording, then restart the game.
Without it, macOS returns only the wallpaper.

## Usage

| Action | How |
|---|---|
| Choose a monitor or window and start sharing / stop sharing | `J` |
| Show shared screen: off → small (top right) → large (center) → off | `K` |
| Watch a specific person | `/ss watch <name>` |
| Stop watching | `/ss off` |
| Reload client settings | `/ss reload` |
| List who is sharing (server command) | `/screenshare` |
| Stop someone's share (ops) | `/screenshare stop <name>` |

Keys can be changed under Options → Controls → Key Binds → Screen Share.

`J` opens a picker: monitors (yellow) and windows are listed on the left, and the selected one is previewed live on the right.
Press **Share** (or double-click an entry) to start; nothing is sent until then.

When you share a monitor, everything on it is shared, including notifications and other windows. Share a single window instead if that matters.
A shared window is captured on its own even when other windows cover it, except on Linux X11, where overlapping windows show through. Minimized windows cannot be shared.

Client settings live in `config/screenshare.properties`:

| Key | Default | Range |
|---|---|---|
| `fps` | 1.0 | 0.2 – 5 |
| `max_width` | 1280 | 320 – 1920 |
| `jpeg_quality` | 0.6 | 0.1 – 1.0 |
| `display` | 1 | monitor selected first in the picker; updated when you share a monitor |

## Bandwidth and cost

Each frame is sent once by the sharer and once to every watcher.
A 1280 px desktop at quality 0.6 is roughly 80–150 KB, so at 1 fps each watcher costs about 0.3–0.5 GB per hour of server egress.
On AWS (about $0.114/GB out of Tokyo) that is around $0.05 per watcher-hour. Raise `fps` with care.

## Status

| Item | State |
|---|---|
| Send → relay → receive → draw | Tested on macOS with a local Paper 26.3 server and a generated test pattern (screenshot above) |
| Paper plugin logic | 9 unit tests (`paper/src/test`) |
| Real desktop capture on macOS | Not yet tested (the development terminal lacked Screen Recording permission) |
| Windows and Linux capture | Not yet tested |
| Monitor/window picker on a real desktop (macOS, Windows, Linux) | Not yet tested; builds, and the self-test path is unchanged |
| Two different players on the real server | Not yet tested |

## Development

Requires JDK 25.

```bash
cd fabric && ./gradlew build   # fabric/build/libs/screenshare-fabric-<version>.jar
cd paper  && ./gradlew build   # paper/build/libs/screenshare-paper-<version>.jar (runs the unit tests)
```

Self-test: start a Paper 26.3 server on `127.0.0.1:25566` with `online-mode=false`, `difficulty=peaceful`, whitelist off and the plugin installed, then run `cd fabric && ./gradlew runClient`.
The dev run joins that server, shares a generated test pattern (`-Dscreenshare.testpattern=true`), watches itself (`-Dscreenshare.selftest=true`), saves a screenshot under `fabric/run/screenshots/` after five frames and quits.

## License

MIT
