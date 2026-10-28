// 使用测试文件源窗口发起真实系统拖放，验证整合包预览和原文件保留。

import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;

final class PackDragChecks {
    static void run(Robot robot, Window destination, File output, Path root) throws Exception {
        Path pack = root.resolve("preview-fixture.mrpack");
        byte[] original = Files.readAllBytes(pack);
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<JLabel> source = new AtomicReference<>();
        EventQueue.invokeAndWait(
                () -> {
                    JFrame window = new JFrame("MoliFox drag fixture");
                    JLabel label = new JLabel("Drag pack fixture", SwingConstants.CENTER);
                    label.setTransferHandler(
                            new TransferHandler() {
                                public int getSourceActions(JComponent component) {
                                    return COPY;
                                }

                                protected Transferable createTransferable(JComponent component) {
                                    return new Transferable() {
                                        public DataFlavor[] getTransferDataFlavors() {
                                            return new DataFlavor[] {DataFlavor.javaFileListFlavor};
                                        }

                                        public boolean isDataFlavorSupported(DataFlavor flavor) {
                                            return DataFlavor.javaFileListFlavor.equals(flavor);
                                        }

                                        public Object getTransferData(DataFlavor flavor)
                                                throws UnsupportedFlavorException {
                                            if (!isDataFlavorSupported(flavor))
                                                throw new UnsupportedFlavorException(flavor);
                                            return List.of(pack.toFile());
                                        }
                                    };
                                }
                            });
                    label.addMouseMotionListener(
                            new java.awt.event.MouseMotionAdapter() {
                                public void mouseDragged(java.awt.event.MouseEvent event) {
                                    label.getTransferHandler()
                                            .exportAsDrag(label, event, TransferHandler.COPY);
                                }
                            });
                    window.add(label);
                    window.setSize(230, 110);
                    window.setLocation(
                            destination.getX() + destination.getWidth() + 20,
                            destination.getY() + 160);
                    window.setVisible(true);
                    frame.set(window);
                    source.set(label);
                });
        try {
            robot.delay(300);
            AtomicReference<Point> start = new AtomicReference<>();
            EventQueue.invokeAndWait(
                    () -> {
                        Point point = source.get().getLocationOnScreen();
                        point.translate(100, 45);
                        start.set(point);
                    });
            Point end =
                    new Point(
                            destination.getX() + destination.getWidth() / 2,
                            destination.getY() + destination.getHeight() / 2);
            robot.mouseMove(start.get().x, start.get().y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int step = 1; step <= 30; step++) {
                robot.mouseMove(
                        start.get().x + (end.x - start.get().x) * step / 30,
                        start.get().y + (end.y - start.get().y) * step / 30);
                robot.delay(30);
            }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            UiSmoke.waitFor("开始导入");
            UiSmoke.waitFor("整合包预览测试");
            UiSmoke.capture(
                    robot,
                    destination,
                    new File(output.getParentFile(), "MoliFox-pack-import.png"),
                    true);
            UiSmoke.click(robot, "取消", false);
            UiSmoke.waitUntilGone("开始导入");
            if (!java.util.Arrays.equals(original, Files.readAllBytes(pack)))
                throw new IllegalStateException("Drag preview altered original pack");
            System.out.println(
                    "PACK_DRAG: real OS file drop opened install preview; cancellation preserved archive");
        } finally {
            EventQueue.invokeAndWait(() -> frame.get().dispose());
        }
    }
}
