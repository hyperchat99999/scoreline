package app.scoreline.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.ImageView;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.model.Team;

/**
 * A team crest or competition logo. Until the image arrives (or if it never does) it draws a
 * monogram on the team's colour, so a row never shows an empty hole.
 */
public final class CrestView extends ImageView {
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String monogram = "";

    public CrestView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.FIT_CENTER);
        letters.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letters.setTextAlign(Paint.Align.CENTER);
        letters.setColor(Color.WHITE);
    }

    public void setTeam(Team team) {
        set(team.crest(Ui.isNight(getContext())), team.abbr, team.color);
    }

    public void set(String url, String label, String color) {
        monogram = label == null ? "" : label.substring(0, Math.min(3, label.length())).toUpperCase();
        // Team colour when we know it; otherwise a quiet tonal disc that doesn't shout while loading.
        int known = Ui.color(color, 0);
        disc.setColor(known != 0 ? Ui.capsule(known) : getContext().getColor(R.color.surface_high));
        letters.setColor(known != 0 ? Color.WHITE : getContext().getColor(R.color.on_surface_variant));
        int px = Math.max(getLayoutParams() == null ? 0 : getLayoutParams().width, Ui.dp(getContext(), 32));
        ((App) getContext().getApplicationContext()).images().into(this, url, Math.min(256, px * 2));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getDrawable() == null && !monogram.isEmpty()) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, disc);
            letters.setTextSize(r * (monogram.length() > 2 ? 0.62f : 0.78f));
            float y = getHeight() / 2f - (letters.descent() + letters.ascent()) / 2f;
            canvas.drawText(monogram, getWidth() / 2f, y, letters);
            return;
        }
        super.onDraw(canvas);
    }
}
