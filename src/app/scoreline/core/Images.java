package app.scoreline.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.scoreline.R;

/**
 * Loads crests and logos. ESPN's image CDN resizes on the fly, so we always request the size we
 * draw at; decoded bitmaps live in a small memory cache and the bytes in the HTTP disk cache.
 */
public final class Images {
    private static final long DISK_MAX_AGE = 14L * 24 * 3600 * 1000;
    private static final String CDN = "https://a.espncdn.com";

    private final Http http;
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    public Images(Http http) {
        this.http = http;
        int kb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 16, 24 * 1024);
        memory = new LruCache<>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** Rewrites an ESPN image URL to a CDN-resized square of {@code px}. Other URLs pass through. */
    public static String sized(String url, int px) {
        if (url == null || url.isEmpty()) return "";
        url = url.replace("http://", "https://");
        String path;
        if (url.startsWith(CDN + "/combiner/i?img=")) {
            String img = url.substring((CDN + "/combiner/i?img=").length());
            int amp = img.indexOf('&');
            path = amp < 0 ? img : img.substring(0, amp);
        } else if (url.startsWith(CDN + "/i/")) {
            path = url.substring(CDN.length());
        } else {
            return url;
        }
        return CDN + "/combiner/i?img=" + path + "&w=" + px + "&h=" + px;
    }

    /** Loads into {@code view} asynchronously with a short fade. Safe to call while views are recycled. */
    public void into(ImageView view, String url, int px) {
        into(view, url, "", px);
    }

    /** A published light crest remains useful when a dark CDN variant is unavailable. */
    public void into(ImageView view, String url, String fallbackUrl, int px) {
        String src = sized(url, px);
        String fallback = sized(fallbackUrl, px);
        view.setTag(R.id.tag_image, src);
        if (src.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = memory.get(src);
        if (cached == null && !fallback.isEmpty()) cached = memory.get(fallback);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = decode(src);
            if (bmp == null && !fallback.isEmpty() && !fallback.equals(src)) bmp = decode(fallback);
            Bitmap loaded = bmp;
            main.post(() -> {
                if (loaded == null || !src.equals(view.getTag(R.id.tag_image))) return;
                view.setAlpha(0f);
                view.setImageBitmap(loaded);
                view.animate().alpha(1f).setDuration(160).start();
            });
        });
    }

    /** Blocking load for notification art. Returns null when offline and uncached. */
    public Bitmap load(String url, int px) {
        String src = sized(url, px);
        return src.isEmpty() ? null : decode(src);
    }

    /** Nonblocking read for RemoteViews receivers. Disk/network work belongs in loadAsync. */
    public Bitmap cached(String url, int px) {
        String src = sized(url, px);
        return src.isEmpty() ? null : memory.get(src);
    }

    /** Reuses the bounded crest executor and HTTP disk cache; completion runs on the main thread. */
    public void loadAsync(String url, int px, java.util.function.Consumer<Bitmap> done) {
        String src = sized(url, px);
        if (src.isEmpty()) { main.post(() -> done.accept(null)); return; }
        Bitmap cached = memory.get(src);
        if (cached != null) { main.post(() -> done.accept(cached)); return; }
        pool.execute(() -> {
            Bitmap bitmap = decode(src);
            main.post(() -> done.accept(bitmap));
        });
    }

    private Bitmap decode(String src) {
        Bitmap cached = memory.get(src);
        if (cached != null) return cached;
        Bitmap bmp = fetch(src);
        if (bmp == null && src.contains("/combiner/i?img=")) {
            // The resizer occasionally returns an empty image; fall back to the original and scale here.
            String path = src.substring(src.indexOf("img=") + 4, src.indexOf('&'));
            bmp = fetch(CDN + path);
            int px = Integer.parseInt(src.substring(src.lastIndexOf("h=") + 2));
            if (bmp != null && bmp.getWidth() > px * 2) bmp = Bitmap.createScaledBitmap(bmp, px, px * bmp.getHeight() / bmp.getWidth(), true);
        }
        if (bmp != null) memory.put(src, bmp);
        return bmp;
    }

    private Bitmap fetch(String url) {
        try {
            byte[] bytes = http.get(url, DISK_MAX_AGE).bytes;
            return bytes.length == 0 ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
