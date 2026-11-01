// 负责验证测试窗口拖动、缩放和窗口控制。

// Exercises only this test window's native move, maximize, resize and minimize behavior.
import java.awt.*;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

final class WindowActionChecks {
    static void run(Robot robot, Window window, File output, Path root) throws Exception {
        Rectangle before = bounds(window);
        drag(robot, before.x + 800, before.y + 25, before.x + 880, before.y + 75);
        UiSmoke.awaitFileState(
                "window dragged",
                5000,
                () -> bounds(window).x != before.x || bounds(window).y != before.y);
        UiFunctionalChecks.record(
                "window.drag", "native window position changed after dragging blank title bar");
        Rectangle moved = bounds(window);
        UiSmoke.click(robot, "最大化");
        UiSmoke.awaitFileState("window maximized", 5000, () -> bounds(window).width > moved.width);
        UiSmoke.click(robot, "最大化");
        UiSmoke.awaitFileState("window restored", 5000, () -> bounds(window).width == moved.width);
        UiFunctionalChecks.record(
                "window.maximize-restore",
                "header button maximized then restored the native window");
        Rectangle normal = bounds(window);
        drag(
                robot,
                normal.x + normal.width - 2,
                normal.y + normal.height - 2,
                normal.x + 1000,
                normal.y + 640);
        UiSmoke.awaitFileState(
                "window resized by border",
                5000,
                () ->
                        bounds(window).width != normal.width
                                || bounds(window).height != normal.height);
        Rectangle resized = bounds(window);
        UiFunctionalChecks.require(
                resized.width >= 1000 && resized.height >= 640,
                "Native resize ignored minimum size");
        UiFunctionalChecks.record(
                "window.resize", "border drag changed size while keeping the minimum size");
        UiSmoke.click(robot, "最小化");
        UiSmoke.awaitFileState(
                "window minimized",
                5000,
                () ->
                        window instanceof Frame
                                && (((Frame) window).getExtendedState() & Frame.ICONIFIED) != 0);
        EventQueue.invokeAndWait(
                () -> {
                    ((Frame) window).setExtendedState(Frame.NORMAL);
                    window.toFront();
                    window.requestFocus();
                });
        UiSmoke.awaitFileState(
                "window restored from minimize",
                5000,
                () -> (((Frame) window).getExtendedState() & Frame.ICONIFIED) == 0);
        UiFunctionalChecks.record(
                "window.minimize-restore",
                "header minimize took effect; own native window restored");
        UiSmoke.capture(
                robot,
                window,
                new File(output.getParentFile(), "MoliFox-actions-window.png"),
                true);
    }

    private static Rectangle bounds(Window window) {
        AtomicReference<Rectangle> result = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(
                    () ->
                            result.set(
                                    new Rectangle(window.getLocationOnScreen(), window.getSize())));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return result.get();
    }

    private static void drag(Robot robot, int x, int y, int endX, int endY) {
        robot.mouseMove(x, y);
        robot.delay(250);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int step = 1; step <= 12; step++) {
            robot.mouseMove(x + (endX - x) * step / 12, y + (endY - y) * step / 12);
            robot.delay(35);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(400);
    }
}
