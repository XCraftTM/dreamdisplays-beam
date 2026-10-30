//! Dream Displays Beam: screen and window capture on Wayland, without Python or GStreamer.
//!
//! Wayland lets applications capture the screen only through the desktop portal (org.freedesktop.portal.ScreenCast):
//! the desktop shows its own picker, then hands out a PipeWire stream. This helper asks the portal for that stream,
//! receives its frames from PipeWire and feeds them to FFmpeg as raw video, which Beam's usual pipeline then turns
//! into JPEG frames for the display and H.264 / HEVC for the other players. It stays alive while capturing: the portal
//! ends the session when the helper's D-Bus connection closes.
//!
//! Usage: ddbeam-wayland FPS CURSOR(0|1) TYPES(1 monitor, 2 window) TOKEN_FILE -- FFMPEG ARGS...
//! The FFmpeg arguments read raw video from stdin; "{size}" and "{pix_fmt}" in them become the picture's size and
//! pixel format once PipeWire has settled them. FFmpeg's stdout and stderr are the helper's.
//! Exit codes: 2 cancelled in the picker, 5 portal error, 6 PipeWire error, 7 FFmpeg did not start, 64 usage;
//! otherwise FFmpeg's own.

use std::cell::RefCell;
use std::io::Write;
use std::os::fd::OwnedFd;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender, TrySendError};
use std::sync::Arc;
use std::time::{Duration, Instant};

use ashpd::desktop::screencast::{CursorMode, Screencast, SourceType};
use ashpd::desktop::{PersistMode, ResponseError};
use pipewire as pw;
use pw::properties::properties;
use pw::spa;
use pw::spa::param::video::{VideoFormat, VideoInfoRaw};
use pw::spa::pod::Pod;

const CANCELLED: i32 = 2;
const PORTAL: i32 = 5;
const PIPEWIRE: i32 = 6;
const NO_FFMPEG: i32 = 7;
const USAGE: i32 = 64;
/// A still picture is sent again this often, so the encoder keeps writing key frames for players who come later.
const KEEPALIVE: Duration = Duration::from_millis(500);

struct Args {
    fps: u32,
    cursor: bool,
    window: bool,
    token_file: PathBuf,
    ffmpeg: Vec<String>,
}

fn parse_args() -> Option<Args> {
    let mut args = std::env::args().skip(1);
    let fps: u32 = args.next()?.parse().ok()?;
    let cursor = args.next()? == "1";
    let window = args.next()? == "2";
    let token_file = PathBuf::from(args.next()?);
    if args.next()? != "--" {
        return None;
    }
    let ffmpeg: Vec<String> = args.collect();
    if ffmpeg.is_empty() || fps == 0 {
        return None;
    }
    Some(Args { fps: fps.min(240), cursor, window, token_file, ffmpeg })
}

fn fail(code: i32, message: &str) -> ! {
    eprintln!("ddbeam-wayland: {message}");
    std::process::exit(code)
}

/// A refused or cancelled portal request.
fn portal_code(error: &ashpd::Error) -> i32 {
    match error {
        ashpd::Error::Response(ResponseError::Cancelled) => CANCELLED,
        _ => PORTAL,
    }
}

fn main() {
    let args = parse_args().unwrap_or_else(|| {
        fail(USAGE, "usage: ddbeam-wayland FPS CURSOR(0|1) TYPES(1 monitor, 2 window) TOKEN_FILE -- FFMPEG ARGS...")
    });
    // PipeWire's loop must run on the main thread; the portal's D-Bus connection runs on a thread of its own and
    // keeps the session going until capturing ends
    let (opened, stream) = std::sync::mpsc::channel::<(OwnedFd, u32)>();
    let (done, finished) = tokio::sync::oneshot::channel::<()>();
    let (cursor, window, token_file) = (args.cursor, args.window, args.token_file.clone());
    let portal_thread = std::thread::Builder::new()
        .name("portal".into())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap_or_else(|e| fail(PORTAL, &format!("no async runtime: {e}")));
            runtime.block_on(async move {
                let (portal, session, fd, node) = open(cursor, window, &token_file).await;
                let _ = opened.send((fd, node));
                let _ = finished.await;
                drop(session);
                drop(portal);
            });
        })
        .unwrap_or_else(|e| fail(PORTAL, &format!("no portal thread: {e}")));
    let Ok((fd, node)) = stream.recv() else {
        fail(PORTAL, "the portal ended early");
    };
    let code = capture(fd, node, args.fps, args.ffmpeg);
    let _ = done.send(());
    let _ = portal_thread.join();
    std::process::exit(code);
}

