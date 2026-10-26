package app.scoreline.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;
import app.scoreline.ui.widget.CrestView;

/**
 * A fixture or result as one compact row in a day's list. Live matches get a full {@link MatchCard};
 * everything else is a row, so a screen holds a weekend's fixtures rather than four of them.
 */
final class MatchRow {
    final View root;
    private final TextView when;
    private final TextView whenDetail;
    private final CrestView homeCrest;
    private final TextView homeName;
    private final TextView homeScore;
    private final CrestView awayCrest;
    private final TextView awayName;
    private final TextView awayScore;
    private final ImageButton island;

    MatchRow(LayoutInflater inflater, ViewGroup parent) {
        root = inflater.inflate(R.layout.item_match_row, parent, false);
        when = root.findViewById(R.id.when);
        whenDetail = root.findViewById(R.id.whenDetail);
        homeCrest = root.findViewById(R.id.homeCrest);
        homeName = root.findViewById(R.id.homeName);
        homeScore = root.findViewById(R.id.homeScore);
        awayCrest = root.findViewById(R.id.awayCrest);
        awayName = root.findViewById(R.id.awayName);
        awayScore = root.findViewById(R.id.awayScore);
        island = root.findViewById(R.id.island);
    }

    void bind(App app, Match m, MatchCard.Actions actions) {
        Context c = root.getContext();
        bindWhen(c, m);
        if (m.sport.headToHead()) {
            side(c, m, m.home, m.away, homeCrest, homeName, homeScore);
            side(c, m, m.away, m.home, awayCrest, awayName, awayScore);
        } else {
            // A race: the event on the first line, the session (and its winner) on the second.
            Catalog.Competition comp = Catalog.competition(m.sport, m.league);
            homeCrest.set(comp == null ? "" : Ui.isNight(c) ? comp.logoDark : comp.logo, m.leagueName, "");
            homeName.setText(m.title);
            homeName.setTextColor(c.getColor(R.color.on_surface));
            homeScore.setText("");
            awayCrest.setVisibility(View.INVISIBLE);
            awayName.setTextColor(c.getColor(R.color.on_surface_variant));
            String winner = m.state == Match.State.POST && !m.standings.isEmpty() ? " · " + m.standings.get(0).surname() : "";
            awayName.setText(m.session + winner);
            awayScore.setText("");
        }

        boolean offered = IslandToggle.offered(m);
        island.setVisibility(offered ? View.VISIBLE : View.GONE);
        if (offered) {
            boolean on = IslandToggle.isOn(app, m);
            island.setImageDrawable(Views.tinted(c, on ? R.drawable.ic_island_on : R.drawable.ic_island,
                    c.getColor(on ? R.color.primary : R.color.on_surface_variant)));
            island.setContentDescription(c.getString(on ? R.string.in_island : R.string.show_in_island));
            island.setOnClickListener(v -> {
                Views.confirm(v);
                actions.toggleIsland(m);
            });
        }
        root.setOnClickListener(v -> actions.open(m));
    }

    /** Kick-off time split into "6:30" over "PM", or the result word ("FT", "Final", "Postponed"). */
    private void bindWhen(Context c, Match m) {
        int strong = c.getColor(R.color.on_surface);
        int weak = c.getColor(R.color.on_surface_variant);
        if (m.state == Match.State.PRE) {
            String t = Ui.time(c, m.start);
            int space = DateFormat.is24HourFormat(c) ? -1 : t.lastIndexOf(' ');
            when.setText(space < 0 ? t : t.substring(0, space));
            whenDetail.setText(space < 0 ? "" : t.substring(space + 1));
            when.setTextColor(strong);
        } else {
            when.setText(m.state == Match.State.OFF ? m.status : Status.finished(m));
            whenDetail.setText("");
            when.setTextColor(weak);
        }
        whenDetail.setVisibility(whenDetail.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static void side(Context c, Match m, Match.Side side, Match.Side other, CrestView crest, TextView name, TextView score) {
        crest.setVisibility(View.VISIBLE);
        crest.setTeam(side.team);
        name.setText(side.team.name);
        boolean decided = m.state == Match.State.POST && (side.winner || other.winner);
        boolean lost = decided && !side.winner;
        int color = c.getColor(lost ? R.color.on_surface_variant : R.color.on_surface);
        name.setTextColor(color);
        name.setTypeface(Typeface.create(side.winner ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        score.setText(m.state == Match.State.PRE ? "" : side.score);
        score.setTextColor(color);
        score.setVisibility(m.sport == Sport.CRICKET && side.score.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
