// 负责定位测试启动的窗口并恢复前台焦点。

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;

/** Foreground activation scoped to a window already verified as owned by this test. */
public final class TestWindowFocus {
    private TestWindowFocus() {}

    public static boolean activate(HWND target) {
        User32 windows = User32.INSTANCE;
        if (target.equals(windows.GetForegroundWindow())) return true;
        DWORD current = new DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        DWORD foreground =
                new DWORD(windows.GetWindowThreadProcessId(windows.GetForegroundWindow(), null));
        DWORD destination = new DWORD(windows.GetWindowThreadProcessId(target, null));
        boolean foregroundAttached = false;
        boolean destinationAttached = false;
        try {
            if (foreground.longValue() != 0 && !foreground.equals(current))
                foregroundAttached = windows.AttachThreadInput(current, foreground, true);
            if (!destination.equals(current) && !destination.equals(foreground))
                destinationAttached = windows.AttachThreadInput(current, destination, true);
            windows.ShowWindow(target, 9);
            windows.SetWindowPos(
                    target, new HWND(Pointer.createConstant(-1)), 0, 0, 0, 0, 0x0001 | 0x0002);
            windows.BringWindowToTop(target);
            windows.SetForegroundWindow(target);
            windows.SetFocus(target);
            return target.equals(windows.GetForegroundWindow());
        } finally {
            if (destinationAttached) windows.AttachThreadInput(current, destination, false);
            if (foregroundAttached) windows.AttachThreadInput(current, foreground, false);
        }
    }
}
