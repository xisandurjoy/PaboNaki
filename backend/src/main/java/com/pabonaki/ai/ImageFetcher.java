package com.pabonaki.ai;

import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Loads a product image for embedding. Handles three cases safely:
 * 1) site-relative paths such as /media/x.jpg are read from the bundled static folder (no network, no path traversal);
 * 2) absolute https/http URLs are fetched with redirects disabled, a size cap, timeouts and a public-address check (SSRF guard);
 * 3) everything else is rejected.
 */
public class ImageFetcher {
    public record Fetched(byte[] bytes, String mime) {}

    public static class FetchException extends RuntimeException {
        public final boolean unsupported;
        public FetchException(String m, boolean unsupported) { super(m); this.unsupported = unsupported; }
    }

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final long maxBytes;

    public ImageFetcher(long maxBytes) { this.maxBytes = maxBytes; }

    public Fetched fetch(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) throw new FetchException("No image.", true);
        String u = imageUrl.trim();
        if (u.startsWith("/") && !u.startsWith("//")) return local(u);
        return remote(ImageSupport.cloudinaryJpeg(u));
    }

    private Fetched local(String path) {
        String clean = path.replace('\\', '/');
        if (clean.contains("..") || clean.contains("%2e") || clean.contains("%2E") || clean.indexOf('\0') >= 0)
            throw new FetchException("Unsafe image path.", true);
        int q = clean.indexOf('?'); if (q >= 0) clean = clean.substring(0, q);
        ClassPathResource res = new ClassPathResource("static" + clean);
        if (!res.exists()) throw new FetchException("Local image not found.", false);
        try (InputStream in = res.getInputStream()) { return check(readLimited(in)); }
        catch (IOException e) { throw new FetchException("Could not read local image.", false); }
    }

    private Fetched remote(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw new FetchException("Invalid image URL.", true); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) || uri.getHost() == null)
            throw new FetchException("Unsupported image URL.", true);
        if (uri.getUserInfo() != null) throw new FetchException("Unsupported image URL.", true);
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost()))
                if (!ImageSupport.isPublicAddress(a)) throw new FetchException("Image host is not publicly reachable.", true);
        } catch (java.net.UnknownHostException e) {
            throw new FetchException("Image host could not be resolved.", false);
        }
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "image/jpeg,image/png").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() != 200) throw new FetchException("Image download failed (HTTP " + res.statusCode() + ").", false);
                return check(readLimited(in));
            }
        } catch (IOException e) {
            throw new FetchException("Image download failed.", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Image download interrupted.", false);
        }
    }

    private byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n; long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new FetchException("Image is too large to index.", true);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Fetched check(byte[] bytes) {
        String mime = ImageSupport.detectMime(bytes);
        if (mime == null) throw new FetchException("Image format is not JPEG or PNG.", true);
        int[] d = ImageSupport.dimensions(bytes);
        if (d == null || (long) d[0] * d[1] > ImageSupport.MAX_PIXELS) throw new FetchException("Image dimensions are not acceptable.", true);
        return new Fetched(bytes, mime);
    }
}
