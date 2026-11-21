package app.scoreline.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.HorizontalScrollView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Feed;
import app.scoreline.data.Browse;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.MatchRef;
import app.scoreline.model.MatchDetail;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;
import app.scoreline.model.Timeline;
import app.scoreline.ui.widget.CrestView;
import app.scoreline.ui.widget.ProgressLine;
import app.scoreline.widgets.SportsWidgetProvider;

/** One match: big scoreboard, island control, and its timeline of goals or running order. */
public final class MatchActivity extends BaseActivity {
    private static final String EXTRA_KEY = "key";
    private static final long REFRESH = 30_000;

    private String key;
    private Match match;
    private MatchRef reference;
    private MatchDetail detail;
    private boolean delayed;
    private boolean loading;
    private int generation;
    private int detailGeneration;
    private long detailUpdated;
    private String shownScore;
    private boolean statsShown;
    private boolean statsExpanded;
    private static final int STATS_COLLAPSED = 8;
    private boolean detailStale;
    private boolean detailFailed;
    private final Runnable refresh = this::refresh;

    public static Intent intent(Context c, String matchKey) {
        return new Intent(c, MatchActivity.class).putExtra(EXTRA_KEY, matchKey);
    }

    public static Intent intent(Context c, Match match) {
        return intent(c, match.key()).putExtra("matchRef", MatchRef.from(match).encode());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_match);
        key = getIntent().getStringExtra(EXTRA_KEY);
        reference = MatchRef.decode(state == null ? getIntent().getStringExtra("matchRef") : state.getString("matchRef"));
        if (reference == null) reference = MatchRef.fromKey(key);
        for (MatchRef pinned : app().store().pinnedMatches()) if (pinned.key().equals(key)) reference = pinned;
        applyInsets(findViewById(R.id.root), findViewById(R.id.top), findViewById(R.id.content));
        statusScrim(findViewById(R.id.root));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        match = app().feed().last().find(key);
        if (match == null) match = app().browse().find(key);
        if (match != null) render();
        else showState(R.string.match_loading);
        findViewById(R.id.matchState).setOnClickListener(v -> load(true));
        load(false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (reference != null) state.putString("matchRef", reference.encode());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (match != null) render();
        postDelayed(refresh, REFRESH);
    }

    @Override
    protected void onPause() {
        removeCallbacks(refresh);
        super.onPause();
    }

    private void refresh() {
        if (match != null && (match.state == Match.State.LIVE || match.state == Match.State.PRE)) load(true);
    }

    private void load(boolean fresh) {
        if (loading) return;
        if (reference == null) { showState(R.string.match_missing); return; }
        loading = true;
        int request = ++generation;
        background(() -> app().browse().resolve(reference, fresh), result -> {
            if (request != generation) return;
            loading = false;
            delayed = result.stale || result.failed;
            if (result.value != null) {
                match = result.value;
                reference = MatchRef.from(match);
                render();
                if (detail == null || System.currentTimeMillis() - detailUpdated > 60_000) loadDetails();
            } else showState(result.failed ? R.string.request_failed : R.string.match_missing);
            removeCallbacks(refresh);
            if (match != null && (match.isLive() || match.state == Match.State.PRE)) postDelayed(refresh, REFRESH);
        }, e -> {
            loading = false;
            delayed = true;
            if (match != null) render(); else showState(R.string.request_failed);
            postDelayed(refresh, REFRESH);
        });
    }

    private void showState(int message) {
        TextView state = findViewById(R.id.matchState);
        state.setVisibility(View.VISIBLE);
        state.setText(message == R.string.match_loading ? getString(message) : getString(message) + "\n" + getString(R.string.retry));
        if (match == null) {
            for (int id : new int[] {R.id.board, R.id.island, R.id.moments, R.id.momentsTitle, R.id.open, R.id.share, R.id.details})
                findViewById(id).setVisibility(View.GONE);
        }
    }

