// 负责协调实际操作验收、测试夹具和逐项结果记录。

// Coordinates actual UI actions and records evidence without retaining temporary account data.
import com.google.gson.*;

import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

final class UiFunctionalChecks {
    private static final java.util.List<Map<String, String>> results = new ArrayList<>();
    private static Path root;
    private static String currentMode;

    static void seed(Path root, Path external) throws Exception {
        if (System.getProperty("molifox.actions.seedPublicCache", "false").equals("true")) {
            Path publicCache = Path.of("D:/MC/.minecraft").toAbsolutePath().normalize();
            for (String folder : java.util.List.of("assets", "libraries")) {
                Path source = publicCache.resolve(folder);
                Path destination = root.resolve(".minecraft/" + folder);
                if (!Files.isDirectory(source)) continue;
                try (var paths = Files.walk(source)) {
                    for (Path entry : paths.toList()) {
                        if (Files.isSymbolicLink(entry)) continue;
                        Path target = destination.resolve(source.relativize(entry));
                        if (Files.isDirectory(entry)) Files.createDirectories(target);
                        else if (Files.isRegularFile(entry)) {
                            Files.createDirectories(target.getParent());
                            Files.copy(entry, target);
                        }
                    }
                }
            }
        }
        Path configuration = root.resolve("profile/config/launcher.json");
        JsonObject data = JsonParser.parseString(Files.readString(configuration)).getAsJsonObject();
        data.getAsJsonObject("settings").addProperty("downloadSource", "auto");
        data.getAsJsonObject("settings").addProperty("versionListSource", "auto");
        JsonObject main = data.getAsJsonArray("instances").get(0).getAsJsonObject();
        main.addProperty("loader", "Fabric");
        main.addProperty("loaderVersion", "0.16.14");
        Files.writeString(configuration, new Gson().toJson(data));
        Path versions = root.resolve(".minecraft/versions");
        Files.createDirectories(versions.resolve("1.20.1"));
        for (String suffix : java.util.List.of("json", "jar"))
            Files.copy(
                    external.resolve("versions/1.20.1/1.20.1." + suffix),
                    versions.resolve("1.20.1/1.20.1." + suffix));
        for (String id : java.util.List.of("smoke-saves", "smoke-saves-target")) {
            Path version = versions.resolve(id);
            Files.createDirectories(version);
            Files.writeString(
                    version.resolve(id + ".json"),
                    "{\"id\":\"" + id + "\",\"inheritsFrom\":\"1.20.1\"}");
        }
    }

    static void run(String mode, Robot robot, Window window, File output, Path directory)
            throws Exception {
        root = directory;
        currentMode = mode;
        save(mode, "running");
        try {
            if (mode.equals("actionsInstances"))
                InstanceActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsSettings"))
                SettingsActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsDownloads"))
                DownloadActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsJava")) JavaActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsAccounts"))
                AccountActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsPacks")) PackActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsWindow"))
                WindowActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsLogs")) LogActionChecks.run(robot, window, output, root);
            else if (mode.equals("actionsMicrosoft"))
                MicrosoftActionChecks.run(robot, window, output, root);
            else throw new IllegalArgumentException("Unknown action check: " + mode);
            save(mode, "passed");
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            results.add(
                    Map.of(
                            "action",
                            "failure",
                            "evidence",
                            cause.getClass().getSimpleName()
                                    + ": "
                                    + String.valueOf(cause.getMessage())));
            save(mode, "failed");
            UiSmoke.capture(
                    robot,
                    window,
                    new File(output.getParentFile(), "MoliFox-actions-failure.png"),
                    true);
            EventQueue.invokeAndWait(
                    () -> {
                        for (Window current : Window.getWindows())
                            if (current.isShowing())
                                UiSmoke.printTree(current.getAccessibleContext(), 0);
                    });
            throw failure;
        }
    }

    static JsonObject config(Path root) throws Exception {
        return JsonParser.parseString(
                        Files.readString(root.resolve("profile/config/launcher.json")))
                .getAsJsonObject();
    }

    static JsonObject settings(Path root) throws Exception {
        return config(root).getAsJsonObject("settings");
    }

    static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    static void waitEnabled(String label) throws Exception {
        UiSmoke.awaitFileState(
                "enabled control " + label,
                30000,
                () -> {
                    try {
                        return UiSmoke.waitFor(label)
                                .getAccessibleStateSet()
                                .contains(javax.accessibility.AccessibleState.ENABLED);
                    } catch (Exception pending) {
                        return false;
                    }
                });
    }

    static void record(String action, String evidence) {
        results.add(Map.of("action", action, "evidence", evidence));
        System.out.println("UI_ACTION_OK " + action + " · " + evidence);
        try {
            save(currentMode, "running");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save UI action evidence", failure);
        }
    }

    private static void save(String mode, String status) throws Exception {
        Path report = Path.of(System.getProperty("molifox.actions.report"));
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                new GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(
                                Map.of(
                                        "mode",
                                        mode,
                                        "platform",
                                        System.getProperty("os.name"),
                                        "result",
                                        status,
                                        "checks",
                                        results)));
    }
}