/// Asks the portal for the screen (or a window) and the PipeWire remote to receive it from; exits on failure.
async fn open<'a>(
    cursor: bool,
    window: bool,
    token_file: &std::path::Path,
) -> (Screencast<'a>, ashpd::desktop::Session<'a, Screencast<'a>>, OwnedFd, u32) {
    let portal = Screencast::new().await.unwrap_or_else(|e| fail(PORTAL, &format!("no screen cast portal: {e}")));
    let session = portal.create_session().await.unwrap_or_else(|e| fail(PORTAL, &format!("CreateSession: {e}")));
    let modes = portal.available_cursor_modes().await.unwrap_or_else(|_| CursorMode::Hidden.into());
    let cursor = if cursor && modes.contains(CursorMode::Embedded) {
        CursorMode::Embedded
    } else if modes.contains(CursorMode::Hidden) {
        CursorMode::Hidden
    } else {
        CursorMode::Embedded
    };
    let types = if window { SourceType::Window } else { SourceType::Monitor };
    // the choice made in the picker last time: no picker this time
    let token = std::fs::read_to_string(token_file).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty());
    portal
        .select_sources(&session, cursor, types.into(), false, token.as_deref(), PersistMode::ExplicitlyRevoked)
        .await
        .and_then(|request| request.response())
        .unwrap_or_else(|e| fail(portal_code(&e), &format!("SelectSources: {e}")));
    let streams = portal
        .start(&session, None)
        .await
        .and_then(|request| request.response())
        .unwrap_or_else(|e| fail(portal_code(&e), &format!("Start: {e}")));
    let Some(stream) = streams.streams().first() else {
        fail(PORTAL, "the portal gave no stream");
    };
    let node = stream.pipe_wire_node_id();
    if let Some(restore) = streams.restore_token() {
        if let Some(dir) = token_file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(token_file, restore);
    }
    let fd = portal
        .open_pipe_wire_remote(&session)
        .await
        .unwrap_or_else(|e| fail(PORTAL, &format!("OpenPipeWireRemote: {e}")));
    (portal, session, fd, node)
}

/// What the PipeWire callbacks share (all on PipeWire's thread).
struct State {
    /// The picture's fixed size and FFmpeg's name of its pixel format, set by the first format.
    width: u32,
    height: u32,
    pix_fmt: Option<&'static str>,
    /// The size of the frames coming in now (a window can change size; the output keeps the first one).
    source_width: u32,
    frame: Vec<u8>,
    fresh: bool,
    has_frame: bool,
    last_sent: Instant,
    ffmpeg_args: Vec<String>,
    child: Option<Child>,
    frames: Option<SyncSender<Vec<u8>>>,
    exit: Option<i32>,
}

/// FFmpeg's name for a PipeWire pixel format (4 bytes per pixel only).
fn ffmpeg_format(format: VideoFormat) -> Option<&'static str> {
    Some(match format {
        VideoFormat::BGRx => "bgr0",
        VideoFormat::RGBx => "rgb0",
        VideoFormat::BGRA => "bgra",
        VideoFormat::RGBA => "rgba",
        VideoFormat::xRGB => "0rgb",
        VideoFormat::xBGR => "0bgr",
        _ => return None,
    })
}

