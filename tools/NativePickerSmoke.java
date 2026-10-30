// 负责实际打开系统文件、目录和保存窗口并验证返回的路径。

import dev.molifox.desktop.platform.dialogs.PickMode;
import dev.molifox.desktop.platform.dialogs.PickRequest;
import dev.molifox.desktop.platform.dialogs.WindowsFilePicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class NativePickerSmoke {
    public static void main(String[] args) throws Exception {
        Thread.currentThread()
                .setUncaughtExceptionHandler(
                        (thread, error) -> {
                            error.printStackTrace();
                            System.exit(1);
                        });
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path first = Files.writeString(root.resolve("中文 原理图 [1].litematic"), "first");
        Path second = Files.writeString(root.resolve("second.litematic"), "second");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var opened =
                    executor.submit(
                            () ->
                                    WindowsFilePicker.INSTANCE.pick(
                                            new PickRequest(
                                                    "选择要添加的原理图文件",
                                                    PickMode.File,
                                                    false,
                                                    List.of("litematic"),
                                                    "",
                                                    root),
                                            null));
            NativeDialogActions.select(List.of(first.toFile()));
            require(opened.get(15, TimeUnit.SECONDS).equals(List.of(first)), "single file");
            var multiple =
                    executor.submit(
                            () ->
                                    WindowsFilePicker.INSTANCE.pick(
                                            new PickRequest(
                                                    "选择多个原理图",
                                                    PickMode.File,
                                                    true,
                                                    List.of("litematic"),
                                                    "",
                                                    root),
                                            null));
            NativeDialogActions.select(List.of(first.toFile(), second.toFile()));
            require(
                    new java.util.HashSet<>(multiple.get(15, TimeUnit.SECONDS))
                            .equals(java.util.Set.of(first, second)),
                    "multiple files");
            var directory =
                    executor.submit(
                            () ->
                                    WindowsFilePicker.INSTANCE.pick(
                                            new PickRequest(
                                                    "选择游戏文件夹",
                                                    PickMode.Directory,
                                                    false,
                                                    List.of(),
                                                    "",
                                                    root),
                                            null));
            NativeDialogActions.select(List.of(root.toFile()));
            require(directory.get(15, TimeUnit.SECONDS).equals(List.of(root)), "directory");
            var save =
                    executor.submit(
                            () ->
                                    WindowsFilePicker.INSTANCE.pick(
                                            new PickRequest(
                                                    "保存启动器日志",
                                                    PickMode.Save,
                                                    false,
                                                    List.of("log"),
                                                    "MoliFox.log",
                                                    root),
                                            null));
            Path target = root.resolve("保存 日志.log");
            NativeDialogActions.select(List.of(target.toFile()));
            require(save.get(15, TimeUnit.SECONDS).equals(List.of(target)), "save path");
            require(!Files.exists(target), "picker must not write exported file itself");
            var cancelled =
                    executor.submit(
                            () ->
                                    WindowsFilePicker.INSTANCE.pick(
                                            new PickRequest(
                                                    "取消选择",
                                                    PickMode.File,
                                                    false,
                                                    List.of(),
                                                    "",
                                                    root),
                                            null));
            NativeDialogActions.cancel();
            require(cancelled.get(15, TimeUnit.SECONDS).isEmpty(), "cancel");
            System.out.println("NATIVE_PICKER: single/multiple/directory/save/cancel passed");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Native picker check failed: " + label);
    }
}
