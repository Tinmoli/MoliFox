// 负责验证整合包导入、升级、修复和标准导出。

// Imports, upgrades, repairs and exports a disposable pack through the actual UI.
import com.google.gson.*;

import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.Map;

final class PackActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        Path first = pack(root, "one", "1.0", "before-upgrade");
        Path second = pack(root, "two", "2.0", "after-upgrade");
        UiSmoke.click(robot, "实例");
        UiSmoke.click(robot, "导入整合包");
        UiSmoke.chooseDirectory(first.toFile());
        UiSmoke.waitFor("开始导入");
        UiSmoke.clickTopButton(robot, "开始导入");
        UiSmoke.awaitFileState(
                "pack imported and installed",
                300000,
                () -> {
                    try {
                        JsonObject instance = findPack(root);
                        return instance != null && instance.get("installed").getAsBoolean();
                    } catch (Exception pending) {
                        return false;
                    }
                });
        JsonObject installed = findPack(root);
        Path game = root.resolve(".minecraft/instances/" + installed.get("id").getAsString());
        Path configuration = game.resolve("config/action-pack.cfg");
        UiFunctionalChecks.require(
                Files.readString(configuration).equals("before-upgrade"),
                "Pack override did not reach the game directory");
        UiFunctionalChecks.record(
                "pack.import",
                "UI confirmed import; real game files installed; override content checked");
        UiSmoke.click(robot, "实例");
        UiSmoke.enterText("搜索名称、版本或备注", "ActionPack");
        UiSmoke.click(robot, "更多操作");
        UiSmoke.click(robot, "管理实例");
        UiSmoke.click(robot, "更多操作");
        UiSmoke.click(robot, "升级整合包…");
        UiSmoke.chooseDirectory(second.toFile());
        UiSmoke.waitFor("开始升级");
        UiSmoke.clickTopButton(robot, "开始升级");
        UiSmoke.awaitFileState(
                "pack upgrade content",
                120000,
                () -> {
                    try {
                        return Files.readString(configuration).equals("after-upgrade");
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiFunctionalChecks.record(
                "pack.upgrade", "confirmed upgrade replaced the previous override with v2 bytes");
        openPack(robot);
        UiSmoke.clickRail(robot, "设置");
        UiSmoke.scrollUntilVisible(robot, "修复游戏");
        Files.delete(configuration);
        UiSmoke.click(robot, "修复游戏");
        UiSmoke.awaitFileState(
                "pack repair restored deleted file",
                120000,
                () -> {
                    try {
                        return Files.readString(configuration).equals("after-upgrade");
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiFunctionalChecks.record(
                "pack.repair", "UI repair restored a deleted v2 override from the saved pack");
        openPack(robot);
        UiSmoke.click(robot, "更多操作");
        UiSmoke.click(robot, "导出整合包…");
        Path exported = root.resolve("action-pack-export.mrpack");
        UiSmoke.enterText("目标文件", exported.toString());
        UiSmoke.waitFor("开始导出");
        UiSmoke.clickTopButton(robot, "开始导出");
        UiSmoke.awaitFileState(
                "standard pack exported", 60000, () -> Files.isRegularFile(exported));
        try (var zip = new java.util.zip.ZipFile(exported.toFile())) {
            UiFunctionalChecks.require(
                    zip.getEntry("modrinth.index.json") != null,
                    "Export has no standard mrpack manifest");
            var entry = zip.getEntry("client-overrides/config/action-pack.cfg");
            UiFunctionalChecks.require(entry != null, "Export omitted the actual config override");
            try (var stream = zip.getInputStream(entry)) {
                UiFunctionalChecks.require(
                        new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .equals("after-upgrade"),
                        "Export contains stale pack data");
            }
        }
        UiFunctionalChecks.record(
                "pack.export-mrpack",
                "actual UI export contains the standard manifest and current override bytes");
        UiSmoke.capture(
                robot, window, new File(output.getParentFile(), "MoliFox-actions-packs.png"), true);
    }

    private static void openPack(Robot robot) throws Exception {
        UiSmoke.click(robot, "实例");
        UiSmoke.waitFor("游戏实例");
        UiSmoke.enterText("搜索名称、版本或备注", "ActionPack");
        UiSmoke.click(robot, "更多操作");
        UiSmoke.click(robot, "管理实例");
        UiSmoke.waitFor("基本信息");
    }

    private static JsonObject findPack(Path root) throws Exception {
        for (JsonElement entry : UiFunctionalChecks.config(root).getAsJsonArray("instances"))
            if (entry.getAsJsonObject().get("name").getAsString().equals("ActionPack"))
                return entry.getAsJsonObject();
        return null;
    }

    private static Path pack(Path root, String filename, String version, String content)
            throws Exception {
        Path archive = root.resolve("action-pack-" + filename + ".mrpack");
        var index =
                Map.of(
                        "formatVersion",
                        1,
                        "game",
                        "minecraft",
                        "versionId",
                        version,
                        "name",
                        "ActionPack",
                        "dependencies",
                        Map.of("minecraft", "1.20.1"),
                        "files",
                        java.util.List.of());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            zip.write(new Gson().toJson(index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("overrides/config/action-pack.cfg"));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }
}
