package app.scoreline.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Standings;
import app.scoreline.model.Team;
import app.scoreline.ui.widget.CrestView;

/** A competition's dated scoreboard, provider standings and browsable teams. */
public final class CompetitionActivity extends BaseActivity {
    private static final String EXTRA_SPORT = "sport";
    private static final String EXTRA_LEAGUE = "league";
    private static final String EXTRA_NAME = "name";
    private static final int FIXTURES = 0, STANDINGS = 1, TEAMS = 2;

    private Sport sport;
    private String league;
    private Follow follow;
    private int section;
    private LocalDate date = LocalDate.now();
    private final Map<LocalDate, HubViews.Load<List<Match>>> scores = new HashMap<>();
    private final HubViews.Load<Standings> table = new HubViews.Load<>(null);
    private final HubViews.Load<List<Team>> teams = new HubViews.Load<>(new ArrayList<>());
    private View root;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView followButton;
    private TextView dateButton;
    private final List<TextView> tabs = new ArrayList<>();
    private int generation;
    private int renderGeneration;
    private boolean valid;
    private Future<?> requestTask;

    public static Intent intent(Context context, Catalog.Competition competition) {
        return intent(context, competition.sport, competition.league, competition.name);
    }

    /** Also accepts provider competitions, such as cricket series outside the curated catalog. */
    public static Intent intent(Context context, Sport sport, String league, String name) {
        return new Intent(context, CompetitionActivity.class).putExtra(EXTRA_SPORT, sport.path)
                .putExtra(EXTRA_LEAGUE, league).putExtra(EXTRA_NAME, name);
    }

