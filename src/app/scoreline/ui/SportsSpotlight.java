package app.scoreline.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.Property;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.scoreline.R;
import app.scoreline.core.SportVisuals;
import app.scoreline.core.Ui;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.SportsEventTracker;
import app.scoreline.model.Status;
import app.scoreline.model.Timeline;
import app.scoreline.ui.widget.CrestView;
import app.scoreline.widgets.SportsWidgetProvider;
import app.scoreline.widgets.WidgetMoments;

/**
 * The match spotlight: a broadcast-style score card washed in both teams' colours. A goal, wicket or
 * other big moment floods the card in the scoring side's colour for about a second, then the board
 * returns and the new digits pulse. Source data drives every word; motion never replaces information.
 */
public final class SportsSpotlight extends FrameLayout {
    private static final long TAKEOVER_MS = 1050;

    private final SportsEventTracker transitions = new SportsEventTracker();
    private final TextView status;
    private final ImageView eventArt;
    private final View flash;
    private AnimatorSet motion;
    private Match current;
    private long currentTime;
    private boolean active;
    private boolean quietNext = true;
    private int presentationGeneration;
    private SportsEventTracker.Kind lastEvent = SportsEventTracker.Kind.NONE;
    private final boolean compact;
    private boolean currentStale;
    private Runnable currentOpen;

