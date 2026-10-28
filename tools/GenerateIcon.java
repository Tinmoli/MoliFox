// 负责生成启动器品牌图标的构建资源。

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/** Reproducible native app icons from MoliFox's geometric fox mark. */
public final class GenerateIcon {
    private static void polygon(Graphics2D graphics, int rgb, int... points) {
        Polygon polygon = new Polygon();
        for (int i = 0; i < points.length; i += 2) polygon.addPoint(points[i], points[i + 1]);
        graphics.setColor(new Color(rgb));
        graphics.fill(polygon);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(4, 4);
        graphics.setColor(new Color(0xF4F5F0));
        graphics.fillRoundRect(0, 0, 64, 64, 15, 15);
        graphics.translate(3, 2);
        graphics.scale(.9, .9);
        polygon(graphics, 0xE67D4A, 7, 10, 24, 19, 40, 19, 57, 10, 53, 43, 32, 58, 11, 43);
        polygon(graphics, 0xCB6539, 7, 10, 26, 25, 15, 32);
        polygon(graphics, 0xCB6539, 57, 10, 38, 25, 49, 32);
        polygon(graphics, 0xFFEED8, 12, 35, 29, 39, 32, 53, 17, 44);
        polygon(graphics, 0xFFEED8, 52, 35, 35, 39, 32, 53, 47, 44);
        graphics.setColor(new Color(0x354334));
        graphics.fillOval(19, 30, 4, 4);
        graphics.fillOval(41, 30, 4, 4);
        polygon(graphics, 0x354334, 28, 45, 36, 45, 32, 49);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] png = output.toByteArray();
        Files.write(directory.resolve("molifox.png"), png);
        ByteBuffer ico = ByteBuffer.allocate(22 + png.length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .putShort((short) 1)
                .putShort((short) 32)
                .putInt(png.length)
                .putInt(22)
                .put(png);
        Files.write(directory.resolve("molifox.ico"), ico.array());
    }
}
