// 验证高级参数可输入并保存、外观与关于页面，以及下载任务独立入口。

import java.awt.Robot;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;

final class RefinementActions {
    private static java.net.ServerSocket motdServer;

    static void seed(Path root) throws Exception {
        Path game = root.resolve(".minecraft/versions/smoke-saves");
        for (String folder :
                new String[] {"logs", "crash-reports", "screenshots", "shaderpacks", "schematics"})
            java.nio.file.Files.createDirectories(game.resolve(folder));
        Path world = game.resolve("saves/LocalWorld");
        java.nio.file.Files.createDirectories(world);
        java.nio.file.Files.writeString(world.resolve("level.dat"), "refinement-world");
        var image =
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var paint = image.createGraphics();
        paint.setColor(new java.awt.Color(0x39a8d6));
        paint.fillRect(0, 0, 16, 16);
        paint.dispose();
        javax.imageio.ImageIO.write(image, "png", world.resolve("icon.png").toFile());
        Path pack = game.resolve("resourcepacks/LocalPack.zip");
        java.nio.file.Files.createDirectories(pack.getParent());
        try (var zip =
                new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(pack))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("pack.png"));
            javax.imageio.ImageIO.write(image, "png", zip);
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            zip.write(
                    "{\"pack\":{\"pack_format\":15,\"description\":\"fixture\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
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
        if (UiSmoke.hasControlStartingWith("最大内存（MB）")
                || UiSmoke.hasControlStartingWith("初始内存（MB）")
                || UiSmoke.hasControlStartingWith("版本隔离"))
            throw new IllegalStateException("Removed instance controls remain");
        UiSmoke.click(robot, "继承全局内存策略");
        UiSmoke.click(robot, "手动分配");
        UiSmoke.setSlider(robot, "实例内存滑块", .08f);
        UiSmoke.click(robot, "保存设置");
        UiSmoke.click(robot, "开始");
        UiSmoke.click(robot, "管理");
        UiSmoke.waitFor("Java 与内存");
        checkLocalContent(robot, window, output, root);
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
        if (UiSmoke.hasControlStartingWith("最大内存（MB）")
                || UiSmoke.hasControlStartingWith("初始内存（MB）"))
            throw new IllegalStateException("Removed global memory inputs remain");
        UiSmoke.scrollUntilVisible(robot, "全局内存滑块");
        UiSmoke.setSlider(robot, "全局内存滑块", .1f);
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
        UiSmoke.click(robot, "游戏语言跟随启动器开关");
        UiSmoke.awaitFileState(
                "game language switch saved",
                10000,
                () -> {
                    try {
                        return UiFunctionalChecks.settings(root)
                                .get("gameLanguageFollowsLauncher")
                                .getAsBoolean();
                    } catch (Exception pending) {
                        return false;
                    }
                });
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
        UiSmoke.click(robot, "字体与缩放");
        checkFont(robot, root);
        UiSmoke.click(robot, "主题与效果");
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
        UiSmoke.click(robot, "开始");
        UiSmoke.click(robot, "管理");
        UiSmoke.waitFor("实例内存滑块");
        UiSmoke.setSlider(robot, "实例内存滑块", .09f);
        UiSmoke.click(robot, "保存设置");
        UiSmoke.click(robot, "打开任务中心");
        UiSmoke.waitFor("任务中心");
        UiSmoke.waitFor("安装 Java 8");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-tasks.png"),
                true);
        UiSmoke.click(robot, "取消任务 安装 Java 8");
        UiSmoke.click(robot, "继续任务");
        UiSmoke.waitFor("取消任务 安装 Java 8");
        UiSmoke.click(robot, "取消任务 安装 Java 8");
        UiSmoke.click(robot, "取消");
        UiSmoke.waitFor("已结束");
        UiSmoke.click(robot, "关闭任务中心");
        UiSmoke.click(robot, "设置");
        UiSmoke.clickRail(robot, "日志");
        if (UiSmoke.hasControlStartingWith("下载任务"))
            throw new IllegalStateException("Tasks still embedded in logs page");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-refinement-logs.png"),
                true);
        System.out.println(
                "REFINEMENT_UI: memory sliders, global isolation layout, language, font, local icons, save backup/restore/copy/delete, JVM editing, appearance and cancellation checked");
    }

    private static void checkFont(Robot robot, Path root) throws Exception {
        UiSmoke.scrollUntilVisible(robot, "选择字体");
        UiSmoke.click(robot, "选择字体");
        String family =
                java.util.Arrays.stream(
                                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                                        .getAvailableFontFamilyNames())
                        .filter(name -> name.contains("YaHei") || name.contains("微软雅黑"))
                        .findFirst()
                        .orElse("Dialog");
        UiSmoke.enterText("搜索字体", family);
        UiSmoke.click(robot, family);
        UiSmoke.awaitFileState(
                "global font saved",
                10000,
                () -> {
                    try {
                        return UiFunctionalChecks.settings(root)
                                .get("fontChoice")
                                .getAsString()
                                .equals("system:" + family);
                    } catch (Exception pending) {
                        return false;
                    }
                });
    }

    private static long backups(Path root) {
        try (var paths = java.nio.file.Files.list(root)) {
            return paths.filter(file -> file.toString().endsWith(".zip")).count();
        } catch (Exception pending) {
            return 0;
        }
    }

    private static void checkLocalContent(Robot robot, Window window, File output, Path root)
            throws Exception {
        Path game = root.resolve(".minecraft/versions/smoke-saves");
        Path world = game.resolve("saves/LocalWorld");
        Path backup = game.resolve(".molifox-saves-backups");
        UiSmoke.clickRail(robot, "资源包");
        UiSmoke.waitFor("LocalPack.zip");
        robot.delay(300);
        verifyIcon(robot, window);
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-local-pack-icon.png"),
                true);
        UiSmoke.clickRail(robot, "存档");
        UiSmoke.waitFor("LocalWorld");
        robot.delay(300);
        verifyIcon(robot, window);
        UiSmoke.clickTopButton(robot, "备份");
        UiSmoke.awaitFileState("save backup", 10000, () -> backups(backup) == 1);
        UiSmoke.waitFor("恢复");
        java.nio.file.Files.writeString(world.resolve("after-backup.txt"), "changed");
        UiSmoke.clickTopButton(robot, "恢复");
        UiSmoke.clickTopButton(robot, "覆盖并恢复");
        UiSmoke.awaitFileState(
                "save restored",
                10000,
                () -> !java.nio.file.Files.exists(world.resolve("after-backup.txt")));
        UiSmoke.clickTopButton(robot, "复制到…");
        UiSmoke.clickTopButton(robot, "开始复制");
        Path copied =
                root.resolve(".minecraft/versions/smoke-saves-target/saves/LocalWorld/level.dat");
        UiSmoke.awaitFileState(
                "save copied", 10000, () -> java.nio.file.Files.isRegularFile(copied));
        if (!java.nio.file.Files.readString(copied).equals("refinement-world"))
            throw new IllegalStateException("Copied world differs");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-local-world-icon.png"),
                true);
        UiSmoke.clickTopButton(robot, "删除");
        UiSmoke.clickTopButton(robot, "移入回收站");
        UiSmoke.awaitFileState("save removed", 10000, () -> !java.nio.file.Files.exists(world));
        if (!java.nio.file.Files.isRegularFile(copied))
            throw new IllegalStateException("Deleting source removed copied world");
        UiSmoke.clickRail(robot, "设置");
    }

    private static void verifyIcon(Robot robot, Window window) {
        var image =
                robot.createScreenCapture(
                        new java.awt.Rectangle(window.getLocationOnScreen(), window.getSize()));
        int pixels = 0;
        for (int x = 0; x < image.getWidth(); x++)
            for (int y = 0; y < image.getHeight(); y++)
                if ((image.getRGB(x, y) & 0xffffff) == 0x39a8d6) pixels++;
        if (pixels < 100)
            throw new IllegalStateException("Local content icon pixels missing: " + pixels);
    }
}
