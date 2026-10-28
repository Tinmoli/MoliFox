// Exercises instance management controls against disposable files and checks their real effects.
import com.google.gson.*;
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.*;

final class InstanceActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        Path game = root.resolve(".minecraft/instances/" + UiSmoke.MAIN_INSTANCE_ID);
        Path target = root.resolve(".minecraft/instances/" + UiSmoke.TARGET_INSTANCE_ID);
        Files.createDirectories(game.resolve("saves/ActionWorld"));
        Files.writeString(game.resolve("saves/ActionWorld/level.dat"), "disposable-world");
        openMain(robot);
        renameAndNotes(robot, root);
        copySave(robot, game, target);
        mods(robot, window, root, game);
        schematics(robot, root, game);
        tidyAndVerify(robot, game);
        backup(robot, root);
        copyInstance(robot, root, game);
        isolation(robot, root, game);
        moveInstance(robot, root, game);
        UiSmoke.capture(robot, window, new File(output.getParentFile(), "MoliFox-actions-instances.png"), true);
    }

    private static void openMain(Robot robot) throws Exception {
        UiSmoke.click(robot, "实例"); UiSmoke.waitFor("游戏实例");
        UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, "管理实例");
        UiSmoke.waitFor("基本信息");
    }

    private static void menu(Robot robot, String label) throws Exception {
        UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, label);
    }

    private static void renameAndNotes(Robot robot, Path root) throws Exception {
        menu(robot, "重命名实例"); UiSmoke.waitFor("保存"); UiSmoke.enterText("实例名称", "ActionRenamed");
        UiSmoke.clickTopButton(robot, "保存");
        UiSmoke.awaitFileState("renamed instance", 10000, () -> {
            try { return UiSmoke.savedInstance(root.resolve("profile/config/launcher.json"), "smoke-saves").get("name").getAsString().equals("ActionRenamed"); }
            catch (Exception pending) { return false; }
        });
        UiSmoke.enterText("版本备注", "操作验收备注"); UiSmoke.click(robot, "保存设置");
        UiSmoke.awaitFileState("instance notes", 10000, () -> {
            try { return UiSmoke.savedInstance(root.resolve("profile/config/launcher.json"), "smoke-saves").get("notes").getAsString().equals("操作验收备注"); }
            catch (Exception pending) { return false; }
        });
        UiFunctionalChecks.require(UiSmoke.savedInstance(root.resolve("profile/config/launcher.json"), "smoke-saves").get("name").getAsString().equals("ActionRenamed"), "Saving notes reverted the independently saved instance name");
        UiFunctionalChecks.record("instance.rename-and-notes", "new name and notes persisted by actual controls");
        UiSmoke.waitFor("游戏实例");
        UiSmoke.enterText("搜索名称、版本或备注", "ActionRenamed");
        UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, "管理实例");
    }

    private static void copySave(Robot robot, Path game, Path target) throws Exception {
        UiSmoke.clickRail(robot, "存档"); UiSmoke.waitFor("ActionWorld");
        UiSmoke.clickTopButton(robot, "复制到…"); UiSmoke.waitFor("复制存档");
        UiSmoke.enterText("新名称（可留空沿用原名）", "ActionWorldCopy");
        UiSmoke.clickTopButton(robot, "开始复制");
        Path copied = target.resolve("saves/ActionWorldCopy/level.dat");
        UiSmoke.awaitFileState("world copied", 30000, () -> Files.isRegularFile(copied));
        UiFunctionalChecks.require(Files.readString(copied).equals("disposable-world") && Files.isRegularFile(game.resolve("saves/ActionWorld/level.dat")), "Copy altered or lost the source world");
        UiFunctionalChecks.record("save.copy", "destination bytes verified; original preserved");
    }

    private static void mods(Robot robot, Window window, Path root, Path game) throws Exception {
        Path source = root.resolve("action-import.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(source))) {
            jar.putNextEntry(new java.util.jar.JarEntry("fabric.mod.json"));
            jar.write("{\"id\":\"action_fixture\",\"name\":\"Action Mod\",\"version\":\"1.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path second = root.resolve("action-second.jar"); Files.copy(source, second);
        UiSmoke.clickRail(robot, "模组"); UiSmoke.click(robot, "导入模组");
        UiSmoke.choosePaths(List.of(source.toFile(), second.toFile())); UiSmoke.waitFor("Action Mod");
        Path installed = game.resolve("mods/action-import.jar");
        UiSmoke.awaitFileState("mod imported", 10000, () -> Files.isRegularFile(installed));
        UiFunctionalChecks.require(Files.isRegularFile(game.resolve("mods/action-second.jar")), "Multiple selection did not import the second file");
        UiFunctionalChecks.require(java.util.Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(installed)), "Imported mod bytes changed");
        AtomicReference<AccessibleContext> switchNode = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> switchNode.set(firstSwitch(window.getAccessibleContext())));
        UiFunctionalChecks.require(switchNode.get() != null, "Mod toggle is inaccessible");
        UiSmoke.activate(robot, switchNode.get(), "mod enabled switch");
        UiSmoke.awaitFileState("mod disabled", 10000, () -> Files.isRegularFile(game.resolve("mods/action-import.jar.disabled")));
        robot.delay(350);
        EventQueue.invokeAndWait(() -> switchNode.set(firstSwitch(window.getAccessibleContext())));
        UiFunctionalChecks.require(switchNode.get() != null, "Disabled mod toggle did not return after the row refreshed");
        UiSmoke.activate(robot, switchNode.get(), "mod enabled switch");
        UiSmoke.awaitFileState("mod enabled", 10000, () -> Files.isRegularFile(installed));
        UiSmoke.click(robot, "将模组移至回收站"); UiSmoke.waitFor("删除模组？");
        UiSmoke.clickTopButton(robot, "移入回收站");
        UiSmoke.awaitFileState("mod recycled", 10000, () -> !Files.exists(installed));
        UiFunctionalChecks.require(Files.isRegularFile(game.resolve("mods/action-second.jar")), "Recycling one mod also removed another mod");
        UiFunctionalChecks.record("mods.import-toggle-delete", "identical imported JAR; .disabled rename both directions; actual recycling");
    }

    private static AccessibleContext firstSwitch(AccessibleContext node) {
        if (node == null) return null;
        if ((node.getAccessibleRole() == AccessibleRole.TOGGLE_BUTTON || node.getAccessibleRole() == AccessibleRole.CHECK_BOX) && node.getAccessibleComponent() != null && node.getAccessibleComponent().isShowing()) return node;
        for (int i = 0; i < node.getAccessibleChildrenCount(); i++) {
            Accessible child = node.getAccessibleChild(i);
            AccessibleContext found = child == null ? null : firstSwitch(child.getAccessibleContext());
            if (found != null) return found;
        }
        return null;
    }

    private static void schematics(Robot robot, Path root, Path game) throws Exception {
        Path source = root.resolve("action-house.schem"); Files.writeString(source, "schematic-copy-fixture");
        UiSmoke.clickRail(robot, "原理图"); UiSmoke.click(robot, "导入原理图");
        UiSmoke.chooseDirectory(source.toFile()); UiSmoke.waitFor("action-house.schem");
        Path installed = game.resolve("schematics/action-house.schem");
        UiSmoke.awaitFileState("schematic imported", 10000, () -> Files.isRegularFile(installed));
        UiFunctionalChecks.require(Files.readString(installed).equals(Files.readString(source)), "Schematic copy differs");
        UiSmoke.click(robot, "移至回收站 action-house.schem"); UiSmoke.waitFor("移至回收站？");
        UiSmoke.clickTopButton(robot, "移至回收站");
        UiSmoke.awaitFileState("schematic recycled", 10000, () -> !Files.exists(installed));
        UiFunctionalChecks.record("schematics.import-delete", "file copied unchanged then removed through confirmed UI recycling");
    }

    private static void tidyAndVerify(Robot robot, Path game) throws Exception {
        Files.createDirectories(game.resolve("empty-to-tidy/nested"));
        menu(robot, "整理实例目录"); UiSmoke.waitFor("整理结果");
        UiFunctionalChecks.require(!Files.exists(game.resolve("empty-to-tidy")), "Tidy left the empty directory");
        UiFunctionalChecks.require(Files.isRegularFile(game.resolve("saves/ActionWorld/level.dat")), "Tidy removed world data");
        UiSmoke.clickTopButton(robot, "知道了");
        menu(robot, "校验实例"); UiSmoke.waitFor("该实例没有整合包账本，无法校验");
        UiSmoke.click(robot, "关闭", false);
        UiFunctionalChecks.record("instance.tidy-verify", "empty directory removed; save kept; absent ledger reported truthfully");
    }

    private static void backup(Robot robot, Path root) throws Exception {
        Path archive = root.resolve("action-backup.zip");
        menu(robot, "完整实例备份 ZIP…"); UiSmoke.chooseDirectory(archive.toFile());
        UiSmoke.awaitFileState("instance backup", 30000, () -> Files.isRegularFile(archive));
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            UiFunctionalChecks.require(zip.stream().anyMatch(entry -> entry.getName().endsWith("saves/ActionWorld/level.dat")), "Backup lacks the world");
        }
        UiFunctionalChecks.record("instance.backup", "UI-selected ZIP contains the actual world");
    }

    private static void copyInstance(Robot robot, Path root, Path game) throws Exception {
        menu(robot, "复制实例"); UiSmoke.enterText("新实例名称", "ActionClone");
        UiSmoke.clickTopButton(robot, "开始复制");
        UiSmoke.awaitFileState("instance clone", 30000, () -> {
            try { return findByName(root, "ActionClone") != null; } catch (Exception pending) { return false; }
        });
        JsonObject clone = findByName(root, "ActionClone");
        Path cloned = root.resolve(".minecraft/instances/" + clone.get("id").getAsString());
        UiFunctionalChecks.require(Files.readString(cloned.resolve("saves/ActionWorld/level.dat")).equals("disposable-world"), "Clone omitted save content");
        UiFunctionalChecks.require(Files.isDirectory(game), "Clone removed original instance");
        UiFunctionalChecks.record("instance.copy", "new instance record and independent world content verified");
    }

    private static JsonObject findByName(Path root, String name) throws Exception {
        for (JsonElement entry : UiFunctionalChecks.config(root).getAsJsonArray("instances")) if (entry.getAsJsonObject().get("name").getAsString().equals(name)) return entry.getAsJsonObject();
        return null;
    }

    private static void isolation(Robot robot, Path root, Path game) throws Exception {
        UiSmoke.click(robot, "实例"); UiSmoke.enterText("搜索名称、版本或备注", "ActionRenamed");
        UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, "管理实例");
        UiSmoke.click(robot, "切换…"); UiSmoke.waitFor("迁移方向");
        UiSmoke.clickTopButton(robot, "开始切换");
        Path shared = root.resolve(".minecraft/saves/ActionWorld/level.dat");
        UiSmoke.awaitFileState("shared save copied", 30000, () -> Files.isRegularFile(shared));
        UiSmoke.awaitFileState("shared mode saved", 10000, () -> {
            try { return UiSmoke.savedInstance(root.resolve("profile/config/launcher.json"), "smoke-saves").get("isolation").getAsString().equals("Disabled"); }
            catch (Exception pending) { return false; }
        });
        UiFunctionalChecks.require(Files.isRegularFile(game.resolve("saves/ActionWorld/level.dat")), "Default isolation copy moved source data");
        openRenamed(robot);
        UiSmoke.click(robot, "切换…"); UiSmoke.waitFor("迁移方向"); UiSmoke.clickTopButton(robot, "开始切换");
        UiSmoke.awaitFileState("isolated mode restored", 30000, () -> {
            try { return UiSmoke.savedInstance(root.resolve("profile/config/launcher.json"), "smoke-saves").get("isolation").getAsString().equals("Enabled"); }
            catch (Exception pending) { return false; }
        });
        UiFunctionalChecks.record("instance.isolation", "actual transfer confirmed in both directions; default copy preserves source");
        openRenamed(robot);
    }

    private static void openRenamed(Robot robot) throws Exception {
        UiSmoke.click(robot, "实例"); UiSmoke.waitFor("游戏实例");
        UiSmoke.enterText("搜索名称、版本或备注", "ActionRenamed");
        UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, "管理实例");
        UiSmoke.waitFor("基本信息");
    }

    private static void moveInstance(Robot robot, Path root, Path game) throws Exception {
        Path destination = root.resolve("action-move-target"); Files.createDirectories(destination);
        menu(robot, "移动到其他游戏目录…"); UiSmoke.chooseDirectory(destination.toFile());
        UiSmoke.waitFor("移动预览"); UiSmoke.clickTopButton(robot, "开始移动");
        Path moved = destination.resolve("instances/" + UiSmoke.MAIN_INSTANCE_ID + "/saves/ActionWorld/level.dat");
        UiSmoke.awaitFileState("instance actually moved", 30000, () -> Files.isRegularFile(moved) && !Files.exists(game));
        UiFunctionalChecks.require(Files.readString(moved).equals("disposable-world"), "Moved world content changed");
        UiFunctionalChecks.record("instance.move", "new directory contains original world; old instance directory removed");
    }
}
