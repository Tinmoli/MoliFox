// 负责验证任务取消、日志过滤和诊断导出。

import java.awt.*;
import java.io.File;
import java.nio.file.*;

final class LogActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.click(robot, "设置");
        UiSmoke.clickRail(robot, "Java 管理");
        UiSmoke.scrollUntilVisible(robot, "Java 25");
        UiSmoke.click(robot, "Java 25");
        UiSmoke.click(robot, "打开任务中心");
        UiSmoke.click(robot, "取消任务 安装 Java 25");
        UiSmoke.waitFor("继续任务");
        UiSmoke.click(robot, "确定取消");
        UiSmoke.waitFor("这里暂时没有任务");
        UiFunctionalChecks.record(
                "tasks.cancel",
                "running installer cancelled using explicit confirmation; current task list emptied");
        UiSmoke.click(robot, "关闭任务中心");
        UiSmoke.clickRail(robot, "日志");
        UiSmoke.enterText("筛选日志内容", "Java 25");
        // 级别筛选走多选级别片：选中「错误」后，信息级的任务行必须从列表里消失，切回「全部」再出现。
        UiSmoke.click(robot, "错误");
        UiSmoke.waitUntilGone("任务已取消：安装 Java 25");
        UiSmoke.click(robot, "全部");
        UiSmoke.waitFor("任务已取消：安装 Java 25");
        UiFunctionalChecks.record(
                "logs.levelFilter",
                "level filter hides INFO lines when ERROR selected and restores them on 全部; search text kept");
        Path log = root.resolve("action-export.log");
        UiSmoke.click(robot, "导出日志");
        UiSmoke.chooseDirectory(log.toFile());
        UiSmoke.awaitFileState("log file exported", 10000, () -> Files.isRegularFile(log));
        UiFunctionalChecks.require(
                Files.size(log) > 0 && Files.readString(log).contains("Java 25"),
                "Exported log does not contain the real task");
        Path diagnostic = root.resolve("action-diagnostics.zip");
        UiSmoke.click(robot, "导出诊断包");
        UiSmoke.chooseDirectory(diagnostic.toFile());
        UiSmoke.awaitFileState(
                "diagnostics exported", 10000, () -> Files.isRegularFile(diagnostic));
        try (var zip = new java.util.zip.ZipFile(diagnostic.toFile())) {
            UiFunctionalChecks.require(
                    zip.size() > 0
                            && zip.stream()
                                    .noneMatch(entry -> entry.getName().contains("credentials")),
                    "Diagnostics exported credentials or no files");
        }
        UiFunctionalChecks.record(
                "logs.exports",
                "actual log contains cancelled task; diagnostic ZIP is nonempty and excludes credential files");
        UiSmoke.capture(
                robot, window, new File(output.getParentFile(), "MoliFox-actions-logs.png"), true);
        // 最小窗口 1000×640：操作行必须换行/收窄而不是裁切。
        EventQueue.invokeAndWait(() -> window.setSize(1000, 640));
        Thread.sleep(800);
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-actions-logs-1000x640.png"),
                true);
    }
}