    public SportsSpotlight(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_sports_spotlight, this, true);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = findViewById(R.id.spotlightStatus);
        eventArt = findViewById(R.id.spotlightEventArt);
        flash = findViewById(R.id.spotlightFlash);
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        compact = configuration.screenWidthDp < 360 || configuration.fontScale > 1.2f;
        if (compact) {
            for (int id : new int[] {R.id.spotlightHomeCrest, R.id.spotlightAwayCrest}) {
                View crest = findViewById(id);
                ViewGroup.LayoutParams bounds = crest.getLayoutParams();
                bounds.width = bounds.height = Ui.dp(context, 42);
                crest.setLayoutParams(bounds);
                int pad = Ui.dp(context, 7);
                crest.setPadding(pad, pad, pad, pad);
            }
            android.widget.LinearLayout footer = findViewById(R.id.spotlightFooter);
            footer.setOrientation(android.widget.LinearLayout.VERTICAL);
            findViewById(R.id.spotlightFreshness).setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.LinearLayout.LayoutParams action = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            action.gravity = android.view.Gravity.END;
            findViewById(R.id.spotlightOpen).setLayoutParams(action);
        }
    }

    /** A goal, wicket or lap is posted to animate on the next frame; crest redraws wait for it. */
    private boolean eventPending;

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        presentationGeneration++;
        quietNext = true;
        if (!active) stopMotion();
    }

    public void bind(Match match, long sourceTime, boolean stale, Runnable open) {
        int request = ++presentationGeneration;
        boolean same = current != null && current.key().equals(match.key()) && currentTime == sourceTime;
        Match previous = current;
        SportsEventTracker.Kind event = transitions.observe(match, sourceTime, stale,
                active && !quietNext && ValueAnimator.areAnimatorsEnabled());
        quietNext = false;
        current = match;
        currentTime = sourceTime;
        currentStale = stale;
        currentOpen = open;
        lastEvent = event;
        if (!same || motion == null) normalStatus();

        Context c = getContext();
        boolean night = Ui.isNight(c);
        boolean race = match.sport == Sport.RACING || match.home == null || match.away == null;
        int sportAccent = SportVisuals.accent(match.sport);
        int home = race ? sportAccent : SportsWidgetProvider.teamColor(c, match.home.team, match.sport, night);
        int away = race ? sportAccent : SportsWidgetProvider.teamColor(c, match.away.team, match.sport, night);
        // A team the feed gives no colour takes its crest's; redraw once that crest has loaded.
        if (!race) for (Match.Side s : new Match.Side[] {match.home, match.away})
            SportsWidgetProvider.whenCrestRead(c, s.team, () -> {
                // Not while a celebration is queued or playing: a re-bind would cancel it.
                if (current != null && current.key().equals(match.key()) && motion == null && !eventPending)
                    bind(current, currentTime, currentStale, currentOpen);
            });
        int surface = c.getColor(R.color.surface), ink = c.getColor(R.color.on_surface);

        ((TextView) findViewById(R.id.spotlightLeague)).setText(match.leagueName.isEmpty() ? c.getString(match.sport.label) : match.leagueName);
        // Both teams' colours washed in from opposite corners, like a broadcast score bug.
        float wash = night ? .72f : .86f;
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Ui.mix(home, surface, wash), surface, Ui.mix(away, surface, wash)});
        background.setCornerRadius(Ui.dp(c, 24));
        getChildAt(0).setBackground(background);
        findViewById(R.id.spotlightAccent).setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Ui.onCard(home, night), Ui.onCard(away, night)}));
        leagueMark(c, findViewById(R.id.spotlightSport), match, night);
        ImageView scene = findViewById(R.id.spotlightScene);
        scene.setImageDrawable(Views.tinted(c, SportVisuals.glyph(match.sport), Ui.mix(Ui.onCard(away, night), ink, .3f)));
        scene.setAlpha(night ? .13f : .10f);

        boolean pre = match.state == Match.State.PRE;
        findViewById(R.id.spotlightArtPanel).setVisibility(pre && !race ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.spotlightPhase)).setText(pre && match.start > 0 ? Ui.time(c, match.start) : Status.full(match));
        ((TextView) findViewById(R.id.spotlightPhaseCaption)).setText(pre && match.start > 0 ? Ui.day(c, match.start) : "");

        android.widget.ProgressBar progress = findViewById(R.id.spotlightProgress);
        Timeline timeline = Timeline.of(match);
        boolean measured = match.isLive() && !timeline.indeterminate;
        progress.setVisibility(measured ? View.VISIBLE : View.GONE);
        if (measured) {
            progress.setMax(timeline.max);
            progress.setProgress(timeline.value);
            progress.setProgressTintList(ColorStateList.valueOf(Ui.onCard(race ? sportAccent : home, night)));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.mix(ink, surface, .86f)));
        }

        findViewById(R.id.spotlightBoard).setVisibility(race ? View.GONE : View.VISIBLE);
        findViewById(R.id.spotlightRace).setVisibility(race ? View.VISIBLE : View.GONE);
        if (race) {
            ((TextView) findViewById(R.id.spotlightRaceTitle)).setText(match.title);
            StringBuilder order = new StringBuilder(match.session);
            for (int i = 0; i < Math.min(3, match.standings.size()); i++) {
                Match.Standing driver = match.standings.get(i);
                order.append('\n').append(driver.position).append("   ").append(driver.shortName.isEmpty() ? driver.name : driver.shortName);
                if (!driver.gap.isEmpty()) order.append("   ").append(driver.gap);
            }
            ((TextView) findViewById(R.id.spotlightRaceOrder)).setText(order);
        } else {
            CrestView homeCrest = findViewById(R.id.spotlightHomeCrest), awayCrest = findViewById(R.id.spotlightAwayCrest);
            homeCrest.setTeam(match.home.team);
            awayCrest.setTeam(match.away.team);
            homeCrest.setBackgroundTintList(ColorStateList.valueOf(disc(home, night)));
            awayCrest.setBackgroundTintList(ColorStateList.valueOf(disc(away, night)));
            ((TextView) findViewById(R.id.spotlightHomeName)).setText(compact && !match.home.team.abbr.isEmpty() ? match.home.team.abbr : match.home.team.shortName);
            ((TextView) findViewById(R.id.spotlightAwayName)).setText(compact && !match.away.team.abbr.isEmpty() ? match.away.team.abbr : match.away.team.shortName);
            boolean cricket = match.sport == Sport.CRICKET;
            ((TextView) findViewById(R.id.spotlightScore)).setText(cricket || pre ? c.getString(R.string.vs) : match.home.score + " – " + match.away.score);
            TextView homeScore = findViewById(R.id.spotlightHomeScore), awayScore = findViewById(R.id.spotlightAwayScore);
            homeScore.setVisibility(pre ? View.GONE : View.VISIBLE);
            awayScore.setVisibility(pre ? View.GONE : View.VISIBLE);
            homeScore.setText(match.home.score);
            awayScore.setText(match.away.score);
            // At the final whistle the loser's digits step back, as on a results board.
            boolean over = match.state == Match.State.POST;
            int muted = c.getColor(R.color.on_surface_variant);
            homeScore.setTextColor(over && match.away.winner ? muted : ink);
            awayScore.setTextColor(over && match.home.winner ? muted : ink);
            int max = cricket ? 28 : 38;
            homeScore.setAutoSizeTextTypeUniformWithConfiguration(18, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            awayScore.setAutoSizeTextTypeUniformWithConfiguration(18, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }

        TextView freshness = findViewById(R.id.spotlightFreshness);
        boolean delayed = stale || match.updatesDelayed;
        // Before kick-off the big time already says when; the footer says where.
        freshness.setText(pre ? (match.venue.isEmpty() ? "" : match.venue)
                : sourceTime > 0 ? c.getString(delayed ? R.string.spotlight_saved : R.string.spotlight_updated, Ui.time(c, sourceTime))
                : c.getString(R.string.stale_scores));
        findViewById(R.id.spotlightOpen).setOnClickListener(v -> open.run());
        setOnClickListener(v -> open.run());
        setContentDescription(c.getString(R.string.spotlight_title) + ": " + (race ? match.title + ", " + match.session
                : match.home.team.name + " " + match.home.score + ", " + match.away.team.name + " " + match.away.score));
        Match.Side side = race ? null : scorer(previous, match, event);
        if (event != SportsEventTracker.Kind.NONE) {
            eventPending = true;
            post(() -> {
                eventPending = false;
                if (request == presentationGeneration && active && isShown() && ValueAnimator.areAnimatorsEnabled()) animateEvent(event, side);
            });
        }
    }

    /** A team colour as a soft disc behind its crest. */
    private static int disc(int color, boolean night) { return (color & 0x00FFFFFF) | ((night ? 0x5C : 0x38) << 24); }

    /** Whose moment it is: the side whose score moved, or for a wicket the side bowling. */
    private static Match.Side scorer(Match before, Match after, SportsEventTracker.Kind event) {
        if (before == null || before.home == null || before.away == null || after.home == null || after.away == null
                || !before.key().equals(after.key())) return null;
        boolean home = !before.home.score.equals(after.home.score), away = !before.away.score.equals(after.away.score);
        if (home == away) return null;
        if (event == SportsEventTracker.Kind.WICKET) return home ? after.away : after.home;
        if (event == SportsEventTracker.Kind.GOAL || event == SportsEventTracker.Kind.SCORE) return home ? after.home : after.away;
        return null;
    }

    private void normalStatus() {
        if (current == null) return;
        Context c = getContext();
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        Views.status(status, current, current.state == Match.State.PRE ? c.getString(R.string.section_upcoming)
                : current.status.isEmpty() ? Status.finished(current) : current.status);
        if (!current.isLive()) {
            status.setText(current.state == Match.State.PRE ? R.string.widget_upcoming : current.state == Match.State.POST ? R.string.widget_result : R.string.widget_unavailable);
            status.setBackgroundResource(R.drawable.bg_btn_tonal);
            status.setTextColor(c.getColor(R.color.on_primary_container));
        } else {
            String clock = Status.compact(current);
            boolean fits = !clock.isEmpty() && clock.length() <= 8 && current.sport != Sport.RACING;
            status.setText(fits ? c.getString(R.string.live) + "  " + clock : c.getString(R.string.live));
        }
    }

    /**
     * The takeover. The card floods with the moment's colour, the sport's mark spins in on a white disc,
     * the word punches in with a small overshoot, then everything clears and the new digits pulse.
     */
    private void animateEvent(SportsEventTracker.Kind event, Match.Side side) {
        stopMotion();
        Context c = getContext();
        Match match = current;
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(label(event));
        status.setBackgroundResource(R.drawable.bg_btn_tonal);
        status.setTextColor(c.getColor(R.color.on_primary_container));
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);

        int color = side != null ? SportsWidgetProvider.banner(c, side.team, match.sport)
                : SportVisuals.tone(SportVisuals.accent(match.sport), 0xFF2B2B30);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {color, Ui.mix(color, 0xFF000000, .28f)});
        fill.setCornerRadius(Ui.dp(c, 24));
        flash.setBackground(fill);
        int glyph = glyph(match.sport, event);
        eventArt.setImageDrawable(Views.tinted(c, glyph, color));
        ImageView flashArt = findViewById(R.id.spotlightFlashArt);
        flashArt.setImageDrawable(Views.tinted(c, glyph, 0xFFFFFFFF));
        TextView word = findViewById(R.id.spotlightFlashWord);
        word.setText(word(c, match.sport, event));
        ((TextView) findViewById(R.id.spotlightFlashScore)).setText(match.sport == Sport.RACING || match.home == null ? match.title
                : abbr(match.home) + "  " + match.home.score + " – " + match.away.score + "  " + abbr(match.away));

        flash.setVisibility(View.VISIBLE);
        eventArt.setVisibility(View.VISIBLE);
        word.setPivotX(0);
        word.setPivotY(word.getHeight() / 2f);
        boolean spin = event == SportsEventTracker.Kind.GOAL || event == SportsEventTracker.Kind.SCORE;
        List<Animator> parts = new ArrayList<>();
        parts.add(keys(flash, View.ALPHA, 0f, 0f, .10f, 1f, .80f, 1f, 1f, 0f));
        parts.add(keys(word, View.ALPHA, 0f, 0f, .12f, 1f, 1f, 1f));
        parts.add(keys(word, View.SCALE_X, 0f, .55f, .26f, 1.12f, .40f, 1f, 1f, 1f));
        parts.add(keys(word, View.SCALE_Y, 0f, .55f, .26f, 1.12f, .40f, 1f, 1f, 1f));
        parts.add(keys(word, View.TRANSLATION_X, 0f, Ui.dp(c, -16), .30f, 0f, 1f, 0f));
        parts.add(keys(eventArt, View.ALPHA, 0f, 0f, .14f, 1f, .82f, 1f, 1f, 0f));
        parts.add(keys(eventArt, View.ROTATION, 0f, spin ? -160f : -35f, .40f, 8f, .52f, 0f, 1f, 0f));
        parts.add(keys(eventArt, View.SCALE_X, 0f, .3f, .30f, 1.18f, .45f, 1f, 1f, 1f));
        parts.add(keys(eventArt, View.SCALE_Y, 0f, .3f, .30f, 1.18f, .45f, 1f, 1f, 1f));
        parts.add(keys(flashArt, View.TRANSLATION_X, 0f, Ui.dp(c, 64), .5f, 0f, 1f, Ui.dp(c, -14)));
        if (side != null) {
            View digits = findViewById(side == match.home ? R.id.spotlightHomeScore : R.id.spotlightAwayScore);
            if (event == SportsEventTracker.Kind.WICKET) digits = findViewById(side == match.home ? R.id.spotlightAwayScore : R.id.spotlightHomeScore);
            if (digits.getVisibility() == View.VISIBLE) {
                digits.setPivotX(digits.getWidth());
                digits.setPivotY(digits.getHeight() / 2f);
                parts.add(keys(digits, View.SCALE_X, 0f, 1f, .80f, 1f, .90f, 1.28f, 1f, 1f));
                parts.add(keys(digits, View.SCALE_Y, 0f, 1f, .80f, 1f, .90f, 1.28f, 1f, 1f));
            }
        }
        parts.add(keys(status, View.SCALE_X, 0f, 1f, .5f, 1.08f, 1f, 1f));
        parts.add(keys(status, View.SCALE_Y, 0f, 1f, .5f, 1.08f, 1f, 1f));
        motion = new AnimatorSet();
        motion.playTogether(parts);
        motion.setDuration(TAKEOVER_MS);
        motion.setInterpolator(new LinearInterpolator());
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                motion = null;
                reset();
                normalStatus();
            }
        });
        motion.start();
    }

    private static String abbr(Match.Side side) {
        return side.team.abbr.isEmpty() ? side.team.shortName : side.team.abbr;
    }

    /** One property through (fraction, value) pairs, decelerating into each key. */
    private static Animator keys(View view, Property<View, Float> property, float... pairs) {
        Keyframe[] frames = new Keyframe[pairs.length / 2];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = Keyframe.ofFloat(pairs[i * 2], pairs[i * 2 + 1]);
            if (i > 0) frames[i].setInterpolator(new DecelerateInterpolator(1.6f));
        }
        return ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofKeyframe(property, frames));
    }

    private void reset() {
        flash.setVisibility(View.INVISIBLE);
        flash.setAlpha(0f);
        eventArt.setVisibility(View.INVISIBLE);
        eventArt.setAlpha(0f);
        eventArt.setRotation(0);
        eventArt.setScaleX(1f);
        eventArt.setScaleY(1f);
        for (int id : new int[] {R.id.spotlightHomeScore, R.id.spotlightAwayScore, R.id.spotlightFlashWord, R.id.spotlightFlashArt}) {
            View v = findViewById(id);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0);
        }
        status.setScaleX(1f);
        status.setScaleY(1f);
    }

    public void stopMotion() {
        if (motion != null) { motion.removeAllListeners(); motion.cancel(); motion = null; }
        reset();
        normalStatus();
    }

    @Override protected void onDetachedFromWindow() { presentationGeneration++; stopMotion(); super.onDetachedFromWindow(); }

    public boolean isAnimatingEvent() { return motion != null && motion.isRunning(); }
    public SportsEventTracker.Kind lastEvent() { return lastEvent; }

    private static int label(SportsEventTracker.Kind event) {
        switch (event) {
            case GOAL: return R.string.event_goal;
            case WICKET: return R.string.event_wicket;
            case LAP: return R.string.event_lap;
            case LEAD: return R.string.event_lead;
            case KICKOFF: return R.string.event_kickoff;
            case FINISH: return R.string.event_final;
            default: return R.string.event_score;
        }
    }

    private static String word(Context c, Sport sport, SportsEventTracker.Kind event) {
        switch (event) {
            case GOAL: return c.getString(WidgetMoments.title(sport, WidgetMoments.Kind.GOAL));
            case WICKET: return c.getString(R.string.moment_wicket);
            case LEAD: return c.getString(R.string.moment_leader);
            case LAP: return c.getString(R.string.event_lap);
            case KICKOFF: return c.getString(WidgetMoments.title(sport, WidgetMoments.Kind.KICKOFF));
            case FINISH: return c.getString(WidgetMoments.title(sport, WidgetMoments.Kind.FINISH));
            default: return c.getString(R.string.event_score) + "!";
        }
    }

    private static int glyph(Sport sport, SportsEventTracker.Kind event) {
        switch (event) {
            case GOAL: return WidgetMoments.glyph(sport, WidgetMoments.Kind.GOAL);
            case WICKET: return WidgetMoments.glyph(sport, WidgetMoments.Kind.WICKET);
            case LEAD:
            case LAP: return R.drawable.glyph_racing;
            case KICKOFF: return WidgetMoments.glyph(sport, WidgetMoments.Kind.KICKOFF);
            case FINISH: return WidgetMoments.glyph(sport, WidgetMoments.Kind.FINISH);
            default: return SportVisuals.glyph(sport);
        }
    }

    /**
     * The competition's own logo, as the list below shows it; the sport icon holds the place until it loads
     * (from memory when we've drawn it before, so a re-bind never flickers) and for leagues we have no logo for.
     */
    private static void leagueMark(Context c, ImageView view, Match match, boolean night) {
        app.scoreline.data.Catalog.Competition comp = app.scoreline.data.Catalog.competition(match.sport, match.league);
        String url = comp == null ? "" : night ? comp.logoDark : comp.logo;
        view.setTag(R.id.tag_image, url);
        app.scoreline.core.Images images = ((app.scoreline.App) c.getApplicationContext()).images();
        int px = Ui.dp(c, 40);
        android.graphics.Bitmap cached = url.isEmpty() ? null : images.cached(url, px);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(Views.tinted(c, icon(match.sport), SportVisuals.ink(c, match.sport)));
        if (!url.isEmpty()) images.loadAsync(url, px, bmp -> {
            if (bmp != null && url.equals(view.getTag(R.id.tag_image))) view.setImageBitmap(bmp);
        });
    }

    private static int icon(Sport sport) {
        switch (sport) {
            case CRICKET: return R.drawable.ic_sport_cricket;
            case BASKETBALL: return R.drawable.ic_sport_basketball;
            case FOOTBALL: return R.drawable.ic_sport_football;
            case HOCKEY: return R.drawable.ic_sport_hockey;
            case BASEBALL: return R.drawable.ic_sport_baseball;
            case RACING: return R.drawable.ic_sport_racing;
            default: return R.drawable.ic_sport_soccer;
        }
    }
}
