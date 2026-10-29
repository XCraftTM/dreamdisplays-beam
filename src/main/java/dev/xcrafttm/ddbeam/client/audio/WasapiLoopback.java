package dev.xcrafttm.ddbeam.client.audio;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import dev.xcrafttm.ddbeam.DdBeam;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What Windows plays, captured with WASAPI loopback through JNA (which every Minecraft version ships).
 * <p>
 * First choice is process loopback (Windows 10 2004+) that leaves out this game's own process tree (or, for a
 * window cast, takes only that application's tree): the other players hear the desktop, not the caster's game
 * sounds or the casts it plays. Older Windows: the plain loopback of the default output device, which includes the
 * game.
 */
public final class WasapiLoopback implements AudioCapture {
    private static final String CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E";
    private static final String IID_MM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6";
    private static final String IID_AUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2";
    private static final String IID_AUDIO_CAPTURE_CLIENT = "C8ADBD64-E71E-48A0-A4DE-185C395CD317";

    private static final int CLSCTX_ALL = 0x17;
    private static final int STREAMFLAGS = 0x00020000 /* LOOPBACK */ | 0x00040000 /* EVENTCALLBACK */
            | 0x80000000 /* AUTOCONVERTPCM */ | 0x08000000 /* SRC_DEFAULT_QUALITY */;
    private static final int BUFFER_FLAG_SILENT = 2;

    // IUnknown 0-2; IAudioClient
    private static final int AC_INITIALIZE = 3, AC_GET_MIX_FORMAT = 8, AC_START = 10, AC_STOP = 11, AC_SET_EVENT = 13, AC_GET_SERVICE = 14;
    // IAudioCaptureClient
    private static final int CC_GET_BUFFER = 3, CC_RELEASE_BUFFER = 4, CC_NEXT_PACKET = 5;

    private static volatile CompletableFuture<Pointer> activation;

    // the completion handler of ActivateAudioInterfaceAsync: a COM object made of callbacks, kept alive here
    private interface QueryInterface extends Callback {
        int invoke(Pointer self, Pointer riid, Pointer ppv);
    }

    private interface AddRef extends Callback {
        int invoke(Pointer self);
    }

    private interface ActivateCompleted extends Callback {
        int invoke(Pointer self, Pointer operation);
    }

    private static final QueryInterface QI = (self, riid, ppv) -> {
        // IUnknown, IAgileObject and IActivateAudioInterfaceCompletionHandler are all this one object
        ppv.setPointer(0, self);
        return 0;
    };
    private static final AddRef REF = self -> 1;
    private static final ActivateCompleted DONE = (self, operation) -> {
        CompletableFuture<Pointer> pending = activation;
        if (pending != null) pending.complete(operation);
        return 0;
    };
    private static Memory handlerVtbl, handler;

    private final Consumer<short[]> sink;
    private final Consumer<String> onError;
    /** Process loopback target: this game (left out), or an application's process (only it is heard). */
    private final long pid;
    private final boolean include;
    private volatile boolean closed;
    /** "process" when the game is left out, "device" for the plain loopback. */
    private volatile String mode = "";

