// Checks all resource categories and confirms a real public download through the visible UI.
import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.List;

final class DownloadActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.click(robot, "下载"); UiSmoke.waitFor("推荐 · 最新正式版");
        for (String category : List.of("整合包", "模组", "资源包", "光影", "数据包")) {
            UiSmoke.clickRail(robot, category); UiSmoke.waitFor("搜索资源名称，例如 Sodium、Fabric API…");
            UiSmoke.click(robot, "搜索");
            UiFunctionalChecks.record("download.category." + category, "category selected and its search action invoked; result-specific checks follow separately");
        }
        UiSmoke.clickRail(robot, "模组"); UiSmoke.waitFor("查看 Sodium 的详情与版本");
        UiSmoke.click(robot, "查看 Sodium 的详情与版本"); UiSmoke.waitFor("选择推荐");
        UiSmoke.click(robot, "选择游戏实例"); UiSmoke.click(robot, "SmokeSaves · 1.20.1");
        UiSmoke.click(robot, "选择推荐"); UiSmoke.click(robot, "下载", false);
        UiSmoke.waitFor("快捷位置"); UiSmoke.click(robot, "选择");
        UiFunctionalChecks.waitEnabled("开始安装"); UiSmoke.clickTopButton(robot, "开始安装");
        Path mods = root.resolve(".minecraft/instances/" + UiSmoke.MAIN_INSTANCE_ID + "/mods");
        UiSmoke.awaitFileState("actual public Sodium installation", 90000, () -> {
            if (!Files.isDirectory(mods)) return false;
            try (var files = Files.list(mods)) { return files.anyMatch(path -> path.getFileName().toString().toLowerCase().contains("sodium") && path.toString().endsWith(".jar")); }
            catch (Exception pending) { return false; }
        });
        UiFunctionalChecks.record("download.mod-install", "actual compatible Sodium JAR downloaded to the selected Fabric instance; not executed");
        UiSmoke.click(robot, "实例"); UiSmoke.click(robot, "更多操作"); UiSmoke.click(robot, "管理实例");
        UiSmoke.clickRail(robot, "模组"); UiSmoke.click(robot, "检查更新");
        UiSmoke.waitFor("锁定"); UiSmoke.click(robot, "锁定"); UiSmoke.waitFor("解锁");
        UiFunctionalChecks.record("download.lock", "origin recognized by real hash and locking changed the visible action to unlock");
        UiSmoke.click(robot, "解锁"); UiSmoke.waitFor("锁定"); UiSmoke.click(robot, "关闭更新管理");
        UiSmoke.capture(robot, window, new File(output.getParentFile(), "MoliFox-actions-downloads.png"), true);
    }
}
