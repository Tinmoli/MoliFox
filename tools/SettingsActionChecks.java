// 负责验证设置保存及实际 Java 进程收到的启动参数。

// Verifies saved settings and their visible UI state through actual controls.
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.accessibility.*;

final class SettingsActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.click(robot, "设置");
        javaDetection(robot, root);
        UiSmoke.clickRail(robot, "全局游戏设置");
        globalGame(robot, window, root);
        launchInheritedSettings(robot, root);
        downloadSources(robot, root);
        general(robot, window, root);
        appearance(robot, window, root);
        pagePreferences(robot, window, root);
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-actions-settings.png"),
                true);
    }

    private static void javaDetection(Robot robot, Path root) throws Exception {
        UiSmoke.clickRail(robot, "Java 管理");
        waitEnabled("检测本机 Java");
        UiSmoke.click(robot, "检测本机 Java");
        waitEnabled("检测本机 Java");
        UiSmoke.click(robot, "自动选择匹配的 Java");
        String path = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
        UiSmoke.click(robot, "Java 25 · Azul Systems, Inc. · " + path);
        save(robot);
        expect(root, "javaPath", path);
        UiSmoke.click(robot, "Java 25 · Azul Systems, Inc. · " + path);
        UiSmoke.click(robot, "自动选择匹配的 Java", false);
        save(robot);
        expect(root, "javaPath", "");
        UiFunctionalChecks.record(
                "settings.java-detection",
                "manual detection clicked after enabled; detected runtime selected and restored to automatic");
    }

    private static void waitEnabled(String label) throws Exception {
        UiSmoke.awaitFileState(
                "enabled control " + label,
                30000,
                () -> {
                    try {
                        return UiSmoke.waitFor(label)
                                .getAccessibleStateSet()
                                .contains(AccessibleState.ENABLED);
                    } catch (Exception pending) {
                        return false;
                    }
                });
    }

    private static void expect(Path root, String path, String value) throws Exception {
        UiSmoke.awaitFileState(
                "setting " + path,
                10000,
                () -> {
                    try {
                        com.google.gson.JsonElement node = UiFunctionalChecks.settings(root);
                        for (String key : path.split("\\.")) node = node.getAsJsonObject().get(key);
                        return node != null
                                && !node.isJsonNull()
                                && node.getAsString().equals(value);
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiFunctionalChecks.record(
                "setting." + path, "saved configuration equals the requested control value");
    }

    private static void pick(Robot robot, String from, String to) throws Exception {
        UiSmoke.scrollUntilVisible(robot, from);
        UiSmoke.click(robot, from);
        UiSmoke.click(robot, to, false);
    }

    private static void save(Robot robot) throws Exception {
        robot.delay(500);
        if (UiSmoke.hasControlStartingWith("有设置未保存"))
            throw new IllegalStateException("Settings did not save");
    }

    private static void globalGame(Robot robot, Window window, Path root) throws Exception {
        if (UiFunctionalChecks.settings(root).get("autoMemory").getAsBoolean())
            toggleRow(robot, window, "自动分配内存");
        UiSmoke.setSlider(robot, "全局内存滑块", .12f);
        UiSmoke.scrollUntilVisible(robot, "窗口宽度（px）");
        UiSmoke.enterText("窗口宽度（px）", "1024");
        UiSmoke.enterText("窗口高度（px）", "576");
        save(robot);
        UiFunctionalChecks.require(
                UiFunctionalChecks.settings(root).get("memoryMb").getAsInt() >= 512,
                "Memory slider did not save a valid allocation");
        expect(root, "initialMemoryMb", "512");
        expect(root, "width", "1024");
        expect(root, "height", "576");
        toggleRow(robot, window, "全屏启动");
        save(robot);
        expect(root, "fullscreen", "true");
        UiFunctionalChecks.require(
                !UiSmoke.waitFor("窗口宽度（px）").getAccessibleComponent().isEnabled(),
                "Fullscreen left width input enabled");
        toggleRow(robot, window, "全屏启动");
        save(robot);
        expect(root, "fullscreen", "false");
        UiSmoke.scrollUntilVisible(robot, "附加 JVM 参数");
        UiSmoke.enterText("附加 JVM 参数", "-Dmolifox.ui.check=true");
        save(robot);
        expect(root, "extraJvmArgs", "-Dmolifox.ui.check=true");
        UiSmoke.scrollUntilVisible(robot, "附加游戏参数");
        UiSmoke.enterText("附加游戏参数", "--server gui.example --port 25565");
        UiSmoke.scrollUntilVisible(robot, "游戏环境变量");
        UiSmoke.enterText(
                "游戏环境变量",
                "MOLIFOX_UI=hello world\nMOLIFOX_UI_MARKER=" + root.resolve("game-marker.txt"));
        save(robot);
        UiSmoke.scrollUntilVisible(robot, "附加 JVM 参数");
        UiSmoke.enterText("附加 JVM 参数", "-Dmolifox.discard=true");
        UiSmoke.click(robot, "还原修改");
        expect(root, "extraJvmArgs", "-Dmolifox.ui.check=true");
        toggleRow(robot, window, "启用版本隔离");
        save(robot);
        expect(root, "isolateVersions", "false");
        toggleRow(robot, window, "启用版本隔离");
        save(robot);
        expect(root, "isolateVersions", "true");
        UiFunctionalChecks.record(
                "settings.game",
                "memory/window/JVM/isolation saved; fullscreen disables resolution; discard preserves saved values");
    }

    private static void launchInheritedSettings(Robot robot, Path root) throws Exception {
        UiSmoke.click(robot, "开始");
        UiSmoke.click(robot, "离线");
        UiSmoke.waitUntilGone("微软登录");
        UiSmoke.enterText("玩家名称", "ActionSettings");
        UiSmoke.click(robot, "添加账户");
        UiSmoke.waitFor("ActionSettings");
        UiSmoke.click(robot, "实例");
        UiSmoke.click(robot, "启动 SmokeSaves");
        Path observed = root.resolve("observed-launch.properties");
        UiSmoke.awaitFileState(
                "actual UI-configured Java process",
                30000,
                () -> java.nio.file.Files.isRegularFile(observed));
        var values = new java.util.Properties();
        try (var stream = java.nio.file.Files.newInputStream(observed)) {
            values.load(stream);
        }
        UiFunctionalChecks.require(
                values.getProperty("jvm").contains("-Xmx2048M")
                        && values.getProperty("jvm").contains("-Xms512M"),
                "UI memory settings did not reach the Java process");
        UiFunctionalChecks.require(
                values.getProperty("property").equals("true"),
                "UI JVM property did not reach the Java process");
        UiFunctionalChecks.require(
                values.getProperty("gameArgs").contains("gui.example")
                        && values.getProperty("environment").equals("hello world"),
                "UI game arguments/environment were not applied");
        Path expected =
                root.resolve(".minecraft/versions/smoke-saves").toAbsolutePath().normalize();
        UiFunctionalChecks.require(
                Path.of(values.getProperty("cwd")).toAbsolutePath().normalize().equals(expected),
                "UI-configured isolated working directory differs");
        UiSmoke.awaitFileState(
                "controlled game finished",
                10000,
                () -> {
                    try {
                        return java.nio.file.Files.readString(root.resolve("game-marker.txt"))
                                .equals("finished");
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiFunctionalChecks.record(
                "settings.runtime-effect",
                "actual controlled Java process received memory/JVM/game/environment values and correct isolated CWD; this is not Minecraft gameplay evidence");
        UiSmoke.click(robot, "设置");
    }

    private static void downloadSources(Robot robot, Path root) throws Exception {
        UiSmoke.clickRail(robot, "下载");
        pick(robot, "自动（官方优先，失败切换）", "官方源");
        save(robot);
        expect(root, "downloadSource", "official");
        pick(robot, "官方源", "BMCLAPI 镜像源");
        save(robot);
        expect(root, "downloadSource", "bmclapi");
        pick(robot, "BMCLAPI 镜像源", "自动（官方优先，失败切换）");
        save(robot);
        expect(root, "downloadSource", "auto");
        UiSmoke.click(robot, "自动（官方优先，失败切换）", false);
        UiSmoke.click(robot, "官方版本列表");
        save(robot);
        expect(root, "versionListSource", "official");
        pick(robot, "官方版本列表", "BMCLAPI 镜像版本列表");
        save(robot);
        expect(root, "versionListSource", "bmclapi");
        pick(robot, "BMCLAPI 镜像版本列表", "自动（官方优先，失败切换）");
        save(robot);
        expect(root, "versionListSource", "auto");
        pick(robot, "8 个文件", "4 个文件");
        save(robot);
        expect(root, "maxConcurrent", "4");
        UiSmoke.scrollUntilVisible(robot, "选择缓存文件夹");
        UiSmoke.click(robot, "选择缓存文件夹");
        Path cache = root.resolve("selected-cache");
        java.nio.file.Files.createDirectories(cache);
        UiSmoke.chooseDirectory(cache.toFile());
        save(robot);
        expect(root, "downloadCachePath", cache.toString());
        UiSmoke.awaitFileState(
                "configured cache layout",
                10000,
                () -> java.nio.file.Files.isDirectory(cache.resolve("metadata")));
        pick(robot, "跟随系统", "直接连接");
        save(robot);
        expect(root, "proxyMode", "direct");
        pick(robot, "直接连接", "手动代理");
        pick(robot, "HTTP", "SOCKS");
        UiSmoke.click(robot, "还原修改");
        expect(root, "proxyMode", "direct");
        pick(robot, "直接连接", "跟随系统");
        save(robot);
        expect(root, "proxyMode", "system");
        UiFunctionalChecks.record(
                "settings.download",
                "both source menus/all choices persist; concurrency/cache layout/proxy mode checked; manual proxy draft discarded");
    }

    private static void general(Robot robot, Window window, Path root) throws Exception {
        UiSmoke.clickRail(robot, "通用");
        UiSmoke.click(robot, "检查更新");
        UiSmoke.waitFor("检查更新");
        toggleRow(robot, window, "启动时自动检查更新");
        save(robot);
        expect(root, "autoCheckUpdates", "true");
        toggleRow(robot, window, "启动时自动检查更新");
        save(robot);
        expect(root, "autoCheckUpdates", "false");
        UiSmoke.enterText("搜索设置项", "zz_no_setting_exists");
        UiSmoke.waitFor("清空搜索");
        UiSmoke.click(robot, "清空搜索");
        UiSmoke.clickRail(robot, "关于 MoliFox");
        UiSmoke.waitFor("MoliFox");
        UiFunctionalChecks.record(
                "settings.general",
                "update button invoked; automatic-check toggle persisted both ways; search cleared; about displayed");
    }

    private static void appearance(Robot robot, Window window, Path root) throws Exception {
        UiSmoke.clickRail(robot, "外观");
        for (String[] choice :
                new String[][] {{"深色", "dark"}, {"跟随系统", "system"}, {"浅色", "light"}}) {
            UiSmoke.click(robot, choice[0]);
            expect(root, "themeMode", choice[1]);
        }
        for (String[] choice :
                new String[][] {
                    {"海盐蓝", "ocean"},
                    {"薄荷绿", "mint"},
                    {"琥珀橙", "amber"},
                    {"薰衣草紫", "lavender"},
                    {"石墨灰", "graphite"},
                    {"樱花粉", "sakura"}
                }) {
            UiSmoke.scrollUntilVisible(robot, choice[0]);
            UiSmoke.click(robot, choice[0]);
            expect(root, "accentColor", choice[1]);
        }
        UiSmoke.enterText("#RRGGBB", "#AB5577");
        expect(root, "accentColor", "#AB5577");
        toggleRow(robot, window, "标题栏半透明");
        expect(root, "titlebarTransparent", "true");
        toggleRow(robot, window, "窗口透明");
        expect(root, "windowTransparent", "true");
        toggleRow(robot, window, "过渡动画");
        expect(root, "animationsEnabled", "false");
        UiSmoke.scrollUntilVisible(robot, "玻璃效果开关");
        UiSmoke.click(robot, "玻璃效果开关");
        expect(root, "glassEnabled", "false");
        UiSmoke.click(robot, "玻璃效果开关");
        expect(root, "glassEnabled", "true");
        UiSmoke.scrollUntilVisible(robot, "面板不透明度滑块");
        UiSmoke.setSlider(robot, "面板不透明度滑块", .4f);
        UiSmoke.scrollUntilVisible(robot, "背景磨砂强度滑块");
        UiSmoke.setSlider(robot, "背景磨砂强度滑块", .6f);
        UiSmoke.click(robot, "恢复默认外观");
        expect(root, "accentColor", "sakura");
        expect(root, "windowTransparent", "false");
        expect(root, "titlebarTransparent", "false");
        expect(root, "animationsEnabled", "true");
        UiFunctionalChecks.record(
                "settings.appearance",
                "all theme modes and preset colors/custom color saved; effect toggles/sliders/reset clicked; transparent restart still requires separate acceptance");
    }

    private static void pagePreferences(Robot robot, Window window, Path root) throws Exception {
        UiSmoke.click(robot, "页面布局");
        for (String[] toggle :
                new String[][] {
                    {"游戏统计", "pages.home.showStatistics"}, {"最近游玩", "pages.home.showRecent"}
                }) {
            toggleRow(robot, window, toggle[0]);
            expect(root, toggle[1], "false");
            toggleRow(robot, window, toggle[0]);
            expect(root, toggle[1], "true");
        }
        pick(robot, "5 个版本", "8 个版本");
        expect(root, "pages.home.recentCount", "8");
        UiSmoke.click(robot, "版本库");
        pick(robot, "列表", "网格");
        expect(root, "pages.versions.view", "grid");
        pick(robot, "最近游玩", "按名称");
        expect(root, "pages.versions.sort", "name");
        toggleRow(robot, window, "收藏优先");
        expect(root, "pages.versions.favoritesFirst", "false");
        toggleRow(robot, window, "显示备注");
        expect(root, "pages.versions.showNotes", "false");
        UiSmoke.click(robot, "下载中心");
        toggleRow(robot, window, "紧凑卡片");
        expect(root, "pages.downloads.compactCards", "true");
        toggleRow(robot, window, "显示作者");
        expect(root, "pages.downloads.showAuthors", "false");
        toggleRow(robot, window, "显示简介");
        expect(root, "pages.downloads.showDescriptions", "false");
        UiSmoke.click(robot, "运行日志");
        toggleRow(robot, window, "日志自动滚动");
        expect(root, "pages.logs.autoScroll", "false");
        UiSmoke.setSlider(robot, "日志字号滑块", .5f);
        UiSmoke.click(robot, "启动设置");
        toggleRow(robot, window, "显示高级参数");
        expect(root, "pages.showAdvancedLaunchOptions", "false");
        UiSmoke.clickRail(robot, "全局游戏设置");
        UiFunctionalChecks.require(
                !UiSmoke.hasControlStartingWith("附加 JVM 参数"),
                "Hidden advanced editor remains visible");
        UiFunctionalChecks.record(
                "settings.page-preferences",
                "all page-layout toggles/dropdowns/sliders clicked and saved; hidden advanced editor checked");
    }

    static void toggleRow(Robot robot, Window window, String label) throws Exception {
        UiSmoke.scrollUntilVisible(robot, label);
        AccessibleContext title = UiSmoke.waitFor(label);
        AtomicReference<AccessibleContext> chosen = new AtomicReference<>();
        EventQueue.invokeAndWait(
                () -> {
                    Rectangle bounds = UiSmoke.componentBounds(title);
                    java.util.List<AccessibleContext> switches = new ArrayList<>();
                    collectSwitches(window.getAccessibleContext(), switches);
                    double best = Double.MAX_VALUE;
                    for (AccessibleContext node : switches) {
                        Rectangle control = UiSmoke.componentBounds(node);
                        if (bounds == null
                                || control == null
                                || control.getCenterX() < bounds.getCenterX()) continue;
                        double score = Math.abs(control.getCenterY() - bounds.getCenterY());
                        if (score < best && score < 40) {
                            best = score;
                            chosen.set(node);
                        }
                    }
                });
        UiFunctionalChecks.require(
                chosen.get() != null, "Cannot locate the actual switch for " + label);
        UiSmoke.activate(robot, chosen.get(), label);
    }

    private static void collectSwitches(
            AccessibleContext node, java.util.List<AccessibleContext> sink) {
        if (node == null) return;
        if (node.getAccessibleRole() == AccessibleRole.TOGGLE_BUTTON
                || node.getAccessibleRole() == AccessibleRole.CHECK_BOX) sink.add(node);
        for (int i = 0; i < node.getAccessibleChildrenCount(); i++) {
            Accessible child = node.getAccessibleChild(i);
            if (child != null) collectSwitches(child.getAccessibleContext(), sink);
        }
    }
}
