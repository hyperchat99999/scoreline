package app.scoreline.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Minimal HTTP GET with a disk cache. Every response is stored by URL; callers say how old a cached
 * copy may be. When the network fails, a stale copy is returned and flagged rather than an error.
 */
public final class Http {

    /** A response body and whether it came from an out-of-date cache because the network failed. */
    public static final class Body {
        public final byte[] bytes;
        public final boolean stale;
        /** When these bytes were fetched, including when served from disk. */
        public final long fetchedAt;

        Body(byte[] bytes, boolean stale, long fetchedAt) {
            this.bytes = bytes;
            this.stale = stale;
            this.fetchedAt = fetchedAt;
        }

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final int TIMEOUT_MS = 12_000;
    private static final String USER_AGENT = "Scoreline/1.0 (Android)";

    private final File dir;
    private final Object[] locks = new Object[32];
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private long lastPrune;

    public Http(File dir) {
        this.dir = dir;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Returns the body of {@code url}, reusing a cached copy younger than {@code maxAgeMs}. */
    public Body get(String url, long maxAgeMs) throws IOException {
        synchronized (locks[(url.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            return getLocked(url, maxAgeMs);
        }
    }

    private Body getLocked(String url, long maxAgeMs) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        File file = new File(dir, sha1(url));
        if (maxAgeMs > 0 && file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) {
            try {
                return new Body(read(file), false, file.lastModified());
            } catch (IOException ignored) {
                // Fall through to the network.
            }
        }
        try {
            byte[] body = fetch(url);
            if (body.length > 0) { write(file, body); trimIfNeeded(); }
            return new Body(body, false, System.currentTimeMillis());
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            if (file.exists()) return new Body(read(file), true, file.lastModified());
            throw e;
        }
    }

    /** Deletes cached responses older than {@code maxAgeMs}. */
    private synchronized void trimIfNeeded() {
        if (System.currentTimeMillis() - lastPrune < 60_000) return;
        lastPrune = System.currentTimeMillis();
        prune(21L * 24 * 3600_000);
    }

    public synchronized void prune(long maxAgeMs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File f : files) if (!f.getName().endsWith(".tmp")) total += f.length();
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) continue;
            long size = f.length();
            //noinspection ResultOfMethodCallIgnored
            if ((f.lastModified() < cutoff || total > MAX_BYTES) && f.delete()) total -= size;
        }
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for " + url);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream body = in) {
                return readAll(body);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static void write(File f, byte[] bytes) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if (out.size() + n > 8 * 1024 * 1024) throw new IOException("Response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
