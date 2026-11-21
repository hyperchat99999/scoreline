package app.scoreline.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.ui.widget.CrestView;

/** Rows with a crest, a name and a Follow button, used by Discover and competition pages. */
final class FollowRows {
    private FollowRows() {}

    static View section(LayoutInflater inflater, ViewGroup parent, CharSequence title) {
        TextView v = (TextView) inflater.inflate(R.layout.item_section, parent, false);
        v.setText(title);
        return v;
    }

    /** A row for {@code f}. {@code open} makes the row itself navigable (shows a chevron). */
    static View row(LayoutInflater inflater, ViewGroup parent, App app, Follow f, String subtitle, Runnable open, Runnable changed) {
        View row = inflater.inflate(R.layout.item_row, parent, false);
        CrestView crest = row.findViewById(R.id.crest);
        boolean night = Ui.isNight(row.getContext());
        crest.set(f.team.crest(night), f.team.abbr.isEmpty() ? f.name() : f.team.abbr, f.team.color);
        ((TextView) row.findViewById(R.id.title)).setText(f.name());
        TextView sub = row.findViewById(R.id.subtitle);
        sub.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        sub.setText(subtitle);
        Runnable navigation = open != null ? open : () -> row.getContext().startActivity(f.kind == Follow.Kind.TEAM
                ? TeamActivity.intent(row.getContext(), f.sport, f.league, f.team)
                : CompetitionActivity.intent(row.getContext(), f.sport, f.league, f.name()));
        row.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> navigation.run());

        TextView button = row.findViewById(R.id.follow);
        Views.followButton(button, app.store().isFollowing(f));
        button.setContentDescription(row.getContext().getString(app.store().isFollowing(f) ? R.string.unfollow : R.string.follow) + " " + f.name());
        button.setOnClickListener(v -> {
            boolean now = app.store().toggle(f);
            Views.followButton(button, now);
            button.setContentDescription(row.getContext().getString(now ? R.string.unfollow : R.string.follow) + " " + f.name());
            Views.confirm(v);
            if (changed != null) changed.run();
        });
        return row;
    }

    /** "Premier League", "Cricket", used under a team's name. */
    static String subtitle(Context c, Follow f) {
        Catalog.Competition comp = Catalog.competition(f.sport, f.league);
        if (f.kind == Follow.Kind.COMPETITION) return c.getString(f.sport.label);
        return comp != null ? comp.name : c.getString(f.sport.label);
    }
}
