package app.scoreline.core;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.util.TypedValue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import app.scoreline.R;

/** Small, dependency-free helpers for units, colour and time. */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---- Colour --------------------------------------------------------------------------------

    /** Parses ESPN colours ("050ceb", "#050ceb"). Returns {@code fallback} for blanks and junk. */
    public static int color(String hex, int fallback) {
        if (hex == null) return fallback;
        hex = hex.trim();
        if (hex.isEmpty()) return fallback;
        if (hex.charAt(0) != '#') hex = "#" + hex;
        try {
            return Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static double luminance(int c) {
        return Color.luminance(c);
    }

    /** Mixes {@code c} towards {@code with} by {@code t} (0..1). */
    public static int mix(int c, int with, float t) {
        float u = 1 - t;
        return Color.rgb(
                Math.round(Color.red(c) * u + Color.red(with) * t),
                Math.round(Color.green(c) * u + Color.green(with) * t),
                Math.round(Color.blue(c) * u + Color.blue(with) * t));
    }

    /**
     * Makes a team colour usable as a filled capsule with white text on the always-black island:
     * very light colours are deepened for text contrast, near-black ones lifted so the capsule shows.
     */
    public static int capsule(int c) {
        c = c | 0xFF000000;
        for (int i = 0; i < 8 && luminance(c) > 0.30; i++) c = mix(c, Color.BLACK, 0.18f);
        if (luminance(c) < 0.025) c = mix(c, Color.WHITE, 0.22f);
        return c;
    }

    /** A team colour that reads as a fill on the given card background (light or dark). */
    public static int onCard(int c, boolean night) {
        c = c | 0xFF000000;
        double l = luminance(c);
        if (night && l < 0.05) return mix(c, Color.WHITE, 0.45f);
        if (!night && l > 0.75) return mix(c, Color.BLACK, 0.35f);
        return c;
    }

    // ---- Time ----------------------------------------------------------------------------------

    public static String time(Context c, long ms) {
        return DateFormat.getTimeFormat(c).format(ms);
    }

    /** "Today", "Tomorrow", "Yesterday" or "Sat 31 Oct", in the device's zone. */
    public static String day(Context c, long ms) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
        long diff = ChronoUnit.DAYS.between(LocalDate.now(zone), d);
        if (diff == 0) return c.getString(R.string.today);
        if (diff == 1) return c.getString(R.string.tomorrow);
        if (diff == -1) return c.getString(R.string.yesterday);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM");
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(d);
    }

    public static boolean isToday(long ms) {
        ZoneId zone = ZoneId.systemDefault();
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().equals(LocalDate.now(zone));
    }

    /** "Today, 22:00" style label for a start time. */
    public static String when(Context c, long ms) {
        return day(c, ms) + ", " + time(c, ms);
    }
}
