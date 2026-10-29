#!/usr/bin/env python3
# Dream Displays Beam: screen and window capture on Wayland.
#
# Wayland lets applications capture the screen only through the desktop portal (org.freedesktop.portal.ScreenCast):
# the desktop shows its own picker, then hands out a PipeWire stream. This helper asks the portal for that stream
# and has GStreamer encode it as a stream of JPEG frames (multipart, like FFmpeg's mpjpeg) on stdout, which Beam
# reads. It stays alive while capturing: the portal ends the session when its D-Bus connection closes.
#
# Needs: python3 with PyGObject (python3-gi), GStreamer 1.x with the PipeWire plugin (gstreamer1.0-pipewire) and
# jpegenc / multipartmux (gst-plugins-good).
#
# Usage: wayland-cast.py WIDTH HEIGHT FPS JPEG_QUALITY(1-100) CURSOR(0|1) TYPES(1 monitor, 2 window) TOKEN_FILE
# Exit codes: 2 cancelled in the picker, 3 PyGObject missing, 4 GStreamer / its PipeWire plugin missing,
#             5 portal error.

import os
import shutil
import signal
import subprocess
import sys


def fail(code, message):
    sys.stderr.write(message + "\n")
    sys.stderr.flush()
    sys.exit(code)


try:
    import gi
    gi.require_version("Gio", "2.0")
    gi.require_version("GLib", "2.0")
    from gi.repository import Gio, GLib
except Exception as e:  # noqa: BLE001
    fail(3, "PyGObject (python3-gi) is missing: %s" % e)

if shutil.which("gst-launch-1.0") is None:
    fail(4, "gst-launch-1.0 (GStreamer) is missing")
inspect = subprocess.run(["gst-inspect-1.0", "pipewiresrc"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         universal_newlines=True)
if inspect.returncode != 0:
    fail(4, "GStreamer's PipeWire plugin (pipewiresrc) is missing")
# newer pipewiresrc only: resend the last frame while the screen does not change
keepalive = ["keepalive-time=1000"] if "keepalive-time" in inspect.stdout else []

width, height, fps, quality, cursor, types, token_file = (
    int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5] == "1", int(sys.argv[6]),
    sys.argv[7])

PORTAL = "org.freedesktop.portal.Desktop"
PATH = "/org/freedesktop/portal/desktop"
SCREENCAST = "org.freedesktop.portal.ScreenCast"

bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
sender = bus.get_unique_name()[1:].replace(".", "_")
loop = GLib.MainLoop()
counter = [0]


def token():
    counter[0] += 1
    return "ddbeam%d_%d" % (os.getpid(), counter[0])


def prop(name, default):
    try:
        value = bus.call_sync(PORTAL, PATH, "org.freedesktop.DBus.Properties", "Get",
                              GLib.Variant("(ss)", (SCREENCAST, name)), GLib.VariantType("(v)"),
                              Gio.DBusCallFlags.NONE, -1, None)
        return value.unpack()[0]
    except Exception:  # noqa: BLE001
        return default


def request(method, args, signature, handle):
    """Calls a portal method that answers through a Request object's Response signal."""
    path = "/org/freedesktop/portal/desktop/request/%s/%s" % (sender, handle)
    answer = {}

    def on_response(_conn, _sender, _path, _iface, _signal, params, _data):
        response, results = params.unpack()
        answer["response"] = response
        answer["results"] = results
        loop.quit()

    # subscribe before calling, so a quick answer is not missed
    sub = bus.signal_subscribe(PORTAL, "org.freedesktop.portal.Request", "Response", path, None,
                               Gio.DBusSignalFlags.NONE, on_response, None)
    try:
        bus.call_sync(PORTAL, PATH, SCREENCAST, method, GLib.Variant(signature, args), None,
                      Gio.DBusCallFlags.NONE, -1, None)
    except Exception as e:  # noqa: BLE001
        bus.signal_unsubscribe(sub)
        fail(5, "%s failed: %s" % (method, e))
    loop.run()
    bus.signal_unsubscribe(sub)
    return answer.get("response", 2), answer.get("results", {})


version = prop("version", 1)
cursor_modes = prop("AvailableCursorModes", 1)

handle = token()
response, results = request("CreateSession", ({
    "handle_token": GLib.Variant("s", handle),
    "session_handle_token": GLib.Variant("s", token()),
},), "(a{sv})", handle)
if response != 0:
    fail(5, "CreateSession was refused (%d)" % response)
session = results["session_handle"]

options = {
    "types": GLib.Variant("u", types),
    "multiple": GLib.Variant("b", False),
}
if cursor and cursor_modes & 2:
    options["cursor_mode"] = GLib.Variant("u", 2)  # drawn into the picture
elif cursor_modes & 1:
    options["cursor_mode"] = GLib.Variant("u", 1)  # hidden
if version >= 4:
    # remember the choice: no picker the next time
    options["persist_mode"] = GLib.Variant("u", 2)
    try:
        with open(token_file) as f:
            saved = f.read().strip()
        if saved:
            options["restore_token"] = GLib.Variant("s", saved)
    except OSError:
        pass
handle = token()
options["handle_token"] = GLib.Variant("s", handle)
response, _ = request("SelectSources", (session, options), "(oa{sv})", handle)
if response != 0:
    fail(2 if response == 1 else 5, "SelectSources ended with %d" % response)

handle = token()
response, results = request("Start", (session, "", {"handle_token": GLib.Variant("s", handle)}), "(osa{sv})", handle)
if response != 0:
    fail(2 if response == 1 else 5, "Start ended with %d" % response)
streams = results.get("streams") or []
if not streams:
    fail(5, "the portal gave no stream")
node = streams[0][0]
if "restore_token" in results:
    try:
        os.makedirs(os.path.dirname(token_file), exist_ok=True)
        with open(token_file, "w") as f:
            f.write(results["restore_token"])
    except OSError:
        pass

reply, fds = bus.call_with_unix_fd_list_sync(PORTAL, PATH, SCREENCAST, "OpenPipeWireRemote",
                                             GLib.Variant("(oa{sv})", (session, {})), GLib.VariantType("(h)"),
                                             Gio.DBusCallFlags.NONE, -1, None, None)
fd = fds.get(reply.unpack()[0])

pipeline = [
    "gst-launch-1.0", "-q",
    "pipewiresrc", "fd=%d" % fd, "path=%d" % node, "do-timestamp=true", *keepalive,
    "!", "videoconvert",
    "!", "videorate",
    "!", "video/x-raw,framerate=%d/1" % fps,
    "!", "videoscale", "add-borders=true",
    "!", "video/x-raw,width=%d,height=%d,pixel-aspect-ratio=1/1" % (width, height),
    "!", "videoconvert",
    "!", "jpegenc", "quality=%d" % quality,
    "!", "multipartmux", "boundary=ffmpeg",
    "!", "fdsink", "fd=1", "sync=false",
]
child = subprocess.Popen(pipeline, pass_fds=(fd,))


def stop(*_):
    child.terminate()
    try:
        child.wait(2)
    except subprocess.TimeoutExpired:
        child.kill()
    sys.exit(0)


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
sys.exit(child.wait())
