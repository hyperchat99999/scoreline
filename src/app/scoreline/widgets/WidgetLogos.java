package app.scoreline.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import app.scoreline.App;
import app.scoreline.core.Images;
import app.scoreline.model.Match;
import app.scoreline.model.Team;

/** Small event-driven bridge from the shared image cache to complete launcher replacements. */
final class WidgetLogos {
    /** Sharp on the moment banner's 64dp disc at this phone's density; one size shared by every slot. */
    static final int PIXELS = 192;
    private static final int MAX_PENDING = 8;
    private static final long RETRY_AFTER = 15 * 60_000L;
    private static final Set<String> PENDING = new HashSet<>();
    private static final Map<String, Long> RETRY = new LinkedHashMap<String, Long>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 64; }
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean updateQueued;

    private WidgetLogos() { }

    private static final Map<String, Integer> CREST_COLORS = new LinkedHashMap<String, Integer>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) { return size() > 96; }
    };

    /**
     * The crest's own dominant colour, for teams the feed gives no colour: PSG navy, Roma maroon. Reads
     * only the in-memory crest, buckets its saturated pixels by hue and averages the heaviest bucket.
     * Returns 0 while the crest is not loaded or when it is monochrome.
     */
    static synchronized int crestColor(Context context, Team team) {
        if (team == null) return 0;
        Integer known = CREST_COLORS.get(team.id);
        if (known != null) return known;
        Bitmap crest = cached(context, team, false);
        if (crest == null) crest = cached(context, team, true);
        if (crest == null) return 0;
        Bitmap small = Bitmap.createScaledBitmap(crest, 24, 24, true);
        float[] weight = new float[12], r = new float[12], g = new float[12], b = new float[12], hsv = new float[3];
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
            int c = small.getPixel(x, y);
            if (Color.alpha(c) < 200) continue;
            Color.colorToHSV(c, hsv);
            if (hsv[1] < .3f || hsv[2] < .18f) continue;
            int bin = Math.min(11, (int) (hsv[0] / 30f));
            float w = hsv[1] * hsv[2];
            weight[bin] += w; r[bin] += Color.red(c) * w; g[bin] += Color.green(c) * w; b[bin] += Color.blue(c) * w;
        }
        if (small != crest) small.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        int color = weight[best] < 6f ? 0 : Color.rgb((int) (r[best] / weight[best]), (int) (g[best] / weight[best]), (int) (b[best] / weight[best]));
        CREST_COLORS.put(team.id, color);
        return color;
    }

    /**
     * For a team the feed gives no colour: loads its crest if it is not in memory yet, then runs
     * {@code done} on the main thread so the caller can redraw in the crest's colour. Does nothing
     * when the colour is already known or readable now.
     */
    static void whenCrestRead(Context context, Team team, Runnable done) {
        if (team == null || (team.color != null && !team.color.trim().isEmpty())) return;
        synchronized (WidgetLogos.class) { if (CREST_COLORS.containsKey(team.id)) return; }
        if (cached(context, team, false) != null || cached(context, team, true) != null) return;
        String url = team.crest(false);
        if (url == null || url.isEmpty()) return;
        images(context).loadAsync(url, PIXELS, bitmap -> { if (bitmap != null) done.run(); });
    }

    /** Memory only. RemoteViews creation never waits for disk or the network. */
    static Bitmap cached(Context context, Team team, boolean dark) {
        if (team == null) return null;
        Bitmap preferred = images(context).cached(team.crest(dark), PIXELS);
        return preferred != null || !dark ? preferred : images(context).cached(team.logo, PIXELS);
    }

    static void request(Context context, Match match, boolean dark) {
        if (match == null) return;
        if (match.home != null) request(context, match.home.team, dark);
        if (match.away != null) request(context, match.away.team, dark);
    }

    private static Images images(Context context) { return ((App) context.getApplicationContext()).images(); }

    private static void request(Context context, Team team, boolean dark) {
        String preferred = team.crest(dark);
        request(context, preferred, preferred.equals(team.logo) ? "" : team.logo);
    }

    private static void request(Context context, String url, String fallback) {
        if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://")) || images(context).cached(url, PIXELS) != null) return;
        String key = Images.sized(url, PIXELS);
        boolean coolingDown;
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING || PENDING.contains(key)) return;
            Long retry = RETRY.get(key);
            coolingDown = retry != null && retry > System.currentTimeMillis();
            if (!coolingDown) PENDING.add(key);
        }
        if (coolingDown) { if (!fallback.isEmpty()) request(context, fallback, ""); return; }
        Context app = context.getApplicationContext();
        images(context).loadAsync(url, PIXELS, bitmap -> {
            synchronized (PENDING) {
                PENDING.remove(key);
                if (bitmap == null) RETRY.put(key, System.currentTimeMillis() + RETRY_AFTER);
                else RETRY.remove(key);
            }
            if (bitmap == null) { if (!fallback.isEmpty()) request(app, fallback, ""); return; }
            synchronized (PENDING) {
                if (updateQueued) return;
                updateQueued = true;
            }
            MAIN.postDelayed(() -> {
                synchronized (PENDING) { updateQueued = false; }
                // Read selection and scores again: an old image callback cannot restore an old match.
                SportsWidgetProvider.updateAll(app);
            }, 100);
        });
    }
}
