import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Gera os ícones do Overlook a partir do mesmo desenho do favicon.svg.
 *
 * <pre>java packaging/IconGen.java</pre>
 *
 * Saída: packaging/icons/overlook.{png,ico,icns} e os PNGs da bandeja em
 * src/main/resources/icon/.
 */
public class IconGen {

    static final Color VIOLET = new Color(0x70, 0x48, 0xe8);
    static final Color PINK = new Color(0xd6, 0x39, 0x8a);

    public static void main(String[] args) throws IOException {
        Path out = Path.of("packaging", "icons");
        Path tray = Path.of("src", "main", "resources", "icon");
        Files.createDirectories(out);
        Files.createDirectories(tray);

        ImageIO.write(render(512), "png", out.resolve("overlook.png").toFile());
        for (int size : new int[] {16, 32, 64}) {
            ImageIO.write(render(size), "png", tray.resolve("overlook-" + size + ".png").toFile());
        }
        Files.write(out.resolve("overlook.ico"), ico(16, 24, 32, 48, 64, 128, 256));

        Map<String, Integer> icns = new LinkedHashMap<>();
        icns.put("icp4", 16);
        icns.put("icp5", 32);
        icns.put("icp6", 64);
        icns.put("ic07", 128);
        icns.put("ic08", 256);
        icns.put("ic09", 512);
        icns.put("ic10", 1024);
        Files.write(out.resolve("overlook.icns"), icns(icns));
        System.out.println("Ícones gerados em " + out.toAbsolutePath() + " e " + tray.toAbsolutePath());
    }

    /** Quadrado arredondado em gradiente com um olho estilizado e uma linha de gráfico na pupila. */
    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = size / 64.0;
        g.scale(s, s);

        g.setPaint(new GradientPaint(4, 4, VIOLET, 60, 60, PINK));
        g.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 18, 18));

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D eye = new Path2D.Double();
        eye.moveTo(10, 32);
        eye.curveTo(17, 19, 25, 15, 32, 15);
        eye.curveTo(39, 15, 47, 19, 54, 32);
        eye.curveTo(47, 45, 39, 49, 32, 49);
        eye.curveTo(25, 49, 17, 45, 10, 32);
        eye.closePath();
        g.draw(eye);

        g.fill(new Ellipse2D.Double(21, 21, 22, 22));
        if (size >= 32) {
            // linha de tendência dentro da pupila
            g.setPaint(new GradientPaint(23, 38, VIOLET, 41, 26, PINK));
            g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D line = new Path2D.Double();
            line.moveTo(25, 37);
            line.lineTo(30, 31);
            line.lineTo(34, 34);
            line.lineTo(39, 27);
            g.draw(line);
        } else {
            g.setColor(VIOLET);
            g.fill(new Ellipse2D.Double(27, 27, 10, 10));
        }
        g.dispose();
        return img;
    }

    static byte[] png(int size) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(render(size), "png", buf);
        return buf.toByteArray();
    }

    /** ICO com entradas PNG (aceito desde o Windows Vista). */
    static byte[] ico(int... sizes) throws IOException {
        byte[][] images = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            images[i] = png(sizes[i]);
        }
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize;
        for (byte[] img : images) {
            total += img.length;
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            int dim = sizes[i] >= 256 ? 0 : sizes[i];
            b.put((byte) dim).put((byte) dim).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32);
            b.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] img : images) {
            b.put(img);
        }
        return b.array();
    }

    /** ICNS com payloads PNG. */
    static byte[] icns(Map<String, Integer> types) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        for (Map.Entry<String, Integer> e : types.entrySet()) {
            byte[] img = png(e.getValue());
            d.writeBytes(e.getKey());
            d.writeInt(img.length + 8);
            d.write(img);
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(all);
        h.writeBytes("icns");
        h.writeInt(body.size() + 8);
        body.writeTo(all);
        return all.toByteArray();
    }
}
