// 验证高级参数可输入并保存、外观与关于页面，以及下载任务独立入口。

import java.awt.Robot;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;

final class RefinementActions {
    private static java.net.ServerSocket motdServer;

    static void seed(Path root) throws Exception {
        Path game = root.resolve(".minecraft/instances/" + UiSmoke.MAIN_INSTANCE_ID);
        for (String folder :
                new String[] {"logs", "crash-reports", "screenshots", "shaderpacks", "schematics"})
            java.nio.file.Files.createDirectories(game.resolve(folder));
        motdServer = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        try (var nbt =
                new java.io.DataOutputStream(
                        java.nio.file.Files.newOutputStream(game.resolve("servers.dat")))) {
            nbt.writeByte(10);
            nbt.writeUTF("");
            nbt.writeByte(9);
            nbt.writeUTF("servers");
            nbt.writeByte(10);
            nbt.writeInt(1);
            nbt.writeByte(8);
            nbt.writeUTF("name");
            nbt.writeUTF("MoliFox test server");
            nbt.writeByte(8);
            nbt.writeUTF("ip");
            nbt.writeUTF("127.0.0.1:" + motdServer.getLocalPort());
            nbt.writeByte(0);
            nbt.writeByte(0);
        }
        Thread server =
                new Thread(
                        () -> {
                            while (!motdServer.isClosed()) {
                                try (var socket = motdServer.accept()) {
                                    socket.setSoTimeout(5000);
                                    var input = socket.getInputStream();
                                    input.readNBytes(readVarInt(input));
                                    input.readNBytes(readVarInt(input));
                                    byte[] json =
                                            "{\"version\":{\"name\":\"fixture\",\"protocol\":767},\"players\":{\"online\":2,\"max\":20},\"description\":{\"text\":\"第一行测试\\n第二行测试\",\"color\":\"#b94e7b\"}}"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8);
                                    var packet = new java.io.ByteArrayOutputStream();
                                    packet.write(0);
                                    writeVarInt(packet, json.length);
                                    packet.write(json);
                                    var output = socket.getOutputStream();
                                    writeVarInt(output, packet.size());
                                    output.write(packet.toByteArray());
                                    output.flush();
                                } catch (Exception failure) {
                                    if (!motdServer.isClosed()) failure.printStackTrace();
                                }
                            }
                        },
                        "MoliFox-fixture-MOTD");
        server.setDaemon(true);
        server.start();
    }

    private static int readVarInt(java.io.InputStream input) throws Exception {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int value = input.read();
            if (value < 0) throw new java.io.EOFException();
            result |= (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new java.io.IOException("Invalid varint");
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws Exception {
        while ((value & ~127) != 0) {
            output.write((value & 127) | 128);
            value >>>= 7;
        }
        output.write(value);
    }

    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        UiSmoke.waitFor("第一行测试\n第二行测试");
        UiSmoke.capture(robot, window, new File(output.getParentFile(), "MoliFox-home.png"), true);
        motdServer.close();
        PackDragChecks.run(robot, window, output, root);
        if (UiSmoke.hasControlStartingWith("选择目录") || UiSmoke.hasControlStartingWith("打开目录"))
            throw new IllegalStateException("Redundant home directory actions remain");
        UiSmoke.click(robot, "管理");
        UiSmoke.waitFor("Java 与内存");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-instance.png"),
                true);
        UiSmoke.click(robot, "游戏文件夹");
        for (String label : new String[] {"日志文件夹", "崩溃报告文件夹", "截图文件夹", "光影包文件夹"})
            UiSmoke.waitFor(label);
        if (UiSmoke.hasControlStartingWith("模组文件夹"))
            throw new IllegalStateException("Missing mods folder must not be offered");
        UiSmoke.click(robot, "设置");
        UiSmoke.clickRail(robot, "全局游戏设置");
        UiSmoke.scrollUntilVisible(robot, "附加 JVM 参数");
        robot.mouseMove(
                window.getX() + window.getWidth() * 3 / 4,
                window.getY() + window.getHeight() * 3 / 4);
        robot.mouseWheel(2);
        robot.delay(350);
        var editor = UiSmoke.waitForEditable("附加 JVM 参数");
        var located = new java.util.concurrent.atomic.AtomicReference<java.awt.Rectangle>();
        java.awt.EventQueue.invokeAndWait(() -> located.set(UiSmoke.componentBounds(editor)));
        var area = located.get();
        if (area == null) throw new IllegalStateException("JVM field is not visible");
        robot.mouseMove(area.x + area.width / 2, area.y + area.height / 2);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                        new java.awt.datatransfer.StringSelection(
                                "-Dmolifox.refinement=\"value with spaces\""),
                        null);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(java.awt.event.KeyEvent.VK_V);
        robot.keyRelease(java.awt.event.KeyEvent.VK_V);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        UiSmoke.awaitFileState(
                "JVM argument saved",
                10000,
                () -> {
                    try {
                        return UiFunctionalChecks.settings(root)
                                .get("extraJvmArgs")
                                .getAsString()
                                .equals("-Dmolifox.refinement=\"value with spaces\"");
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-parameters.png"),
                true);
        UiSmoke.clickRail(robot, "通用");
        UiSmoke.click(robot, "检查更新");
        UiSmoke.waitFor("尚无正式发布版本");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-update.png"),
                true);
        UiSmoke.clickRail(robot, "关于 MoliFox");
        UiSmoke.click(robot, "附加条款");
        UiSmoke.waitFor("NOTICE");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-license.png"),
                true);
        UiSmoke.click(robot, "关闭", false);
        UiSmoke.clickRail(robot, "外观");
        UiSmoke.scrollUntilVisible(robot, "界面圆角");
        UiSmoke.setSlider(robot, "界面圆角滑块", .65f);
        UiSmoke.awaitFileState(
                "custom radius saved",
                10000,
                () -> {
                    try {
                        return UiFunctionalChecks.settings(root).get("cornerRadiusDp").getAsInt()
                                > 6;
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiSmoke.scrollUntilVisible(robot, "填满并裁切");
        UiSmoke.click(robot, "填满并裁切");
        UiSmoke.click(robot, "完整显示");
        robot.mouseMove(
                window.getX() + window.getWidth() * 3 / 4,
                window.getY() + window.getHeight() * 3 / 4);
        robot.mouseWheel(3);
        robot.delay(300);
        UiSmoke.click(robot, "居中", false);
        UiSmoke.click(robot, "右下");
        UiSmoke.awaitFileState(
                "background presets saved",
                10000,
                () -> {
                    try {
                        var settings = UiFunctionalChecks.settings(root);
                        return settings.get("backgroundFit").getAsString().equals("fit")
                                && settings.get("backgroundAlignment")
                                        .getAsString()
                                        .equals("bottomRight");
                    } catch (Exception pending) {
                        return false;
                    }
                });
        UiSmoke.scrollUntilVisible(robot, "选择背景图片");
        UiSmoke.click(robot, "选择背景图片");
        UiSmoke.chooseDirectory(root.resolve("background-fixture.png").toFile());
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-appearance.png"),
                true);
        UiSmoke.clickRail(robot, "Java 管理");
        UiSmoke.scrollUntilVisible(robot, "Java 8");
        UiSmoke.click(robot, "Java 8");
        UiSmoke.waitFor("打开任务中心");
        UiSmoke.click(robot, "打开任务中心");
        UiSmoke.waitFor("任务中心");
        UiSmoke.waitFor("安装 Java 8");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-tasks.png"),
                true);
        UiSmoke.click(robot, "取消任务 安装 Java 8");
        UiSmoke.click(robot, "确认取消");
        UiSmoke.waitFor("已结束");
        UiSmoke.click(robot, "关闭任务中心");
        UiSmoke.clickRail(robot, "日志");
        if (UiSmoke.hasControlStartingWith("下载任务"))
            throw new IllegalStateException("Tasks still embedded in logs page");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-logs.png"),
                true);
        System.out.println(
                "REFINEMENT_UI: JVM editing, GitHub empty-release, legal notices, appearance, tasks and logs checked");
    }
}