    private WasapiLoopback(Consumer<short[]> sink, Consumer<String> onError, long pid, boolean include) {
        this.sink = sink;
        this.onError = onError;
        this.pid = pid;
        this.include = include;
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Everything Windows plays except this game. */
    public static WasapiLoopback start(Consumer<short[]> sink, Consumer<String> onError) {
        return start(sink, onError, ProcessHandle.current().pid(), false);
    }

    /** Only what this process (and its children) plays: the audio of a cast window. */
    public static WasapiLoopback startProcess(long pid, Consumer<short[]> sink, Consumer<String> onError) {
        return start(sink, onError, pid, true);
    }

    private static WasapiLoopback start(Consumer<short[]> sink, Consumer<String> onError, long pid, boolean include) {
        WasapiLoopback capture = new WasapiLoopback(sink, onError, pid, include);
        Thread thread = new Thread(capture::run, "ddbeam-audio-wasapi");
        thread.setDaemon(true);
        thread.start();
        return capture;
    }

    public String mode() {
        return mode;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ---- native helpers ----

    private static Function fn(String lib, String name) {
        return NativeLibrary.getInstance(lib).getFunction(name);
    }

    private static Memory guid(String text) {
        String[] p = text.split("-");
        Memory g = new Memory(16);
        g.setInt(0, Integer.parseUnsignedInt(p[0], 16));
        g.setShort(4, (short) Integer.parseInt(p[1], 16));
        g.setShort(6, (short) Integer.parseInt(p[2], 16));
        String rest = p[3] + p[4];
        for (int i = 0; i < 8; i++) g.setByte(8 + i, (byte) Integer.parseInt(rest.substring(2 * i, 2 * i + 2), 16));
        return g;
    }

    /** Method {@code index} of a COM object's vtable. */
    private static Function method(Pointer obj, int index) {
        Pointer vtbl = obj.getPointer(0);
        return Function.getFunction(vtbl.getPointer((long) index * Native.POINTER_SIZE));
    }

    private static int com(Pointer obj, int index, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        return method(obj, index).invokeInt(all);
    }

    private static void release(Pointer obj) {
        if (obj == null) return;
        try {
            com(obj, 2);
        } catch (RuntimeException ignored) {
        }
    }

    private static Memory out() {
        Memory p = new Memory(Native.POINTER_SIZE);
        p.setPointer(0, null);
        return p;
    }

    private static synchronized Pointer completionHandler() {
        if (handler != null) return handler;
        handlerVtbl = new Memory(4L * Native.POINTER_SIZE);
        handlerVtbl.setPointer(0, CallbackReference.getFunctionPointer(QI));
        handlerVtbl.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(REF));
        handlerVtbl.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(REF));
        handlerVtbl.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(DONE));
        handler = new Memory(Native.POINTER_SIZE);
        handler.setPointer(0, handlerVtbl);
        return handler;
    }

    /** IAudioClient for one process tree (included or left out), or null on older Windows. */
    private static Pointer processLoopback(long pid, boolean include) {
        try {
            Function activate = fn("Mmdevapi", "ActivateAudioInterfaceAsync");
            // AUDIOCLIENT_ACTIVATION_PARAMS: PROCESS_LOOPBACK, the pid, INCLUDE (0) / EXCLUDE (1) _TARGET_PROCESS_TREE
            Memory params = new Memory(12);
            params.setInt(0, 1);
            params.setInt(4, (int) pid);
            params.setInt(8, include ? 0 : 1);
            // PROPVARIANT holding a VT_BLOB of those
            Memory prop = new Memory(24);
            prop.clear();
            prop.setShort(0, (short) 65);
            prop.setInt(8, 12);
            prop.setPointer(16, params);
            Memory path = new Memory(("VAD\\Process_Loopback".length() + 1) * 2L);
            path.setWideString(0, "VAD\\Process_Loopback");
            Memory op = out();
            CompletableFuture<Pointer> pending = new CompletableFuture<>();
            synchronized (WasapiLoopback.class) {
                activation = pending;
                int hr = activate.invokeInt(new Object[]{path, guid(IID_AUDIO_CLIENT), prop, completionHandler(), op});
                if (hr < 0) throw new IllegalStateException("ActivateAudioInterfaceAsync 0x" + Integer.toHexString(hr));
                Pointer operation = pending.get(5, TimeUnit.SECONDS);
                activation = null;
                Memory result = new Memory(4);
                Memory unknown = out();
                com(operation, 3, result, unknown);
                release(op.getPointer(0));
                if (result.getInt(0) < 0) throw new IllegalStateException("activation 0x" + Integer.toHexString(result.getInt(0)));
                return unknown.getPointer(0);
            }
        } catch (Throwable e) {
            activation = null;
            DdBeam.LOGGER.info("Process loopback not available ({}); capturing the whole output device", e.toString());
            return null;
        }
    }

    /** IAudioClient of the default output device (plain loopback, includes the game). */
    private static Pointer deviceLoopback() {
        Memory enumerator = out();
        int hr = fn("ole32", "CoCreateInstance").invokeInt(new Object[]{guid(CLSID_MM_DEVICE_ENUMERATOR), null, CLSCTX_ALL,
                guid(IID_MM_DEVICE_ENUMERATOR), enumerator});
        if (hr < 0) throw new IllegalStateException("CoCreateInstance 0x" + Integer.toHexString(hr));
        Memory device = out();
        hr = com(enumerator.getPointer(0), 4, 0, 0, device);
        release(enumerator.getPointer(0));
        if (hr < 0) throw new IllegalStateException("GetDefaultAudioEndpoint 0x" + Integer.toHexString(hr));
        Memory client = out();
        hr = com(device.getPointer(0), 3, guid(IID_AUDIO_CLIENT), CLSCTX_ALL, null, client);
        release(device.getPointer(0));
        if (hr < 0) throw new IllegalStateException("IMMDevice.Activate 0x" + Integer.toHexString(hr));
        return client.getPointer(0);
    }

    /** 48 kHz, stereo, 16 bit PCM (WAVEFORMATEX). */
    private static Memory format() {
        Memory f = new Memory(20);
        f.clear();
        f.setShort(0, (short) 1);
        f.setShort(2, (short) 2);
        f.setInt(4, RATE);
        f.setInt(8, RATE * 4);
        f.setShort(12, (short) 4);
        f.setShort(14, (short) 16);
        return f;
    }

    private void run() {
        Pointer client = null, capture = null, event = null;
        boolean comStarted = false;
        try {
            fn("ole32", "CoInitializeEx").invokeInt(new Object[]{null, 0});
            comStarted = true;
            Memory fmt = format();
            int channels = 2, bits = 16;
            boolean isFloat = false;
            client = processLoopback(pid, include);
            int hr = -1;
            if (client != null) {
                hr = com(client, AC_INITIALIZE, 0, STREAMFLAGS, 2_000_000L, 0L, fmt, null);
                if (hr < 0) {
                    DdBeam.LOGGER.info("Process loopback failed to start (0x{}); capturing the whole output device", Integer.toHexString(hr));
                    release(client);
                    client = null;
                } else {
                    mode = "process";
                }
            }
            if (client == null && include) throw new IllegalStateException("application audio needs Windows 10 2004 or newer");
            if (client == null) {
                client = deviceLoopback();
                hr = com(client, AC_INITIALIZE, 0, STREAMFLAGS, 2_000_000L, 0L, fmt, null);
                if (hr < 0) {
                    // no format conversion here: take the device's own mix format
                    Memory mixOut = out();
                    com(client, AC_GET_MIX_FORMAT, mixOut);
                    Pointer mix = mixOut.getPointer(0);
                    channels = mix.getShort(2);
                    bits = mix.getShort(14);
                    int tag = mix.getShort(0) & 0xFFFF;
                    isFloat = tag == 3 || (tag == 0xFFFE && bits == 32);
                    if (mix.getInt(4) != RATE) throw new IllegalStateException("device rate " + mix.getInt(4) + " Hz");
                    hr = com(client, AC_INITIALIZE, 0, STREAMFLAGS & ~0x88000000, 2_000_000L, 0L, mix, null);
                }
                if (hr < 0) throw new IllegalStateException("IAudioClient.Initialize 0x" + Integer.toHexString(hr));
                mode = "device";
            }
            event = fn("kernel32", "CreateEventW").invokePointer(new Object[]{null, 0, 0, null});
            com(client, AC_SET_EVENT, event);
            Memory captureOut = out();
            hr = com(client, AC_GET_SERVICE, guid(IID_AUDIO_CAPTURE_CLIENT), captureOut);
            if (hr < 0) throw new IllegalStateException("GetService 0x" + Integer.toHexString(hr));
            capture = captureOut.getPointer(0);
            hr = com(client, AC_START);
            if (hr < 0) throw new IllegalStateException("Start 0x" + Integer.toHexString(hr));
            DdBeam.LOGGER.info("Capturing system audio ({} loopback)", mode);

            Function wait = fn("kernel32", "WaitForSingleObject");
            Function nextPacket = method(capture, CC_NEXT_PACKET), getBuffer = method(capture, CC_GET_BUFFER),
                    releaseBuffer = method(capture, CC_RELEASE_BUFFER);
            Memory packet = new Memory(4), data = out(), frames = new Memory(4), flags = new Memory(4);
            int frameBytes = channels * bits / 8;
            while (!closed) {
                wait.invokeInt(new Object[]{event, 20});
                while (!closed) {
                    if (nextPacket.invokeInt(new Object[]{capture, packet}) < 0) throw new IllegalStateException("capture lost");
                    if (packet.getInt(0) == 0) break;
                    if (getBuffer.invokeInt(new Object[]{capture, data, frames, flags, null, null}) < 0) break;
                    int n = frames.getInt(0);
                    short[] mono = new short[n];
                    if ((flags.getInt(0) & BUFFER_FLAG_SILENT) == 0 && n > 0) {
                        Pointer pcm = data.getPointer(0);
                        for (int i = 0; i < n; i++) {
                            long at = (long) i * frameBytes;
                            float l, r;
                            if (isFloat) {
                                l = pcm.getFloat(at) * 32767f;
                                r = channels > 1 ? pcm.getFloat(at + 4) * 32767f : l;
                            } else {
                                l = pcm.getShort(at);
                                r = channels > 1 ? pcm.getShort(at + 2) : l;
                            }
                            mono[i] = (short) Math.max(-32768, Math.min(32767, (int) ((l + r) * 0.5f)));
                        }
                    }
                    releaseBuffer.invokeInt(new Object[]{capture, n});
                    if (n > 0) sink.accept(mono);
                }
            }
            com(client, AC_STOP);
        } catch (Throwable e) {
            if (!closed) {
                DdBeam.LOGGER.warn("System audio capture failed: {}", e.toString());
                onError.accept(e.toString());
            }
        } finally {
            release(capture);
            release(client);
            try {
                if (event != null) fn("kernel32", "CloseHandle").invokeInt(new Object[]{event});
                if (comStarted) fn("ole32", "CoUninitialize").invoke(Void.class, new Object[0]);
            } catch (Throwable ignored) {
            }
        }
    }
}
