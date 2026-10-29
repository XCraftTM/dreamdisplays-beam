package dev.xcrafttm.ddbeam.client.capture;

import dev.xcrafttm.ddbeam.DdBeam;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The application windows on Windows (what a taskbar shows: visible, titled, not owned, no tool windows, not
 * cloaked), through user32 with Java's foreign function API. Each becomes a WINDOW source with its handle (to
 * capture it) and its process id as index (for the application's own audio).
 */
public final class WindowList {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ThreadLocal<List<CaptureSource>> COLLECTING = new ThreadLocal<>();
    private static MethodHandle isVisible, textLength, text, getWindow, exStyle, processId, cloaked;
    private static MemorySegment callback;

    private WindowList() {
    }

    public static List<CaptureSource> list() {
        if (!Sources.os().equals("windows")) return List.of();
        List<CaptureSource> out = new ArrayList<>();
        try {
            MethodHandle enumWindows = init();
            COLLECTING.set(out);
            enumWindows.invokeWithArguments(callback, 0L);
        } catch (Throwable e) {
            DdBeam.LOGGER.warn("Could not list the windows: {}", e.toString());
        } finally {
            COLLECTING.remove();
        }
        return out;
    }

    private static synchronized MethodHandle init() throws ReflectiveOperationException {
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
        if (callback == null) {
            isVisible = LINKER.downcallHandle(user32.find("IsWindowVisible").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            textLength = LINKER.downcallHandle(user32.find("GetWindowTextLengthW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            text = LINKER.downcallHandle(user32.find("GetWindowTextW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            getWindow = LINKER.downcallHandle(user32.find("GetWindow").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            exStyle = LINKER.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
            processId = LINKER.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            try {
                SymbolLookup dwm = SymbolLookup.libraryLookup("dwmapi", Arena.global());
                cloaked = LINKER.downcallHandle(dwm.find("DwmGetWindowAttribute").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            } catch (RuntimeException e) {
                cloaked = null;
            }
            callback = LINKER.upcallStub(MethodHandles.lookup().findStatic(WindowList.class, "visit",
                            MethodType.methodType(int.class, MemorySegment.class, long.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), Arena.global());
        }
        return LINKER.downcallHandle(user32.find("EnumWindows").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    }

    @SuppressWarnings("unused")
    private static int visit(MemorySegment hwnd, long param) {
        List<CaptureSource> out = COLLECTING.get();
        if (out == null) return 0;
        try (Arena arena = Arena.ofConfined()) {
            if ((int) isVisible.invokeExact(hwnd) == 0) return 1;
            if (((MemorySegment) getWindow.invokeExact(hwnd, 4 /* GW_OWNER */)).address() != 0) return 1;
            if (((long) exStyle.invokeExact(hwnd, -20 /* GWL_EXSTYLE */) & 0x80 /* WS_EX_TOOLWINDOW */) != 0) return 1;
            if (cloaked != null) {
                MemorySegment value = arena.allocate(JAVA_INT);
                if ((int) cloaked.invokeExact(hwnd, 14 /* DWMWA_CLOAKED */, value, 4) == 0 && value.get(JAVA_INT, 0) != 0) return 1;
            }
            int length = (int) textLength.invokeExact(hwnd);
            if (length <= 0) return 1;
            MemorySegment buf = arena.allocate(JAVA_CHAR, length + 1);
            int n = (int) text.invokeExact(hwnd, buf, length + 1);
            char[] chars = new char[n];
            for (int i = 0; i < n; i++) chars[i] = buf.getAtIndex(JAVA_CHAR, i);
            String title = new String(chars).trim();
            if (title.isEmpty() || title.equals("Program Manager")) return 1;
            MemorySegment pid = arena.allocate(JAVA_INT);
            int ignored = (int) processId.invokeExact(hwnd, pid);
            out.add(new CaptureSource(CaptureSource.Kind.WINDOW, pid.get(JAVA_INT, 0), title, 0, 0, 0, 0, title, hwnd.address()));
        } catch (Throwable e) {
            return 1;
        }
        return 1;
    }
}
