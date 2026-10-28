// 负责测试进程内 Windows 系统文件窗口的定位、路径输入与取消。

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeDialogActions {
    interface DialogApi extends com.sun.jna.win32.StdCallLibrary {
        HWND GetDlgItem(HWND dialog, int id);
    }

    private static final DialogApi DIALOG_API = com.sun.jna.Native.load("user32", DialogApi.class);

    private NativeDialogActions() {}

    static HWND waitForDialog() throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            AtomicReference<HWND> found = new AtomicReference<>();
            User32.INSTANCE.EnumWindows(
                    (window, data) -> {
                        IntByReference process = new IntByReference();
                        User32.INSTANCE.GetWindowThreadProcessId(window, process);
                        char[] type = new char[128];
                        User32.INSTANCE.GetClassName(window, type, type.length);
                        if (Integer.toUnsignedLong(process.getValue())
                                        == ProcessHandle.current().pid()
                                && User32.INSTANCE.IsWindowVisible(window)
                                && com.sun.jna.Native.toString(type).equals("#32770"))
                            found.set(window);
                        return true;
                    },
                    null);
            if (found.get() != null) return found.get();
            Thread.sleep(100);
        }
        throw new IllegalStateException("No native file dialog belonging to this test process");
    }

    static void select(List<File> files) throws Exception {
        HWND dialog = waitForDialog();
        User32.INSTANCE.SetForegroundWindow(dialog);
        Robot robot = new Robot();
        robot.setAutoDelay(70);
        robot.delay(350);
        String paths =
                files.size() == 1
                        ? files.get(0).getAbsolutePath()
                        : files.stream()
                                .map(file -> "\"" + file.getAbsolutePath() + "\"")
                                .collect(java.util.stream.Collectors.joining(" "));
        boolean directory = files.size() == 1 && files.get(0).isDirectory();
        if (directory) {
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_L);
            robot.keyRelease(KeyEvent.VK_L);
            robot.keyRelease(KeyEvent.VK_CONTROL);
        } else {
            robot.keyPress(KeyEvent.VK_ALT);
            robot.keyPress(KeyEvent.VK_N);
            robot.keyRelease(KeyEvent.VK_N);
            robot.keyRelease(KeyEvent.VK_ALT);
        }
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(paths), null);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        if (directory) {
            robot.delay(600);
            HWND select = DIALOG_API.GetDlgItem(dialog, 1);
            if (select == null)
                throw new IllegalStateException("Native directory confirmation missing");
            User32.INSTANCE.SendMessage(
                    select,
                    0x00F5,
                    new com.sun.jna.platform.win32.WinDef.WPARAM(0),
                    new com.sun.jna.platform.win32.WinDef.LPARAM(0));
        }
        for (int attempt = 0; attempt < 150 && User32.INSTANCE.IsWindowVisible(dialog); attempt++)
            Thread.sleep(100);
        if (User32.INSTANCE.IsWindowVisible(dialog))
            throw new IllegalStateException("Native picker did not accept fixture path");
    }

    static void cancel() throws Exception {
        HWND dialog = waitForDialog();
        User32.INSTANCE.SetForegroundWindow(dialog);
        Robot robot = new Robot();
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        for (int attempt = 0; attempt < 50 && User32.INSTANCE.IsWindowVisible(dialog); attempt++)
            Thread.sleep(100);
        if (User32.INSTANCE.IsWindowVisible(dialog))
            throw new IllegalStateException("Native picker did not cancel");
    }
}
