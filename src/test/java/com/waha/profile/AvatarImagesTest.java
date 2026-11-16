package com.waha.profile;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AvatarImagesTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void anyPictureBecomesASmallSquareJpeg() throws Exception {
        byte[] result = AvatarImages.process(png(1000, 500));
        assertEquals((byte) 0xFF, result[0]);
        assertEquals((byte) 0xD8, result[1]);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(256, back.getWidth());
        assertEquals(256, back.getHeight());
        assertTrue(result.length < 100_000);
    }

    @Test
    void smallPicturesAreScaledUpToTheSameSize() throws Exception {
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(AvatarImages.process(png(40, 60))));
        assertEquals(256, back.getWidth());
    }

    @Test
    void nonPicturesAndHugePicturesAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AvatarImages.process("<html>hello</html>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> AvatarImages.process(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> AvatarImages.process(png(9000, 2)));
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "gif", gif);
        assertThrows(IllegalArgumentException.class, () -> AvatarImages.process(gif.toByteArray()));
    }
}
