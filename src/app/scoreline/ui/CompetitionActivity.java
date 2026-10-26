package app.scoreline.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;
import app.scoreline.ui.widget.CrestView;

/** A competition's teams, with a Follow button for the competition itself in the header. */
public final class CompetitionActivity extends BaseActivity {
    private static final String EXTRA_SPORT = "sport";
    private static final String EXTRA_LEAGUE = "league";

    private LinearLayout list;
    private Catalog.Competition competition;

    static Intent intent(Context c, Catalog.Competition comp) {
        return new Intent(c, CompetitionActivity.class).putExtra(EXTRA_SPORT, comp.sport.path).putExtra(EXTRA_LEAGUE, comp.league);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        competition = Catalog.competition(Sport.of(getIntent().getStringExtra(EXTRA_SPORT)), getIntent().getStringExtra(EXTRA_LEAGUE));
        if (competition == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_competition);
        list = findViewById(R.id.list);
        applyInsets(findViewById(R.id.root), findViewById(R.id.top), findViewById(R.id.scroll));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.heading)).setText(competition.name);
        ((CrestView) findViewById(R.id.logo)).set(Ui.isNight(this) ? competition.logoDark : competition.logo, "", "");

        Follow follow = competition.follow();
        TextView button = findViewById(R.id.follow);
        Views.followButton(button, app().store().isFollowing(follow));
        button.setOnClickListener(v -> {
            Views.followButton(button, app().store().toggle(follow));
            Views.confirm(v);
        });

        list.addView(FollowRows.section(getLayoutInflater(), list, getString(R.string.loading)));
        background(() -> {
            try {
                return app().espn().teams(competition.sport, competition.league);
            } catch (IOException e) {
                return Collections.<Team>emptyList();
            }
        }, this::show);
    }

    private void show(List<Team> teams) {
        list.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        if (teams.isEmpty()) {
            TextView err = (TextView) FollowRows.section(inflater, list, getString(R.string.load_failed));
            err.setTextColor(getColor(R.color.on_surface_variant));
            list.addView(err);
            return;
        }
        list.addView(FollowRows.section(inflater, list, getString(R.string.all_teams)));
        for (Team t : teams) {
            Follow f = Follow.team(competition.sport, competition.league, t);
            list.addView(FollowRows.row(inflater, list, app(), f, null, null, null));
        }
    }
}