    private final MatchCard.Actions actions = new MatchCard.Actions() {
        @Override public void open(Match match) { startActivity(MatchActivity.intent(CompetitionActivity.this, match)); }
        @Override public void toggleIsland(Match match) {
            IslandToggle.toggle(CompetitionActivity.this, match);
            render(true);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (valid && section == FIXTURES && date.equals(LocalDate.now()) && !selectedScores().loading) load(true);
            postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sport = Sport.of(getIntent().getStringExtra(EXTRA_SPORT));
        String requestedLeague = getIntent().getStringExtra(EXTRA_LEAGUE);
        league = requestedLeague == null ? "" : requestedLeague;
        Catalog.Competition competition = Catalog.competition(sport, league);
        String name = competition == null ? getIntent().getStringExtra(EXTRA_NAME) : competition.name;
        if (name == null || name.isEmpty()) name = league;
        follow = competition == null ? Follow.competition(sport, league, name, "", "") : competition.follow();
        valid = !league.isEmpty();
        if (state != null) {
            section = Math.max(FIXTURES, Math.min(TEAMS, state.getInt("section", FIXTURES)));
            try { date = LocalDate.parse(state.getString("date", date.toString())); }
            catch (RuntimeException ignored) { date = LocalDate.now(); }
        }
        setContentView(R.layout.activity_hub);
        root = findViewById(R.id.root);
        HubViews.responsiveIdentity(root);
        content = findViewById(R.id.hubContent);
        scroll = findViewById(R.id.hubScroll);
        followButton = findViewById(R.id.hubFollow);
        applyInsets(root, findViewById(R.id.top), scroll);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.hubTitle)).setText(R.string.hub_competition);
        ((TextView) findViewById(R.id.hubName)).setText(name);
        ((TextView) findViewById(R.id.hubContext)).setText(sport.label);
        ((CrestView) findViewById(R.id.hubCrest)).set(follow.team.crest(Ui.isNight(this)), name, "");
        followButton.setOnClickListener(v -> {
            app().store().toggle(follow);
            bindFollow();
            Views.confirm(v);
        });
        findViewById(R.id.hubRefresh).setOnClickListener(v -> load(true));
        createSections();
        createDates();
        if (valid) load(false);
        else {
            followButton.setVisibility(View.GONE);
            findViewById(R.id.hubRefresh).setVisibility(View.GONE);
            findViewById(R.id.hubSectionScroll).setVisibility(View.GONE);
            findViewById(R.id.hubDates).setVisibility(View.GONE);
            findViewById(R.id.hubNotice).setVisibility(View.GONE);
            HubViews.message(content, R.string.hub_missing_competition);
        }
    }

    private void createSections() {
        LinearLayout sections = findViewById(R.id.hubSections);
        int[] names = {sport == Sport.RACING ? R.string.hub_sessions : R.string.hub_fixtures, R.string.hub_table, R.string.hub_teams};
        for (int i = 0; i < names.length; i++) {
            final int selected = i;
            TextView tab = HubViews.button(this, getString(names[i]));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(this, 8));
            sections.addView(tab, p);
            tabs.add(tab);
            tab.setOnClickListener(v -> {
                if (section == selected) return;
                section = selected;
                scroll.scrollTo(0, 0);
                load(false);
            });
        }
    }

    private void createDates() {
        LinearLayout dates = findViewById(R.id.hubDates);
        ImageButton previous = dateArrow(R.string.hub_previous_date, false);
        previous.setOnClickListener(v -> changeDate(date.minusDays(1)));
        dates.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        dateButton = HubViews.button(this, "");
        dateButton.setOnClickListener(v -> new DatePickerDialog(this,
                (picker, year, month, day) -> changeDate(LocalDate.of(year, month + 1, day)),
                date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show());
        dates.addView(dateButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton next = dateArrow(R.string.hub_next_date, true);
        next.setOnClickListener(v -> changeDate(date.plusDays(1)));
        dates.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView today = HubViews.button(this, getString(R.string.today));
        today.setOnClickListener(v -> changeDate(LocalDate.now()));
        dates.addView(today, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private ImageButton dateArrow(int description, boolean next) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(Views.tinted(this, R.drawable.ic_arrow_back, getColor(R.color.on_surface)));
        button.setBackgroundResource(R.drawable.bg_row);
        button.setContentDescription(getString(description));
        if (next) button.setRotation(180);
        return button;
    }

    private void changeDate(LocalDate selected) {
        if (date.equals(selected)) return;
        date = selected;
        scroll.scrollTo(0, 0);
        load(false);
    }

    private HubViews.Load<List<Match>> selectedScores() {
        HubViews.Load<List<Match>> value = scores.get(date);
        if (value == null) {
            value = new HubViews.Load<>(new ArrayList<>());
            scores.put(date, value);
            // A short dated cache keeps back/forward useful without retaining months of scoreboards.
            if (scores.size() > 14) {
                LocalDate oldest = null;
                for (LocalDate candidate : scores.keySet()) if (!candidate.equals(date)
                        && (oldest == null || candidate.isBefore(oldest))) oldest = candidate;
                if (oldest != null) scores.remove(oldest);
            }
        }
        return value;
    }

    private void load(boolean fresh) {
        if (!valid) return;
        final int request = ++generation;
        if (requestTask != null) requestTask.cancel(true);
        final int requestedSection = section;
        if (section == FIXTURES) {
            final LocalDate requestedDate = date;
            final HubViews.Load<List<Match>> state = selectedScores();
            state.loading = true;
            render(true);
            requestTask = background(() -> app().browse().scores(sport, league, requestedDate, ZoneId.systemDefault(), fresh), result -> {
                if (request != generation || section != requestedSection || !date.equals(requestedDate)) return;
                state.accept(result, (result == null || result.failed && result.value.isEmpty()) && !state.value.isEmpty());
                render(true);
            }, error -> failed(request, state));
        } else if (section == STANDINGS) {
            table.loading = true;
            render(true);
            requestTask = background(() -> app().browse().standings(sport, league, fresh), result -> {
                if (request != generation || section != requestedSection) return;
                table.accept(result, (result == null || result.failed && result.value.groups.isEmpty()) && table.value != null);
                render(true);
            }, error -> failed(request, table));
        } else {
            teams.loading = true;
            render(true);
            requestTask = background(() -> app().browse().teams(sport, league, fresh), result -> {
                if (request != generation || section != requestedSection) return;
                teams.accept(result, (result == null || result.failed && result.value.isEmpty()) && !teams.value.isEmpty());
                render(true);
            }, error -> failed(request, teams));
        }
    }

    private void failed(int request, HubViews.Load<?> state) {
        if (request != generation) return;
        state.accept(null, true);
        render(true);
    }

    private void render(boolean keepScroll) {
        int y = keepScroll ? scroll.getScrollY() : 0;
        int rendered = ++renderGeneration;
        bindFollow();
        for (int i = 0; i < tabs.size(); i++) HubViews.tab(tabs.get(i), section == i);
        findViewById(R.id.hubDates).setVisibility(section == FIXTURES ? View.VISIBLE : View.GONE);
        String label = HubViews.date(this, date);
        dateButton.setText(label);
        dateButton.setContentDescription(getString(R.string.hub_choose_date, label));
        content.removeAllViews();
        if (section == FIXTURES) renderScores();
        else if (section == STANDINGS) renderStandings();
        else renderTeams();
        scroll.post(() -> { if (rendered == renderGeneration) scroll.scrollTo(0, y); });
    }

    private void renderScores() {
        HubViews.Load<List<Match>> state = selectedScores();
        HubViews.notice(root, state, !state.value.isEmpty(), () -> load(true));
        if (!state.supported) { HubViews.message(content, R.string.cricket_coverage); return; }
        if (state.value.isEmpty()) {
            if (state.loaded && !state.loading && !state.failed) HubViews.message(content, R.string.hub_no_matches);
            return;
        }
        List<Match> live = new ArrayList<>();
        List<Match> others = new ArrayList<>();
        for (Match match : state.value) (match.isLive() ? live : others).add(match);
        Comparator<Match> order = Comparator.comparingLong(match -> match.start);
        live.sort(order);
        others.sort(order);
        if (!live.isEmpty()) {
            HubViews.section(content, getString(R.string.section_live));
            HubViews.matches(content, app(), live, false, actions);
        }
        if (!others.isEmpty()) {
            HubViews.section(content, getString(sport == Sport.RACING ? R.string.hub_sessions : R.string.hub_scheduled));
            HubViews.matches(content, app(), others, false, actions);
        }
    }

    private void renderStandings() {
        boolean hasData = table.value != null && !table.value.groups.isEmpty();
        HubViews.notice(root, table, hasData, () -> load(true));
        if (!table.supported || table.value != null && !table.value.supported) {
            HubViews.message(content, sport == Sport.RACING ? R.string.hub_racing_table_unsupported
                    : sport == Sport.CRICKET ? R.string.hub_cricket_table_unsupported : R.string.hub_table_unsupported);
        } else if (hasData) {
            HubViews.standings(content, table.value, team -> startActivity(TeamActivity.intent(this, sport, league, team)));
        } else if (table.loaded && !table.loading && !table.failed) HubViews.message(content, R.string.hub_no_standings);
    }

    private void renderTeams() {
        HubViews.notice(root, teams, !teams.value.isEmpty(), () -> load(true));
        if (!teams.supported) {
            HubViews.message(content, sport == Sport.RACING ? R.string.hub_racing_teams_unsupported : R.string.hub_teams_unsupported);
        } else if (!teams.value.isEmpty()) {
            if (sport == Sport.CRICKET) HubViews.message(content, R.string.hub_cricket_teams);
            for (Team team : teams.value) {
                content.addView(FollowRows.row(getLayoutInflater(), content, app(), Follow.team(sport, league, team), null,
                        () -> startActivity(TeamActivity.intent(this, sport, league, team)), null));
            }
        } else if (teams.loaded && !teams.loading && !teams.failed) HubViews.message(content, R.string.hub_no_teams);
    }

    private void bindFollow() {
        boolean following = app().store().isFollowing(follow);
        Views.followButton(followButton, following);
        followButton.setContentDescription(getString(following ? R.string.hub_unfollow_team : R.string.hub_follow_team, follow.name()));
    }

    @Override protected void onResume() {
        super.onResume();
        if (valid) {
            bindFollow();
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

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("section", section);
        state.putString("date", date.toString());
        super.onSaveInstanceState(state);
    }
}
