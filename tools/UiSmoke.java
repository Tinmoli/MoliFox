// 负责通过桌面无障碍接口执行界面流程验收并保存截图。

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.imageio.ImageIO;

/** Captures only the MoliFox window in this JVM, using an isolated test data directory. */
public final class UiSmoke {
    private static Window mainWindow;

    /** 测试自己在 .work 里预置的两个内部隔离实例；存档/移动/隔离预览只操作它们。 */
    static final String MAIN_INSTANCE_ID = "3f6a1e40-2d38-4d6e-9c1a-5b8f7a9d0e11";

    static final String TARGET_INSTANCE_ID = "8c4b2f51-6a7d-4e93-b2c5-1d9e0f3a4b22";
    static final String MAIN_INSTANCE_NAME = "SmokeSaves";
    static final String TARGET_INSTANCE_NAME = "SmokeSavesTarget";

    private static Component findRenderLayer(Component component) {
        for (Class<?> type = component.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("org.jetbrains.skiko.SkiaLayer")) return component;
        if (component instanceof Container)
            for (Component child : ((Container) component).getComponents()) {
                Component found = findRenderLayer(child);
                if (found != null) return found;
            }
        return null;
    }

    private static boolean belongsToApp(Window window) {
        for (Window current = window; current != null; current = current.getOwner())
            if (current == mainWindow) return true;
        return false;
    }

