package dev.xcrafttm.ddbeam.client.capture;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import dev.xcrafttm.ddbeam.DdBeam;

import java.util.ArrayList;
import java.util.List;

/**
 * The application windows on Windows (what a taskbar shows: visible, titled, not owned, no tool windows, not
 * cloaked), through user32 with JNA. Each becomes a WINDOW source with its handle (to capture it) and its process id
 * as index (for the application's own audio).
 */
public final class WindowList {
    private interface EnumProc extends Callback {
        boolean invoke(Pointer hwnd, Pointer param);
    }

    private WindowList() {
    }

    public static synchronized List<CaptureSource> list() {
        if (!Sources.os().equals("windows")) return List.of();
        List<CaptureSource> out = new ArrayList<>();
        try {
            NativeLibrary user32 = NativeLibrary.getInstance("user32");
            Function isVisible = user32.getFunction("IsWindowVisible"), textLength = user32.getFunction("GetWindowTextLengthW"),
                    text = user32.getFunction("GetWindowTextW"), getWindow = user32.getFunction("GetWindow"),
                    exStyle = user32.getFunction("GetWindowLongPtrW"), processId = user32.getFunction("GetWindowThreadProcessId");
            Function cloaked;
            try {
                cloaked = NativeLibrary.getInstance("dwmapi").getFunction("DwmGetWindowAttribute");
            } catch (UnsatisfiedLinkError e) {
                cloaked = null;
            }
            Function dwm = cloaked;
            EnumProc visit = (hwnd, param) -> {
                try {
                    if (isVisible.invokeInt(new Object[]{hwnd}) == 0) return true;
                    if (getWindow.invokePointer(new Object[]{hwnd, 4 /* GW_OWNER */}) != null) return true;
                    if ((exStyle.invokeLong(new Object[]{hwnd, -20 /* GWL_EXSTYLE */}) & 0x80 /* WS_EX_TOOLWINDOW */) != 0) return true;
                    if (dwm != null) {
                        Memory value = new Memory(4);
                        value.setInt(0, 0);
                        if (dwm.invokeInt(new Object[]{hwnd, 14 /* DWMWA_CLOAKED */, value, 4}) == 0 && value.getInt(0) != 0) return true;
                    }
                    int length = textLength.invokeInt(new Object[]{hwnd});
                    if (length <= 0) return true;
                    Memory buf = new Memory((length + 1) * 2L);
                    buf.clear();
                    text.invokeInt(new Object[]{hwnd, buf, length + 1});
                    String title = buf.getWideString(0).trim();
                    if (title.isEmpty() || title.equals("Program Manager")) return true;
                    Memory pid = new Memory(4);
                    processId.invokeInt(new Object[]{hwnd, pid});
                    out.add(new CaptureSource(CaptureSource.Kind.WINDOW, pid.getInt(0), title, 0, 0, 0, 0, title,
                            Pointer.nativeValue(hwnd)));
                } catch (RuntimeException ignored) {
                }
                return true;
            };
            user32.getFunction("EnumWindows").invokeInt(new Object[]{visit, null});
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            DdBeam.LOGGER.warn("Could not list the windows: {}", e.toString());
        }
        return out;
    }
}
