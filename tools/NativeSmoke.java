// 负责验证单文件启动、离线缓存和运行目录归属。

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;

import java.awt.Rectangle;
import java.awt.Robot;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

/** Windows package check; only inspects the newly started, isolated process. */
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize();
        if (!root.equals(Path.of(args[0]).toAbsolutePath().normalize()))
            throw new IllegalArgumentException(
                    "Use the nativeSmoke Gradle task's isolated directory");
        Files.createDirectories(root);
        ProcessBuilder builder = new ProcessBuilder(root.resolve("MoliFox.exe").toString());
        builder.directory(root.toFile());
        builder.environment()
                .put("MOLIFOX_DIAGNOSTIC_LOG", root.resolve("native-startup-error.log").toString());
        builder.environment().remove("MOLIFOX_DATA_DIR");
        if (args.length > 2 && args[2].equals("without-java")) {
            builder.environment().remove("JAVA_HOME");
            builder.environment().put("PATH", System.getenv("SystemRoot") + "\\System32");
        }
        if (args.length > 2 && args[2].equals("offline")) {
            builder.environment()
                    .put(
                            "JAVA_TOOL_OPTIONS",
                            "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -DsocksProxyHost=127.0.0.1 -DsocksProxyPort=9");
        }
        builder.redirectErrorStream(true)
                .redirectOutput(root.resolve("native-console.log").toFile());
        Process process = builder.start();
        System.out.println("NATIVE_PROCESS " + process.pid());
        Set<Long> processIds = new HashSet<>();
        processIds.add(process.pid());
        Set<String> ownTitles = new HashSet<>();
        try {
            AtomicReference<HWND> target = new AtomicReference<>();
            AtomicReference<String> startupError = new AtomicReference<>();
            for (int attempt = 0; attempt < 240 && target.get() == null; attempt++) {
                if (!process.isAlive())
                    throw new IllegalStateException(
                            "Packaged app exited with " + process.exitValue());
                process.descendants().forEach(child -> processIds.add(child.pid()));
                User32.INSTANCE.EnumWindows(
                        (handle, ignored) -> {
                            IntByReference owner = new IntByReference();
                            User32.INSTANCE.GetWindowThreadProcessId(handle, owner);
                            if (processIds.contains((long) owner.getValue())
                                    && User32.INSTANCE.IsWindowVisible(handle)) {
                                char[] title = new char[256];
                                User32.INSTANCE.GetWindowText(handle, title, title.length);
                                ownTitles.add(Native.toString(title));
                                if (Native.toString(title).equals("MoliFox")) target.set(handle);
                                else if (Native.toString(title).contains("启动失败")) {
                                    StringBuilder message = new StringBuilder();
                                    User32.INSTANCE.EnumChildWindows(
                                            handle,
                                            (child, unused) -> {
                                                char[] text = new char[4096];
                                                User32.INSTANCE.GetWindowText(
                                                        child, text, text.length);
                                                String value = Native.toString(text);
                                                if (!value.isBlank())
                                                    message.append(value).append("\n");
                                                return true;
                                            },
                                            null);
                                    startupError.set(message.toString());
                                }
                            }
                            return true;
                        },
                        null);
                if (startupError.get() != null)
                    throw new IllegalStateException("NATIVE_STARTUP_ERROR: " + startupError.get());
                Thread.sleep(250);
            }
            if (target.get() == null)
                throw new IllegalStateException(
                        "Packaged MoliFox window did not open; owned window titles: "
                                + ownTitles
                                + "; processes: "
                                + processIds);
            TestWindowFocus.activate(target.get());
            Thread.sleep(2000);
            if (!target.get().equals(User32.INSTANCE.GetForegroundWindow()))
                throw new IllegalStateException(
                        "Test window is not in front; refusing to capture another application");
            RECT rect = new RECT();
            User32.INSTANCE.GetWindowRect(target.get(), rect);
            Rectangle bounds =
                    new Rectangle(
                            rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
            if (bounds.width < 1000 || bounds.height < 650)
                throw new IllegalStateException("Unexpected native window size");
            Robot robot = new Robot();
            robot.mouseMove(bounds.x + bounds.width - 25, bounds.y + bounds.height - 20);
            robot.delay(150);
            File screenshot = new File(args[1]);
            screenshot.getParentFile().mkdirs();
            ImageIO.write(robot.createScreenCapture(bounds), "png", screenshot);
            if (!Files.isRegularFile(root.resolve("MoliFox/instance.lock")))
                throw new IllegalStateException("Portable data missing beside the test executable");
            var config =
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(root.resolve("MoliFox/config/launcher.json")))
                            .getAsJsonObject();
            if (!"auto"
                            .equals(
                                    config.getAsJsonObject("settings")
                                            .get("downloadSource")
                                            .getAsString())
                    || !"auto"
                            .equals(
                                    config.getAsJsonObject("settings")
                                            .get("versionListSource")
                                            .getAsString()))
                throw new IllegalStateException("New profiles do not default to automatic sources");
            try (var payloads = Files.list(root.resolve("MoliFox/dependencies/launcher"))) {
                if (payloads.noneMatch(path -> path.getFileName().toString().endsWith(".jar")))
                    throw new IllegalStateException(
                            "Launcher payload was not placed under dependencies");
            }
            if (Files.exists(root.resolve("MoliFox/cache/skiko")))
                throw new IllegalStateException(
                        "The desktop entry point recreated the obsolete native cache");
            try (var natives = Files.list(root.resolve("MoliFox/dependencies/native"))) {
                if (natives.noneMatch(Files::isRegularFile))
                    throw new IllegalStateException(
                            "Native UI libraries are missing from dependencies/native");
            }
            if (args.length > 2
                    && args[2].equals("offline")
                    && Files.isDirectory(root.resolve("MoliFox/runtimes"))) {
                try (var runtimes = Files.list(root.resolve("MoliFox/runtimes"))) {
                    if (runtimes.findAny().isPresent())
                        throw new IllegalStateException(
                                "Offline system-Java test unexpectedly prepared a runtime");
                }
            }
            System.out.println("NATIVE_PACKAGE_OK " + bounds.width + "x" + bounds.height);
            User32.INSTANCE.PostMessage(target.get(), 0x0010, null, null);
            if (!process.waitFor(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Packaged application did not close");
            if (process.exitValue() != 0)
                throw new IllegalStateException("Packaged application exited with an error");
            try (var sessions = Files.list(root.resolve("MoliFox/cache/tmp"))) {
                if (sessions.anyMatch(path -> path.getFileName().toString().startsWith("session-")))
                    throw new IllegalStateException(
                            "Native launcher left its session temporary files behind");
            }
            System.out.println(
                    "NATIVE_LAYOUT_OK default automatic sources, dependency payload and session cleanup");
        } finally {
            for (long child : processIds)
                if (child != process.pid())
                    ProcessHandle.of(child)
                            .filter(ProcessHandle::isAlive)
                            .ifPresent(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