    /** 窗口在屏幕上的真实矩形（Win32 物理像素）。只用于诊断打印： AWT 在本机使用的是逻辑像素，Robot 点击也按逻辑像素工作，混用会让点击落到别处。 */
    private static Rectangle windowRect(Window window) {
        try {
            com.sun.jna.platform.win32.WinDef.RECT rect =
                    new com.sun.jna.platform.win32.WinDef.RECT();
            if (!User32.INSTANCE.GetWindowRect(windowHandle(window), rect)) return null;
            Rectangle rectangle =
                    new Rectangle(
                            rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
            return rectangle.width > 0 && rectangle.height > 0 ? rectangle : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** 节点矩形（AWT 逻辑像素，和 Robot 点击同一坐标系）。 */
    static Rectangle componentBounds(AccessibleContext context) {
        try {
            var component = context.getAccessibleComponent();
            if (component == null || !component.isShowing()) return null;
            Point point = component.getLocationOnScreen();
            var size = component.getSize();
            if (point == null || size == null || size.width <= 0 || size.height <= 0) return null;
            return new Rectangle(point.x, point.y, size.width, size.height);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** 节点相对主窗口左边缘的横向位置，用来区分标题栏导航与页内左侧栏（与窗口放在哪里无关）。 */
    private static double relativeX(Rectangle bounds) {
        return bounds.x - mainWindow.getX();
    }

    /** 窗口被外部改分辨率挤到屏幕外时挪回可见区域，否则 Robot 点击会落到窗口外。 */
    private static void ensureWindowOnScreen() throws Exception {
        Rectangle screen =
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Rectangle window = new Rectangle(mainWindow.getLocationOnScreen(), mainWindow.getSize());
        if (screen.contains(window)) return;
        EventQueue.invokeAndWait(
                () -> {
                    mainWindow.setLocation(48, 48);
                    mainWindow.setSize(1180, 720);
                });
        Thread.sleep(300);
        System.out.println(
                "WINDOW_REPOSITIONED screen="
                        + screen
                        + " window="
                        + new Rectangle(mainWindow.getLocationOnScreen(), mainWindow.getSize()));
    }

    private static HWND windowHandle(Window window) {
        HWND handle = new HWND(Native.getComponentPointer(window));
        HWND root = User32.INSTANCE.GetAncestor(handle, 2);
        return root == null ? handle : root;
    }

    private static boolean appIsForeground() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        for (Window window : Window.getWindows()) {
            if (window.isShowing()
                    && belongsToApp(window)
                    && windowHandle(window).equals(foreground)) return true;
        }
        return false;
    }

    private static void ensureForeground() throws Exception {
        if (appIsForeground()) return;
        ensureWindowOnScreen();
        // 重新激活要放进循环里：窗口刚出现时 SetForegroundWindow 常被系统拒绝一次，
        // 之前只在循环外激活一次，偶尔会以「窗口拿不到焦点」失败。
        for (int attempt = 0; attempt < 60; attempt++) {
            EventQueue.invokeAndWait(
                    () -> {
                        mainWindow.setAlwaysOnTop(true);
                        mainWindow.toFront();
                        mainWindow.requestFocus();
                        TestWindowFocus.activate(windowHandle(mainWindow));
                    });
            if (appIsForeground()) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "Test window could not take focus; refusing to click or capture another application (own="
                        + windowHandle(mainWindow)
                        + ", foreground="
                        + User32.INSTANCE.GetForegroundWindow()
                        + ")");
    }

    static void chooseDirectory(File selection) throws Exception {
        choosePaths(List.of(selection));
    }

    static void choosePaths(List<File> selections) throws Exception {
        File selection = selections.get(0);
        Path ownedRoot =
                Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize();
        Path requested = selection.toPath().toAbsolutePath().normalize();
        if (!requested.startsWith(ownedRoot))
            throw new IllegalArgumentException(
                    "Picker fixture must be inside its isolated test directory");
        for (File file : selections) {
            if (!file.toPath().toAbsolutePath().normalize().startsWith(ownedRoot))
                throw new IllegalArgumentException(
                        "Picker fixture must be inside its test directory");
        }
        NativeDialogActions.select(selections);
    }

    private static AccessibleContext find(AccessibleContext context, String name, int depth) {
        return find(context, name, depth, true);
    }

    // first=true 取先序遍历第一个匹配；first=false 取最后一个，用于绕开顶部主导航里的同名项。
    private static AccessibleContext find(
            AccessibleContext context, String name, int depth, boolean first) {
        if (context == null || depth > 30) return null;
        AccessibleContext owner = null;
        String label = context.getAccessibleName();
        if (label != null
                && (name.equals(label) || label.startsWith(name + ", "))
                && context.getAccessibleComponent() != null
                && context.getAccessibleComponent().isShowing()) {
            if (first) return context;
            owner = context;
        }
        for (int i = 0; i < context.getAccessibleChildrenCount(); i++) {
            Accessible child = context.getAccessibleChild(i);
            AccessibleContext found =
                    child == null
                            ? null
                            : find(child.getAccessibleContext(), name, depth + 1, first);
            if (found != null) {
                if (first) return found;
                owner = found;
            }
        }
        return owner;
    }

    static AccessibleContext waitFor(String name) throws Exception {
        return waitFor(name, true);
    }

    static AccessibleContext waitFor(String name, boolean first) throws Exception {
        return waitFor(name, first, Integer.MAX_VALUE);
    }

    /** 与顶部主导航同名的元素（实例详情页左侧页签的「设置」等）用 x 上限区分： 主导航在标题栏里（x 远大于 300），页内二级导航贴左边。 */
    static AccessibleContext waitFor(String name, boolean first, int maximumX) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<AccessibleContext> match = new AtomicReference<>();
            EventQueue.invokeAndWait(
                    () -> {
                        List<AccessibleContext> matches = new ArrayList<>();
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                collectMatches(window.getAccessibleContext(), name, 0, matches);
                            }
                        if (!matches.isEmpty()) {
                            List<AccessibleContext> allowed = new ArrayList<>();
                            for (AccessibleContext candidate : matches) {
                                if (maximumX != Integer.MAX_VALUE) {
                                    Rectangle bounds = componentBounds(candidate);
                                    if (bounds == null || relativeX(bounds) > maximumX) continue;
                                }
                                allowed.add(candidate);
                            }
                            if (!allowed.isEmpty())
                                match.set(allowed.get(first ? 0 : allowed.size() - 1));
                        }
                    });
            if (match.get() != null) return match.get();
            Thread.sleep(250);
        }
        throw new IllegalStateException("UI control not found: " + name);
    }

    private static void collectMatches(
            AccessibleContext context, String name, int depth, List<AccessibleContext> sink) {
        if (context == null || depth > 30) return;
        String label = context.getAccessibleName();
        if (label != null
                && (name.equals(label) || label.startsWith(name + ", "))
                && context.getAccessibleComponent() != null
                && context.getAccessibleComponent().isShowing()) sink.add(context);
        for (int index = 0; index < context.getAccessibleChildrenCount(); index++) {
            Accessible child = context.getAccessibleChild(index);
            if (child != null) collectMatches(child.getAccessibleContext(), name, depth + 1, sink);
        }
    }

    private static void walk(AccessibleContext context, int depth, List<AccessibleContext> sink) {
        if (context == null || depth > 30) return;
        sink.add(context);
        for (int index = 0; index < context.getAccessibleChildrenCount(); index++) {
            Accessible child = context.getAccessibleChild(index);
            if (child != null) walk(child.getAccessibleContext(), depth + 1, sink);
        }
    }

    private static boolean hasEditableText(AccessibleContext context) {
        try {
            return context.getAccessibleEditableText() != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String clickAction(AccessibleContext context) {
        var action = context.getAccessibleAction();
        if (action == null) return null;
        for (int index = 0; index < action.getAccessibleActionCount(); index++) {
            String description = action.getAccessibleActionDescription(index);
            if (description != null
                    && (description.toLowerCase(java.util.Locale.ROOT).contains("click")
                            || description.contains("点击")
                            || description.contains("单击"))) return description;
        }
        return null;
    }

    private static boolean isClickable(AccessibleContext context) {
        try {
            return clickAction(context) != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 等待某个无障碍名字消失：用于「切回列表视图后网格卡片入口应当消失」这类负向断言。 */
    static void waitUntilGone(String name) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<Boolean> present = new AtomicReference<>(false);
            EventQueue.invokeAndWait(
                    () -> {
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                if (find(window.getAccessibleContext(), name, 0, true) != null)
                                    present.set(true);
                            }
                    });
            if (!present.get()) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("UI control still present: " + name);
    }

    static void click(Robot robot, String name) throws Exception {
        click(robot, name, true);
    }

    static void click(Robot robot, String name, boolean first) throws Exception {
        click(robot, name, first, Integer.MAX_VALUE);
    }

    /** 点击贴左边栏的页签（实例详情页/设置页侧栏），避开标题栏里同名的顶部导航。 */
    static void clickRail(Robot robot, String name) throws Exception {
        click(robot, name, true, 200);
    }

    static void click(Robot robot, String name, boolean first, int maximumX) throws Exception {
        ensureForeground();
        AccessibleContext context = waitFor(name, first, maximumX);
        activate(robot, context, name);
    }

    static void activate(Robot robot, AccessibleContext context, String name) throws Exception {
        AtomicReference<Point> center = new AtomicReference<>();
        AtomicReference<String> info = new AtomicReference<>("");
        EventQueue.invokeAndWait(
                () -> {
                    Rectangle bounds = componentBounds(context);
                    if (bounds == null)
                        throw new IllegalStateException(
                                "Test control has no visible bounds: " + name);
                    if (!context.getAccessibleStateSet()
                            .contains(javax.accessibility.AccessibleState.ENABLED))
                        throw new IllegalStateException(
                                "Refusing to treat a disabled control as a completed click: "
                                        + name);
                    center.set(new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
                    info.set(
                            context.getAccessibleRole()
                                    + " enabled="
                                    + context.getAccessibleStateSet()
                                            .contains(javax.accessibility.AccessibleState.ENABLED));
                });
        System.out.println("CLICK " + name + " [" + info.get() + "] @ " + center.get());
        if (!isWithinOwnedWindow(center.get()))
            throw new IllegalStateException(
                    "Refusing to click outside the owned test window: " + name);
        AtomicReference<Boolean> activated = new AtomicReference<>(false);
        EventQueue.invokeAndWait(
                () -> {
                    // Drive the same UI action through accessibility when available. This avoids
                    // dropped native clicks after focus changes and never touches another process.
                    var action = context.getAccessibleAction();
                    if (!javax.accessibility.AccessibleRole.TEXT.equals(context.getAccessibleRole())
                            && action != null) {
                        for (int index = 0; index < action.getAccessibleActionCount(); index++) {
                            String description = action.getAccessibleActionDescription(index);
                            if (description != null
                                    && (description
                                                    .toLowerCase(java.util.Locale.ROOT)
                                                    .contains("click")
                                            || description.contains("点击")
                                            || description.contains("单击"))) {
                                System.out.println("UI_ACTION " + name + " " + description);
                                activated.set(action.doAccessibleAction(index));
                                break;
                            }
                        }
                    }
                });
        if (!activated.get()) {
            robot.mouseMove(center.get().x, center.get().y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.delay(550);
    }

    /** 版本库工具行的「列表 / 网格」切换按钮。界面已补无障碍名字（列表视图 / 网格视图）， 优先按名字激活；名字缺失时再退回旧做法：以「收藏」为锚点按横坐标取右侧可点节点。 */
    private static void clickViewMode(Robot robot, boolean grid) throws Exception {
        String named = grid ? "网格视图" : "列表视图";
        for (int attempt = 0; attempt < 12; attempt++) {
            AtomicReference<AccessibleContext> byName = new AtomicReference<>();
            EventQueue.invokeAndWait(
                    () -> {
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                List<AccessibleContext> nodes = new ArrayList<>();
                                walk(window.getAccessibleContext(), 0, nodes);
                                for (AccessibleContext node : nodes) {
                                    if (named.equals(node.getAccessibleName())
                                            && isClickable(node)) {
                                        byName.set(node);
                                        return;
                                    }
                                }
                            }
                    });
            if (byName.get() != null) {
                ensureForeground();
                activate(robot, byName.get(), named);
                robot.delay(650);
                return;
            }
            Thread.sleep(250);
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            AtomicReference<AccessibleContext> picked = new AtomicReference<>();
            AtomicReference<String> detail = new AtomicReference<>("anchor missing");
            EventQueue.invokeAndWait(
                    () -> {
                        AccessibleContext anchor = null;
                        List<AccessibleContext> unnamed = new ArrayList<>();
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                List<AccessibleContext> nodes = new ArrayList<>();
                                walk(window.getAccessibleContext(), 0, nodes);
                                for (AccessibleContext node : nodes) {
                                    if (anchor == null && "收藏".equals(node.getAccessibleName()))
                                        anchor = node;
                                    if (node.getAccessibleName() == null && isClickable(node))
                                        unnamed.add(node);
                                }
                            }
                        if (anchor == null) return;
                        Rectangle base = componentBounds(anchor);
                        if (base == null) return;
                        List<AccessibleContext> candidates = new ArrayList<>();
                        for (AccessibleContext node : unnamed) {
                            Rectangle bounds = componentBounds(node);
                            if (bounds == null) continue;
                            if (Math.abs(bounds.getCenterY() - base.getCenterY()) > 22) continue;
                            if (bounds.x + bounds.width < base.x + base.width) continue;
                            candidates.add(node);
                        }
                        candidates.sort(
                                java.util.Comparator.comparingInt(node -> componentBounds(node).x));
                        detail.set("candidates=" + candidates.size());
                        if (candidates.size() >= 2) picked.set(candidates.get(grid ? 1 : 0));
                    });
            if (picked.get() != null) {
                ensureForeground();
                activate(robot, picked.get(), "视图切换" + (grid ? "网格" : "列表"));
                robot.delay(650);
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(
                "Versions view toggle not found: " + (grid ? "grid" : "list"));
    }

    private static boolean isWithinOwnedWindow(Point point) {
        for (Window window : Window.getWindows())
            if (window.isShowing()
                    && belongsToApp(window)
                    && new Rectangle(window.getLocationOnScreen(), window.getSize())
                            .contains(point)) return true;
        return false;
    }

    static void scrollUntilVisible(Robot robot, String name) throws Exception {
        ensureForeground();
        for (int attempt = 0; attempt < 14; attempt++) {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            AtomicReference<Integer> direction = new AtomicReference<>(attempt == 0 ? -100 : 3);
            EventQueue.invokeAndWait(
                    () -> {
                        AccessibleContext context =
                                find(mainWindow.getAccessibleContext(), name, 0);
                        if (context != null) {
                            Rectangle bounds = componentBounds(context);
                            if (bounds != null) {
                                direction.set(
                                        bounds.getCenterY() < mainWindow.getY() + 140 ? -3 : 3);
                                visible.set(
                                        isWithinOwnedWindow(
                                                        new Point(
                                                                (int) bounds.getCenterX(),
                                                                (int) bounds.getCenterY()))
                                                && bounds.getCenterY() > mainWindow.getY() + 140
                                                && bounds.getCenterY()
                                                        < mainWindow.getY()
                                                                + mainWindow.getHeight()
                                                                - 45);
                            }
                        }
                    });
            if (visible.get()) return;
            Point point = mainWindow.getLocationOnScreen();
            robot.mouseMove(
                    point.x + mainWindow.getWidth() * 3 / 4,
                    point.y + mainWindow.getHeight() * 3 / 4);
            robot.mouseWheel(direction.get());
            robot.delay(220);
        }
        throw new IllegalStateException("Scrollable test control not visible: " + name);
    }

    static void setSlider(Robot robot, String name, float fraction) throws Exception {
        scrollUntilVisible(robot, name);
        AccessibleContext context = waitFor(name);
        AtomicReference<Point> target = new AtomicReference<>();
        EventQueue.invokeAndWait(
                () -> {
                    Rectangle bounds = componentBounds(context);
                    if (bounds == null)
                        throw new IllegalStateException(
                                "Test slider has no visible bounds: " + name);
                    target.set(
                            new Point(
                                    bounds.x + (int) (bounds.width * fraction),
                                    bounds.y + bounds.height / 2));
                });
        if (!isWithinOwnedWindow(target.get()))
            throw new IllegalStateException("Refusing to click outside the owned test window");
        ensureForeground();
        robot.mouseMove(target.get().x, target.get().y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(600);
    }

    /** 输入框名字现在直接落在可编辑节点上；探测时优先按名字找可编辑节点， 如果名字仍被静态文本占住（旧布局或无标签控件），退回页面中唯一的可编辑节点。 */
    static AccessibleContext waitForEditable(String fieldName) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<AccessibleContext> chosen = new AtomicReference<>();
            AtomicReference<String> fallback = new AtomicReference<>("");
            EventQueue.invokeAndWait(
                    () -> {
                        List<AccessibleContext> editable = new ArrayList<>();
                        AccessibleContext named = null;
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                List<AccessibleContext> nodes = new ArrayList<>();
                                walk(window.getAccessibleContext(), 0, nodes);
                                for (AccessibleContext node : nodes) {
                                    if (!hasEditableText(node)) continue;
                                    editable.add(node);
                                    String label = node.getAccessibleName();
                                    if (label != null
                                            && (fieldName.equals(label)
                                                    || label.startsWith(fieldName + ", ")))
                                        named = node;
                                }
                            }
                        if (named != null) chosen.set(named);
                        else if (editable.size() == 1) {
                            fallback.set("FALLBACK_EDITABLE");
                            chosen.set(editable.get(0));
                        }
                    });
            if (chosen.get() != null) {
                if (!fallback.get().isBlank()) System.out.println(fallback.get() + " " + fieldName);
                return chosen.get();
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(
                "Test field has no editable accessibility node: " + fieldName);
    }

    static void enterText(String fieldName, String value) throws Exception {
        AccessibleContext context = waitForEditable(fieldName);
        EventQueue.invokeAndWait(
                () -> {
                    var editor = context.getAccessibleEditableText();
                    if (editor == null)
                        throw new IllegalStateException(
                                "Test field has no editable accessibility action: " + fieldName);
                    editor.setTextContents(value);
                });
        Thread.sleep(300);
    }

    static void capture(Robot robot, Window target, File output, boolean trimChrome)
            throws Exception {
        ensureForeground();
        Rectangle bounds = new Rectangle(target.getLocationOnScreen(), target.getSize());
        output.getParentFile().mkdirs();
        AtomicReference<byte[]> rendered = new AtomicReference<>();
        EventQueue.invokeAndWait(
                () -> {
                    Component layer = findRenderLayer(target);
                    if (layer == null) return;
                    Object bitmap = null;
                    Object image = null;
                    Object encoded = null;
                    try {
                        bitmap = layer.getClass().getMethod("screenshot").invoke(layer);
                        if (bitmap == null) return;
                        ClassLoader loader = layer.getClass().getClassLoader();
                        Class<?> imageType =
                                Class.forName("org.jetbrains.skia.Image", true, loader);
                        Class<?> bitmapType =
                                Class.forName("org.jetbrains.skia.Bitmap", true, loader);
                        Object companion = imageType.getField("Companion").get(null);
                        image =
                                companion
                                        .getClass()
                                        .getMethod("makeFromBitmap", bitmapType)
                                        .invoke(companion, bitmap);
                        Class<?> formatType =
                                Class.forName(
                                        "org.jetbrains.skia.EncodedImageFormat", true, loader);
                        encoded =
                                imageType
                                        .getMethod("encodeToData", formatType, int.class, int.class)
                                        .invoke(
                                                image,
                                                formatType.getField("PNG").get(null),
                                                100,
                                                6);
                        rendered.set(
                                (byte[]) encoded.getClass().getMethod("getBytes").invoke(encoded));
                    } catch (ReflectiveOperationException failure) {
                        System.out.println(
                                "CAPTURE_RENDER_UNAVAILABLE " + failure.getClass().getSimpleName());
                    } finally {
                        for (Object resource : new Object[] {encoded, image, bitmap})
                            if (resource instanceof AutoCloseable)
                                try {
                                    ((AutoCloseable) resource).close();
                                } catch (Exception ignored) {
                                }
                    }
                });
        if (rendered.get() != null) Files.write(output.toPath(), rendered.get());
        else ImageIO.write(robot.createScreenCapture(bounds), "png", output);
        System.out.println(
                "CAPTURED " + output.getName() + " " + bounds.width + "x" + bounds.height);
    }

    /** 从落盘的 launcher.json 里取出某个版本的实例条目。 */
    static com.google.gson.JsonObject savedInstance(Path configuration, String versionId)
            throws Exception {
        var instances =
                com.google.gson.JsonParser.parseString(Files.readString(configuration))
                        .getAsJsonObject()
                        .getAsJsonArray("instances");
        for (var element : instances) {
            var instance = element.getAsJsonObject();
            if (versionId.equals(instance.get("versionId").getAsString())) return instance;
        }
        throw new IllegalStateException("Instance not found in saved configuration: " + versionId);
    }

    static void printTree(AccessibleContext context, int depth) {
        if (context == null || depth > 22) return;
        String name = context.getAccessibleName();
        if (name != null && !name.isBlank())
            System.out.println("  ".repeat(depth) + context.getAccessibleRole() + " : " + name);
        for (int i = 0; i < context.getAccessibleChildrenCount(); i++) {
            Accessible child = context.getAccessibleChild(i);
            if (child != null) printTree(child.getAccessibleContext(), depth + 1);
        }
    }

    /** 内部实例的 JSON：字段与 GameInstance 一一对应，缺字段会被 Gson 置空而在界面上 NPE。 */
    private static String seededInstance(
            String id, String name, String versionId, String createdAt) {
        return """
            {
              "id": "%s",
              "name": "%s",
              "gameVersion": "1.20.1",
              "versionId": "%s",
              "loader": "Vanilla",
              "loaderVersion": "",
              "createdAt": "%s",
              "lastPlayed": null,
              "playTimeSeconds": 0,
              "installed": true,
              "memoryMb": 0,
              "javaPath": "",
              "externalProfile": false,
              "packFile": "",
              "gameDirectoryId": null,
              "isolation": "Enabled",
              "initialMemoryMb": 0,
              "width": 0,
              "height": 0,
              "windowMode": "inherit",
              "extraJvmArgs": "",
              "notes": "",
              "favorite": false,
              "managedGameDirectory": false
            }
            """
                .formatted(id, name, versionId, createdAt);
    }

    /**
     * 预置配置的 settings 必须是完整的一组默认值：Gson 不会调用 Kotlin 默认参数， 省略的字段会变成 null/0，界面读 pages、advancedLaunch
     * 时会直接出错。
     */
    private static String seededConfiguration(Path minecraftRoot) {
        String settings =
                """
            {
              "gameRoot": %s,
              "gameRootSemantics": 1,
              "javaPath": "",
              "memoryMb": 3840,
              "width": 1280,
              "height": 720,
              "extraJvmArgs": "",
              "advancedLaunch": {
                "extraGameArgs": "",
                "environmentVariables": {},
                "preLaunchCommand": "",
                "postExitCommand": "",
                "commandTimeoutSeconds": 60,
                "launcherVisibility": "",
                "closeLauncherAfterGameExit": false
              },
              "downloadSource": "official",
              "maxConcurrent": 8,
              "microsoftClientId": "",
              "darkTheme": false,
              "minimizeOnLaunch": false,
              "isolateVersions": true,
              "initialMemoryMb": 512,
              "fullscreen": false,
              "themeMode": "",
              "uiScale": 1.0,
              "backgroundPath": "",
              "backgroundRevision": 0,
              "backgroundOpacity": 0.7,
              "glassEnabled": true,
              "surfaceOpacity": 0.9,
              "backgroundBlur": 16.0,
              "fontChoice": "",
              "fontScale": 1.0,
              "customFonts": [],
              "pages": {
                "home": {"showStatistics": true, "showRecent": true, "recentCount": 5},
                "versions": {"view": "list", "sort": "recent", "favoritesFirst": true, "showNotes": true},
                "downloads": {"compactCards": false, "showAuthors": true, "showDescriptions": true},
                "logs": {"defaultTab": "tasks", "fontSize": 12, "autoScroll": true},
                "showAdvancedLaunchOptions": true
              },
              "accentColor": "sakura",
              "animationsEnabled": true,
              "titlebarTransparent": false,
              "windowTransparent": false,
              "language": "zh-CN",
              "updateManifestUrl": "",
              "autoCheckUpdates": false,
              "versionListSource": "official",
              "contentDownloadSource": "modrinth",
              "downloadCachePath": "",
              "proxyEnabled": false,
              "proxyMode": "system",
              "proxyType": "http",
              "proxyHost": "",
              "proxyPort": 0,
              "curseForgeApiKey": "",
              "gameWindowMode": "custom"
            }
            """
                        .formatted(new com.google.gson.Gson().toJson(minecraftRoot.toString()));
        String main =
                seededInstance(
                        MAIN_INSTANCE_ID,
                        MAIN_INSTANCE_NAME,
                        "smoke-saves",
                        java.time.Instant.now().minusSeconds(60).toString());
        String target =
                seededInstance(
                        TARGET_INSTANCE_ID,
                        TARGET_INSTANCE_NAME,
                        "smoke-saves-target",
                        java.time.Instant.now().minusSeconds(3600).toString());
        return "{\"schemaVersion\":1,\"settings\":"
                + settings
                + ",\"accounts\":[],\"instances\":["
                + main
                + ","
                + target
                + "],\"gameDirectories\":[],\"activeGameDirectoryId\":null}";
    }

    /** 首次启动应用之前写入配置与实例目录；这些内容全部位于构建任务自己的 .work 沙箱。 */
    private static void seedInternalInstances(Path testRoot) throws Exception {
        Path minecraftRoot = testRoot.resolve(".minecraft").toAbsolutePath().normalize();
        for (String id : List.of(MAIN_INSTANCE_ID, TARGET_INSTANCE_ID)) {
            Path directory =
                    minecraftRoot
                            .resolve("versions")
                            .resolve(
                                    id.equals(MAIN_INSTANCE_ID)
                                            ? "smoke-saves"
                                            : "smoke-saves-target");
            Files.createDirectories(directory.resolve("saves"));
            Files.writeString(directory.resolve("instance-marker.txt"), "internal fixture " + id);
        }
        Path profile = testRoot.resolve("profile");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("launcher.json"), seededConfiguration(minecraftRoot));
    }

    private static long countFiles(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) return 0;
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String firstFile(Path directory, String suffix) throws Exception {
        if (!Files.isDirectory(directory)) return "<none>";
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .sorted()
                    .findFirst()
                    .orElse("<none>");
        }
    }

    static void awaitFileState(
            String step, long timeoutMillis, java.util.function.BooleanSupplier condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(150);
        }
        throw new IllegalStateException("Filesystem state not reached: " + step);
    }

    static boolean hasControlStartingWith(String prefix) throws Exception {
        AtomicReference<Boolean> present = new AtomicReference<>(false);
        EventQueue.invokeAndWait(
                () -> {
                    for (Window window : Window.getWindows())
                        if (window.isShowing() && belongsToApp(window)) {
                            List<AccessibleContext> nodes = new ArrayList<>();
                            walk(window.getAccessibleContext(), 0, nodes);
                            for (AccessibleContext node : nodes) {
                                String label = node.getAccessibleName();
                                if (label != null && label.startsWith(prefix)) {
                                    present.set(true);
                                    return;
                                }
                            }
                        }
                });
        return present.get();
    }

    /** 同名按钮可能同时出现在存档行与备份行，按屏幕纵坐标取最上面的那个。 */
    static void clickTopButton(Robot robot, String name) throws Exception {
        ensureForeground();
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<AccessibleContext> best = new AtomicReference<>();
            AtomicReference<Integer> bestY = new AtomicReference<>(Integer.MAX_VALUE);
            AtomicReference<Integer> bestX = new AtomicReference<>(Integer.MAX_VALUE);
            EventQueue.invokeAndWait(
                    () -> {
                        for (Window window : Window.getWindows())
                            if (window.isShowing() && belongsToApp(window)) {
                                List<AccessibleContext> nodes = new ArrayList<>();
                                walk(window.getAccessibleContext(), 0, nodes);
                                for (AccessibleContext node : nodes) {
                                    if (!javax.accessibility.AccessibleRole.PUSH_BUTTON.equals(
                                            node.getAccessibleRole())) continue;
                                    String label = node.getAccessibleName();
                                    if (label == null
                                            || !(name.equals(label)
                                                    || label.startsWith(name + ", "))) continue;
                                    Rectangle bounds = componentBounds(node);
                                    if (bounds == null) continue;
                                    if (bounds.y < bestY.get()
                                            || (bounds.y == bestY.get()
                                                    && bounds.x < bestX.get())) {
                                        best.set(node);
                                        bestY.set(bounds.y);
                                        bestX.set(bounds.x);
                                    }
                                }
                            }
                    });
            if (best.get() != null) {
                activate(robot, best.get(), name + "[top button]");
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("UI button not found: " + name);
    }

    /**
     * 界面真实操作验收：存档页（搜索/刷新/复制对话框/备份/恢复覆盖/删除存档/删除备份）、隔离切换预览（只取消）、
     * 移动实例预览（选空目录后只取消）。全部在预置的内部隔离实例上执行，不启动游戏、不下载。
     */
    private static void exerciseInstanceExtras(
            Robot robot, Window target, File output, Path testRoot, Path fixture) throws Exception {
        Path mainInstance = testRoot.resolve(".minecraft/versions/smoke-saves");
        Path targetInstance = testRoot.resolve(".minecraft/versions/smoke-saves-target");
        Path saves = mainInstance.resolve("saves");
        Path backups = mainInstance.resolve(".molifox-saves-backups");
        Path world = saves.resolve("SmokeWorld");
        Path movedCopy = targetInstance.resolve("saves").resolve("SmokeWorld");
        Path moveTarget = testRoot.resolve("move-target");
        Files.createDirectories(moveTarget);
        String fixtureName = fixture.getFileName().toString();
        // 从顶部导航回实例列表：不依赖保存实例后落在哪一页，也避免窗口被其它程序挪动后的坐标假设。
        click(robot, "实例");
        waitFor("游戏实例");
        click(robot, "移除游戏目录");
        waitFor("移除目录「" + fixtureName + "」？");
        clickTopButton(robot, "移除目录记录");
        waitFor("启动 " + MAIN_INSTANCE_NAME);
        click(robot, "更多操作");
        click(robot, "管理实例");
        waitFor("基本信息");

        if (hasControlStartingWith("切换…"))
            throw new IllegalStateException("Per-instance isolation entry remains");
        if (!UiFunctionalChecks.settings(testRoot).get("isolateVersions").getAsBoolean())
            throw new IllegalStateException("Expected global version isolation");

        // 2) 移动实例：目录选择器选 .work 下的空目录，预览出现后取消，且不得写目标目录。
        click(robot, "更多操作");
        click(robot, "移动到其他游戏目录…");
        chooseDirectory(moveTarget.toFile());
        waitFor("移动预览");
        waitFor("需要复制");
        waitFor(moveTarget.toString());
        AccessibleContext moveConfirm = waitFor("开始移动");
        if (!moveConfirm.getAccessibleComponent().isEnabled())
            throw new IllegalStateException(
                    "Move dialog confirm disabled for an empty destination");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-instance-move.png"), true);
        clickTopButton(robot, "取消");
        waitUntilGone("移动预览");
        if (Files.exists(moveTarget.resolve("instances")))
            throw new IllegalStateException(
                    "Cancelled move preview wrote " + moveTarget.resolve("instances"));
        System.out.println(
                "MOVE_DIALOG_OK destination=" + moveTarget + " cancelled=true wroteFiles=false");

        // 3) 导出整合包：只验证入口与字段。预览会联网核对哈希，不联网时不断言成功。
        click(robot, "更多操作");
        click(robot, "导出整合包…");
        waitFor("导出整合包");
        waitFor("目标文件");
        waitFor("整合包版本");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-mrpack-export.png"), true);
        clickTopButton(robot, "取消");
        waitUntilGone("导出整合包");
        System.out.println(
                "MRPACK_EXPORT_DIALOG_OK opened=true networkPreviewResultNotAsserted=true");

        // 4) 存档页：空态 → 真实世界目录 → 刷新出现行 → 复制对话框 → 备份/恢复/删除。
        clickRail(robot, "存档");
        waitFor("搜索存档名称");
        waitFor("刷新存档列表");
        waitFor("打开存档文件夹");
        waitFor("这个实例还没有存档");
        waitFor("0 个存档 · 0 个备份 · " + saves);
        waitFor("还没有备份。存档行末尾的「备份」会把整个世界打包到这里。");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "smoke-original-level");
        Files.writeString(world.resolve("session.lock"), "smoke-session-lock");
        clickTopButton(robot, "刷新存档列表");
        waitFor("1 个存档 · 0 个备份 · " + saves);
        waitFor("SmokeWorld");
        // 有行以后才能验证搜索过滤：无匹配显示专用空态，清除后行回来。
        enterText("搜索存档名称", "NoSuchWorld");
        waitFor("没有匹配的存档");
        clickTopButton(robot, "清除搜索");
        waitFor("SmokeWorld");
        waitFor("复制到…");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-saves.png"), true);

        clickTopButton(robot, "复制到…");
        waitFor("复制存档");
        waitFor("目标实例");
        waitFor(TARGET_INSTANCE_NAME + " · 1.20.1");
        waitFor("新名称（可留空沿用原名）");
        AccessibleContext copyConfirm = waitFor("开始复制");
        if (!copyConfirm.getAccessibleComponent().isEnabled())
            throw new IllegalStateException(
                    "Copy save dialog confirm disabled with an isolated target");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-save-copy-dialog.png"),
                true);
        clickTopButton(robot, "取消");
        waitUntilGone("复制存档");
        if (Files.exists(movedCopy))
            throw new IllegalStateException(
                    "Cancelled copy dialog wrote a save into the target instance");
        System.out.println(
                "COPY_SAVE_DIALOG_OK target="
                        + TARGET_INSTANCE_NAME
                        + " cancelled=true wroteFiles=false");

        clickTopButton(robot, "备份");
        awaitFileState("save backup zip", 30000, () -> countFiles(backups, ".zip") == 1);
        waitFor("1 个存档 · 1 个备份 · " + saves);
        waitFor("恢复");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-saves-backups.png"), true);
        System.out.println("SAVE_BACKUP_OK archive=" + firstFile(backups, ".zip"));

        // 备份后改动世界，恢复必须把改动覆盖掉（真实文件结果，而非只看界面提示）。
        Files.writeString(world.resolve("changed-after-backup.txt"), "changed-after-backup");
        clickTopButton(robot, "恢复");
        waitFor("覆盖现有存档？");
        waitFor("覆盖并恢复");
        clickTopButton(robot, "覆盖并恢复");
        awaitFileState(
                "restore removes the post-backup marker",
                30000,
                () -> !Files.exists(world.resolve("changed-after-backup.txt")));
        awaitFileState(
                "restore writes a .replaced.zip backup",
                30000,
                () -> countFiles(backups, ".replaced.zip") >= 1);
        if (!Files.readString(world.resolve("level.dat")).equals("smoke-original-level"))
            throw new IllegalStateException("Restored level.dat content does not match the backup");
        if (!Files.isRegularFile(world.resolve("session.lock")))
            throw new IllegalStateException("Restored world is missing session.lock");
        waitFor("1 个存档 · 2 个备份 · " + saves);
        System.out.println(
                "SAVE_RESTORE_OK markerGone=true levelDatRestored=true archives="
                        + countFiles(backups, ".zip"));

        clickTopButton(robot, "删除");
        waitFor("移入回收站？");
        waitFor("「SmokeWorld」会移入回收站，可从回收站恢复。");
        clickTopButton(robot, "移入回收站");
        awaitFileState("save moved to recycle bin", 30000, () -> !Files.exists(world));
        waitFor("0 个存档 · 2 个备份 · " + saves);
        System.out.println("SAVE_DELETE_OK worldGone=true");

        // 现在只剩备份行：先删除最上面的 .replaced.zip，再核对备份区与计数。
        clickTopButton(robot, "删除");
        waitFor("删除这个备份？");
        clickTopButton(robot, "移入回收站");
        awaitFileState(
                "backup moved to recycle bin", 30000, () -> countFiles(backups, ".zip") == 1);
        waitFor("0 个存档 · 1 个备份 · " + saves);
        if (countFiles(backups, ".replaced.zip") != 0)
            throw new IllegalStateException(
                    "Backup row did not delete the newest .replaced.zip archive");
        if (countFiles(backups, ".part") != 0)
            throw new IllegalStateException("Backup directory left a .part file behind");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-saves-after-delete.png"),
                true);
        System.out.println("SAVE_BACKUP_DELETE_OK remaining=" + firstFile(backups, ".zip"));

        // 回到实例列表并把外部游戏目录重新加回来，后续启动流程继续使用它。
        click(robot, "实例");
        waitFor("游戏实例");
        click(robot, "选择游戏目录");
        chooseDirectory(fixture.toFile());
        waitFor("启动 1.20.1");
        System.out.println(
                "NEW_UI_STEPS_OK instance-saves isolation-change move-dialog saves-dir=" + saves);
    }

    /** A small opt-in path for the reported defects, independent from the full regression flow. */
    private static void seedReview(Path root) throws Exception {
        Path config = root.resolve("profile/launcher.json");
        var json =
                com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        String texture = "https://textures.minecraft.net/texture/" + "a".repeat(64);
        var account = new com.google.gson.JsonObject();
        String accountId = "2f6a1e40-2d38-4d6e-9c1a-5b8f7a9d0e11";
        account.addProperty("id", accountId);
        account.addProperty("type", "Microsoft");
        account.addProperty("name", "SkinReview");
        account.addProperty("uuid", "1".repeat(32));
        account.addProperty("skinUrl", texture.replace("https:", "http:"));
        json.getAsJsonArray("accounts").add(account);
        json.addProperty("activeAccountId", accountId);
        json.addProperty("selectedInstanceId", MAIN_INSTANCE_ID);
        json.getAsJsonObject("settings").addProperty("autoMemory", false);
        json.getAsJsonArray("instances").get(0).getAsJsonObject().addProperty("loader", "Fabric");
        json.getAsJsonArray("instances")
                .get(0)
                .getAsJsonObject()
                .addProperty("loaderVersion", "0.19.5");
        Files.writeString(config, new com.google.gson.Gson().toJson(json));
        var skin =
                new java.awt.image.BufferedImage(
                        64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var drawing = skin.createGraphics();
        drawing.drawImage(
                ImageIO.read(UiSmoke.class.getResourceAsStream("/icons/loaders/steve.png")),
                0,
                0,
                null);
        drawing.dispose();
        for (int x = 8; x < 16; x++) for (int y = 8; y < 16; y++) skin.setRGB(x, y, 0xff36b5a5);
        String key =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                texture.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        Path cached = root.resolve("profile/cache/skins/" + key + ".png");
        Files.createDirectories(cached.getParent());
        ImageIO.write(skin, "png", cached.toFile());
    }

    private static void reviewFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        ensureForeground();
        var home =
                robot.createScreenCapture(
                        new Rectangle(target.getLocationOnScreen(), target.getSize()));
        int skinPixels = 0;
        for (int x = 0; x < home.getWidth(); x++)
            for (int y = 0; y < home.getHeight(); y++)
                if ((home.getRGB(x, y) & 0xffffff) == 0x36b5a5) skinPixels++;
        if (skinPixels < 100)
            throw new IllegalStateException("Real skin pixels did not replace the Steve fallback");
        click(robot, "设置");
        waitFor("自动分配内存开关");
        click(robot, "自动分配内存开关");
        click(robot, "保存设置");
        robot.delay(300);
        var settings =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("settings");
        if (!settings.get("autoMemory").getAsBoolean())
            throw new IllegalStateException("Automatic memory did not persist from the UI");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-review-memory.png"), true);
        click(robot, "实例");
        waitFor("更多操作");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-review-instances.png"),
                true);
        click(robot, "更多操作");
        click(robot, "管理实例");
        clickRail(robot, "设置");
        scrollUntilVisible(robot, "继承全局内存策略");
        click(robot, "继承全局内存策略");
        click(robot, "自动分配");
        click(robot, "保存设置");
        robot.delay(300);
        var config =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject();
        var instance = config.getAsJsonArray("instances").get(0).getAsJsonObject();
        if (!instance.get("autoMemory").getAsBoolean())
            throw new IllegalStateException("Per-instance automatic memory did not persist");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-review-saved-instance-list.png"),
                true);
        System.out.println(
                "REVIEW_FLOW_OK skin pixels, Fabric icon, global and per-instance memory persistence");
    }

    private static void downloadDetailsFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        click(robot, "下载");
        waitFor("推荐 · 最新正式版");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-download-recommended.png"),
                true);
        clickRail(robot, "模组");
        waitFor("查看 Sodium 的详情与版本");
        click(robot, "查看 Sodium 的详情与版本");
        waitFor("项目详情 · 先选游戏与资源版本，再安装");
        waitFor("选择推荐");
        waitFor("MC 百科搜索");
        waitFor("复制名称");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-download-project.png"),
                true);
        click(robot, "复制名称");
        Object clipboard =
                java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        if (!"Sodium".equals(clipboard))
            throw new IllegalStateException("Project name did not reach clipboard");
        click(robot, "更新日志");
        waitUntilGone("选择推荐");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-download-changelog.png"),
                true);
        click(robot, "版本列表");
        click(robot, "选择推荐");
        click(robot, "下载", false);
        chooseDirectory(root.resolve(".minecraft/versions/smoke-saves/mods").toFile());
        waitFor("开始安装");
        waitFor("已匹配");
        if (!waitFor("开始安装").getAccessibleComponent().isEnabled())
            throw new IllegalStateException(
                    "Compatible selected resource version did not produce an installable plan");
        waitFor("MC 百科搜索");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-download-install-plan.png"),
                true);
        clickTopButton(robot, "取消");
        waitFor("项目详情 · 先选游戏与资源版本，再安装");
        click(robot, "关闭", false);
        click(robot, "设置");
        clickRail(robot, "外观");
        scrollUntilVisible(robot, "选择背景图片");
        click(robot, "选择背景图片");
        chooseDirectory(root.resolve("background-fixture.png").toFile());
        scrollUntilVisible(robot, "图片适配");
        click(robot, "填满并裁切");
        click(robot, "完整显示");
        scrollUntilVisible(robot, "图片位置");
        click(robot, "居中");
        click(robot, "左上");
        awaitFileState(
                "background layout settings persisted",
                5000,
                () -> {
                    try {
                        var settings =
                                com.google.gson.JsonParser.parseString(
                                                Files.readString(
                                                        root.resolve(
                                                                "profile/config/launcher.json")))
                                        .getAsJsonObject()
                                        .getAsJsonObject("settings");
                        return settings.get("backgroundFit").getAsString().equals("fit")
                                && settings.get("backgroundAlignment")
                                        .getAsString()
                                        .equals("topLeft");
                    } catch (java.io.IOException pendingSave) {
                        return false;
                    }
                });
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-appearance-background-layout.png"),
                true);
        System.out.println(
                "DOWNLOAD_DETAILS_FLOW_OK recommendation, internal project versions, clipboard, exact-version install plan, background fit and alignment");
    }

    static void dragSkinModel(Robot robot) throws Exception {
        ensureForeground();
        Rectangle bounds = null;
        for (int i = 0; i < 60 && bounds == null; i++) {
            var preview = waitFor("3D皮肤预览（拖动旋转）");
            AtomicReference<Rectangle> located = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> located.set(componentBounds(preview)));
            bounds = located.get();
            if (bounds == null) robot.delay(100);
        }
        if (bounds == null) {
            EventQueue.invokeAndWait(() -> printTree(mainWindow.getAccessibleContext(), 0));
            throw new IllegalStateException("Skin preview has no visible bounds");
        }
        robot.delay(500);
        var before = robot.createScreenCapture(bounds);
        int x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        try {
            for (int i = 1; i <= 12; i++) {
                robot.mouseMove(x + i * 5, y + i);
                robot.delay(35);
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.delay(700);
        var after = robot.createScreenCapture(bounds);
        int changed = 0;
        for (int row = 0; row < before.getHeight(); row++)
            for (int column = 0; column < before.getWidth(); column++)
                if (before.getRGB(column, row) != after.getRGB(column, row)) changed++;
        if (changed < 100)
            throw new IllegalStateException("Dragging did not rotate the skin: " + changed);
        if (hasControlStartingWith("查看背面"))
            throw new IllegalStateException("Obsolete back-view button is still present");
        System.out.println("SKIN_ROTATION_OK changedPixels=" + changed);
    }

    private static void storageUiFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        Path config = root.resolve("profile/config/launcher.json");
        if (!Files.isRegularFile(config) || Files.exists(root.resolve("profile/launcher.json")))
            throw new IllegalStateException("Legacy configuration was not migrated");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-home.png"), true);
        click(robot, "管理账户皮肤");
        waitFor("皮肤管理");
        robot.delay(600);
        capture(robot, target, new File(output.getParentFile(), "MoliFox-skin-three-d.png"), true);
        dragSkinModel(robot);
        capture(robot, target, new File(output.getParentFile(), "MoliFox-skin-three-d.png"), true);
        click(robot, "完成");
        click(robot, "实例");
        click(robot, "更多操作");
        click(robot, "管理实例");
        waitFor("基本信息");
        if (hasControlStartingWith("概览"))
            throw new IllegalStateException("Overview tab is still visible");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-instance-settings.png"),
                true);
        click(robot, "更换图标");
        waitFor("实例图标");
        click(robot, "选择图标图片");
        chooseDirectory(root.resolve("icon-source.png").toFile());
        waitUntilGone("实例图标");
        var instance = savedInstance(config, "smoke-saves");
        if (!"Custom".equals(instance.get("iconStyle").getAsString()))
            throw new IllegalStateException("Custom icon was not saved");
        click(robot, "更多操作");
        click(robot, "导出整合包…");
        waitFor("导出整合包");
        click(robot, "Modrinth · .mrpack（推荐）");
        click(robot, "MCBBS · .zip（PCL / HMCL）");
        enterText("目标文件", root.resolve("ui-export.zip").toString());
        waitFor("开始导出");
        for (int i = 0; i < 100 && !waitFor("开始导出").getAccessibleComponent().isEnabled(); i++)
            robot.delay(100);
        capture(robot, target, new File(output.getParentFile(), "MoliFox-pack-export.png"), true);
        click(robot, "开始导出");
        awaitFileState(
                "MCBBS archive", 30000, () -> Files.isRegularFile(root.resolve("ui-export.zip")));
        try (var zip = new java.util.zip.ZipFile(root.resolve("ui-export.zip").toFile())) {
            if (zip.getEntry("mcbbs.packmeta") == null
                    || zip.getEntry("overrides/config/ui-fixture.cfg") == null
                    || zip.getEntry("icon.png") == null)
                throw new IllegalStateException(
                        "Export is missing manifest, configuration, or custom icon");
        }
        waitUntilGone("导出整合包");
        click(robot, "下载");
        waitFor("推荐 · 最新正式版");
        if (hasControlStartingWith("安装目录") || hasControlStartingWith("下载源"))
            throw new IllegalStateException(
                    "Vanilla page retains redundant source or directory selectors");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-download-recommended.png"),
                true);
        System.out.println(
                "STORAGE_UI_OK migration, 3D drag, settings-first tabs, custom icon, MCBBS export and simplified vanilla page");
    }

    private static void skinFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        click(robot, "管理账户皮肤");
        waitFor("皮肤管理");
        dragSkinModel(robot);
        enterText("皮肤名称", "SkinFixture");
        click(robot, "导入皮肤");
        chooseDirectory(root.resolve("skin-import.png").toFile());
        waitFor("SkinFixture");
        capture(robot, target, new File(output.getParentFile(), "MoliFox-skin-library.png"), true);
        click(robot, "导出 PNG");
        chooseDirectory(root.resolve("skin-export.png").toFile());
        for (int i = 0; i < 50 && !Files.isRegularFile(root.resolve("skin-export.png")); i++)
            robot.delay(100);
        if (!java.util.Arrays.equals(
                Files.readAllBytes(root.resolve("skin-import.png")),
                Files.readAllBytes(root.resolve("skin-export.png"))))
            throw new IllegalStateException("Skin export bytes differ");
        click(robot, "上传到正版账户");
        waitFor("确认上传");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-skin-upload-confirm.png"),
                true);
        click(robot, "取消");
        click(robot, "完成");
        click(robot, "离线");
        waitFor("玩家名称");
        enterText("玩家名称", "SkinOffline");
        click(robot, "添加账户");
        waitFor("SkinOffline");
        click(robot, "管理账户皮肤");
        click(robot, "SkinFixture");
        click(robot, "用于本地预览");
        waitFor("已更新本地预览");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-skin-offline-preview.png"),
                true);
        var json =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject();
        boolean applied = false;
        for (var element : json.getAsJsonArray("accounts")) {
            var account = element.getAsJsonObject();
            if (account.get("name").getAsString().equals("SkinOffline"))
                applied =
                        account.has("localSkinId")
                                && !account.get("localSkinId").getAsString().isBlank();
        }
        if (!applied) throw new IllegalStateException("Offline local skin preview did not persist");
        click(robot, "恢复默认");
        waitFor("已恢复默认头像");
        click(robot, "移除皮肤 SkinFixture");
        click(robot, "移除");
        waitFor("还没有皮肤，使用下方按钮导入 PNG。");
        click(robot, "完成");
        try (var credentials = Files.list(root.resolve("profile/credentials"))) {
            if (credentials.findAny().isPresent())
                throw new IllegalStateException(
                        "Skin preview unexpectedly touched real authentication");
        }
        System.out.println(
                "SKIN_FLOW_OK preview, import, export, upload confirmation cancel, offline local preview, reset and removal");
    }

    private static void iconFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        click(robot, "实例");
        click(robot, "更多操作");
        click(robot, "管理实例");
        click(robot, "更多操作");
        click(robot, "更换实例图标…");
        waitFor("实例图标");
        click(robot, "选择图标图片");
        chooseDirectory(root.resolve("icon-source.png").toFile());
        waitUntilGone("实例图标");
        var json =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject();
        var instance = json.getAsJsonArray("instances").get(0).getAsJsonObject();
        if (!instance.get("iconStyle").getAsString().equals("Custom")
                || instance.get("iconId").getAsString().isBlank())
            throw new IllegalStateException("Custom icon did not persist");
        click(robot, "返回实例列表");
        waitFor(MAIN_INSTANCE_NAME + " 图标");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-instance-custom-icon.png"),
                true);
        var picture =
                robot.createScreenCapture(
                        new Rectangle(target.getLocationOnScreen(), target.getSize()));
        int pixels = 0;
        for (int x = 0; x < picture.getWidth(); x++)
            for (int y = 0; y < picture.getHeight(); y++)
                if ((picture.getRGB(x, y) & 0xffffff) == 0x8866cc) pixels++;
        if (pixels < 100) throw new IllegalStateException("Custom instance icon did not render");
        clickViewMode(robot, true);
        waitFor("管理");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-instance-custom-grid.png"),
                true);
        clickViewMode(robot, false);
        click(robot, "更多操作");
        click(robot, "管理实例");
        click(robot, "更多操作");
        click(robot, "更换实例图标…");
        click(robot, "恢复自动图标");
        waitUntilGone("实例图标");
        json =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject();
        if (!json.getAsJsonArray("instances")
                .get(0)
                .getAsJsonObject()
                .get("iconStyle")
                .getAsString()
                .equals("Auto")) throw new IllegalStateException("Icon reset did not persist");
        if (!Files.isRegularFile(root.resolve("icon-source.png")))
            throw new IllegalStateException("Icon import source changed");
        System.out.println(
                "ICON_FLOW_OK image import, list and grid rendering, metadata persistence and reset");
    }

    private static void screenshotsFlow(Robot robot, Window target, File output, Path root)
            throws Exception {
        click(robot, "实例");
        click(robot, "更多操作");
        click(robot, "管理实例");
        click(robot, "更多操作");
        click(robot, "管理游戏截图…");
        waitFor("游戏截图");
        waitFor("today.png");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-screenshots-gallery.png"),
                true);
        click(robot, "选择当前结果");
        click(robot, "导出所选");
        chooseDirectory(root.resolve("screenshot-export").toFile());
        waitFor("已导出 2 张；重名文件自动编号。");
        Path source = root.resolve(".minecraft/versions/smoke-saves/screenshots");
        for (String name : List.of("today.png", "yesterday.png")) {
            byte[] originalBytes = Files.readAllBytes(source.resolve(name));
            byte[] exportedBytes =
                    Files.readAllBytes(root.resolve("screenshot-export").resolve(name));
            if (!java.util.Arrays.equals(originalBytes, exportedBytes))
                throw new IllegalStateException("Screenshot export bytes differ: " + name);
        }
        click(robot, "预览截图 today.png");
        waitFor("截图预览");
        click(robot, "复制图片");
        waitFor("图片已复制");
        if (!java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor))
            throw new IllegalStateException("Screenshot clipboard did not contain an image");
        click(robot, "设为背景");
        waitFor("已设为启动器背景");
        var settings =
                com.google.gson.JsonParser.parseString(
                                Files.readString(root.resolve("profile/config/launcher.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("settings");
        if (!Files.isRegularFile(Path.of(settings.get("backgroundPath").getAsString())))
            throw new IllegalStateException("Screenshot background did not persist");
        capture(
                robot,
                target,
                new File(output.getParentFile(), "MoliFox-screenshot-preview.png"),
                true);
        click(robot, "关闭预览");
        click(robot, "取消选择");
        click(robot, "全部日期");
        click(robot, java.time.LocalDate.now().minusDays(1).toString());
        waitUntilGone("today.png");
        waitFor("yesterday.png");
        click(robot, "选择当前结果");
        click(robot, "移入回收站");
        waitFor("确认移除截图");
        click(robot, "取消");
        if (!Files.isRegularFile(source.resolve("today.png"))
                || !Files.isRegularFile(source.resolve("yesterday.png")))
            throw new IllegalStateException("Cancelled screenshot delete changed original files");
        click(robot, "移入回收站");
        waitFor("确认移除截图");
        clickTopButton(robot, "确认移除截图");
        awaitFileState(
                "selected screenshot recycled",
                10000,
                () -> !Files.exists(source.resolve("yesterday.png")));
        if (!Files.isRegularFile(source.resolve("today.png")))
            throw new IllegalStateException(
                    "Deleting the filtered screenshot also removed an unselected screenshot");
        click(robot, "完成");
        System.out.println(
                "SCREENSHOTS_FLOW_OK gallery, export byte equality, image clipboard, background, date filter, cancellation and actual selected recycling");
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread()
                .setUncaughtExceptionHandler(
                        (thread, failure) -> {
                            failure.printStackTrace();
                            System.exit(1);
                        });
        // 窗口被外部关闭时 Compose 会以 0 退出，Gradle 会把未跑完的流程误判为通过；这里显式失败。
        java.util.concurrent.atomic.AtomicBoolean smokeFinished =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (!smokeFinished.get()) {
                                        System.out.println("UI_SMOKE_ABORTED before UI_SMOKE_OK");
                                        System.out.flush();
                                        Runtime.getRuntime().halt(1);
                                    }
                                },
                                "ui-smoke-abort-guard"));
        Path testRoot =
                Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize();
        System.setProperty("molifox.data.dir", testRoot.resolve("profile").toString());
        Path fixture = testRoot.resolve("game-directory-fixture");
        Path version = fixture.resolve("versions/1.20.1");
        Files.createDirectories(version);
        Path lifecycleClasses = testRoot.resolve("lifecycle-classes");
        Files.createDirectories(lifecycleClasses);
        Path lifecycleSource = testRoot.resolve("UiLifecycleFixture.java");
        Files.writeString(
                lifecycleSource,
                "import java.nio.file.*; public class UiLifecycleFixture { public static void main(String[] args) throws Exception { Path marker=Path.of(System.getenv(\"MOLIFOX_UI_MARKER\")); Files.writeString(marker,\"running\"); var observed=new java.util.Properties(); observed.setProperty(\"jvm\",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString()); observed.setProperty(\"property\",System.getProperty(\"molifox.ui.check\",\"\")); observed.setProperty(\"cwd\",System.getProperty(\"user.dir\")); observed.setProperty(\"gameArgs\",String.join(\"|\",args)); observed.setProperty(\"environment\",System.getenv().getOrDefault(\"MOLIFOX_UI\",\"\")); try(var stream=Files.newOutputStream(marker.resolveSibling(\"observed-launch.properties\"))) { observed.store(stream,\"disposable UI launch observation\"); } Thread.sleep(3000); Files.writeString(marker,\"finished\"); System.out.println(\"UI_FIXTURE_DONE\"); } }");
        if (javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "17",
                                "-d",
                                lifecycleClasses.toString(),
                                lifecycleSource.toString())
                != 0) throw new IllegalStateException("Lifecycle fixture compilation failed");
        Files.writeString(
                version.resolve("1.20.1.json"),
                new com.google.gson.Gson()
                        .toJson(
                                java.util.Map.of(
                                        "id",
                                        "1.20.1",
                                        "mainClass",
                                        "UiLifecycleFixture",
                                        "type",
                                        "release",
                                        "javaVersion",
                                        java.util.Map.of(
                                                "majorVersion", Runtime.version().feature()),
                                        "libraries",
                                        java.util.List.of(),
                                        "arguments",
                                        java.util.Map.of(
                                                "game",
                                                java.util.List.of(),
                                                "jvm",
                                                java.util.List.of()))));
        // A controlled Java program tests launcher lifecycle; it is not a real Minecraft verdict.
        try (var jar =
                new java.util.jar.JarOutputStream(
                        Files.newOutputStream(version.resolve("1.20.1.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("UiLifecycleFixture.class"));
            jar.write(Files.readAllBytes(lifecycleClasses.resolve("UiLifecycleFixture.class")));
            jar.closeEntry();
        }
        Files.writeString(
                fixture.resolve("keep-user-data.txt"), "directory contents remain unchanged");
        Files.createDirectories(fixture.resolve("mods"));
        try (var jar =
                new java.util.jar.JarOutputStream(
                        Files.newOutputStream(fixture.resolve("mods/fox-ui.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("fabric.mod.json"));
            jar.write(
                    "{\"id\":\"fox_ui\",\"name\":\"茉狐测试模组\",\"version\":\"1.2.3\",\"authors\":[\"MoliFox\"],\"description\":\"本地声明界面测试\",\"depends\":{\"minecraft\":\"1.20.1\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Path packFixture = testRoot.resolve("preview-fixture.mrpack");
        var packHashes = java.util.Map.of("sha1", "0".repeat(40), "sha512", "0".repeat(128));
        var packFiles =
                java.util.List.of(
                        java.util.Map.of(
                                "path",
                                "mods/required.jar",
                                "hashes",
                                packHashes,
                                "downloads",
                                java.util.List.of("https://cdn.modrinth.com/required.jar"),
                                "fileSize",
                                1),
                        java.util.Map.of(
                                "path",
                                "mods/optional.jar",
                                "hashes",
                                packHashes,
                                "downloads",
                                java.util.List.of("https://cdn.modrinth.com/optional.jar"),
                                "fileSize",
                                1,
                                "env",
                                java.util.Map.of("client", "optional", "server", "unsupported")));
        String packIndex =
                new com.google.gson.Gson()
                        .toJson(
                                java.util.Map.of(
                                        "formatVersion",
                                        1,
                                        "game",
                                        "minecraft",
                                        "versionId",
                                        "ui-fixture",
                                        "name",
                                        "整合包预览测试",
                                        "dependencies",
                                        java.util.Map.of("minecraft", "1.20.1"),
                                        "files",
                                        packFiles));
        try (var pack = new java.util.zip.ZipOutputStream(Files.newOutputStream(packFixture))) {
            pack.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            pack.write(packIndex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pack.closeEntry();
        }
        Path backgroundFixture = testRoot.resolve("background-fixture.png");
        var background =
                new java.awt.image.BufferedImage(
                        1400, 900, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var paint = background.createGraphics();
        paint.setPaint(
                new java.awt.GradientPaint(
                        0,
                        0,
                        new java.awt.Color(238, 187, 211),
                        1400,
                        900,
                        new java.awt.Color(194, 208, 236)));
        paint.fillRect(0, 0, 1400, 900);
        paint.setColor(new java.awt.Color(255, 226, 231));
        paint.fillOval(500, -200, 1000, 1000);
        paint.setColor(new java.awt.Color(219, 174, 205));
        paint.fillOval(-200, 450, 900, 800);
        paint.dispose();
        ImageIO.write(background, "png", backgroundFixture.toFile());
        // 预置两个内部隔离实例，让存档/移动/隔离预览能操作真实文件而不是只读的外部实例。
        seedInternalInstances(testRoot);
        if (args.length > 1 && args[1].equals("refinement")) RefinementActions.seed(testRoot);
        if (args.length > 1
                && (args[1].equals("review")
                        || args[1].equals("skins")
                        || args[1].equals("icons")
                        || args[1].equals("screenshots")
                        || args[1].equals("downloadDetails")
                        || args[1].equals("storageUi"))) seedReview(testRoot);
        if (args.length > 1 && args[1].startsWith("actions"))
            UiFunctionalChecks.seed(testRoot, fixture);
        if (args.length > 1 && args[1].equals("storageUi")) {
            Path config = testRoot.resolve(".minecraft/versions/smoke-saves/config/ui-fixture.cfg");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "independent-ui-export=true");
        }
        if (args.length > 1 && args[1].equals("screenshots")) {
            Path source = testRoot.resolve(".minecraft/versions/smoke-saves/screenshots");
            Files.createDirectories(source);
            Files.createDirectories(testRoot.resolve("screenshot-export"));
            for (String name : List.of("today.png", "yesterday.png")) {
                var shot =
                        new java.awt.image.BufferedImage(
                                320, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var paintShot = shot.createGraphics();
                paintShot.setColor(
                        new java.awt.Color(name.equals("today.png") ? 0x6677aa : 0x559977));
                paintShot.fillRect(0, 0, 320, 180);
                paintShot.setColor(java.awt.Color.WHITE);
                paintShot.drawString(name, 20, 80);
                paintShot.dispose();
                Path file = source.resolve(name);
                ImageIO.write(shot, "png", file.toFile());
                if (name.equals("yesterday.png"))
                    Files.setLastModifiedTime(
                            file,
                            java.nio.file.attribute.FileTime.from(
                                    java.time.Instant.now().minusSeconds(86400)));
            }
        }
        if (args.length > 1 && (args[1].equals("icons") || args[1].equals("storageUi"))) {
            var image =
                    new java.awt.image.BufferedImage(
                            32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 32; x++)
                for (int y = 0; y < 32; y++) image.setRGB(x, y, 0xff8866cc);
            ImageIO.write(image, "png", testRoot.resolve("icon-source.png").toFile());
        }
        if (args.length > 1 && args[1].equals("skins")) {
            var skin =
                    new java.awt.image.BufferedImage(
                            64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics = skin.createGraphics();
            graphics.setColor(new java.awt.Color(54, 181, 165));
            graphics.fillRect(8, 8, 8, 8);
            graphics.setColor(new java.awt.Color(180, 120, 140));
            graphics.fillRect(24, 8, 8, 8);
            graphics.setColor(new java.awt.Color(96, 148, 208));
            graphics.fillRect(20, 20, 8, 12);
            graphics.fillRect(32, 20, 8, 12);
            graphics.setColor(new java.awt.Color(190, 160, 130));
            graphics.fillRect(44, 20, 4, 12);
            graphics.fillRect(36, 52, 4, 12);
            graphics.setColor(new java.awt.Color(80, 90, 115));
            graphics.fillRect(4, 20, 4, 12);
            graphics.fillRect(20, 52, 4, 12);
            graphics.dispose();
            ImageIO.write(skin, "png", testRoot.resolve("skin-import.png").toFile());
        }
        System.setProperty("compose.accessibility.enable", "true");
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread app =
                new Thread(
                        () -> {
                            try {
                                Class.forName("dev.molifox.desktop.MainKt")
                                        .getMethod("main")
                                        .invoke(null);
                            } catch (Throwable failure) {
                                error.set(failure);
                                failure.printStackTrace();
                            }
                        },
                        "MoliFox UI smoke");
        app.start();
        Window target = null;
        for (int i = 0; i < 120; i++) {
            for (Window candidate : Window.getWindows())
                if (candidate.isShowing() && candidate.getWidth() > 1000) target = candidate;
            if (target != null || error.get() != null) break;
            Thread.sleep(250);
        }
        if (target == null)
            throw new IllegalStateException("MoliFox window did not open", error.get());
        mainWindow = target;
        ensureForeground();
        var screen =
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration();
        HWND handle = windowHandle(target);
        char[] nativeTitle = new char[512];
        char[] nativeClass = new char[512];
        User32.INSTANCE.GetWindowText(handle, nativeTitle, nativeTitle.length);
        User32.INSTANCE.GetClassName(handle, nativeClass, nativeClass.length);
        System.out.println(
                "SCREEN bounds="
                        + screen.getBounds()
                        + " scale="
                        + screen.getDefaultTransform().getScaleX()
                        + "x"
                        + screen.getDefaultTransform().getScaleY());
        System.out.println(
                "WINDOW_GEOMETRY awt="
                        + new Rectangle(target.getLocationOnScreen(), target.getSize())
                        + " win32="
                        + windowRect(target)
                        + " handle="
                        + handle
                        + " title=["
                        + new String(nativeTitle).trim()
                        + "] class=["
                        + new String(nativeClass).trim()
                        + "]");
        Robot robot = new Robot();
        robot.setAutoDelay(70);
        robot.delay(2500);
        File output = new File(args[0]);
        capture(robot, target, output, true);
        Window ownWindow = target;
        if (args.length > 1 && args[1].equals("flow")) {
            try {
                click(robot, "离线");
                // 分段切换有过渡动画：旧「微软登录」按钮还在时点名称框会误触真实登录。
                waitUntilGone("微软登录");
                click(robot, "玩家名称");
                enterText("玩家名称", "MoliTester");
                robot.delay(300);
                click(robot, "添加账户");
                waitFor("MoliTester");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-home-account.png"),
                        true);
                click(robot, "设置");
                waitFor("内存分配");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-settings.png"),
                        true);
                scrollUntilVisible(robot, "附加游戏参数");
                enterText("附加游戏参数", "--server ui.example --port 25565");
                scrollUntilVisible(robot, "游戏环境变量");
                enterText("游戏环境变量", "INVALID-NAME=x");
                robot.delay(250);
                if (waitFor("保存设置").getAccessibleComponent().isEnabled())
                    throw new IllegalStateException("Invalid environment left Save enabled");
                enterText(
                        "游戏环境变量",
                        "MOLIFOX_UI=hello world\nMOLIFOX_UI_MARKER="
                                + testRoot.resolve("game-marker.txt"));
                robot.delay(250);
                // 「游戏运行时的启动器」改为下拉框：先点当前值「保持显示」展开菜单，再选「隐藏到托盘」。
                scrollUntilVisible(robot, "游戏运行时的启动器");
                click(robot, "保持显示");
                click(robot, "隐藏到托盘");
                click(robot, "保存设置");
                var launchSettings =
                        com.google.gson.JsonParser.parseString(
                                        Files.readString(
                                                testRoot.resolve("profile/config/launcher.json")))
                                .getAsJsonObject()
                                .getAsJsonObject("settings")
                                .getAsJsonObject("advancedLaunch");
                if (!launchSettings.get("extraGameArgs").getAsString().contains("ui.example")
                        || !launchSettings
                                .getAsJsonObject("environmentVariables")
                                .get("MOLIFOX_UI")
                                .getAsString()
                                .equals("hello world"))
                    throw new IllegalStateException("Advanced launch settings did not persist");
                click(robot, "Java 管理");
                waitFor("Java 运行环境");
                capture(robot, target, new File(output.getParentFile(), "MoliFox-java.png"), true);
                click(robot, "通用");
                waitFor("检查更新");
                // 设置侧栏的「下载」与顶部主导航重名，取树中靠后的匹配。
                click(robot, "下载", false);
                waitFor("版本列表源");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-download-settings.png"),
                        true);
                click(robot, "实例");
                {
                    click(robot, "选择游戏目录");
                    chooseDirectory(fixture.toFile());
                    waitFor("启动 1.20.1");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-game-directories.png"),
                            true);
                    // 列表/网格切换只剩图标：按工具行里「收藏」右侧的两个按钮定位。
                    clickViewMode(robot, true);
                    waitFor("管理");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-versions-grid.png"),
                            true);
                    clickViewMode(robot, false);
                    waitUntilGone("管理");
                    // 实例行尾的入口收进「更多操作」菜单。
                    click(robot, "更多操作");
                    click(robot, "管理实例");
                    waitFor("基本信息");
                    clickRail(robot, "设置");
                    if (hasControlStartingWith("版本隔离"))
                        throw new IllegalStateException("Per-instance isolation remains");
                    click(robot, "继承全局内存策略");
                    click(robot, "手动分配");
                    setSlider(robot, "实例内存滑块", .08f);
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-isolation.png"),
                            true);
                    // 查看启动参数在页头「更多操作」菜单里。
                    click(robot, "更多操作");
                    click(robot, "查看启动参数");
                    waitFor("启动参数预览");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-launch-preview.png"),
                            true);
                    click(robot, "关闭预览");
                    // 组件维护位于实例设置页。
                    clickRail(robot, "设置");
                    waitFor("更换加载器");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-instance-install.png"),
                            true);
                    click(robot, "模组");
                    waitFor("茉狐测试模组");
                    // 模组详情改为点行打开，不再有单独的「详情」按钮。
                    click(robot, "茉狐测试模组");
                    waitFor("作者：MoliFox");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-mod-details.png"),
                            true);
                    click(robot, "关闭模组详情");
                    click(robot, "检查更新");
                    waitFor("资源更新管理");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-content-updates.png"),
                            true);
                    click(robot, "关闭更新管理");
                    click(robot, "游戏文件夹");
                    waitFor("模组文件夹");
                    click(robot, "管理");
                    waitFor("重命名实例");
                    capture(
                            robot,
                            target,
                            new File(output.getParentFile(), "MoliFox-instance-manage.png"),
                            true);
                    // 保存入口由页内的「保存版本设置」改为页头统一的「保存设置」。
                    if (!waitFor("保存设置").getAccessibleComponent().isEnabled())
                        throw new IllegalStateException("Instance settings save stayed disabled");
                    click(robot, "保存设置");
                    Path profileJson = testRoot.resolve("profile/config/launcher.json");
                    var savedInstance = savedInstance(profileJson, "1.20.1");
                    if (savedInstance.get("memoryMb").getAsInt() < 512)
                        throw new IllegalStateException("Instance memory slider was not saved");
                    if (!Files.readString(fixture.resolve("keep-user-data.txt"))
                            .equals("directory contents remain unchanged"))
                        throw new IllegalStateException("Linked directory data changed");
                }
                // 新界面：存档页真实备份/恢复/删除、隔离切换预览、移动实例预览（后两者只取消）。
                exerciseInstanceExtras(robot, target, output, testRoot, fixture);
                click(robot, "开始");
                waitFor("启动游戏");
                click(robot, "启动游戏");
                Path gameMarker = testRoot.resolve("game-marker.txt");
                for (int attempt = 0; attempt < 300 && !Files.isRegularFile(gameMarker); attempt++)
                    Thread.sleep(100);
                if (!Files.isRegularFile(gameMarker))
                    throw new IllegalStateException("Lifecycle fixture did not start");
                robot.delay(250);
                if (java.awt.SystemTray.isSupported() && target.isVisible())
                    throw new IllegalStateException("Launcher did not hide while game was running");
                for (int attempt = 0;
                        attempt < 150
                                && (!Files.readString(gameMarker).equals("finished")
                                        || !target.isVisible());
                        attempt++) Thread.sleep(100);
                if (!Files.readString(gameMarker).equals("finished") || !target.isVisible())
                    throw new IllegalStateException("Launcher did not restore after game exit");
                robot.delay(300);
                ensureForeground();
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-window-restored.png"),
                        true);
                click(robot, "实例");
                click(robot, "导入整合包");
                chooseDirectory(packFixture.toFile());
                waitFor("开始导入");
                waitFor("必需文件 1 项 · 可选文件 1/1 项");
                click(robot, "全不选");
                waitFor("必需文件 1 项 · 可选文件 0/1 项");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-pack-import.png"),
                        true);
                click(robot, "取消");
                if (Files.readString(testRoot.resolve("profile/config/launcher.json"))
                        .contains("整合包预览测试"))
                    throw new IllegalStateException("Cancelled preview created an instance");
                click(robot, "新建实例");
                waitFor("Minecraft 版本");
                robot.delay(2500);
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-install.png"),
                        true);
                click(robot, "模组");
                robot.delay(7000);
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-discover.png"),
                        true);
                click(robot, "资源包");
                waitFor("选择版本");
                // Scroll the grid so the first card's clipped install button is visible.
                robot.mouseMove(target.getX() + 650, target.getY() + 540);
                robot.mouseWheel(2);
                robot.delay(700);
                click(robot, "选择版本");
                waitFor("项目详情 · 先选游戏与资源版本，再安装");
                // 外链交给系统浏览器；验证入口，不操作用户已有浏览器。
                AccessibleContext projectPage = waitFor("MC 百科搜索");
                if (!projectPage.getAccessibleComponent().isEnabled())
                    throw new IllegalStateException(
                            "Install dialog project website button disabled");
                System.out.println(
                        "INSTALL_DIALOG_PROJECT_WEB_OK present=true enabled=true clicked=false");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-content-versions.png"),
                        true);
                click(robot, "关闭", false);
                click(robot, "设置");
                click(robot, "日志");
                waitFor("自动滚动");
                capture(robot, target, new File(output.getParentFile(), "MoliFox-logs.png"), true);
                click(robot, "关于 MoliFox");
                waitFor("MoliFox · 茉狐");
                click(robot, "外观");
                waitFor("界面主题");
                click(robot, "深色");
                robot.delay(400);
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-appearance-dark.png"),
                        true);
                if (!Files.readString(testRoot.resolve("profile/config/launcher.json"))
                        .contains("\"dark\""))
                    throw new IllegalStateException("Dark theme did not persist");
                click(robot, "浅色");
                robot.delay(400);
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-appearance.png"),
                        true);
                scrollUntilVisible(robot, "标题栏半透明");
                scrollUntilVisible(robot, "默认游戏窗口");
                scrollUntilVisible(robot, "面板不透明度滑块");
                setSlider(robot, "面板不透明度滑块", .5f);
                setSlider(robot, "背景磨砂强度滑块", .65f);
                scrollUntilVisible(robot, "选择背景图片");
                click(robot, "选择背景图片");
                chooseDirectory(backgroundFixture.toFile());
                // 背景图片要复制到外观目录后才写入配置；固定 700ms 在机器繁忙时会读到旧值。
                var appearanceSettings =
                        com.google.gson.JsonParser.parseString(
                                        Files.readString(
                                                testRoot.resolve("profile/config/launcher.json")))
                                .getAsJsonObject()
                                .getAsJsonObject("settings");
                for (int attempt = 0; attempt < 60; attempt++) {
                    if (appearanceSettings.get("surfaceOpacity").getAsFloat() < .9f
                            && appearanceSettings.get("backgroundBlur").getAsFloat() > 16f
                            && !appearanceSettings.get("backgroundPath").getAsString().isBlank())
                        break;
                    Thread.sleep(250);
                    appearanceSettings =
                            com.google.gson.JsonParser.parseString(
                                            Files.readString(
                                                    testRoot.resolve(
                                                            "profile/config/launcher.json")))
                                    .getAsJsonObject()
                                    .getAsJsonObject("settings");
                }
                if (appearanceSettings.get("surfaceOpacity").getAsFloat() >= .9f
                        || appearanceSettings.get("backgroundBlur").getAsFloat() <= 16f
                        || appearanceSettings.get("backgroundPath").getAsString().isBlank())
                    throw new IllegalStateException("Glass/background settings did not persist");
                capture(robot, target, new File(output.getParentFile(), "MoliFox-glass.png"), true);
                click(robot, "字体与缩放");
                waitFor("选择字体");
                click(robot, "选择字体");
                waitFor("搜索字体");
                enterText("搜索字体", "Segoe UI");
                robot.delay(400);
                click(robot, "Segoe UI");
                robot.delay(500);
                capture(robot, target, new File(output.getParentFile(), "MoliFox-fonts.png"), true);
                String fontConfiguration =
                        Files.readString(testRoot.resolve("profile/config/launcher.json"));
                if (!fontConfiguration.contains("system:Segoe UI"))
                    throw new IllegalStateException("System font did not persist");
                Path systemFont = Path.of(System.getenv("WINDIR"), "Fonts", "arial.ttf");
                if (Files.isRegularFile(systemFont)) {
                    Path importedFixture = testRoot.resolve("font-fixture.ttf");
                    Files.copy(systemFont, importedFixture);
                    click(robot, "导入字体");
                    chooseDirectory(importedFixture.toFile());
                    robot.delay(700);
                    var importedSettings =
                            com.google.gson.JsonParser.parseString(
                                            Files.readString(
                                                    testRoot.resolve(
                                                            "profile/config/launcher.json")))
                                    .getAsJsonObject()
                                    .getAsJsonObject("settings");
                    var importedFont =
                            importedSettings.getAsJsonArray("customFonts").get(0).getAsJsonObject();
                    Path portableFont = Path.of(importedFont.get("path").getAsString());
                    if (!Files.isRegularFile(portableFont)
                            || !importedSettings
                                    .get("fontChoice")
                                    .getAsString()
                                    .startsWith("file:"))
                        throw new IllegalStateException("Imported font did not persist");
                    Files.delete(importedFixture);
                    String removeFont = "移除字体 " + importedFont.get("name").getAsString();
                    scrollUntilVisible(robot, removeFont);
                    click(robot, removeFont);
                    robot.delay(500);
                    if (Files.exists(portableFont))
                        throw new IllegalStateException(
                                "Removed font copy is still locked or retained");
                }
                // 页面布局：默认视图改为下拉框，先点当前值展开再选目标项。
                click(robot, "页面布局");
                click(robot, "版本库");
                click(robot, "列表");
                click(robot, "网格");
                click(robot, "实例");
                waitFor("管理");
                click(robot, "设置");
                click(robot, "外观");
                click(robot, "页面布局");
                click(robot, "版本库");
                click(robot, "网格");
                click(robot, "列表");
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-page-options.png"),
                        true);
                click(robot, "开始");
                EventQueue.invokeAndWait(() -> ownWindow.setSize(1060, 760));
                robot.delay(600);
                capture(
                        robot,
                        target,
                        new File(output.getParentFile(), "MoliFox-compact.png"),
                        true);
                EventQueue.invokeAndWait(() -> ownWindow.setSize(1280, 725));
                System.out.println(
                        "UI_FLOW_OK account, settings, directories, isolation, instance saves backup/restore/delete, isolation change preview, instance move preview, mrpack export dialog, install dialog project website, grid, download center, logs, Sakura theme, glass, fonts, page preferences, compact window");
            } catch (Throwable failure) {
                smokeFinished.set(true);
                failure.printStackTrace();
                try {
                    ensureForeground();
                    Rectangle bounds =
                            new Rectangle(target.getLocationOnScreen(), target.getSize());
                    boolean saved =
                            ImageIO.write(
                                    robot.createScreenCapture(bounds),
                                    "png",
                                    new File(output.getParentFile(), "MoliFox-failure-screen.png"));
                    System.out.println("SCREEN_CAPTURE_ON_FAILURE saved=" + saved + " " + bounds);
                } catch (Exception ignored) {
                }
                EventQueue.invokeAndWait(
                        () -> {
                            for (Window current : Window.getWindows())
                                if (current.isShowing())
                                    printTree(current.getAccessibleContext(), 0);
                        });
                System.exit(1);
            }
        } else if (args.length > 1 && args[1].equals("refinement")) {
            RefinementActions.run(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("review")) {
            reviewFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("skins")) {
            skinFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("icons")) {
            iconFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("screenshots")) {
            screenshotsFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("downloadDetails")) {
            downloadDetailsFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].equals("storageUi")) {
            storageUiFlow(robot, target, output, testRoot);
        } else if (args.length > 1 && args[1].startsWith("actions")) {
            UiFunctionalChecks.run(args[1], robot, target, output, testRoot);
        } else EventQueue.invokeAndWait(() -> printTree(ownWindow.getAccessibleContext(), 0));
        System.out.println("UI_SMOKE_OK");
        smokeFinished.set(true);
        if (args.length > 1 && args[1].equals("actionsWindow")) click(robot, "关闭");
        else
            EventQueue.invokeAndWait(
                    () ->
                            ownWindow.dispatchEvent(
                                    new WindowEvent(ownWindow, WindowEvent.WINDOW_CLOSING)));
        app.join(10000);
        if (app.isAlive())
            throw new IllegalStateException("The test window did not shut down normally");
        if (error.get() != null) throw new IllegalStateException(error.get());
        System.out.println("UI_SMOKE_OK");
        System.exit(0);
    }
}
