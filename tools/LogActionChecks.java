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
        UiSmoke.click(robot, "确认取消");
        UiSmoke.click(robot, "已结束");
        UiSmoke.waitFor("已取消");
        UiFunctionalChecks.record(
                "tasks.cancel",
                "running installer cancelled using confirmation dialog; cancelled status displayed");
        UiSmoke.click(robot, "进行中");
        UiSmoke.click(robot, "全部");
        UiSmoke.click(robot, "关闭任务中心");
        UiSmoke.clickRail(robot, "日志");
        UiSmoke.enterText("筛选日志内容", "Java 25");
        UiSmoke.click(robot, "只看错误");
        UiSmoke.click(robot, "只看错误");
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
        UiSmoke.click(robot, "打开任务中心");
        UiSmoke.click(robot, "清除已结束");
        UiSmoke.waitFor("清除已结束任务？");
        UiSmoke.clickTopButton(robot, "清除");
        UiSmoke.waitUntilGone("安装 Java 25");
        UiFunctionalChecks.record(
                "tasks.clear-finished",
                "cancelled task record removed through the confirmed UI action");
        UiSmoke.click(robot, "关闭任务中心");
        UiSmoke.capture(
                robot, window, new File(output.getParentFile(), "MoliFox-actions-logs.png"), true);
    }
}
