package app.scoreline.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.content.Context;
import android.util.LruCache;

import app.scoreline.model.Sport;
import app.scoreline.R;

/** Shared, locally drawn sport illustrations for native and RemoteViews surfaces. No network art. */
public final class SportVisuals {
    private static final LruCache<String, Bitmap> ART = new LruCache<>(21);
    private SportVisuals() { }

    public static int accent(Sport sport) {
        switch (sport) {
            case SOCCER: return 0xFF168650;
            case CRICKET: return 0xFF087F8C;
            case BASKETBALL: return 0xFFE56818;
            case RACING: return 0xFFD92632;
            case FOOTBALL: return 0xFFAD632C;
            case HOCKEY: return 0xFF008B9C;
            default: return 0xFF5266D5;
        }
    }

    /** Keeps the hue, tames neon saturation and sets a value white text reads on; greys give fallback. */
    public static int tone(int color, int fallback) {
        if (color == 0) return fallback;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < .2f) return fallback;
        hsv[1] = Math.min(hsv[1], .74f);
        hsv[2] = Math.max(.34f, Math.min(hsv[2], .56f));
        return Color.HSVToColor(hsv);
    }

    /** The tintable sport glyph (Recraft set) used for watermarks and moment art. */
    public static int glyph(Sport sport) {
        switch (sport) {
            case CRICKET: return R.drawable.glyph_cricket;
            case BASKETBALL: return R.drawable.glyph_basketball;
            case RACING: return R.drawable.glyph_racing;
            case FOOTBALL: return R.drawable.glyph_football;
            case HOCKEY: return R.drawable.glyph_hockey;
            case BASEBALL: return R.drawable.glyph_baseball;
            default: return R.drawable.glyph_soccer;
        }
    }

    public static int ink(Context context, Sport sport) { return Ui.onCard(accent(sport), Ui.isNight(context)); }

    public static int panel(Context context, Sport sport) {
        return Ui.mix(accent(sport), context.getColor(R.color.surface), Ui.isNight(context) ? .74f : .80f);
    }

    /** Small full-replacement bitmap, shared by both widget variants and cached with a strict bound. */
    public static synchronized Bitmap art(Sport sport, int accent, boolean dark) {
        String key = sport.name() + ":" + accent + ":" + dark;
        Bitmap cached = ART.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(dark ? Ui.mix(accent, Color.WHITE, .45f) : accent);
        line.setStrokeWidth(3f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setAlpha(185);
        Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
        wash.setColor(accent);
        wash.setAlpha(dark ? 45 : 26);
        switch (sport) {
            case BASKETBALL:
                canvas.drawRoundRect(10, 12, 230, 168, 12, 12, line);
                canvas.drawLine(120, 12, 120, 168, line);
                canvas.drawCircle(120, 90, 28, line);
                canvas.drawRect(10, 53, 53, 127, line);
                canvas.drawRect(187, 53, 230, 127, line);
                canvas.drawArc(new RectF(-67, 12, 111, 168), -62, 124, false, line);
                canvas.drawArc(new RectF(129, 12, 307, 168), 118, 124, false, line);
                canvas.drawCircle(176, 49, 31, wash);
                canvas.drawCircle(176, 49, 31, line);
                canvas.drawLine(145, 49, 207, 49, line);
                canvas.drawLine(176, 18, 176, 80, line);
                canvas.drawArc(new RectF(139, 18, 180, 80), -70, 140, false, line);
                canvas.drawArc(new RectF(172, 18, 213, 80), 110, 140, false, line);
                break;
            case SOCCER:
                for (int x = 12; x < 230; x += 52) canvas.drawRect(x, 14, x + 26, 166, wash);
                canvas.drawRoundRect(10, 12, 230, 168, 10, 10, line);
                canvas.drawLine(120, 12, 120, 168, line);
                canvas.drawCircle(120, 90, 27, line);
                canvas.drawRect(10, 48, 47, 132, line);
                canvas.drawRect(193, 48, 230, 132, line);
                canvas.drawRect(10, 69, 25, 111, line);
                canvas.drawRect(215, 69, 230, 111, line);
                ball(canvas, line, wash, 181, 136, 24);
                break;
            case CRICKET:
                canvas.drawOval(19, 8, 221, 172, wash);
                canvas.drawOval(19, 8, 221, 172, line);
                canvas.drawRoundRect(89, 13, 151, 167, 8, 8, wash);
                canvas.drawLine(77, 43, 163, 43, line);
                canvas.drawLine(77, 137, 163, 137, line);
                canvas.drawLine(94, 28, 94, 152, line);
                canvas.drawLine(146, 28, 146, 152, line);
                for (int x = 108; x <= 132; x += 12) canvas.drawLine(x, 20, x, 43, line);
                canvas.drawLine(105, 19, 135, 19, line);
                Paint red = new Paint(line);
                red.setColor(dark ? 0xFFFFA18C : 0xFFD34C36);
                red.setAlpha(235);
                canvas.drawCircle(184, 57, 21, red);
                canvas.drawArc(new RectF(171, 37, 189, 77), -85, 170, false, red);
                break;
            case RACING:
                Path track = new Path();
                track.moveTo(-10, 146); track.cubicTo(70, 18, 64, 195, 163, 119); track.cubicTo(218, 76, 182, 3, 252, 18);
                line.setStrokeWidth(16); line.setAlpha(45); canvas.drawPath(track, line);
                line.setStrokeWidth(2); line.setAlpha(190); canvas.drawPath(track, line);
                canvas.save(); canvas.rotate(-12, 122, 80);
                canvas.drawLine(75, 20, 75, 154, line);
                canvas.drawRoundRect(75, 20, 199, 94, 3, 3, line);
                Paint square = new Paint(wash); square.setAlpha(dark ? 155 : 125);
                for (int row = 0; row < 4; row++) for (int col = 0; col < 7; col++)
                    if ((row + col) % 2 == 0) canvas.drawRect(77 + col * 17, 22 + row * 17, 94 + col * 17, 39 + row * 17, square);
                canvas.restore();
                break;
            case FOOTBALL:
                canvas.drawRoundRect(10, 12, 230, 168, 10, 10, line);
                for (int x = 43; x <= 198; x += 31) canvas.drawLine(x, 12, x, 168, line);
                line.setAlpha(110);
                for (int x = 28; x < 220; x += 15) { canvas.drawLine(x, 65, x, 72, line); canvas.drawLine(x, 108, x, 115, line); }
                canvas.save(); canvas.rotate(-28, 167, 53);
                line.setAlpha(230); canvas.drawOval(127, 29, 207, 77, wash); canvas.drawOval(127, 29, 207, 77, line);
                canvas.drawLine(147, 53, 188, 53, line);
                for (int x = 152; x <= 184; x += 8) canvas.drawLine(x, 46, x, 60, line);
                canvas.restore();
                break;
            case HOCKEY:
                canvas.drawRoundRect(10, 12, 230, 168, 44, 44, wash);
                canvas.drawRoundRect(10, 12, 230, 168, 44, 44, line);
                canvas.drawLine(120, 12, 120, 168, line);
                canvas.drawLine(72, 12, 72, 168, line);
                canvas.drawLine(168, 12, 168, 168, line);
                canvas.drawCircle(120, 90, 24, line);
                for (int x : new int[] {46, 194}) for (int y : new int[] {54, 126}) canvas.drawCircle(x, y, 15, line);
                Paint puck = new Paint(wash); puck.setAlpha(220);
                canvas.drawOval(167, 119, 208, 138, puck);
                canvas.drawOval(167, 119, 208, 138, line);
                break;
            default:
                Path diamond = new Path();
                diamond.moveTo(120, 19); diamond.lineTo(211, 103); diamond.lineTo(120, 169); diamond.lineTo(29, 103); diamond.close();
                canvas.drawPath(diamond, wash); canvas.drawPath(diamond, line);
                canvas.drawLine(120, 169, 120, 99, line);
                for (int[] base : new int[][] {{120, 19}, {211, 103}, {29, 103}}) {
                    canvas.save(); canvas.rotate(45, base[0], base[1]);
                    canvas.drawRect(base[0] - 5, base[1] - 5, base[0] + 5, base[1] + 5, line); canvas.restore();
                }
                canvas.drawCircle(120, 99, 10, line);
                canvas.drawCircle(190, 43, 26, wash); canvas.drawCircle(190, 43, 26, line);
                canvas.drawArc(new RectF(166, 17, 190, 69), -75, 150, false, line);
                canvas.drawArc(new RectF(190, 17, 214, 69), 105, 150, false, line);
        }
        ART.put(key, bitmap);
        return bitmap;
    }

    private static void ball(Canvas canvas, Paint line, Paint wash, int x, int y, int radius) {
        canvas.drawCircle(x, y, radius, wash); canvas.drawCircle(x, y, radius, line);
        Path pentagon = new Path();
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2 / 5;
            float px = x + (float) Math.cos(angle) * radius * .45f;
            float py = y + (float) Math.sin(angle) * radius * .45f;
            if (i == 0) pentagon.moveTo(px, py); else pentagon.lineTo(px, py);
            canvas.drawLine(px, py, x + (float) Math.cos(angle) * radius, y + (float) Math.sin(angle) * radius, line);
        }
        pentagon.close(); canvas.drawPath(pentagon, line);
    }
}
