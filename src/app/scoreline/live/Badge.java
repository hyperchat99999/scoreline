package app.scoreline.live;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;

/**
 * The island's icon. The island draws it inside a rounded frame on black, so it needs its own
 * padding. Football fixtures use a cropped diagonal pair within the fixed square slot.
 */
final class Badge {
    private Badge() {}

    private static final int SIZE = 112;

    /** One crest or logo, centred with breathing room. */
    static Bitmap single(Bitmap crest) {
        if (crest == null) return null;
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        draw(new Canvas(out), crest, 3, 3, SIZE - 6);
        return out;
    }

    /** Home upper-left, away lower-right, with a narrow silhouette separator on black. */
    static Bitmap pair(Bitmap home, Bitmap away) {
        if (home == null) return away == null ? null : single(away);
        if (away == null) return single(home);
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        // 72px boxes instead of 53px: larger artwork without changing the system slot.
        draw(canvas, home, 2, 2, 72);
        draw(canvas, away, 38, 38, 72, 72, true);
        return out;
    }

    /**
     * The matchup for the notification shade: both crests side by side, home on the left as in the
     * title, on a transparent ground so it suits a light or dark shade.
     */
    static Bitmap versus(Bitmap home, Bitmap away) {
        if (home == null) return away == null ? null : single(away);
        if (away == null) return single(home);
        int size = 144;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        draw(canvas, home, 2, 38, 66, 68);
        draw(canvas, away, 76, 38, 66, 68);
        return out;
    }

    /** The knob riding the island's progress bar: the bar's colour inside a white ring. */
    static Bitmap tracker(int color) {
        int size = 48;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint);
        paint.setColor(color | 0xFF000000);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 9, paint);
        return out;
    }

    /** Fits {@code src} into the square at (x, y) of side {@code size}, keeping its aspect ratio. */
    private static void draw(Canvas canvas, Bitmap src, float x, float y, float size) {
        draw(canvas, src, x, y, size, size);
    }

    private static void draw(Canvas canvas, Bitmap src, float x, float y, float width, float height) {
        draw(canvas, src, x, y, width, height, false);
    }

    private static void draw(Canvas canvas, Bitmap src, float x, float y, float width, float height, boolean outlined) {
        Rect bounds = visibleBounds(src);
        float scale = Math.min(width / bounds.width(), height / bounds.height());
        float w = bounds.width() * scale;
        float h = bounds.height() * scale;
        float left = x + (width - w) / 2;
        float top = y + (height - h) / 2;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (outlined) {
            paint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                float ox = dx * 1.5f, oy = dy * 1.5f;
                canvas.drawBitmap(src, bounds, new RectF(left + ox, top + oy, left + w + ox, top + h + oy), paint);
            }
            paint.setColorFilter(null);
        }
        canvas.drawBitmap(src, bounds, new RectF(left, top, left + w, top + h), paint);
    }
    /** Ignore transparent CDN margins, retaining all visible crest pixels. */
    private static Rect visibleBounds(Bitmap src) {
        int left = src.getWidth(), top = src.getHeight(), right = -1, bottom = -1;
        int[] pixels = new int[src.getWidth() * src.getHeight()];
        src.getPixels(pixels, 0, src.getWidth(), 0, 0, src.getWidth(), src.getHeight());
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) {
            if ((pixels[y * src.getWidth() + x] >>> 24) < 24) continue;
            left = Math.min(left, x); top = Math.min(top, y);
            right = Math.max(right, x); bottom = Math.max(bottom, y);
        }
        return right < left ? new Rect(0, 0, src.getWidth(), src.getHeight())
                : new Rect(left, top, right + 1, bottom + 1);
    }

}
