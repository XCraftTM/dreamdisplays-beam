# Dream Displays Beam

An addon for [Dream Displays](https://modrinth.com/mod/dreamdisplays): cast a **screen**, an **application window**
or a **camera** onto a Dream Displays display, live, and stream it to the other players near the display, with
sound.

- **Cast button in Dream Displays' display menu** (next to the delete button) and a key (`B`, look at a display):
  opens the cast dialog with tabs for screens, applications and cameras, live preview tiles, resolution
  (480p – 1440p) and frame rate (15 / 30 / 60) select menus, "Stream to players" and the sound switch.
  "Stream to players" is greyed out and struck through, with a tooltip saying why, on a server without Beam.
- **Camera mode**: for a camera, the dialog lists what the camera itself can send (size, frame rate and format, as
  FFmpeg reports them) and remembers the choice per camera. Many webcams reach 1080p at 60 FPS only as MJPEG and
  fall back to a slow uncompressed mode by default.
- **A cast and the display's video take turns**: a cast pauses the display's video (picture and sound); a new video
  set on the display, or play pressed by the caster, ends the cast. A viewer who presses play hides that cast for
  themselves until it starts again.
- **[Dream Displays Lumen](https://github.com/XCraftTM/dreamdisplays-lumen)** (0.1.1+): casts light the room in
  their colors like videos do.
- The picture is drawn through Dream Displays' own display renderer, so it sits exactly where its videos do, with
  the display's rotation, brightness and shader-pack handling. It works on displays without a video too.
- **Streaming**: with Beam on the server (or in singleplayer / LAN), players within 96 blocks of the display see
  the cast at its own resolution and frame rate. It goes out as H.264 video, or as **HEVC** (about a third less)
  when every Beam player on the server can decode it, at a **variable bit rate** capped at about 12 Mbit/s for
  1440p60: a calm picture takes a fraction of that. It is encoded by the same FFmpeg process that captures, on the
  GPU where there is one: NVIDIA NVENC, AMD AMF, Intel Quick Sync, Windows Media Foundation, Linux VAAPI
  (AMD/Intel), macOS VideoToolbox; libx264 on the CPU otherwise (`streamEncoder`, `streamCodec`,
  `streamBitrateKbps` in the config). Players decode it with Dream Displays' FFmpeg, in hardware where possible. A
  player who comes into range has the picture within two seconds (a key frame every two seconds). The server only
  passes the data on (it checks who may cast and limits the bandwidth); an FFmpeg without any H.264 encoder falls
  back to JPEG frames.
- **No lag from casting**: the video goes out paced, so the game's own packets never wait behind it (a delay probe
  echoed by the server measures what they go through). When a connection cannot keep up, whole frames are skipped up
  to the next key frame: viewers see the last whole picture a little longer, never a broken one, and the caster's
  ping stays normal (tested with a 4 Mbit/s upload: mostly 20 to 140 ms, instead of climbing to 21 s). The server
  does the same for each viewer. Optionally the video goes over a port of its own (`streamPort`).
- **Sound**:
  - Screen and application casts send what the computer plays to the other players only. You don't hear it twice,
    since you already hear it from your computer. On Windows the game's own sound is left out (process loopback,
    Windows 10 2004+), and an application cast takes only that application's sound.
  - Camera casts take the camera's own audio input (found by name, like "Game Capture 4K60 Pro MK.2 Audio" or a
    webcam's microphone) and play it for everyone, you included, from the display.
  - Played positionally at the display, fading out over 32 blocks; volume follows "Jukebox/Note Blocks".

## Requirements

- Fabric Loader 0.19.5+, Fabric API, Dream Displays 1.10.6+, on every Minecraft version Dream Displays supports:

  | Minecraft | 1.18.2 | 1.19.4 | 1.20.1 | 1.21.1 | 1.21.11 | 26.1.2 | 26.2 | 26.3 |
  |---|---|---|---|---|---|---|---|---|
  | Java | 17 | 17 | 17 | 21 | 21 | 25 | 25 | 25 |

  One jar per version: `dreamdisplays-beam-<version>+<minecraft>.jar`. Players on different Minecraft versions
  can't join the same server anyway, and the cast data is the same on every version.
- FFmpeg: the one Dream Displays downloads is used (else the one on the PATH, or `ffmpegPath` in the config).
- For streaming to others: Beam on the server too: the mod itself on a Fabric server, or the **Paper plugin**
  (`dreamdisplays-beam-paper-<version>.jar`, for Paper and Purpur with Dream Displays' Paper plugin; Dream Displays
  has no Spigot version, so neither does this). Without either, casting works and only you see it. Every viewer
  needs the mod.

## Paper plugin