    private void loadDetails() {
        Match current = match;
        int request = ++detailGeneration;
        background(() -> app().browse().details(current), result -> {
            if (request != detailGeneration || match == null || !match.key().equals(current.key())) return;
            if (!result.failed) {
                detailUpdated = System.currentTimeMillis();
                detail = result.value;
            } else if (detail == null) detail = result.value;
            renderDetails(result.stale, result.failed);
        }, e -> renderDetails(false, true));
    }

    private void renderDetails(boolean stale, boolean failed) {
        detailStale = stale;
        detailFailed = failed;
        LinearLayout box = findViewById(R.id.details);
        if ((match.sport == Sport.CRICKET || match.sport == Sport.RACING)
                && !stale && !failed && (detail == null || (detail.stats.isEmpty() && detail.summary.isEmpty()))) {
            box.setVisibility(View.GONE);
            box.removeAllViews();
            return;
        }
        box.setVisibility(View.VISIBLE);
        box.removeAllViews();
        // Before kick-off the provider's numbers are each side's season so far; say so.
        TextView title = (TextView) FollowRows.section(getLayoutInflater(), box,
                getString(match.state == Match.State.PRE ? R.string.season_stats : R.string.match_stats));
        box.addView(title);
        if (failed || stale) {
            TextView status = detailText(getString(failed ? R.string.details_failed : R.string.stale_scores));
            status.setMinHeight(Ui.dp(this, 48));
            status.setOnClickListener(v -> loadDetails());
            box.addView(status);
        }
        if (detail == null || detail.stats.isEmpty()) box.addView(detailText(getString(R.string.match_stats_empty)));
        else {
            boolean grow = !statsShown && android.animation.ValueAnimator.areAnimatorsEnabled();
            statsShown = true;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card_plain);
            card.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 10));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bounds.setMarginStart(getResources().getDimensionPixelSize(R.dimen.gutter));
            bounds.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.gutter));
            card.setLayoutParams(bounds);
            boolean night = Ui.isNight(this);
            int homeColor = match.home == null ? getColor(R.color.primary) : Ui.onCard(SportsWidgetProvider.teamColor(this, match.home.team, match.sport, night), night);
            int awayColor = match.away == null ? getColor(R.color.primary) : Ui.onCard(SportsWidgetProvider.teamColor(this, match.away.team, match.sport, night), night);
            if (match.home != null && match.away != null) card.addView(statHeader(match.home, match.away));
            int background = getColor(R.color.surface);
            homeColor = Ui.chartColor(homeColor, background);
            awayColor = Ui.chartColor(awayColor, background);
            int index = 0;
            List<MatchDetail.Stat> shown = presentable(detail.stats);
            // The headline numbers first; the long tail waits behind one tap.
            int visible = statsExpanded || shown.size() <= STATS_COLLAPSED + 2 ? shown.size() : STATS_COLLAPSED;
            for (int i = 0; i < visible; i++) card.addView(statRow(shown.get(i), homeColor, awayColor, grow ? index++ : -1));
            if (visible < shown.size()) {
                TextView more = new TextView(this);
                more.setTextAppearance(R.style.Text_Label);
                more.setTextColor(getColor(R.color.primary));
                more.setText(getString(R.string.stats_show_all, shown.size()));
                more.setGravity(android.view.Gravity.CENTER);
                more.setMinHeight(Ui.dp(this, 48));
                more.setBackgroundResource(R.drawable.bg_row);
                more.setOnClickListener(v -> {
                    statsExpanded = true;
                    renderDetails(detailStale, detailFailed);
                });
                card.addView(more);
            }
            TextView legend = new TextView(this);
            legend.setTextAppearance(R.style.Text_Caption);
            legend.setText(R.string.stat_values_legend);
            legend.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
            card.addView(legend);
            box.addView(card);
        }
        if (detail != null && !detail.summary.isEmpty()) box.addView(detailText(detail.summary));
    }

    /** The two sides over the stats card: crest and name at each end. */
    private LinearLayout statHeader(Match.Side home, Match.Side away) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        row.addView(smallCrest(home));
        TextView left = cell(home.team.abbr.isEmpty() ? home.team.shortName : home.team.abbr, 0, R.style.Text_Label, android.view.Gravity.START, true);
        left.setPadding(Ui.dp(this, 8), 0, 0, 0);
        TextView right = cell(away.team.abbr.isEmpty() ? away.team.shortName : away.team.abbr, 0, R.style.Text_Label, android.view.Gravity.END, true);
        right.setPadding(0, 0, Ui.dp(this, 8), 0);
        row.addView(left);
        row.addView(right);
        row.addView(smallCrest(away));
        return row;
    }

    private CrestView smallCrest(Match.Side side) {
        CrestView crest = new CrestView(this, null);
        crest.setTeam(side.team);
        crest.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        crest.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        return crest;
    }

    /** Football (soccer) stats in the order fans read them, with plain names. Other keys follow in provider order. */
    private static final String[][] SOCCER = {
            {"possessionPct", "Possession"}, {"totalShots", "Shots"}, {"shotsOnTarget", "Shots on target"},
            {"wonCorners", "Corners"}, {"saves", "Saves"}, {"foulsCommitted", "Fouls"},
            {"yellowCards", "Yellow cards"}, {"redCards", "Red cards"}, {"offsides", "Offsides"},
            {"passPct", "Pass accuracy"}, {"totalPasses", "Passes"}, {"totalCrosses", "Crosses"},
            {"crossPct", "Cross accuracy"}, {"totalLongBalls", "Long balls"}, {"longballPct", "Long ball accuracy"},
            {"totalTackles", "Tackles"}, {"tacklePct", "Tackles won"}, {"blockedShots", "Blocked shots"},
            {"interceptions", "Interceptions"}, {"totalClearance", "Clearances"}, {"penaltyKickGoals", "Penalties scored"}};
    /** Already said by another row: each "accurate" count is inside its accuracy %, and shots on target covers shotPct. */
    private static final java.util.Set<String> SOCCER_HIDDEN = java.util.Set.of("accuratePasses", "accurateCrosses",
            "accurateLongBalls", "effectiveTackles", "effectiveClearance", "shotPct", "penaltyKickShots");
    /** Accuracy rows worked out from the counts: the provider rounds them to one decimal of a fraction. */
    private static final String[][] SOCCER_RATES = {{"passPct", "accuratePasses", "totalPasses"},
            {"crossPct", "accurateCrosses", "totalCrosses"}, {"longballPct", "accurateLongBalls", "totalLongBalls"},
            {"tacklePct", "effectiveTackles", "totalTackles"}};

    /**
     * What the stats card shows: football gets fan order, plain names and real percentages; every sport drops
     * rows where both sides are zero (no red cards, no penalties) and SHOUTED provider labels are calmed down.
     */
    private List<MatchDetail.Stat> presentable(List<MatchDetail.Stat> stats) {
        java.util.Map<String, MatchDetail.Stat> byKey = new java.util.LinkedHashMap<>();
        for (MatchDetail.Stat stat : stats) byKey.put(stat.key, stat);
        List<MatchDetail.Stat> out = new ArrayList<>();
        boolean soccer = match != null && match.sport == Sport.SOCCER;
        if (soccer) {
            java.util.Map<String, String[]> rates = new java.util.HashMap<>();
            for (String[] rate : SOCCER_RATES) rates.put(rate[0], rate);
            for (String[] entry : SOCCER) {
                MatchDetail.Stat stat = byKey.get(entry[0]);
                if (stat == null) continue;
                String home = stat.home, away = stat.away;
                String[] rate = rates.get(entry[0]);
                if (rate != null) {
                    home = percent(byKey.get(rate[1]), byKey.get(rate[2]), true, stat.home);
                    away = percent(byKey.get(rate[1]), byKey.get(rate[2]), false, stat.away);
                } else if (entry[0].equals("possessionPct")) {
                    home = withPercent(home);
                    away = withPercent(away);
                }
                add(out, new MatchDetail.Stat(stat.key, entry[1], home, away));
            }
        }
        for (MatchDetail.Stat stat : stats) {
            if (soccer && (SOCCER_HIDDEN.contains(stat.key) || known(stat.key))) continue;
            add(out, new MatchDetail.Stat(stat.key, calm(stat.label), stat.home, stat.away));
        }
        return out;
    }

    private static boolean known(String key) {
        for (String[] entry : SOCCER) if (entry[0].equals(key)) return true;
        return false;
    }

    private static void add(List<MatchDetail.Stat> out, MatchDetail.Stat stat) {
        if (stat.comparisonValue(stat.home) == 0 && stat.comparisonValue(stat.away) == 0) return; // 0 v 0 says nothing
        out.add(stat);
    }

    /** 394 of 471 as "84%"; falls back to the provider's fraction (0.8 as "80%") if a count is missing. */
    private static String percent(MatchDetail.Stat made, MatchDetail.Stat total, boolean home, String fallback) {
        try {
            double m = Double.parseDouble((home ? made.home : made.away).trim());
            double t = Double.parseDouble((home ? total.home : total.away).trim());
            if (t > 0 && m >= 0 && m <= t) return Math.round(100 * m / t) + "%";
        } catch (RuntimeException ignored) {
        }
        try {
            double f = Double.parseDouble(fallback.trim());
            if (f >= 0 && f <= 1) return Math.round(100 * f) + "%";
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static String withPercent(String value) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() || v.endsWith("%") ? v : v + "%";
    }

    /** "ON GOAL" becomes "On goal"; mixed-case labels and short codes like "FG%" or "3PT" are left alone. */
    private static String calm(String label) {
        String letters = label.replaceAll("[^A-Za-z]", "");
        if (letters.length() < 5 || !letters.equals(letters.toUpperCase(java.util.Locale.ROOT))) return label;
        String lower = label.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Fewer is better for fouls and cards; but a football interception is a defender winning the ball. */
    private boolean lowerIsBetter(MatchDetail.Stat stat) {
        if (match != null && match.sport == Sport.SOCCER && stat.key.equals("interceptions")) return false;
        return stat.lowerIsBetter();
    }

    /** Full-width labels preserve qualifiers; chart length always describes the actual value. */
    private LinearLayout statRow(MatchDetail.Stat stat, int homeColor, int awayColor, int grow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        double h = stat.comparisonValue(stat.home), a = stat.comparisonValue(stat.away);
        boolean known = Double.isFinite(h) && Double.isFinite(a);
        boolean lower = lowerIsBetter(stat);
        boolean homeAhead = known && (lower ? h < a : h > a);
        boolean awayAhead = known && (lower ? a < h : a > h);
        TextView label = new TextView(this);
        label.setTextAppearance(R.style.Text_Caption);
        label.setText(stat.label);
        label.setPadding(0, 0, 0, Ui.dp(this, 5));
        row.addView(label);

        LinearLayout line = new LinearLayout(this);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setBaselineAligned(false);
        TextView left = cell(stat.home, 0, R.style.Text_Body, android.view.Gravity.START, homeAhead);
        TextView right = cell(stat.away, 0, R.style.Text_Body, android.view.Gravity.END, awayAhead);
        left.setTextSize(18);
        right.setTextSize(18);
        // Values get half the width each, without competing with a center label.
        left.setMaxLines(Integer.MAX_VALUE);
        right.setMaxLines(Integer.MAX_VALUE);
        line.addView(left);
        line.addView(right);
        row.addView(line);

        boolean chart = known && !stat.isSignedComparison() && h >= 0 && a >= 0 && h + a > 0;
        if (chart) {
            LinearLayout bar = new LinearLayout(this);
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
            bounds.topMargin = Ui.dp(this, 7);
            bar.setLayoutParams(bounds);
            float share = (float) (h / (h + a));
            View homePart = segment(homeColor, share, true);
            View awayPart = segment(awayColor, 1 - share, false);
            bar.addView(homePart);
            bar.addView(awayPart);
            row.addView(bar);
            if (grow >= 0) for (View part : new View[] {homePart, awayPart}) {
                boolean home = part == homePart;
                part.setScaleX(0f);
                part.post(() -> {
                    part.setPivotX(home ? part.getWidth() : 0);
                    part.animate().scaleX(1f).setStartDelay(80 + 35L * Math.min(grow, 12)).setDuration(520)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
                });
            }
        }
        String hint = "";
        if (stat.isSignedComparison() && known) {
            String team = homeAhead ? match.home.team.abbr : awayAhead ? match.away.team.abbr : "";
            if (team.isEmpty() && (homeAhead || awayAhead)) team = (homeAhead ? match.home : match.away).team.shortName;
            hint = homeAhead || awayAhead ? getString(R.string.stat_leads, team) : getString(R.string.stat_tied);
        } else if (stat.isCountAndYards()) hint = getString(R.string.stat_count_yards);
        else if (stat.isRate()) hint = getString(R.string.stat_rate);
        if (!hint.isEmpty()) {
            TextView context = new TextView(this);
            context.setTextAppearance(R.style.Text_Caption);
            context.setText(hint);
            context.setPadding(0, Ui.dp(this, 5), 0, 0);
            row.addView(context);
        }
        row.setContentDescription(stat.label + ": " + match.home.team.name + " " + stat.home + ", "
                + match.away.team.name + " " + stat.away + (hint.isEmpty() ? "" : ". " + hint));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return row;
    }

    private View segment(int color, float share, boolean home) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(Ui.dp(this, 3));
        v.setBackground(fill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(share, 0f));
        if (home) lp.setMarginEnd(Ui.dp(this, 3));
        v.setLayoutParams(lp);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }


    private TextView detailText(String text) {
        TextView view = new TextView(this);
        view.setTextAppearance(R.style.Text_Caption);
        view.setText(text);
        view.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        return view;
    }

    private void render() {
        Match m = match;
        findViewById(R.id.matchState).setVisibility(delayed || m.updatesDelayed ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.matchState)).setText(R.string.stale_scores);
        TextView league = findViewById(R.id.league);
        league.setText(m.sport == Sport.CRICKET && !m.title.isEmpty() ? m.title + " · " + m.leagueName : m.leagueName);
        league.setOnClickListener(v -> startActivity(CompetitionActivity.intent(this, m.sport, m.league, m.leagueName)));

        View board = findViewById(R.id.board);
        boolean night = Ui.isNight(this);
        int accent = app.scoreline.core.SportVisuals.accent(m.sport);
        int homeTone = m.sport.headToHead() ? SportsWidgetProvider.teamColor(this, m.home.team, m.sport, night) : accent;
        int awayTone = m.sport.headToHead() ? SportsWidgetProvider.teamColor(this, m.away.team, m.sport, night) : accent;
        findViewById(R.id.hero).setBackground(Views.heroBackdrop(this, homeTone, awayTone, app.scoreline.core.SportVisuals.glyph(m.sport)));
        if (m.sport.headToHead()) for (Match.Side s : new Match.Side[] {m.home, m.away})
            SportsWidgetProvider.whenCrestRead(this, s.team, () -> {
                if (isFinishing() || match == null || !match.key().equals(m.key())) return;
                render();
                if (detail != null && !detail.stats.isEmpty()) renderDetails(detailStale, detailFailed);
            });
        TextView raceTitle = findViewById(R.id.raceTitle);
        TextView clock = findViewById(R.id.clock);
        if (m.sport.headToHead()) {
            board.setVisibility(View.VISIBLE);
            raceTitle.setVisibility(View.GONE);
            side(R.id.homeCrest, R.id.homeName, m, m.home);
            side(R.id.awayCrest, R.id.awayName, m, m.away);
            disc(R.id.homeCrest, homeTone, night);
            disc(R.id.awayCrest, awayTone, night);
            TextView score = findViewById(R.id.score);
            boolean scoresInNames = m.sport == Sport.CRICKET;
            score.setVisibility(scoresInNames ? View.GONE : View.VISIBLE);
            score.setText(m.state == Match.State.PRE ? getString(R.string.vs) : m.home.score + " – " + m.away.score);
            boolean pre = m.state == Match.State.PRE;
            score.setAutoSizeTextTypeUniformWithConfiguration(18, pre ? 26 : 56, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            String now = m.key() + "|" + m.home.score + ":" + m.away.score;
            if (!pre && shownScore != null && !shownScore.equals(now) && shownScore.startsWith(m.key() + "|")
                    && android.animation.ValueAnimator.areAnimatorsEnabled()) pulse(score);
            shownScore = now;
        } else {
            board.setVisibility(View.GONE);
            raceTitle.setVisibility(View.VISIBLE);
            raceTitle.setText(m.title);
        }
        Views.status(clock, m, m.state == Match.State.PRE ? Ui.time(this, m.start)
                : m.status.isEmpty() ? Status.finished(m) : m.status);
        clock.setMaxLines(3);

        TextView summary = findViewById(R.id.summary);
        String text;
        if (m.sport == Sport.RACING) {
            // Races have no scoreboard, so the session line carries the state.
            text = m.isLive() ? getString(R.string.live) + " · " + Status.full(m) + " · " + m.session
                    : m.state == Match.State.PRE ? m.session + " · " + Ui.when(this, m.start) : m.session;
        } else if (m.sport == Sport.CRICKET) {
            text = m.summary;
        } else {
            text = m.state == Match.State.PRE ? getString(R.string.match_starts, Ui.when(this, m.start)) : "";
        }
        summary.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(text);

        Timeline t = Timeline.of(m);
        ProgressLine progress = findViewById(R.id.progress);
        boolean showProgress = m.isLive() && !t.indeterminate;
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            Follow focus = app().store().focus(m);
            String teamColor = focus != null ? focus.team.color : m.home != null ? m.home.team.color : "";
            // Each goal is labelled with the scoring side and minute, in that side's colour.
            List<ProgressLine.Mark> marks = new ArrayList<>();
            for (Timeline.Goal g : t.goals) {
                Match.Side side = g.home ? m.home : m.away;
                marks.add(new ProgressLine.Mark(g.at / (float) t.max, side.team.abbr + " " + g.clock,
                        Ui.onCard(Ui.color(side.team.color, getColor(R.color.primary)), night)));
            }
            progress.set(t.segments, t.labels, t.fraction(), true, marks, getColor(R.color.surface_high),
                    Ui.onCard(Ui.color(teamColor, getColor(R.color.primary)), night),
                    getColor(R.color.on_surface_variant), getColor(R.color.on_surface), getColor(R.color.bg));
            progress.setContentDescription(t.phase(m));
        }

        TextView island = findViewById(R.id.island);
        boolean offered = IslandToggle.offered(m);
        island.setVisibility(offered ? View.VISIBLE : View.GONE);
        if (offered) {
            Views.islandButton(island, IslandToggle.isOn(app(), m), true);
            island.setOnClickListener(v -> {
                Views.confirm(v);
                IslandToggle.toggle(this, m);
                render();
            });
        }

        renderTimeline(m);
        TextView venue = findViewById(R.id.venue);
        venue.setText(Ui.when(this, m.start) + (m.venue.isEmpty() ? "" : "\n" + m.venue));
        venue.setVisibility(View.VISIBLE);
        View open = findViewById(R.id.open);
        open.setVisibility(m.link.isEmpty() ? View.GONE : View.VISIBLE);
        open.setOnClickListener(v -> openUrl(m.link));
        View share = findViewById(R.id.share);
        share.setVisibility(View.VISIBLE);
        share.setOnClickListener(v -> {
            String heading = m.home == null ? m.title + " · " + m.session : m.home.team.name + " "
                    + (m.state == Match.State.PRE ? getString(R.string.vs) : m.home.score + " – " + m.away.score) + " " + m.away.team.name;
            launch(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, heading + "\n" + Ui.when(this, m.start) + "\n" + m.link), getString(R.string.share_match)));
        });
    }

    /** A crest on a soft disc of its team's colour. */
    private void disc(int crestId, int color, boolean night) {
        View crest = findViewById(crestId);
        crest.setBackgroundResource(R.drawable.bg_spotlight_crest);
        crest.setBackgroundTintList(android.content.res.ColorStateList.valueOf((color & 0x00FFFFFF) | ((night ? 0x5C : 0x3A) << 24)));
    }

    /** The new score punches up and settles, so a goal is felt even with the screen already open. */
    private void pulse(View score) {
        score.animate().cancel();
        score.animate().scaleX(1.22f).scaleY(1.22f).setDuration(170).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> score.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start()).start();
    }

    private void side(int crestId, int nameId, Match m, Match.Side side) {
        ((CrestView) findViewById(crestId)).setTeam(side.team);
        TextView name = findViewById(nameId);
        View teamBox = findViewById(crestId == R.id.homeCrest ? R.id.homeBox : R.id.awayBox);
        teamBox.setOnClickListener(v -> startActivity(TeamActivity.intent(this, m.sport, m.league, side.team)));
        teamBox.setContentDescription(side.team.name);
        boolean compact = getResources().getConfiguration().screenWidthDp <= 360
                || getResources().getConfiguration().fontScale > 1.2f;
        name.setText(compact && !side.team.abbr.isEmpty() ? side.team.abbr : side.team.shortName);
        name.setContentDescription(side.team.name);
        boolean innings = m.sport == Sport.CRICKET && m.state != Match.State.PRE && !side.score.isEmpty();
        TextView score = findViewById(crestId == R.id.homeCrest ? R.id.homeInnings : R.id.awayInnings);
        TextView overs = findViewById(crestId == R.id.homeCrest ? R.id.homeOvers : R.id.awayOvers);
        score.setVisibility(innings ? View.VISIBLE : View.GONE);
        score.setText(side.score);
        overs.setVisibility(innings && !side.overs.isEmpty() ? View.VISIBLE : View.GONE);
        overs.setText(getString(R.string.cricket_overs, side.overs));
    }

    private void renderTimeline(Match m) {
        TextView title = findViewById(R.id.momentsTitle);
        LinearLayout box = findViewById(R.id.moments);
        box.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        if (m.sport == Sport.RACING) {
            boolean any = !m.standings.isEmpty() && m.state != Match.State.PRE;
            title.setVisibility(any ? View.VISIBLE : View.GONE);
            box.setVisibility(any ? View.VISIBLE : View.GONE);
            title.setText(R.string.standings);
            for (Match.Standing s : m.standings) {
                View row = inflater.inflate(R.layout.item_standing, box, false);
                row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
                ((TextView) row.findViewById(R.id.position)).setText(String.valueOf(s.position));
                ((CrestView) row.findViewById(R.id.flag)).set(s.flag, "", "");
                ((TextView) row.findViewById(R.id.name)).setText(s.name);
                ((TextView) row.findViewById(R.id.gap)).setText(s.gap);
                box.addView(row);
            }
            return;
        }
        if (m.sport != Sport.SOCCER) {
            // The other team sports score too often for a list of moments, so they get the same
            // story told as a table: points per quarter, period or inning, named as on the timeline.
            boolean table = m.sport != Sport.CRICKET && m.state != Match.State.PRE
                    && (!m.home.periods.isEmpty() || !m.away.periods.isEmpty());
            title.setVisibility(table ? View.VISIBLE : View.GONE);
            box.setVisibility(table ? View.VISIBLE : View.GONE);
            if (table) renderPeriods(m, title, box);
            return;
        }
        boolean soccer = m.state != Match.State.PRE;
        title.setVisibility(soccer ? View.VISIBLE : View.GONE);
        box.setVisibility(soccer ? View.VISIBLE : View.GONE);
        if (!soccer) return;
        title.setText(R.string.key_moments);
        if (m.moments.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextAppearance(R.style.Text_Caption);
            none.setText(R.string.no_moments);
            none.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            box.addView(none);
            return;
        }
        for (Match.Moment g : m.moments) {
            View row = inflater.inflate(R.layout.item_moment, box, false);
            String mark = g.kind == Match.Moment.Kind.RED_CARD ? "🟥" : "⚽";
            String tag = g.kind == Match.Moment.Kind.PENALTY ? " (P)" : g.kind == Match.Moment.Kind.OWN_GOAL ? " (OG)" : "";
            String text = g.home ? g.player + tag + "  " + mark : mark + "  " + g.player + tag;
            ((TextView) row.findViewById(g.home ? R.id.homeText : R.id.awayText)).setText(text);
            ((TextView) row.findViewById(R.id.minute)).setText(g.clock);
            box.addView(row);
        }
    }

    /** A header row of period names, then one row per side with its points per period and total. */
    private void renderPeriods(Match m, TextView title, LinearLayout box) {
        String unit = m.sport == Sport.BASEBALL ? getString(R.string.unit_inning)
                : m.sport == Sport.HOCKEY ? getString(R.string.unit_period) : getString(R.string.unit_quarter);
        title.setText(getString(R.string.score_by, unit));
        String[] names = Timeline.of(m).labels;
        int columns = Math.max(Math.max(m.home.periods.size(), m.away.periods.size()), names.length);
        String[] header = new String[columns];
        for (int i = 0; i < columns; i++) {
            header[i] = i < names.length && !names[i].isEmpty() ? names[i] : periodName(m.sport, i);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.addView(periodRow("", header, getString(R.string.total), false, false));
        table.addView(periodRow(m.home.team.abbr, padded(m.home.periods, columns), m.home.score, true, m.home.winner));
        table.addView(periodRow(m.away.team.abbr, padded(m.away.periods, columns), m.away.score, true, m.away.winner));
        scroll.addView(table);
        box.addView(scroll);
    }

    /** For when the timeline has no names (an overtime of unknown length): "Q2", "P3", "7", "OT". */
    private static String periodName(Sport sport, int i) {
        if (sport == Sport.BASEBALL) return String.valueOf(i + 1);
        int regulation = sport == Sport.HOCKEY ? 3 : 4;
        return i < regulation ? (sport == Sport.HOCKEY ? "P" : "Q") + (i + 1) : "OT";
    }

    private static String[] padded(java.util.List<String> values, int columns) {
        String[] out = new String[columns];
        for (int i = 0; i < columns; i++) out[i] = i < values.size() ? values.get(i) : "";
        return out;
    }

    private LinearLayout periodRow(String name, String[] cells, String total, boolean team, boolean winner) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, team ? 8 : 4), Ui.dp(this, 16), Ui.dp(this, team ? 8 : 4));
        row.addView(cell(name, 56, team ? R.style.Text_Label : R.style.Text_Caption, android.view.Gravity.START, false));
        for (String c : cells) row.addView(cell(c, 44, team ? R.style.Text_Body : R.style.Text_Caption, android.view.Gravity.CENTER, false));
        row.addView(cell(total, 48, team ? R.style.Text_Label : R.style.Text_Caption, android.view.Gravity.CENTER, winner));
        return row;
    }

    /** A table cell; width 0 shares the remaining space equally. */
    private TextView cell(String text, int widthDp, int style, int gravity, boolean strong) {
        TextView v = new TextView(this);
        v.setTextAppearance(style);
        v.setText(text);
        v.setGravity(gravity);
        v.setMaxLines(1);
        v.setFontFeatureSettings("tnum");
        if (strong) v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        v.setLayoutParams(new LinearLayout.LayoutParams(widthDp == 0 ? 0 : Ui.dp(this, widthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT, widthDp == 0 ? 1 : 0));
        return v;
    }
}