/// Hands a buffer's pixels (its chunk) to [f]: from memory (MemPtr), or mapped read-only from the buffer's file
/// descriptor for the copy (MemFd, DmaBuf). PipeWire's own mapping follows the buffer's flags, and screen cast buffers
/// often come without READABLE, which maps them unreadable.
fn with_pixels(data: &mut spa::buffer::Data, f: impl FnOnce(&[u8])) {
    let raw = *data.as_raw();
    let (offset, size) = {
        let chunk = data.chunk();
        (chunk.offset() as usize, chunk.size() as usize)
    };
    if size == 0 {
        return;
    }
    let kind = data.type_();
    if kind == spa::buffer::DataType::MemPtr {
        if let Some(bytes) = data.data() {
            if offset + size <= bytes.len() {
                f(&bytes[offset..offset + size]);
            }
        }
    } else if kind == spa::buffer::DataType::MemFd || kind == spa::buffer::DataType::DmaBuf {
        if raw.fd < 0 {
            return;
        }
        let length = raw.mapoffset as usize + raw.maxsize as usize;
        // SAFETY: a read-only shared mapping of the buffer's memory, unmapped right after the copy
        let map = unsafe { libc::mmap(std::ptr::null_mut(), length, libc::PROT_READ, libc::MAP_SHARED, raw.fd as i32, 0) };
        if map == libc::MAP_FAILED {
            return;
        }
        let all = unsafe { std::slice::from_raw_parts(map as *const u8, length) };
        let start = raw.mapoffset as usize + offset;
        if start + size <= length {
            f(&all[start..start + size]);
        }
        unsafe { libc::munmap(map, length) };
    }
}

fn capture(fd: OwnedFd, node: u32, fps: u32, ffmpeg_args: Vec<String>) -> i32 {
    match run(fd, node, fps, ffmpeg_args) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("ddbeam-wayland: PipeWire: {e}");
            PIPEWIRE
        }
    }
}

