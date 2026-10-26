package app.scoreline.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Timeline;
import app.scoreline.ui.widget.CrestView;
import app.scoreline.ui.widget.ProgressLine;

/** Binds one match to the card layout. Cards are reused across refreshes to avoid flicker. */
final class MatchCard {

    interface Actions {
        void open(Match m);

        void toggleIsland(Match m);
    }

    private static final class Row {
        final CrestView crest;
        final TextView name;
        final TextView detail;
        final TextView score;

        Row(View v) {
            crest = v.findViewById(R.id.crest);
            name = v.findViewById(R.id.name);
            detail = v.findViewById(R.id.detail);
            score = v.findViewById(R.id.score);
        }
    }

    final View root;
    private final CrestView leagueLogo;
    private final TextView league;
    private final TextView status;
    private final TextView title;
    private final View teams;
    private final Row home;
    private final Row away;
    private final LinearLayout standings;
    private final TextView summary;
    private final View footer;
    private final ProgressLine progress;
    private final TextView island;

    MatchCard(LayoutInflater inflater, ViewGroup parent) {
        root = inflater.inflate(R.layout.item_match, parent, false);
        leagueLogo = root.findViewById(R.id.leagueLogo);
        league = root.findViewById(R.id.league);
        status = root.findViewById(R.id.status);
        title = root.findViewById(R.id.title);
        teams = root.findViewById(R.id.teams);
        home = new Row(root.findViewById(R.id.home));
        away = new Row(root.findViewById(R.id.away));
        standings = root.findViewById(R.id.standings);
        summary = root.findViewById(R.id.summary);
        footer = root.findViewById(R.id.footer);
        progress = root.findViewById(R.id.progress);
        island = root.findViewById(R.id.island);
    }

    void bind(App app, Match m, boolean withDay, Actions actions) {
        Context c = root.getContext();
        boolean night = Ui.isNight(c);

        Catalog.Competition comp = Catalog.competition(m.sport, m.league);
        if (comp != null) {
            leagueLogo.setVisibility(View.VISIBLE);
            leagueLogo.set(night ? comp.logoDark : comp.logo, "", "");
        } else {
            leagueLogo.setVisibility(View.GONE);
        }
        league.setText(m.sport == Sport.CRICKET && !m.title.isEmpty() ? m.title + " · " + m.leagueName : m.leagueName);
        Views.status(status, m, Views.quietStatus(c, m, withDay));

        int brand = c.getColor(R.color.primary);
        if (m.isLive() && m.sport.headToHead()) {
            root.setBackground(Views.liveCard(c, Ui.color(m.home.team.color, brand), Ui.color(m.away.team.color, brand)));
        } else {
            root.setBackgroundResource(R.drawable.bg_card);
        }
        if (m.sport.headToHead()) {
            teams.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
            standings.setVisibility(View.GONE);
            bindRow(c, home, m, m.home, m.away);
            bindRow(c, away, m, m.away, m.home);
        } else {
            teams.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            title.setText(m.title);
            bindStandings(c, m);
        }

        String line = summaryLine(c, m);
        summary.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        summary.setText(line);

        boolean offered = IslandToggle.offered(m);
        footer.setVisibility(offered ? View.VISIBLE : View.GONE);
        if (offered) {
            Timeline t = Timeline.of(m);
            boolean showProgress = m.isLive() && !t.indeterminate;
            progress.setVisibility(showProgress ? View.VISIBLE : View.INVISIBLE);
            if (showProgress) {
                Follow focus = app.store().focus(m);
                String teamColor = focus != null ? focus.team.color : m.home != null ? m.home.team.color : "";
                int fill = Ui.onCard(Ui.color(teamColor, c.getColor(R.color.primary)), night);
                // Goals are already listed under the teams, so the card's line is just the clock.
                progress.set(t.segments, t.labels, t.fraction(), true, null, c.getColor(R.color.surface_high), fill,
                        c.getColor(R.color.on_surface_variant), c.getColor(R.color.on_surface), c.getColor(R.color.surface));
                progress.setContentDescription(t.phase(m));
            }
            Views.islandButton(island, IslandToggle.isOn(app, m), false);
            island.setOnClickListener(v -> {
                Views.confirm(v);
                actions.toggleIsland(m);
            });
        }
        root.setOnClickListener(v -> actions.open(m));
    }

    private static void bindRow(Context c, Row row, Match m, Match.Side side, Match.Side other) {
        row.crest.setTeam(side.team);
        row.name.setText(side.team.name);
        boolean decided = m.state == Match.State.POST && (side.winner || other.winner);
        boolean lost = decided && !side.winner;
        int strong = c.getColor(R.color.on_surface);
        int weak = c.getColor(R.color.on_surface_variant);
        row.name.setTextColor(lost ? weak : strong);
        row.score.setTextColor(lost ? weak : strong);
        // Scoreboard digits throughout; the loser is muted rather than thinner.
        row.score.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        row.name.setTypeface(Typeface.create(side.winner ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        row.score.setText(m.state == Match.State.PRE ? "" : side.score);
        boolean overs = m.sport == Sport.CRICKET && !side.overs.isEmpty();
        row.detail.setVisibility(overs ? View.VISIBLE : View.GONE);
        if (overs) row.detail.setText("(" + side.overs + ")");
    }

    private void bindStandings(Context c, Match m) {
        standings.removeAllViews();
        if (m.standings.isEmpty() || m.state == Match.State.PRE) {
            standings.setVisibility(View.GONE);
            return;
        }
        standings.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(c);
        for (int i = 0; i < Math.min(3, m.standings.size()); i++) {
            Match.Standing s = m.standings.get(i);
            View row = inflater.inflate(R.layout.item_standing, standings, false);
            ((TextView) row.findViewById(R.id.position)).setText(String.valueOf(s.position));
            ((CrestView) row.findViewById(R.id.flag)).set(s.flag, "", "");
            ((TextView) row.findViewById(R.id.name)).setText(s.name);
            ((TextView) row.findViewById(R.id.gap)).setText(s.gap);
            standings.addView(row);
        }
    }

    private static String summaryLine(Context c, Match m) {
        switch (m.sport) {
            case CRICKET:
                return m.summary;
            case RACING:
                return m.state == Match.State.PRE ? m.session + " · " + m.venue : m.session;
            case SOCCER:
                if (m.state == Match.State.PRE) return m.venue;
                StringBuilder sb = new StringBuilder();
                for (Match.Moment g : m.moments) {
                    if (!g.isGoal()) continue;
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(g.player).append(' ').append(g.clock);
                }
                return sb.toString();
            default:
                return m.state == Match.State.PRE ? m.venue : "";
        }
    }
}
