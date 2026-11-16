package com.waha.profile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

// Turns an uploaded picture into a small square JPEG: checks it is a real JPEG or PNG, refuses huge pictures
// before decoding them, crops the middle square, shrinks it, and saves it fresh. Saving fresh drops everything
// the original carried, such as GPS location and camera details.
final class AvatarImages {

    static {
        // The server has no screen: image work must run without one.
        System.setProperty("java.awt.headless", "true");
    }

    private AvatarImages() {}

    static final int SIZE = 256;
    static final int MAX_SIDE = 8000;
    static final long MAX_PIXELS = 40_000_000L;

    static byte[] process(byte[] original) {
        BufferedImage source = decode(original);
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);

        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);                       // a transparent PNG gets a white background
            g.fillRect(0, 0, SIZE, SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(square, 0, 0, SIZE, SIZE, null);
        } finally {
            g.dispose();
        }
        return jpeg(out);
    }

    private static BufferedImage decode(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("The file is not a picture. Use a JPEG or PNG");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                if (!format.equals("jpeg") && !format.equals("png"))
                    throw new IllegalArgumentException("Use a JPEG or PNG picture");
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS)
                    throw new IllegalArgumentException("The picture is too large");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("The picture could not be read");
        }
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("The picture could not be saved");
        } finally {
            writer.dispose();
        }
    }
}
