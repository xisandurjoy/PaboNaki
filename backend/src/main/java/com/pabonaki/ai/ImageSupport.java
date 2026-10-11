package com.pabonaki.ai;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.regex.Pattern;

/** Pure helpers for validating images and URLs. No Spring dependencies so they are easy to unit test. */
public final class ImageSupport {
    private ImageSupport() {}

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    /** Reject images larger than this many pixels before any full decode (decompression-bomb guard). */
    public static final long MAX_PIXELS = 25_000_000L;

    /** Detects the real type from magic bytes. Only JPEG and PNG are accepted by gemini-embedding-2. */
    public static String detectMime(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return JPEG;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return PNG;
        return null;
    }

    /** Reads width and height from the image header only (does not decode pixels). Returns null if unreadable. */
    public static int[] dimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(in, true, true);
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Throws IllegalArgumentException with a customer-friendly message when the image is not acceptable. */
    public static String validate(byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Choose an image first.");
        if (bytes.length > maxBytes)
            throw new IllegalArgumentException("Image must be " + (maxBytes / (1024 * 1024)) + " MB or smaller.");
        String mime = detectMime(bytes);
        if (mime == null) throw new IllegalArgumentException("Please upload a JPG or PNG image.");
        int[] d = dimensions(bytes);
        if (d == null) throw new IllegalArgumentException("That file does not look like a valid image.");
        if (d[0] < 16 || d[1] < 16) throw new IllegalArgumentException("That image is too small to search with.");
        if ((long) d[0] * d[1] > MAX_PIXELS) throw new IllegalArgumentException("That image has too many pixels. Please use a smaller photo.");
        return mime;
    }

    /** True only for addresses that are routable on the public internet (SSRF guard). */
    public static boolean isPublicAddress(InetAddress a) {
        if (a == null) return false;
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] raw = a.getAddress();
        if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) return false;          // IPv6 unique local fc00::/7
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF, b1 = raw[1] & 0xFF;
            if (b0 == 0) return false;                                           // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;                // CGNAT 100.64.0.0/10
            if (b0 == 169 && b1 == 254) return false;                            // link-local / cloud metadata
            if (b0 >= 240) return false;                                         // reserved
        }
        return true;
    }

    private static final Pattern TRANSFORM_SEGMENT = Pattern.compile("^([a-z]{1,3}_[^/]*|v\\d+)$");

    /**
     * Cloudinary can hand out JPEG renditions of any upload. Asking for f_jpg,w_768 means WEBP/AVIF uploads
     * can still be embedded, and keeps downloads small. Non-Cloudinary URLs are returned unchanged.
     */
    public static String cloudinaryJpeg(String url) {
        if (url == null) return null;
        String marker = "://res.cloudinary.com/";
        int i = url.indexOf(marker);
        int up = url.indexOf("/image/upload/");
        if (i < 0 || up < 0) return url;
        String head = url.substring(0, up + "/image/upload/".length());
        String rest = url.substring(head.length());
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        if (first.contains(",") || (TRANSFORM_SEGMENT.matcher(first).matches() && !first.matches("v\\d+"))) return url; // already transformed
        return head + "f_jpg,w_768,c_limit/" + rest;
    }
}
