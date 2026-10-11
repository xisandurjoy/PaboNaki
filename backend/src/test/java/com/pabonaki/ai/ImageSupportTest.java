package com.pabonaki.ai;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ImageSupportTest {
    private static byte[] img(String fmt) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), fmt, o);
        return o.toByteArray();
    }
    @Test void acceptsJpegAndPng() throws Exception {
        assertEquals("image/png", ImageSupport.validate(img("png"), 4 << 20));
        assertEquals("image/jpeg", ImageSupport.validate(img("jpg"), 4 << 20));
    }
    @Test void rejectsGifJunkEmptyOversizeAndFakeHeaders() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ImageSupport.validate(img("gif"), 4 << 20));
        assertThrows(IllegalArgumentException.class, () -> ImageSupport.validate(new byte[]{1, 2, 3}, 4 << 20));
        assertThrows(IllegalArgumentException.class, () -> ImageSupport.validate(new byte[0], 4 << 20));
        assertThrows(IllegalArgumentException.class, () -> ImageSupport.validate(img("png"), 10));
        byte[] fake = new byte[200]; fake[0] = (byte) 0xFF; fake[1] = (byte) 0xD8; fake[2] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> ImageSupport.validate(fake, 4 << 20));
    }
    @Test void blocksPrivateAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "172.16.0.1", "100.64.0.1", "::1", "fd00::1"})
            assertFalse(ImageSupport.isPublicAddress(InetAddress.getByName(ip)), ip);
        assertTrue(ImageSupport.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }
    @Test void cloudinaryUrlsAskForJpeg() {
        assertEquals("https://res.cloudinary.com/demo/image/upload/f_jpg,w_768,c_limit/v123/a.webp", ImageSupport.cloudinaryJpeg("https://res.cloudinary.com/demo/image/upload/v123/a.webp"));
        assertEquals("https://example.com/a.png", ImageSupport.cloudinaryJpeg("https://example.com/a.png"));
    }
}
