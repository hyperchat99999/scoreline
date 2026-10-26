package app.scoreline.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * How far through a match we are, read like a clock: one rounded track per period with its name
 * underneath ("1st half", "Q3"), a knob where play is now, and optionally the goals above the
 * track, each labelled with who scored and when.
 */
public final class ProgressLine extends View {
    /** A goal (or any moment) to label above the track. */
    public static final class Mark {
        final float at;
        final String text;
        final int color;

        /** {@code at}: 0..1 along the match. */
        public Mark(float at, String text, int color) {
            this.at = at;
            this.text = text;
            this.color = color;
        }
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int[] segments = {1};
    private String[] labels = {""};
    private float progress;
    private boolean live;
    private List<Mark> marks = new ArrayList<>();
    private int labelColor;
    private int currentLabelColor;

    private final float trackHeight;
    private final float gap;
    private final float knob;
    private final float labelGap;
    private final float markRow;
    /** Which of two rows above the track each mark sits in, so close goals don't overlap. */
    private int[] rows = new int[0];
    private int rowCount;

    public ProgressLine(Context context, AttributeSet attrs) {
        super(context, attrs);
        // The layout's minHeight sets the track thickness: 4dp on cards, 6dp on the match screen.
        trackHeight = Math.max(dp(3), getMinimumHeight());
        setMinimumHeight(0);
        gap = dp(4);
        knob = trackHeight / 2f + dp(3);
        labelGap = dp(6);
        markRow = sp(16) + dp(6);
        boolean large = trackHeight >= dp(5);
        label.setTextSize(sp(large ? 12 : 11));
        label.setTextAlign(Paint.Align.CENTER);
        markText.setTextSize(sp(12));
        markText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        markText.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param labels    one name per segment; blank names are skipped.
     * @param progress  0..1.
     * @param live      draw the knob at {@code progress}.
     * @param marks     labelled points above the track, or null.
     * @param ringColor the colour behind the view, used to cut the knob out of the track.
     */
    public void set(int[] segments, String[] labels, float progress, boolean live, List<Mark> marks,
                    int trackColor, int fillColor, int labelColor, int currentLabelColor, int ringColor) {
        this.segments = segments.length == 0 ? new int[] {1} : segments;
        this.labels = labels != null && labels.length == this.segments.length ? labels : new String[this.segments.length];
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.live = live;
        this.marks = marks == null ? new ArrayList<>() : marks;
        this.labelColor = labelColor;
        this.currentLabelColor = currentLabelColor;
        track.setColor(trackColor);
        fill.setColor(fillColor);
        ring.setColor(ringColor);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        layoutMarks(width);
        float h = rowCount * markRow + Math.max(trackHeight, knob * 2) + (hasLabels() ? labelGap + label.getTextSize() : 0);
        setMeasuredDimension(width, (int) Math.ceil(h + dp(1)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float centre = rowCount * markRow + Math.max(trackHeight, knob * 2) / 2f;
        float top = centre - trackHeight / 2f;
        float r = trackHeight / 2f;
        int total = 0;
        for (int s : segments) total += s;
        float usable = w - gap * (segments.length - 1);
        float done = progress * total;
        int current = currentSegment(total);

        float x = 0;
        Paint.FontMetrics fm = label.getFontMetrics();
        float labelBaseline = top + trackHeight + labelGap - fm.ascent;
        for (int i = 0; i < segments.length; i++) {
            float len = usable * segments[i] / total;
            rect.set(x, top, x + len, top + trackHeight);
            canvas.drawRoundRect(rect, r, r, track);
            float filled = Math.max(0, Math.min(segments[i], done));
            if (filled > 0) {
                rect.set(x, top, x + Math.max(trackHeight, len * filled / segments[i]), top + trackHeight);
                canvas.drawRoundRect(rect, r, r, fill);
            }
            String name = labels[i];
            if (name != null && !name.isEmpty() && label.measureText(name) <= len + gap) {
                boolean now = live && i == current;
                label.setColor(now ? currentLabelColor : labelColor);
                label.setTypeface(now ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
                canvas.drawText(name, x + len / 2f, labelBaseline, label);
            }
            done -= segments[i];
            x += len + gap;
        }

        // Goals hang from their labels as thin ticks in the team's colour, so the knob stays the
        // only dot on the line. Labels use the text colour: some teams' colours are near white.
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            float px = position(m.at, w);
            float baseline = (rows[i] + 1) * markRow - markText.getFontMetrics().descent - dp(2);
            markDot.setColor(m.color);
            rect.set(px - dp(1), baseline + dp(3), px + dp(1), top + trackHeight);
            canvas.drawRoundRect(rect, dp(1), dp(1), markDot);
            markText.setColor(currentLabelColor);
            float half = markText.measureText(m.text) / 2f;
            canvas.drawText(m.text, Math.max(half, Math.min(w - half, px)), baseline, markText);
        }

        if (live && progress > 0f && progress < 1f) {
            float px = position(progress, w);
            canvas.drawCircle(px, centre, knob, ring);
            canvas.drawCircle(px, centre, knob - dp(2), fill);
        }
    }

    /** Index of the segment {@code progress} falls in. */
    private int currentSegment(int total) {
        float at = progress * total;
        int end = 0;
        for (int i = 0; i < segments.length; i++) {
            end += segments[i];
            if (at < end) return i;
        }
        return segments.length - 1;
    }

    /** Maps 0..1 of match time to x, skipping the gaps between periods. */
    private float position(float at, float w) {
        int total = 0;
        for (int s : segments) total += s;
        float usable = w - gap * (segments.length - 1);
        float units = at * total;
        float x = 0;
        for (int s : segments) {
            if (units <= s) return Math.max(knob, Math.min(w - knob, x + usable * units / total));
            units -= s;
            x += usable * s / total + gap;
        }
        return w - knob;
    }

    /** Greedy two-row layout: a mark moves up a row only when it would overlap its neighbour. */
    private void layoutMarks(int width) {
        rows = new int[marks.size()];
        rowCount = 0;
        float[] rowEnd = {-Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            float half = markText.measureText(m.text) / 2f + dp(4);
            float px = Math.max(half, Math.min(width - half, position(m.at, width)));
            int row = px - half >= rowEnd[0] ? 0 : px - half >= rowEnd[1] ? 1 : 0;
            rows[i] = row;
            rowEnd[row] = px + half;
            rowCount = Math.max(rowCount, row + 1);
        }
        // Row 0 is nearest the track, so draw rows bottom-up.
        for (int i = 0; i < rows.length; i++) rows[i] = rowCount - 1 - rows[i];
    }

    private boolean hasLabels() {
        for (String l : labels) if (l != null && !l.isEmpty()) return true;
        return false;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
