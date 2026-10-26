package app.scoreline.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;

/** Shared styling for the app's few interactive elements, so every screen states them the same way. */
final class Views {
    private Views() {}

    static void followButton(TextView b, boolean following) {
        Context c = b.getContext();
        b.setText(following ? R.string.following : R.string.follow);
        b.setBackgroundResource(following ? R.drawable.bg_btn_tonal : R.drawable.bg_btn_outline);
        int fg = c.getColor(following ? R.color.on_primary_container : R.color.on_surface);
        b.setTextColor(fg);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(following ? tinted(c, R.drawable.ic_check, fg) : null, null, null, null);
    }

    static void islandButton(TextView b, boolean on, boolean large) {
        Context c = b.getContext();
        if (large) {
            b.setText(on ? R.string.showing_in_island : R.string.show_in_island);
            b.setBackgroundResource(on ? R.drawable.bg_btn_filled : R.drawable.bg_btn_tonal);
        } else {
            b.setText(on ? R.string.in_island : R.string.island);
            b.setBackgroundResource(on ? R.drawable.bg_btn_filled : R.drawable.bg_btn_outline);
        }
        int fg = c.getColor(on ? R.color.on_primary : large ? R.color.on_primary_container : R.color.on_surface);
        b.setTextColor(fg);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(c, on ? R.drawable.ic_island_on : R.drawable.ic_island, fg), null, null, null);
        b.setSelected(on);
    }

    /** Live: a red pill with a dot and the clock. Otherwise quiet text. */
    static void status(TextView v, Match m, String quietText) {
        Context c = v.getContext();
        if (m.isLive()) {
            String words = Status.full(m);
            v.setText(words.isEmpty() ? c.getString(R.string.live) : words);
            v.setBackgroundResource(R.drawable.bg_pill_live);
            v.setTextColor(c.getColor(R.color.on_live));
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_live, 0, 0, 0);
            v.setContentDescription(c.getString(R.string.live) + " " + v.getText());
        } else {
            v.setText(quietText);
            v.setBackground(null);
            v.setTextColor(c.getColor(R.color.on_surface_variant));
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            v.setContentDescription(null);
        }
    }

    /** The quiet status for a match that isn't live: kick-off time, or the result and its day. */
    static String quietStatus(Context c, Match m, boolean withDay) {
        switch (m.state) {
            case PRE:
                return withDay ? Ui.when(c, m.start) : Ui.time(c, m.start);
            case POST:
                // The source's own status can be richer ("Final/OT", "FT (Pens)"); otherwise the shared word.
                String done = m.status.isEmpty() ? Status.finished(m) : m.status;
                return Ui.isToday(m.start) ? done : done + " · " + Ui.day(c, m.start);
            default:
                return m.status;
        }
    }

    /**
     * A live card's background: the card surface with a faint wash of the home colour in the top
     * corner (beside the home row) and the away colour in the bottom one, keeping the ripple.
     */
    static Drawable liveCard(Context c, int home, int away) {
        float r = c.getResources().getDimension(R.dimen.card_radius);
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(r);
        surface.setColor(c.getColor(R.color.surface));
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {tint(c, home), Color.TRANSPARENT, tint(c, away)});
        wash.setCornerRadius(r);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(r);
        mask.setColor(Color.WHITE);
        TypedValue highlight = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, highlight, true);
        return new RippleDrawable(ColorStateList.valueOf(highlight.data), new LayerDrawable(new Drawable[] {surface, wash}), mask);
    }

    /** The match header's backdrop: home colour on the left, away on the right, fading into the page. */
    static Drawable heroBackdrop(Context c, int home, int away) {
        GradientDrawable teams = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {tint(c, home), tint(c, away)});
        int bg = c.getColor(R.color.bg);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {bg & 0x00FFFFFF, bg & 0x00FFFFFF, bg});
        return new LayerDrawable(new Drawable[] {teams, fade});
    }

    /** A team colour faint enough to sit behind text: stronger at night, when colours read darker. */
    private static int tint(Context c, int color) {
        int alpha = Ui.isNight(c) ? 0x47 : 0x2E;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** Title on one line, a smaller secondary line under it, for rows like switches. */
    static CharSequence twoLine(Context c, int title, int body) {
        String t = c.getString(title);
        String b = c.getString(body);
        SpannableString s = new SpannableString(t + "\n" + b);
        int from = t.length() + 1;
        s.setSpan(new RelativeSizeSpan(0.86f), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(c.getColor(R.color.on_surface_variant)), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    static void confirm(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }
}