fn run(fd: OwnedFd, node: u32, fps: u32, ffmpeg_args: Vec<String>) -> Result<i32, pw::Error> {
    pw::init();
    let mainloop = pw::main_loop::MainLoop::new(None)?;
    let context = pw::context::Context::new(&mainloop)?;
    let core = context.connect_fd(fd, None)?;
    let state = Rc::new(RefCell::new(State {
        width: 0,
        height: 0,
        pix_fmt: None,
        source_width: 0,
        frame: Vec::new(),
        fresh: false,
        has_frame: false,
        last_sent: Instant::now(),
        ffmpeg_args,
        child: None,
        frames: None,
        exit: None,
    }));
    let writer_gone = Arc::new(AtomicBool::new(false));

    let stream = pw::stream::Stream::new(
        &core,
        "ddbeam-wayland",
        properties! {
            *pw::keys::MEDIA_TYPE => "Video",
            *pw::keys::MEDIA_CATEGORY => "Capture",
            *pw::keys::MEDIA_ROLE => "Screen",
        },
    )?;

    let _listener = stream
        .add_local_listener_with_user_data(())
        .state_changed({
            let mainloop = mainloop.clone();
            let state = state.clone();
            move |_, _, _, new| {
                if let pw::stream::StreamState::Error(message) = new {
                    eprintln!("ddbeam-wayland: PipeWire stream: {message}");
                    state.borrow_mut().exit.get_or_insert(PIPEWIRE);
                    mainloop.quit();
                }
            }
        })
        .param_changed({
            let mainloop = mainloop.clone();
            let state = state.clone();
            let writer_gone = writer_gone.clone();
            move |_, _, id, param| {
                let Some(param) = param else { return };
                if id != pw::spa::param::ParamType::Format.as_raw() {
                    return;
                }
                let mut info = VideoInfoRaw::default();
                if info.parse(param).is_err() {
                    return;
                }
                let size = info.size();
                let mut s = state.borrow_mut();
                s.source_width = size.width;
                if s.pix_fmt.is_some() {
                    return;
                }
                // the first format: the picture's size and pixel format from now on, and FFmpeg starts
                let Some(pix_fmt) = ffmpeg_format(info.format()) else {
                    eprintln!("ddbeam-wayland: unsupported pixel format {:?}", info.format());
                    s.exit = Some(PIPEWIRE);
                    mainloop.quit();
                    return;
                };
                s.width = size.width;
                s.height = size.height;
                s.pix_fmt = Some(pix_fmt);
                s.frame = vec![0; size.width as usize * size.height as usize * 4];
                let video_size = format!("{}x{}", size.width, size.height);
                let args: Vec<String> = s
                    .ffmpeg_args
                    .iter()
                    .map(|a| a.replace("{size}", &video_size).replace("{pix_fmt}", pix_fmt))
                    .collect();
                match Command::new(&args[0]).args(&args[1..]).stdin(Stdio::piped()).spawn() {
                    Ok(mut child) => {
                        let mut stdin = child.stdin.take().expect("FFmpeg's stdin");
                        // one frame waits at most: FFmpeg gets the newest one when it is ready for it
                        let (tx, rx) = sync_channel::<Vec<u8>>(1);
                        let gone = writer_gone.clone();
                        std::thread::spawn(move || {
                            for frame in rx {
                                if stdin.write_all(&frame).is_err() {
                                    break;
                                }
                            }
                            gone.store(true, Ordering::SeqCst);
                        });
                        s.frames = Some(tx);
                        s.child = Some(child);
                    }
                    Err(e) => {
                        eprintln!("ddbeam-wayland: could not start FFmpeg ({}): {e}", args[0]);
                        s.exit = Some(NO_FFMPEG);
                        mainloop.quit();
                    }
                }
            }
        })
        .process({
            let state = state.clone();
            move |stream, _| {
                let Some(mut buffer) = stream.dequeue_buffer() else { return };
                let datas = buffer.datas_mut();
                let Some(data) = datas.first_mut() else { return };
                let stride = data.chunk().stride();
                let mut s = state.borrow_mut();
                if s.pix_fmt.is_none() {
                    return;
                }
                let s = &mut *s;
                with_pixels(data, |pixels| {
                    let source_row = if stride > 0 { stride as usize } else { s.source_width as usize * 4 };
                    if source_row == 0 {
                        return;
                    }
                    let out_row = s.width as usize * 4;
                    let height = s.height as usize;
                    let copy = out_row.min(source_row).min(s.source_width as usize * 4);
                    let rows = height.min(pixels.len() / source_row);
                    let frame = &mut s.frame;
                    for y in 0..rows {
                        let from = &pixels[y * source_row..y * source_row + copy];
                        frame[y * out_row..y * out_row + copy].copy_from_slice(from);
                        // a narrower source leaves the rest of the row black
                        frame[y * out_row + copy..(y + 1) * out_row].fill(0);
                    }
                    for y in rows..height {
                        frame[y * out_row..(y + 1) * out_row].fill(0);
                    }
                    s.fresh = true;
                    s.has_frame = true;
                });
            }
        })
        .register()?;

    let format = pw::spa::pod::object!(
        pw::spa::utils::SpaTypes::ObjectParamFormat,
        pw::spa::param::ParamType::EnumFormat,
        pw::spa::pod::property!(pw::spa::param::format::FormatProperties::MediaType, Id, pw::spa::param::format::MediaType::Video),
        pw::spa::pod::property!(pw::spa::param::format::FormatProperties::MediaSubtype, Id, pw::spa::param::format::MediaSubtype::Raw),
        pw::spa::pod::property!(
            pw::spa::param::format::FormatProperties::VideoFormat,
            Choice,
            Enum,
            Id,
            VideoFormat::BGRx,
            VideoFormat::BGRx,
            VideoFormat::RGBx,
            VideoFormat::BGRA,
            VideoFormat::RGBA,
            VideoFormat::xRGB,
            VideoFormat::xBGR
        ),
        pw::spa::pod::property!(
            pw::spa::param::format::FormatProperties::VideoSize,
            Choice,
            Range,
            Rectangle,
            pw::spa::utils::Rectangle { width: 1920, height: 1080 },
            pw::spa::utils::Rectangle { width: 1, height: 1 },
            pw::spa::utils::Rectangle { width: 8192, height: 8192 }
        ),
        pw::spa::pod::property!(
            pw::spa::param::format::FormatProperties::VideoFramerate,
            Choice,
            Range,
            Fraction,
            pw::spa::utils::Fraction { num: fps, denom: 1 },
            pw::spa::utils::Fraction { num: 0, denom: 1 },
            pw::spa::utils::Fraction { num: 1000, denom: 1 }
        ),
    );
    let values: Vec<u8> = pw::spa::pod::serialize::PodSerializer::serialize(
        std::io::Cursor::new(Vec::new()),
        &pw::spa::pod::Value::Object(format),
    )
    .map_err(|_| pw::Error::CreationFailed)?
    .0
    .into_inner();
    let mut params = [Pod::from_bytes(&values).ok_or(pw::Error::CreationFailed)?];
    stream.connect(
        spa::utils::Direction::Input,
        Some(node),
        // no MAP_BUFFERS: with_pixels maps the buffers itself
        pw::stream::StreamFlags::AUTOCONNECT,
        &mut params,
    )?;

    // hands the newest frame to FFmpeg at the cast's frame rate (a still picture twice a second), and ends when
    // FFmpeg does
    let timer = mainloop.loop_().add_timer({
        let mainloop = mainloop.clone();
        let state = state.clone();
        let writer_gone = writer_gone.clone();
        move |_| {
            let mut s = state.borrow_mut();
            if let Some(child) = s.child.as_mut() {
                if let Ok(Some(status)) = child.try_wait() {
                    let code = status.code().unwrap_or(1);
                    s.exit.get_or_insert(code);
                    mainloop.quit();
                    return;
                }
            }
            if writer_gone.load(Ordering::SeqCst) {
                s.exit.get_or_insert(1);
                mainloop.quit();
                return;
            }
            if !s.has_frame || !(s.fresh || s.last_sent.elapsed() >= KEEPALIVE) {
                return;
            }
            let Some(frames) = s.frames.clone() else { return };
            match frames.try_send(s.frame.clone()) {
                Ok(()) => {
                    s.fresh = false;
                    s.last_sent = Instant::now();
                }
                // FFmpeg is still busy with the last one: this one waits for the next tick
                Err(TrySendError::Full(_)) => {}
                Err(TrySendError::Disconnected(_)) => {
                    s.exit.get_or_insert(1);
                    mainloop.quit();
                }
            }
        }
    });
    let tick = Duration::from_nanos(1_000_000_000 / fps as u64);
    timer.update_timer(Some(tick), Some(tick));

    // Beam stops a cast by ending the helper
    let _term = mainloop.loop_().add_signal_local(pw::loop_::Signal::SIGTERM, {
        let mainloop = mainloop.clone();
        move || mainloop.quit()
    });
    let _int = mainloop.loop_().add_signal_local(pw::loop_::Signal::SIGINT, {
        let mainloop = mainloop.clone();
        move || mainloop.quit()
    });

    mainloop.run();

    let mut s = state.borrow_mut();
    // the end of its input lets FFmpeg finish on its own; it is ended after a second
    s.frames = None;
    let code = s.exit.unwrap_or(0);
    if let Some(mut child) = s.child.take() {
        let until = Instant::now() + Duration::from_secs(1);
        while child.try_wait().ok().flatten().is_none() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(20));
        }
        if child.try_wait().ok().flatten().is_none() {
            let _ = child.kill();
        }
        let _ = child.wait();
    }
    Ok(code)
}