Put `dreamdisplays-beam-paper-0.3.0.jar` into `plugins/` next to Dream Displays. It relays casts like the Fabric
server part: who may cast, the 96 block range, the bandwidth limit; it never decodes anything. Settings in
`plugins/DreamDisplaysBeam/config.yml` (same as `ddbeam-server.json` below); the permission `ddbeam.cast.any`
(default: operators) lets a player cast onto displays they do not own. `streamPort` / `streamHost` work as on
Fabric (see the table below). Folia is not supported.

## Platforms

| | Screens | Applications | Cameras | Screen / app sound | Camera sound |
|---|---|---|---|---|---|
| Windows | gdigrab (or `ddagrab`) | listed windows (gdigrab by title) | DirectShow | WASAPI loopback without the game; an app's own sound | DirectShow input of the same name |
| Linux X11 | x11grab | – | V4L2 | PulseAudio / PipeWire monitor (game included) | PulseAudio source of the same name |
| Linux Wayland | desktop portal (the desktop's own picker) + PipeWire | desktop portal | V4L2 | as above | as above |
| macOS | AVFoundation | – | AVFoundation | only through a loopback input such as BlackHole (`systemAudioDevice`) | AVFoundation input of the same name |

Notes on what was tested: everything on Windows 11. **Linux and macOS are implemented but less tested**:
- Wayland on x86-64 and ARM needs nothing but PipeWire: Beam writes its native helper, `config/ddbeam/ddbeam-wayland`
  (Rust, from `native/wayland-capture`, glibc 2.31 or newer), that asks the portal for the stream and hands it to
  FFmpeg. On other processors a Python helper takes its place, `config/ddbeam/wayland-cast.py`, which needs `python3`
  with PyGObject (`python3-gi` / `python-gobject`) and GStreamer with its PipeWire plugin (`gstreamer1.0-pipewire` +
  `gstreamer1.0-plugins-good`, or `gst-plugin-pipewire`). Go Live and Switch show the desktop's picker for the
  screen or window each time; Wayland allows no previews before that choice.
- macOS asks for the screen recording and camera permissions (for the launcher).

## Commands

`/ddbeam menu`, `/ddbeam stop`, `/ddbeam status`, `/ddbeam sources`, `/ddbeam reload`,
`/ddbeam start monitor|camera|window <n>`, `/ddbeam start title <window title>`.
The test pattern (a tile in the dialog and `/ddbeam start test`) is only offered in the development environment
or with `-Dddbeam.testPattern=true`.

## Settings

Client: `config/ddbeam.json` (resolution, frame rate, JPEG quality, fit/fill/stretch, cursor, share frame rate,
Windows grabber, camera size, camera modes, sound options, volume, audio range). Cast sound follows the master
and "Jukebox/Note Blocks" volumes. Server: `config/ddbeam-server.json`:

| field | default | |
|---|---|---|
| relay | true | pass casts on at all |
| whoCanCast | owner | `everyone`, `owner` (the display's owner and operators) or `operators` |
| viewDistance | 96 | blocks around the display that receive a cast |
| maxKilobytesPerSecond | 4096 | per caster; data above it is dropped (1440p60 needs about 1500) |
| maxFps | 30 | per caster |
| streamPort | 0 | a TCP port for casts' video next to the game connection (0: off); open it like the game port. Players who cannot reach it use the game connection |
| streamHost | "" | the host players reach the stream port at ("": the one they joined at), e.g. behind a proxy |

## Building and testing

```bash
./gradlew build                      # build/libs/dreamdisplays-beam-0.3.0+26.3.jar
./gradlew build -Pminecraft_version=1.20.1   # any version from versions/*.properties
./gradlew runAutotest                # test scene: casts the test pattern, screenshots in run/autotest/<mc>/screenshots
./gradlew runAutotest -PtestSource=monitor1
./gradlew runAutotest -PtestMode=stream   # caster, opens the world to LAN on port 25700 ...
./gradlew runViewer                       # ... and a second player that joins it and screenshots the cast
```

Paper plugin: `./gradlew :paper:build` (`paper/build/libs/`). Its test: a Paper server with Dream Displays' Paper
plugin, `paper/build/libs/ddbeam-paper-testscene.jar` (builds the room and the display) and the plugin on port
25700 (`online-mode=false`, `white-list=false`), then `./gradlew runCaster -PtestSource=monitor1` and
`./gradlew runViewer`. `./gradlew runCaster -PtestMode=cast` on a server without Beam shows the greyed-out
"Stream to players" with its tooltip.

On Windows, run `./gradlew` from Git Bash: `gradlew.bat` breaks on the `&` in this folder's path.
The test worlds `run/autotest/<mc>/saves/beam-test` are vanilla flat worlds, made with each version's vanilla
server (`level-type=flat`). Before 1.20 (no quick play) the test driver opens the world from the title screen.
