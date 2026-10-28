// Invokes every visible Java installer and validates each downloaded executable in temporary storage.
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class JavaActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        for (int major : List.of(8, 17, 21, 25)) {
            UiSmoke.click(robot, "设置"); UiSmoke.clickRail(robot, "Java 管理");
            UiSmoke.scrollUntilVisible(robot, "Java " + major); UiSmoke.click(robot, "Java " + major);
            Path runtimes = root.resolve("profile/runtimes");
            UiSmoke.awaitFileState("installed Java " + major, 240000, () -> findRuntime(runtimes, major) != null);
            Path executable = findRuntime(runtimes, major);
            Process process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
            try {
                UiFunctionalChecks.require(process.waitFor(10, TimeUnit.SECONDS), "Downloaded runtime did not answer -version");
                String version = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String expected = major == 8 ? "1.8." : major + ".";
                UiFunctionalChecks.require(process.exitValue() == 0 && version.contains("version \"" + expected), "Downloaded Java version mismatch");
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            UiFunctionalChecks.record("java.install." + major, "button initiated installation; vendor package executable returned the correct version");
        }
        UiSmoke.click(robot, "设置"); UiSmoke.clickRail(robot, "Java 管理"); UiSmoke.click(robot, "检测本机 Java");
        UiSmoke.capture(robot, window, new File(output.getParentFile(), "MoliFox-actions-java.png"), true);
    }

    private static Path findRuntime(Path root, int major) {
        if (!Files.isDirectory(root)) return null;
        try (var dirs = Files.list(root)) {
            for (Path folder : dirs.filter(path -> path.getFileName().toString().matches("(?:zulu|temurin)-" + major + "-.*")).toList()) {
                try (var files = Files.walk(folder)) {
                    Path executable = files.filter(path -> path.getFileName().toString().equals("java.exe")).findFirst().orElse(null);
                    if (executable != null) return executable;
                }
            }
        } catch (Exception pending) { return null; }
        return null;
    }
}
