# Dream Displays Beam

An addon for [Dream Displays](https://modrinth.com/mod/dreamdisplays): cast a **screen**, an **application window**
or a **camera** onto a Dream Displays display, live, and stream it to the other players near the display, with
sound.

- **Cast button in Dream Displays' display menu** (next to the delete button) and a key (`B`, look at a display):
  opens the cast dialog with tabs for screens, applications and cameras, live preview tiles, resolution
  (480p – 1440p) and frame rate (15 / 30 / 60) select menus, "Stream to players" and the sound switch.
- The picture is drawn through Dream Displays' own display renderer, so it sits exactly where its videos do, with
  the display's rotation, brightness and shader-pack handling. It works on displays without a video too.
- **Streaming**: with Beam on the server (or in singleplayer / LAN), players within 96 blocks of the display see
  the cast at its own resolution and frame rate. It goes out as H.264 video (about 1.4 MB/s for 1440p60, instead
  of about 17 MB/s as JPEG frames), encoded by the same FFmpeg process that captures, on the GPU where there is
  one: NVIDIA NVENC, AMD AMF, Intel Quick Sync, Windows Media Foundation, Linux VAAPI (AMD/Intel), macOS
  VideoToolbox; libx264 on the CPU otherwise (`streamEncoder` / `streamBitrateKbps` in the config). Players decode
  it with Dream Displays' FFmpeg, in hardware where possible. A player who comes into range has the picture within
  two seconds (a key frame every two seconds). The server only passes the data on (it checks who may cast and
  limits the bandwidth); an FFmpeg without any H.264 encoder falls back to JPEG frames.
- **Sound**:
  - Screen and application casts send what the computer plays to the other players only. You don't hear it twice,
    since you already hear it from your computer. On Windows the game's own sound is left out (process loopback,
    Windows 10 2004+), and an application cast takes only that application's sound.
  - Camera casts take the camera's own audio input (found by name, like "Game Capture 4K60 Pro MK.2 Audio" or a
    webcam's microphone) and play it for everyone, you included, from the display.
  - Played positionally at the display, fading out over 32 blocks; volume follows "Jukebox/Note Blocks".

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Dream Displays 1.10.6+, Java 25.
- FFmpeg: the one Dream Displays downloads is used (else the one on the PATH, or `ffmpegPath` in the config).
- For streaming to others: Beam on the server too: the mod itself on a Fabric server, or the **Paper plugin**
  (`dreamdisplays-beam-paper-<version>.jar`, for Paper and Purpur with Dream Displays' Paper plugin; Dream Displays
  has no Spigot version, so neither does this). Without either, casting works and only you see it. Every viewer
  needs the mod.

## Paper plugin

Put `dreamdisplays-beam-paper-0.1.0.jar` into `plugins/` next to Dream Displays. It relays casts like the Fabric
server part: who may cast, the 96 block range, the bandwidth limit; it never decodes anything. Settings in
`plugins/DreamDisplaysBeam/config.yml` (same as `ddbeam-server.json` below); the permission `ddbeam.cast.any`
(default: operators) lets a player cast onto displays they do not own. Folia is not supported.

## Platforms

| | Screens | Applications | Cameras | Screen / app sound | Camera sound |
|---|---|---|---|---|---|
| Windows | gdigrab (or `ddagrab`) | listed windows (gdigrab by title) | DirectShow | WASAPI loopback without the game; an app's own sound | DirectShow input of the same name |
| Linux X11 | x11grab | – | V4L2 | PulseAudio / PipeWire monitor (game included) | PulseAudio source of the same name |
| Linux Wayland | desktop portal (the desktop's own picker) + PipeWire | desktop portal | V4L2 | as above | as above |
| macOS | AVFoundation | – | AVFoundation | only through a loopback input such as BlackHole (`systemAudioDevice`) | AVFoundation input of the same name |

Notes on what was tested: everything on Windows 11. **Linux and macOS are implemented but untested**:
- Wayland needs `python3` with PyGObject (`python3-gi` / `python-gobject`) and GStreamer with its PipeWire plugin
  (`gstreamer1.0-pipewire` + `gstreamer1.0-plugins-good`, or `gst-plugin-pipewire`). Beam writes a small helper,
  `config/ddbeam/wayland-cast.py`, that asks the portal for the stream. The desktop asks once which screen or window
  to share and remembers the choice. Wayland allows no previews before that choice.
- macOS asks for the screen recording and camera permissions (for the launcher).

## Commands

`/ddbeam menu`, `/ddbeam stop`, `/ddbeam status`, `/ddbeam sources`, `/ddbeam reload`,
`/ddbeam start monitor|camera|window <n>`, `/ddbeam start title <window title>`, `/ddbeam start test`.

## Settings

Client: `config/ddbeam.json` (resolution, frame rate, JPEG quality, fit/fill/stretch, cursor, share frame rate,
Windows grabber, camera size, sound options, volume, audio range). Server: `config/ddbeam-server.json`:

| field | default | |
|---|---|---|
| relay | true | pass casts on at all |
| whoCanCast | owner | `everyone`, `owner` (the display's owner and operators) or `operators` |
| viewDistance | 96 | blocks around the display that receive a cast |
| maxKilobytesPerSecond | 4096 | per caster; data above it is dropped (1440p60 needs about 1500) |
| maxFps | 30 | per caster |

## Building and testing

```bash
./gradlew build                      # build/libs/dreamdisplays-beam-0.1.0+26.3.jar
./gradlew runAutotest                # test scene: casts the test pattern, screenshots in run/autotest/26.3/screenshots
./gradlew runAutotest -PtestSource=monitor1
./gradlew runAutotest -PtestMode=stream   # caster, opens the world to LAN on port 25700 ...
./gradlew runViewer                       # ... and a second player that joins it and screenshots the cast
```

Paper plugin: `./gradlew :paper:build` (`paper/build/libs/`). Its test: a Paper server with Dream Displays' Paper
plugin, `paper/build/libs/ddbeam-paper-testscene.jar` (builds the room and the display) and the plugin on port
25700 (`online-mode=false`, `white-list=false`), then `./gradlew runCaster -PtestSource=monitor1` and
`./gradlew runViewer`.

On Windows, run `./gradlew` from Git Bash: `gradlew.bat` breaks on the `&` in this folder's path.
The test world `run/autotest/26.3/saves/beam-test` is a vanilla flat world.
