package app.scoreline.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Feed;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;
import app.scoreline.model.Timeline;
import app.scoreline.ui.widget.CrestView;
import app.scoreline.ui.widget.ProgressLine;

/** One match: big scoreboard, island control, and its timeline of goals or running order. */
public final class MatchActivity extends BaseActivity {
    private static final String EXTRA_KEY = "key";
    private static final long REFRESH = 30_000;

    private String key;
    private Match match;
    private final Runnable refresh = this::refresh;

    public static Intent intent(Context c, String matchKey) {
        return new Intent(c, MatchActivity.class).putExtra(EXTRA_KEY, matchKey);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_match);
        key = getIntent().getStringExtra(EXTRA_KEY);
        applyInsets(findViewById(R.id.root), findViewById(R.id.top), findViewById(R.id.content));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        match = app().feed().last().find(key);
        if (match != null) render();
        else background(() -> app().feed().load(false), (Feed.Snapshot s) -> {
            match = s.find(key);
            if (match == null) finish();
            else render();
        });
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
        if (match == null || (match.state != Match.State.LIVE && match.state != Match.State.PRE)) {
            postDelayed(refresh, REFRESH);
            return;
        }
        Match current = match;
        background(() -> {
            try {
                return app().espn().refresh(current);
            } catch (IOException e) {
                return null;
            }
        }, (Match fresh) -> {
            if (fresh != null) match = fresh;
            else match.updatesDelayed = true;
            render();
            postDelayed(refresh, REFRESH);
        });
    }

    private void render() {
        Match m = match;
        TextView league = findViewById(R.id.league);
        league.setText(m.sport == Sport.CRICKET && !m.title.isEmpty() ? m.title + " · " + m.leagueName : m.leagueName);

        View board = findViewById(R.id.board);
        int brand = getColor(R.color.primary);
        findViewById(R.id.hero).setBackground(m.sport.headToHead()
                ? Views.heroBackdrop(this, Ui.color(m.home.team.color, brand), Ui.color(m.away.team.color, brand))
                : Views.heroBackdrop(this, 0xFFE10600, 0xFFE10600));
        TextView raceTitle = findViewById(R.id.raceTitle);
        TextView clock = findViewById(R.id.clock);
        if (m.sport.headToHead()) {
            board.setVisibility(View.VISIBLE);
            raceTitle.setVisibility(View.GONE);
            side(R.id.homeCrest, R.id.homeName, m, m.home);
            side(R.id.awayCrest, R.id.awayName, m, m.away);
            TextView score = findViewById(R.id.score);
            boolean scoresInNames = m.sport == Sport.CRICKET;
            score.setVisibility(scoresInNames ? View.GONE : View.VISIBLE);
            score.setText(m.state == Match.State.PRE ? getString(R.string.vs) : m.home.score + " – " + m.away.score);
            score.setTextSize(m.state == Match.State.PRE ? 22 : 56);
        } else {
            board.setVisibility(View.GONE);
            raceTitle.setVisibility(View.VISIBLE);
            raceTitle.setText(m.title);
        }
        Views.status(clock, m, Views.quietStatus(this, m, true));

        TextView summary = findViewById(R.id.summary);
        String text;
        if (m.sport == Sport.RACING) {
            // Races have no scoreboard, so the session line carries the state.
            text = m.isLive() ? getString(R.string.live) + " · " + Status.full(m) + " · " + m.session
                    : m.state == Match.State.PRE ? m.session + " · " + Ui.when(this, m.start) : m.session;
        } else if (m.sport == Sport.CRICKET) {
            text = m.summary;
        } else {
            text = m.state == Match.State.PRE ? getString(R.string.kick_off, Ui.when(this, m.start)) : "";
        }
        if (m.updatesDelayed) text = "Updates delayed · " + text;
        summary.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(text);

        Timeline t = Timeline.of(m);
        ProgressLine progress = findViewById(R.id.progress);
        boolean showProgress = m.isLive() && !t.indeterminate;
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            Follow focus = app().store().focus(m);
            String teamColor = focus != null ? focus.team.color : m.home.team.color;
            boolean night = Ui.isNight(this);
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
        venue.setText(m.venue);
        venue.setVisibility(m.venue.isEmpty() ? View.GONE : View.VISIBLE);
        View open = findViewById(R.id.open);
        open.setVisibility(m.link.isEmpty() ? View.GONE : View.VISIBLE);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.link)));
            } catch (ActivityNotFoundException ignored) {
                // No browser: nothing sensible to do.
            }
        });
    }

    private void side(int crestId, int nameId, Match m, Match.Side side) {
        ((CrestView) findViewById(crestId)).setTeam(side.team);
        TextView name = findViewById(nameId);
        if (m.sport != Sport.CRICKET || side.score.isEmpty()) {
            name.setText(side.team.shortName);
            return;
        }
        // Cricket scores belong with the side: "India\n69/4 (10.3)".
        SpannableStringBuilder s = new SpannableStringBuilder(side.team.shortName).append('\n');
        int from = s.length();
        s.append(side.score);
        s.setSpan(new RelativeSizeSpan(1.6f), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!side.overs.isEmpty()) {
            int o = s.length();
            s.append("  (").append(side.overs).append(')');
            s.setSpan(new ForegroundColorSpan(getColor(R.color.on_surface_variant)), o, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        name.setText(s);
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
        box.addView(periodRow("", header, getString(R.string.total), false, false));
        box.addView(periodRow(m.home.team.abbr, padded(m.home.periods, columns), m.home.score, true, m.home.winner));
        box.addView(periodRow(m.away.team.abbr, padded(m.away.periods, columns), m.away.score, true, m.away.winner));
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
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, team ? 8 : 4), Ui.dp(this, 16), Ui.dp(this, team ? 8 : 4));
        row.addView(cell(name, 56, team ? R.style.Text_Label : R.style.Text_Caption, android.view.Gravity.START, false));
        for (String c : cells) row.addView(cell(c, 0, team ? R.style.Text_Body : R.style.Text_Caption, android.view.Gravity.CENTER, false));
        row.addView(cell(total, 40, team ? R.style.Text_Label : R.style.Text_Caption, android.view.Gravity.END, winner));
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
