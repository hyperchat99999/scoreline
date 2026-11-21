package app.scoreline.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Future;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;
import app.scoreline.ui.widget.CrestView;

/** A persistent team identity and its available upcoming fixtures and recent results. */
public final class TeamActivity extends BaseActivity {
    private static final String EXTRA_SPORT = "sport", EXTRA_LEAGUE = "league";
    private Sport sport;
    private String league;
    private Team team;
    private Follow follow;
    private View root;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView followButton;
    private final HubViews.Load<List<Match>> schedule = new HubViews.Load<>(new ArrayList<>());
    private int generation;
    private int renderGeneration;
    private boolean valid;
    private Future<?> requestTask;

    /**
     * The team's colour washed down from the top of the screen behind its sport's mark, and its crest
     * on a disc of that colour, as on the match screen.
     */
    private void brand(CrestView crest) {
        boolean night = Ui.isNight(this);
        int tone = app.scoreline.widgets.SportsWidgetProvider.teamColor(this, team, sport, night);
        android.graphics.drawable.LayerDrawable backdrop = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                new android.graphics.drawable.ColorDrawable(getColor(R.color.bg)),
                Views.heroBackdrop(this, tone, tone, app.scoreline.core.SportVisuals.glyph(sport))});
        backdrop.setLayerGravity(1, android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
        backdrop.setLayerHeight(1, Ui.dp(this, 320));
        root.setBackground(backdrop);
        android.view.ViewGroup.LayoutParams size = crest.getLayoutParams();
        size.width = size.height = Ui.dp(this, 60);
        crest.setLayoutParams(size);
        int pad = Ui.dp(this, 10);
        crest.setPadding(pad, pad, pad, pad);
        crest.setBackgroundResource(R.drawable.bg_spotlight_crest);
        crest.setBackgroundTintList(android.content.res.ColorStateList.valueOf((tone & 0x00FFFFFF) | ((night ? 0x5C : 0x3A) << 24)));
    }

    public static Intent intent(Context context, Sport sport, String league, Team team) {
        return new Intent(context, TeamActivity.class).putExtra(EXTRA_SPORT, sport.path).putExtra(EXTRA_LEAGUE, league)
                .putExtra("id", team.id).putExtra("name", team.name).putExtra("shortName", team.shortName)
                .putExtra("abbr", team.abbr).putExtra("logo", team.logo).putExtra("logoDark", team.logoDark).putExtra("color", team.color);
    }

    private final MatchCard.Actions actions = new MatchCard.Actions() {
        @Override public void open(Match match) { startActivity(MatchActivity.intent(TeamActivity.this, match)); }
        @Override public void toggleIsland(Match match) {
            IslandToggle.toggle(TeamActivity.this, match);
            render(true);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            boolean relevant = false;
            for (Match match : schedule.value) if (match.isLive()
                    || match.state == Match.State.PRE && match.start >= now - 15 * 60_000 && match.start <= now + 15 * 60_000) relevant = true;
            if (valid && relevant && !schedule.loading) load(true);
            postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sport = Sport.of(getIntent().getStringExtra(EXTRA_SPORT));
        league = extra(EXTRA_LEAGUE);
        String name = extra("name");
        if (name.isEmpty()) name = getString(R.string.hub_team);
        team = new Team(extra("id"), name, extra("shortName"), extra("abbr"), extra("logo"), extra("logoDark"), extra("color"));
        follow = Follow.team(sport, league, team);
        valid = !team.id.isEmpty() && !league.isEmpty();
        setContentView(R.layout.activity_hub);
        root = findViewById(R.id.root);
        HubViews.responsiveIdentity(root);
        content = findViewById(R.id.hubContent);
        scroll = findViewById(R.id.hubScroll);
        followButton = findViewById(R.id.hubFollow);
        applyInsets(root, findViewById(R.id.top), scroll);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.hubTitle)).setText(R.string.hub_team);
        ((TextView) findViewById(R.id.hubName)).setText(team.name);
        CrestView crest = findViewById(R.id.hubCrest);
        crest.setTeam(team);
        brand(crest);
        app.scoreline.widgets.SportsWidgetProvider.whenCrestRead(this, team, () -> { if (!isFinishing()) brand(crest); });
        TextView context = findViewById(R.id.hubContext);
        Catalog.Competition competition = Catalog.competition(sport, league);
        if (competition != null) {
            context.setText(getString(sport.label) + " · " + competition.name);
            context.setContentDescription(getString(R.string.hub_open_competition, competition.name));
            context.setMinHeight(Ui.dp(this, 48));
            context.setBackgroundResource(R.drawable.bg_row);
            context.setOnClickListener(v -> startActivity(CompetitionActivity.intent(this, competition)));
        } else context.setText(sport.label);
        followButton.setOnClickListener(v -> {
            app().store().toggle(follow);
            bindFollow();
            Views.confirm(v);
        });
        findViewById(R.id.hubSectionScroll).setVisibility(View.GONE);
        findViewById(R.id.hubDates).setVisibility(View.GONE);
        findViewById(R.id.hubRefresh).setOnClickListener(v -> load(true));
        if (valid) load(false);
        else {
            followButton.setVisibility(View.GONE);
            findViewById(R.id.hubRefresh).setVisibility(View.GONE);
            findViewById(R.id.hubNotice).setVisibility(View.GONE);
            HubViews.message(content, R.string.hub_missing_team);
        }
    }

    private String extra(String name) {
        String value = getIntent().getStringExtra(name);
        return value == null ? "" : value;
    }

    private void load(boolean fresh) {
        if (!valid) return;
        int request = ++generation;
        if (requestTask != null) requestTask.cancel(true);
        schedule.loading = true;
        render(true);
        requestTask = background(() -> app().browse().team(sport, league, team, fresh), result -> {
            if (request != generation) return;
            schedule.accept(result, (result == null || result.failed && result.value.isEmpty()) && !schedule.value.isEmpty());
            render(true);
        }, error -> {
            if (request != generation) return;
            schedule.accept(null, true);
            render(true);
        });
    }

    private void render(boolean keepScroll) {
        int y = keepScroll ? scroll.getScrollY() : 0;
        int rendered = ++renderGeneration;
        bindFollow();
        HubViews.notice(root, schedule, !schedule.value.isEmpty(), () -> load(true));
        content.removeAllViews();
        if (!schedule.supported) HubViews.message(content, R.string.hub_team_schedule_unsupported);
        else {
            if (sport == Sport.CRICKET) HubViews.message(content, R.string.hub_cricket_schedule);
            List<Match> live = new ArrayList<>();
            List<Match> upcoming = new ArrayList<>();
            List<Match> recent = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Match match : schedule.value) {
                if (match.isLive()) live.add(match);
                else if (match.state == Match.State.PRE || match.state == Match.State.OFF && match.start > now) upcoming.add(match);
                else recent.add(match);
            }
            Comparator<Match> order = Comparator.comparingLong(match -> match.start);
            live.sort(order);
            upcoming.sort(order);
            recent.sort(order.reversed());
            if (!live.isEmpty()) {
                HubViews.section(content, getString(R.string.section_live));
                HubViews.matches(content, app(), live, true, actions);
            }
            if (!upcoming.isEmpty() || schedule.loaded && !schedule.failed && !schedule.loading) {
                HubViews.section(content, getString(R.string.hub_upcoming));
                if (upcoming.isEmpty()) HubViews.message(content, R.string.hub_no_upcoming);
                else HubViews.matches(content, app(), upcoming, true, actions);
            }
            if (!recent.isEmpty() || schedule.loaded && !schedule.failed && !schedule.loading) {
                HubViews.section(content, getString(R.string.hub_recent));
                if (recent.isEmpty()) HubViews.message(content, R.string.hub_no_recent);
                else HubViews.matches(content, app(), recent, true, actions);
            }
        }
        scroll.post(() -> { if (rendered == renderGeneration) scroll.scrollTo(0, y); });
    }

    private void bindFollow() {
        boolean following = app().store().isFollowing(follow);
        Views.followButton(followButton, following);
        followButton.setContentDescription(getString(following ? R.string.hub_unfollow_team : R.string.hub_follow_team, team.name));
    }

    @Override protected void onResume() {
        super.onResume();
        if (valid) {
            render(true);
            postDelayed(poll, 60_000);
        }
    }

    @Override protected void onPause() {
        removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        renderGeneration++;
        super.onDestroy();
    }
}
