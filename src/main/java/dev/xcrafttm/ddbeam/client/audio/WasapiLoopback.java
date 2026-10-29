package dev.xcrafttm.ddbeam.client.audio;

import dev.xcrafttm.ddbeam.DdBeam;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * What Windows plays, captured with WASAPI loopback through Java's foreign function API (no native library).
 * <p>
 * First choice is process loopback (Windows 10 2004+) that leaves out this game's own process tree: the other
 * players hear the desktop, not the caster's game sounds or the casts it plays. Older Windows: the plain loopback
 * of the default output device, which includes the game.
 */
public final class WasapiLoopback implements AudioCapture {
    private static final Linker LINKER = Linker.nativeLinker();

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

    private static volatile CompletableFuture<MemorySegment> activation;
    private static MemorySegment completionHandler;

    private final Consumer<short[]> sink;
    private final Consumer<String> onError;
    private volatile boolean closed;
    /** "process" when the game is left out, "device" for the plain loopback. */
    private volatile String mode = "";

    /** Process loopback target: this game (left out), or an application's process (only it is heard). */
    private final long pid;
    private final boolean include;

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

    private static SymbolLookup lib(String name) {
        return SymbolLookup.libraryLookup(name, Arena.global());
    }

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("no " + name)), fd);
    }

    private static MemorySegment guid(Arena arena, String text) {
        String[] p = text.split("-");
        MemorySegment g = arena.allocate(16);
        g.set(JAVA_INT, 0, Integer.parseUnsignedInt(p[0], 16));
        g.set(JAVA_SHORT, 4, (short) Integer.parseInt(p[1], 16));
        g.set(JAVA_SHORT, 6, (short) Integer.parseInt(p[2], 16));
        String rest = p[3] + p[4];
        for (int i = 0; i < 8; i++) g.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(rest.substring(2 * i, 2 * i + 2), 16));
        return g;
    }

    /** Calls method {@code index} of a COM object's vtable. */
    private static Object com(MemorySegment obj, int index, FunctionDescriptor fd, Object... args) throws Throwable {
        MemorySegment vtbl = obj.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * 32);
        MethodHandle mh = LINKER.downcallHandle(vtbl.getAtIndex(ADDRESS, index), fd);
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        return mh.invokeWithArguments(all);
    }

    private static int hr(Object result) {
        return (Integer) result;
    }

    private static void release(MemorySegment obj) {
        if (obj == null || obj.address() == 0) return;
        try {
            com(obj, 2, FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (Throwable ignored) {
        }
    }

    private static MemorySegment out(Arena arena) {
        MemorySegment p = arena.allocate(ADDRESS);
        p.set(ADDRESS, 0, MemorySegment.NULL);
        return p;
    }

    // ---- the completion handler of ActivateAudioInterfaceAsync, a COM object made of upcalls ----

    @SuppressWarnings("unused")
    private static int qi(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        // IUnknown, IAgileObject and IActivateAudioInterfaceCompletionHandler are all this one object
        ppv.reinterpret(8).set(ADDRESS, 0, self);
        return 0;
    }

    @SuppressWarnings("unused")
    private static int addRef(MemorySegment self) {
        return 1;
    }

    @SuppressWarnings("unused")
    private static int activateCompleted(MemorySegment self, MemorySegment operation) {
        CompletableFuture<MemorySegment> pending = activation;
        if (pending != null) pending.complete(operation);
        return 0;
    }

    private static synchronized MemorySegment completionHandler() throws ReflectiveOperationException {
        if (completionHandler != null) return completionHandler;
        Arena global = Arena.global();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MemorySegment qi = LINKER.upcallStub(lookup.findStatic(WasapiLoopback.class, "qi",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), global);
        MemorySegment ref = LINKER.upcallStub(lookup.findStatic(WasapiLoopback.class, "addRef",
                MethodType.methodType(int.class, MemorySegment.class)), FunctionDescriptor.of(JAVA_INT, ADDRESS), global);
        MemorySegment done = LINKER.upcallStub(lookup.findStatic(WasapiLoopback.class, "activateCompleted",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), global);
        MemorySegment vtbl = global.allocate(ADDRESS, 4);
        vtbl.setAtIndex(ADDRESS, 0, qi);
        vtbl.setAtIndex(ADDRESS, 1, ref);
        vtbl.setAtIndex(ADDRESS, 2, ref);
        vtbl.setAtIndex(ADDRESS, 3, done);
        MemorySegment obj = global.allocate(ADDRESS);
        obj.set(ADDRESS, 0, vtbl);
        completionHandler = obj;
        return obj;
    }

    /** IAudioClient for one process tree (included or left out), or null on older Windows. */
    private static MemorySegment processLoopback(Arena arena, long pid, boolean include) {
        try {
            MethodHandle activate = fn(lib("Mmdevapi"), "ActivateAudioInterfaceAsync",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            // AUDIOCLIENT_ACTIVATION_PARAMS: PROCESS_LOOPBACK, the pid, INCLUDE (0) / EXCLUDE (1) _TARGET_PROCESS_TREE
            MemorySegment params = arena.allocate(12);
            params.set(JAVA_INT, 0, 1);
            params.set(JAVA_INT, 4, (int) pid);
            params.set(JAVA_INT, 8, include ? 0 : 1);
            // PROPVARIANT holding a VT_BLOB of those
            MemorySegment prop = arena.allocate(24);
            prop.set(JAVA_SHORT, 0, (short) 65);
            prop.set(JAVA_INT, 8, 12);
            prop.set(ADDRESS, 16, params);
            MemorySegment op = out(arena);
            CompletableFuture<MemorySegment> pending = new CompletableFuture<>();
            synchronized (WasapiLoopback.class) {
                activation = pending;
                int hr = (int) activate.invokeWithArguments(arena.allocateFrom("VAD\\Process_Loopback", StandardCharsets.UTF_16LE),
                        guid(arena, IID_AUDIO_CLIENT), prop, completionHandler(), op);
                if (hr < 0) throw new IllegalStateException("ActivateAudioInterfaceAsync 0x" + Integer.toHexString(hr));
                MemorySegment operation = pending.get(5, TimeUnit.SECONDS);
                activation = null;
                MemorySegment result = arena.allocate(JAVA_INT);
                MemorySegment unknown = out(arena);
                com(operation, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), result, unknown);
                release(op.get(ADDRESS, 0));
                if (result.get(JAVA_INT, 0) < 0) throw new IllegalStateException("activation 0x" + Integer.toHexString(result.get(JAVA_INT, 0)));
                return unknown.get(ADDRESS, 0);
            }
        } catch (Throwable e) {
            activation = null;
            DdBeam.LOGGER.info("Process loopback not available ({}); capturing the whole output device", e.toString());
            return null;
        }
    }

    /** IAudioClient of the default output device (plain loopback, includes the game). */
    private static MemorySegment deviceLoopback(Arena arena, MethodHandle coCreate) throws Throwable {
        MemorySegment enumerator = out(arena);
        int hr = (int) coCreate.invokeWithArguments(guid(arena, CLSID_MM_DEVICE_ENUMERATOR), MemorySegment.NULL, CLSCTX_ALL,
                guid(arena, IID_MM_DEVICE_ENUMERATOR), enumerator);
        if (hr < 0) throw new IllegalStateException("CoCreateInstance 0x" + Integer.toHexString(hr));
        MemorySegment device = out(arena);
        hr = hr(com(enumerator.get(ADDRESS, 0), 4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), 0, 0, device));
        release(enumerator.get(ADDRESS, 0));
        if (hr < 0) throw new IllegalStateException("GetDefaultAudioEndpoint 0x" + Integer.toHexString(hr));
        MemorySegment client = out(arena);
        hr = hr(com(device.get(ADDRESS, 0), 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                guid(arena, IID_AUDIO_CLIENT), CLSCTX_ALL, MemorySegment.NULL, client));
        release(device.get(ADDRESS, 0));
        if (hr < 0) throw new IllegalStateException("IMMDevice.Activate 0x" + Integer.toHexString(hr));
        return client.get(ADDRESS, 0);
    }

    /** 48 kHz, stereo, 16 bit PCM (WAVEFORMATEX). */
    private static MemorySegment format(Arena arena) {
        MemorySegment f = arena.allocate(20);
        f.set(JAVA_SHORT, 0, (short) 1);
        f.set(JAVA_SHORT, 2, (short) 2);
        f.set(JAVA_INT, 4, RATE);
        f.set(JAVA_INT, 8, RATE * 4);
        f.set(JAVA_SHORT, 12, (short) 4);
        f.set(JAVA_SHORT, 14, (short) 16);
        f.set(JAVA_SHORT, 16, (short) 0);
        return f;
    }

    private void run() {
        SymbolLookup ole32, kernel32;
        try {
            ole32 = lib("ole32");
            kernel32 = lib("kernel32");
        } catch (RuntimeException e) {
            onError.accept(e.toString());
            return;
        }
        MemorySegment client = null, capture = null, event = MemorySegment.NULL;
        MethodHandle closeHandle = null, coUninit = null;
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle coInit = fn(ole32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            coUninit = fn(ole32, "CoUninitialize", FunctionDescriptor.ofVoid());
            MethodHandle coCreate = fn(ole32, "CoCreateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle createEvent = fn(kernel32, "CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            MethodHandle wait = fn(kernel32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            closeHandle = fn(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            coInit.invokeWithArguments(MemorySegment.NULL, 0);

            FunctionDescriptor init = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS);
            MemorySegment fmt = format(arena);
            int channels = 2, bits = 16;
            boolean isFloat = false;
            client = processLoopback(arena, pid, include);
            int hr = -1;
            if (client != null) {
                hr = hr(com(client, AC_INITIALIZE, init, 0, STREAMFLAGS, 2_000_000L, 0L, fmt, MemorySegment.NULL));
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
                client = deviceLoopback(arena, coCreate);
                hr = hr(com(client, AC_INITIALIZE, init, 0, STREAMFLAGS, 2_000_000L, 0L, fmt, MemorySegment.NULL));
                if (hr < 0) {
                    // no format conversion here: take the device's own mix format
                    MemorySegment mixOut = out(arena);
                    com(client, AC_GET_MIX_FORMAT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), mixOut);
                    MemorySegment mix = mixOut.get(ADDRESS, 0).reinterpret(40);
                    channels = mix.get(JAVA_SHORT, 2);
                    bits = mix.get(JAVA_SHORT, 14);
                    int tag = mix.get(JAVA_SHORT, 0) & 0xFFFF;
                    isFloat = tag == 3 || (tag == 0xFFFE && bits == 32);
                    if (mix.get(JAVA_INT, 4) != RATE) throw new IllegalStateException("device rate " + mix.get(JAVA_INT, 4) + " Hz");
                    hr = hr(com(client, AC_INITIALIZE, init, 0, STREAMFLAGS & ~0x88000000, 2_000_000L, 0L, mix, MemorySegment.NULL));
                }
                if (hr < 0) throw new IllegalStateException("IAudioClient.Initialize 0x" + Integer.toHexString(hr));
                mode = "device";
            }
            event = (MemorySegment) createEvent.invokeWithArguments(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
            com(client, AC_SET_EVENT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), event);
            MemorySegment captureOut = out(arena);
            hr = hr(com(client, AC_GET_SERVICE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    guid(arena, IID_AUDIO_CAPTURE_CLIENT), captureOut));
            if (hr < 0) throw new IllegalStateException("GetService 0x" + Integer.toHexString(hr));
            capture = captureOut.get(ADDRESS, 0);
            hr = hr(com(client, AC_START, FunctionDescriptor.of(JAVA_INT, ADDRESS)));
            if (hr < 0) throw new IllegalStateException("Start 0x" + Integer.toHexString(hr));
            DdBeam.LOGGER.info("Capturing system audio ({} loopback)", mode);

            MemorySegment vtbl = capture.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * 8);
            MethodHandle nextPacket = LINKER.downcallHandle(vtbl.getAtIndex(ADDRESS, CC_NEXT_PACKET), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle getBuffer = LINKER.downcallHandle(vtbl.getAtIndex(ADDRESS, CC_GET_BUFFER),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle releaseBuffer = LINKER.downcallHandle(vtbl.getAtIndex(ADDRESS, CC_RELEASE_BUFFER), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MemorySegment packet = arena.allocate(JAVA_INT), data = out(arena), frames = arena.allocate(JAVA_INT), flags = arena.allocate(JAVA_INT);
            int frameBytes = channels * bits / 8;
            while (!closed) {
                wait.invokeWithArguments(event, 20);
                while (!closed) {
                    if ((int) nextPacket.invokeWithArguments(capture, packet) < 0) throw new IllegalStateException("capture lost");
                    if (packet.get(JAVA_INT, 0) == 0) break;
                    if ((int) getBuffer.invokeWithArguments(capture, data, frames, flags, MemorySegment.NULL, MemorySegment.NULL) < 0) break;
                    int n = frames.get(JAVA_INT, 0);
                    short[] mono = new short[n];
                    if ((flags.get(JAVA_INT, 0) & BUFFER_FLAG_SILENT) == 0 && n > 0) {
                        MemorySegment pcm = data.get(ADDRESS, 0).reinterpret((long) n * frameBytes);
                        for (int i = 0; i < n; i++) {
                            long at = (long) i * frameBytes;
                            float l, r;
                            if (isFloat) {
                                l = pcm.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, at) * 32767f;
                                r = channels > 1 ? pcm.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, at + 4) * 32767f : l;
                            } else {
                                l = pcm.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, at);
                                r = channels > 1 ? pcm.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, at + 2) : l;
                            }
                            mono[i] = (short) Math.clamp((int) ((l + r) * 0.5f), -32768, 32767);
                        }
                    }
                    releaseBuffer.invokeWithArguments(capture, n);
                    if (n > 0) sink.accept(mono);
                }
            }
            com(client, AC_STOP, FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (Throwable e) {
            if (!closed) {
                DdBeam.LOGGER.warn("System audio capture failed: {}", e.toString());
                onError.accept(e.toString());
            }
        } finally {
            release(capture);
            release(client);
            try {
                if (closeHandle != null && event.address() != 0) closeHandle.invokeWithArguments(event);
                if (coUninit != null) coUninit.invokeWithArguments(List.of());
            } catch (Throwable ignored) {
            }
        }
    }
}
