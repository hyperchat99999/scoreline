package app.scoreline.ui;

import android.content.Intent;
import android.content.Context;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.data.Feed;
import app.scoreline.data.Browse;
import app.scoreline.data.Store;
import app.scoreline.live.LiveService;
import app.scoreline.live.Tracker;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.ui.widget.CrestView;
import app.scoreline.widgets.WidgetSelection;
import app.scoreline.widgets.WidgetSnapshot;

/** The timeline: what's live, what's on today, what's coming and recent results for everything followed. */
public final class HomeActivity extends BaseActivity implements Store.Listener, MatchCard.Actions {
    private String revealed = "";
    private static final long REFRESH_LIVE = 30_000;
    private static final long REFRESH_IDLE = 5 * 60_000;
    private static final int MAX_UPCOMING = 12;
    private static final int MAX_RESULTS = 10;

    private LinearLayout rail;
    private LinearLayout feed;
    private TextView banner;
    private TextView footer;
    private ScrollView scroll;
    private Feed.Snapshot snapshot = Feed.Snapshot.EMPTY;
    private final Map<String, MatchCard> cards = new HashMap<>();
    /** Follow key the timeline is filtered to, or null for everything. */
    private String filter;
    private boolean loading;
    private String tab = "scores";
    private Sport sport = Sport.SOCCER;
    private LocalDate selectedDate = LocalDate.now();
    private boolean liveOnly;
    private boolean manage;
    private int generation;
    private Browse.Result<List<Match>> scoreboard;
    private long scoresUpdated;
    private Future<?> requestTask;
    private SportsSpotlight spotlight;
    private final Runnable autoRefresh = () -> refresh(false);

    static Intent intent(Context context, String tab) {
        return new Intent(context, HomeActivity.class).putExtra("tab", tab);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!app().store().onboarded()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        tab = state == null ? getIntent().getStringExtra("tab") : state.getString("tab", "scores");
        if (!"following".equals(tab)) tab = "scores";
        if (state != null) {
            sport = Sport.of(state.getString("sport"));
            try { selectedDate = LocalDate.parse(state.getString("selectedDate", LocalDate.now().toString())); }
            catch (java.time.DateTimeException ignored) { selectedDate = LocalDate.now(); }
            liveOnly = state.getBoolean("liveOnly");
            filter = state.getString("filter");
            manage = state.getBoolean("manage");
        } else manage = getIntent().getBooleanExtra("manage", false);
        rail = findViewById(R.id.rail);
        feed = findViewById(R.id.feed);
        spotlight = new SportsSpotlight(this);
        banner = findViewById(R.id.banner);
        footer = findViewById(R.id.footer);
        View root = findViewById(R.id.root);
        applyInsets(root, findViewById(R.id.header), findViewById(R.id.bottomNavigation));

        ((TextView) findViewById(R.id.date)).setText(today());
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        scroll = findViewById(R.id.scroll);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh(true));
        findViewById(R.id.manage).setOnClickListener(v -> { manage = !manage; render(); });

        app().store().addListener(this);
        snapshot = app().feed().last();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (feed == null) return;
        spotlight.setActive(true);
        render();
        refresh(false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tab = "following".equals(intent.getStringExtra("tab")) ? "following" : "scores";
        manage = intent.getBooleanExtra("manage", false);
        generation++;
        loading = false;
        render();
        refresh(false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab);
        state.putString("sport", sport.path);
        state.putString("selectedDate", selectedDate.toString());
        state.putBoolean("liveOnly", liveOnly);
        state.putBoolean("manage", manage);
        state.putString("filter", filter);
    }

    @Override
    protected void onPause() {
        if (spotlight != null) spotlight.setActive(false);
        removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        app().store().removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onFollowsChanged() {
        runOnUiThread(() -> {
            if (filter != null && !isFollowed(filter)) filter = null;
            if (feed != null) {
                render();
                if ("following".equals(tab)) refresh(true);
            }
        });
    }

    @Override
    public void open(Match m) {
        startActivity(MatchActivity.intent(this, m));
    }

    @Override
    public void toggleIsland(Match m) {
        IslandToggle.toggle(this, m);
        render();
    }

    // ---- Loading -------------------------------------------------------------------------------

    private void refresh(boolean fresh) {
        removeCallbacks(autoRefresh);
        if (loading && !fresh) return;
        int request = ++generation;
        if (requestTask != null) requestTask.cancel(true);
        loading = true;
        refreshUi();
        if ("scores".equals(tab)) {
            Sport requestedSport = sport;
            LocalDate requestedDate = selectedDate;
            requestTask = background(() -> app().browse().scores(requestedSport, null, requestedDate, ZoneId.systemDefault(), fresh), result -> {
                if (request != generation) return;
                scoreboard = result;
                if (!result.stale && !result.failed) scoresUpdated = System.currentTimeMillis();
                loading = false;
                render();
                if (requestedDate.equals(LocalDate.now())) postDelayed(autoRefresh, REFRESH_LIVE);
            }, e -> failed(request));
            return;
        }
        requestTask = background(() -> app().feed().load(fresh), s -> {
            if (request != generation) return;
            loading = false;
            snapshot = s;
            render();
            if (isScreenResumed() && !Tracker.wanted(s, app().store(), System.currentTimeMillis()).isEmpty()) LiveService.sync(this);
            boolean anyLive = false;
            for (Match m : s.matches) anyLive |= m.isLive();
            postDelayed(autoRefresh, anyLive ? REFRESH_LIVE : REFRESH_IDLE);
        }, e -> failed(request));
    }

    private void failed(int request) {
        if (request != generation) return;
        loading = false;
        render();
        banner.setText(R.string.request_failed);
        banner.setVisibility(View.VISIBLE);
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private void render() {
        if (feed == null) return;
        int y = scroll.getScrollY();
        boolean following = "following".equals(tab);
        ((TextView) findViewById(R.id.heading)).setText(following ? R.string.following : R.string.scores);
        findViewById(R.id.scoreControls).setVisibility(following ? View.GONE : View.VISIBLE);
        boolean hasFollows = !app().store().follows().isEmpty();
        findViewById(R.id.followControls).setVisibility(following && hasFollows ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.manage)).setText(manage ? R.string.show_matches : R.string.manage);
        refreshUi();
        Navigation.bind(this, findViewById(R.id.bottomNavigation), tab);
        if (!following) {
            renderScores();
            scroll.post(() -> scroll.scrollTo(0, y));
            return;
        }
        renderRail();
        if (manage) renderManage(); else renderFeed();
        reveal("following|" + manage + "|" + (snapshot.updatedAt > 0 || !snapshot.matches.isEmpty()));
        banner.setText(R.string.stale_scores);
        banner.setVisibility(snapshot.offline ? View.VISIBLE : View.GONE);
        footer.setText(manage || !hasFollows || snapshot.updatedAt == 0 ? "" : getString(R.string.updated, Ui.time(this, snapshot.updatedAt)));
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void refreshUi() {
        View refresh = findViewById(R.id.refresh);
        refresh.setEnabled(!loading);
        refresh.setAlpha(loading ? 0.4f : 1f);
        refresh.setContentDescription(getString(loading ? R.string.refreshing : R.string.refresh));
    }

    private void renderScores() {
        LinearLayout sports = findViewById(R.id.sports);
        sports.removeAllViews();
        for (Sport item : Catalog.sports()) {
            TextView button = chip(getString(item.label), sport == item, () -> {
                sport = item;
                changeDate(selectedDate);
            });
            Views.sportIcon(button, item, sport == item);
            sports.addView(button);
        }
        LinearLayout dates = findViewById(R.id.dateControls);
        dates.setBaselineAligned(false);
        dates.setPadding(dates.getPaddingLeft(), Ui.dp(this, 4), dates.getPaddingRight(), Ui.dp(this, 8));
        dates.setMinimumHeight(Ui.dp(this, 60));
        dates.removeAllViews();
        android.widget.ImageButton previous = dateArrow(false);
        previous.setOnClickListener(v -> changeDate(selectedDate.minusDays(1)));
        dates.addView(previous);
        TextView date = chip(selectedDate.format(DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault())), false, () -> {
            DatePickerDialog picker = new DatePickerDialog(this, (v, year, month, day) -> changeDate(LocalDate.of(year, month + 1, day)),
                    selectedDate.getYear(), selectedDate.getMonthValue() - 1, selectedDate.getDayOfMonth());
            picker.show();
        });
        date.setContentDescription(getString(R.string.choose_date) + ": " + selectedDate);
        date.setBackgroundResource(R.drawable.bg_row);
        dates.addView(date, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        android.widget.ImageButton next = dateArrow(true);
        next.setOnClickListener(v -> changeDate(selectedDate.plusDays(1)));
        dates.addView(next);
        TextView today = chip(getString(R.string.today), selectedDate.equals(LocalDate.now()), () -> changeDate(LocalDate.now()));
        today.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48)));
        today.setBackgroundResource(R.drawable.bg_btn_tonal);
        dates.addView(today);
        List<Match> all = scoreboard == null ? java.util.Collections.emptyList() : scoreboard.value;
        int live = 0;
        List<Match> shown = new ArrayList<>();
        for (Match m : all) { if (m.isLive()) live++; if (!liveOnly || m.isLive()) shown.add(m); }
        LinearLayout filters = findViewById(R.id.scoreFilters);
        filters.removeAllViews();
        filters.addView(chip(getString(R.string.all_count, all.size()), !liveOnly, () -> { liveOnly = false; render(); }));
        filters.addView(chip(getString(R.string.live_count, live), liveOnly, () -> { liveOnly = true; render(); }));
        feed.removeAllViews();
        if (scoreboard == null) {
            feed.addView(FollowRows.section(getLayoutInflater(), feed, getString(R.string.loading)));
        } else if (shown.isEmpty()) {
            empty(getLayoutInflater(), liveOnly ? R.string.no_live_scores : R.string.no_scores, R.string.scores_empty_body, false);
        } else {
            addSpotlight(shown, scoresUpdated, scoreboard.stale || scoreboard.failed);
            Map<String, List<Match>> groups = new LinkedHashMap<>();
            for (Match m : shown) groups.computeIfAbsent(m.league, k -> new ArrayList<>()).add(m);
            for (List<Match> matches : groups.values()) rows(getLayoutInflater(), matches, false);
        }
        int message = scoreboard != null && scoreboard.failed ? (scoreboard.value.isEmpty() ? R.string.request_failed : R.string.partial_scores)
                : scoreboard != null && scoreboard.stale ? R.string.stale_scores
                : sport == Sport.CRICKET ? R.string.cricket_coverage : 0;
        if (scoreboard != null && !scoreboard.supported) message = R.string.cricket_coverage;
        banner.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        if (message != 0) banner.setText(message);
        footer.setText(scoresUpdated == 0 ? getString(R.string.set_about_body) : getString(R.string.updated, Ui.time(this, scoresUpdated)));
        if (scoreboard != null) reveal("scores|" + sport + "|" + selectedDate + "|" + liveOnly);
    }

    /**
     * Fresh content rises into place card by card. Only when what the list shows changes (another tab,
     * sport, day or filter, or the first load), never on the quiet refreshes that follow.
     */
    private void reveal(String content) {
        boolean fresh = !content.equals(revealed);
        revealed = content;
        if (!fresh || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        int count = Math.min(feed.getChildCount(), 8);
        for (int i = 0; i < count; i++) {
            View child = feed.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(Ui.dp(this, 20));
            child.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(360)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
    }

    private android.widget.ImageButton dateArrow(boolean next) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageDrawable(Views.tinted(this, R.drawable.ic_chevron_right, getColor(R.color.on_surface)));
        if (!next) button.setRotation(180);
        button.setBackgroundResource(R.drawable.bg_row);
        button.setContentDescription(getString(next ? R.string.next_day : R.string.previous_day));
        button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        return button;
    }

    private TextView chip(String text, boolean selected, Runnable action) {
        TextView chip = (TextView) getLayoutInflater().inflate(R.layout.item_chip, null, false);
        chip.setText(text);
        chip.setMinWidth(Ui.dp(this, 48));
        chip.setMinHeight(Ui.dp(this, 48));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setSelected(selected);
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(getColor(selected ? R.color.on_primary_container : R.color.on_surface));
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        params.bottomMargin = Ui.dp(this, 8);
        chip.setLayoutParams(params);
        return chip;
    }

    private void changeDate(LocalDate date) {
        selectedDate = date;
        scoreboard = null;
        scoresUpdated = 0;
        generation++;
        loading = false;
        scroll.scrollTo(0, 0);
        render();
        refresh(false);
    }

    private void renderManage() {
        feed.removeAllViews();
        for (Follow f : app().store().follows()) feed.addView(FollowRows.row(getLayoutInflater(), feed, app(), f,
                FollowRows.subtitle(this, f), () -> openFollow(f), null));
        TextView add = chip(getString(R.string.add_follows), false, () -> startActivity(new Intent(this, DiscoverActivity.class)));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) add.getLayoutParams();
        params.setMarginStart(Ui.dp(this, 16));
        params.setMarginEnd(Ui.dp(this, 16));
        params.topMargin = Ui.dp(this, 12);
        add.setLayoutParams(params);
        feed.addView(add);
    }

    private void openFollow(Follow f) {
        startActivity(f.kind == Follow.Kind.TEAM ? TeamActivity.intent(this, f.sport, f.league, f.team)
                : CompetitionActivity.intent(this, f.sport, f.league, f.name()));
    }

    private void renderRail() {
        rail.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (Follow f : app().store().follows()) {
            View item = inflater.inflate(R.layout.item_follow_chip, rail, false);
            CrestView crest = item.findViewById(R.id.crest);
            boolean night = Ui.isNight(this);
            crest.set(f.team.crest(night), f.team.abbr.isEmpty() ? f.name() : f.team.abbr, f.team.color);
            ((TextView) item.findViewById(R.id.label)).setText(f.kind == Follow.Kind.TEAM ? f.team.shortName : f.name());
            boolean selected = f.key().equals(filter);
            item.findViewById(R.id.ring).setBackgroundResource(selected ? R.drawable.bg_avatar_selected : R.drawable.bg_avatar);
            item.findViewById(R.id.liveDot).setVisibility(hasLive(f) ? View.VISIBLE : View.GONE);
            item.setContentDescription(f.name());
            item.setSelected(selected);
            item.setOnClickListener(v -> {
                filter = selected ? null : f.key();
                Views.confirm(v);
                render();
            });
            rail.addView(item);
        }
        View add = inflater.inflate(R.layout.item_follow_chip, rail, false);
        add.findViewById(R.id.crest).setVisibility(View.GONE);
        ImageView icon = add.findViewById(R.id.icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(R.drawable.ic_add);
        ((TextView) add.findViewById(R.id.label)).setText(R.string.add_follow);
        add.setContentDescription(getString(R.string.discover));
        add.setOnClickListener(v -> startActivity(new Intent(this, DiscoverActivity.class)));
        rail.addView(add);
    }

    private void renderFeed() {
        feed.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        List<Match> candidates = new ArrayList<>();
        for (Match match : snapshot.matches) if (visible(match)) candidates.add(match);
        WidgetSnapshot saved = app().widgets().snapshot();
        if (candidates.isEmpty() && snapshot.updatedAt == 0)
            for (Match match : saved.matches) if (visible(match)) candidates.add(match);
        addSpotlight(candidates, snapshot.updatedAt > 0 ? snapshot.updatedAt : saved.updatedAt, snapshot.offline || saved.stale);
        if (app().store().follows().isEmpty()) {
            empty(inflater, R.string.empty_none_title, R.string.empty_none_body, true);
            return;
        }
            if (snapshot.updatedAt == 0 && snapshot.matches.isEmpty()) {
            for (int i = 0; i < 3; i++) feed.addView(inflater.inflate(R.layout.item_skeleton, feed, false));
            return;
        }

        List<Match> live = new ArrayList<>();
        List<Match> today = new ArrayList<>();
        List<Match> upcoming = new ArrayList<>();
        List<Match> results = new ArrayList<>();
        for (Match m : snapshot.matches) {
            if (!visible(m)) continue;
            if (m.isLive()) live.add(m);
            else if (m.state == Match.State.PRE && Ui.isToday(m.start)) today.add(m);
            else if (m.state == Match.State.PRE) upcoming.add(m);
            else results.add(m);
        }
        results.sort(Comparator.comparingLong((Match m) -> m.start).reversed());
        if (live.isEmpty() && today.isEmpty() && upcoming.isEmpty() && results.isEmpty()) {
            empty(inflater, R.string.empty_title, R.string.empty_body, false);
            return;
        }
        Map<String, MatchCard> reused = new HashMap<>();
        section(inflater, R.string.section_live, live, Integer.MAX_VALUE, false, reused, true);
        section(inflater, R.string.section_today, today, Integer.MAX_VALUE, false, reused, false);
        section(inflater, R.string.section_upcoming, upcoming, MAX_UPCOMING, true, reused, false);
        section(inflater, R.string.section_results, results, MAX_RESULTS, true, reused, false);
        cards.clear();
        cards.putAll(reused);
    }

    private void addSpotlight(List<Match> matches, long sourceTime, boolean stale) {
        java.util.Set<String> pinned = new java.util.HashSet<>();
        for (app.scoreline.model.MatchRef reference : app().store().pinnedMatches()) pinned.add(reference.key());
        List<Match> ordered = WidgetSelection.ordered(matches, pinned);
        if (ordered.isEmpty()) return;
        Match selected = ordered.get(0);
        if (spotlight.getParent() != null) ((ViewGroup) spotlight.getParent()).removeView(spotlight);
        feed.addView(spotlight);
        spotlight.bind(selected, selected.checkedAt > 0 ? selected.checkedAt : sourceTime, stale,
                () -> startActivity(MatchActivity.intent(this, selected)));
    }

    private void section(LayoutInflater inflater, int title, List<Match> matches, int max, boolean withDay,
                         Map<String, MatchCard> reused, boolean live) {
        if (matches.isEmpty()) return;
        int shown = Math.min(max, matches.size());
        TextView header = (TextView) inflater.inflate(R.layout.item_section, feed, false);
        // "Coming up  12": the count in the quiet colour, so the title still leads.
        SpannableString text = new SpannableString(getString(title) + "  " + shown);
        text.setSpan(new ForegroundColorSpan(getColor(R.color.on_surface_variant)), text.length() - String.valueOf(shown).length(),
                text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        header.setText(text);
        if (live) header.setCompoundDrawablesRelativeWithIntrinsicBounds(Views.tinted(this, R.drawable.dot_live, getColor(R.color.live)), null, null, null);
        feed.addView(header);
        if (!live) {
            rows(inflater, matches.subList(0, shown), withDay);
            return;
        }
        for (int i = 0; i < shown; i++) {
            Match m = matches.get(i);
            MatchCard card = cards.get(m.key());
            if (card == null) card = new MatchCard(inflater, feed);
            if (card.root.getParent() != null) ((ViewGroup) card.root.getParent()).removeView(card.root);
            card.bind(app(), m, withDay, this);
            reused.put(m.key(), card);
            feed.addView(card.root);
        }
    }

    /**
     * Fixtures and results as compact rows: one card per competition per day, headed like a live
     * card (logo and name), with the day added where the section spans several.
     */
    private void rows(LayoutInflater inflater, List<Match> matches, boolean withDay) {
        // Matches arrive in time order, so groups come out in order of their first match.
        Map<String, List<Match>> groups = new LinkedHashMap<>();
        for (Match m : matches) {
            String day = withDay ? Ui.day(this, m.start) : "";
            groups.computeIfAbsent(day + "|" + m.sport + "|" + m.league, k -> new ArrayList<>()).add(m);
        }
        boolean night = Ui.isNight(this);
        for (List<Match> group : groups.values()) {
            Match first = group.get(0);
            LinearLayout card = (LinearLayout) inflater.inflate(R.layout.item_group, feed, false);
            Catalog.Competition comp = Catalog.competition(first.sport, first.league);
            CrestView logo = card.findViewById(R.id.groupLogo);
            logo.setVisibility(comp == null ? View.GONE : View.VISIBLE);
            if (comp != null) logo.set(night ? comp.logoDark : comp.logo, "", "");
            String day = withDay ? Ui.day(this, first.start) : "";
            ((TextView) card.findViewById(R.id.groupTitle)).setText(day.isEmpty() ? first.leagueName : first.leagueName + " · " + day);
            View groupHeader = card.findViewById(R.id.groupHeader);
            groupHeader.setOnClickListener(v -> startActivity(CompetitionActivity.intent(this, first.sport, first.league, first.leagueName)));
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) {
                    View divider = new View(this);
                    divider.setBackgroundColor(getColor(R.color.outline));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f)));
                    lp.setMarginStart(Ui.dp(this, 90));
                    card.addView(divider, lp);
                }
                MatchRow row = new MatchRow(inflater, card);
                row.bind(app(), group.get(i), this);
                card.addView(row.root);
            }
            feed.addView(card);
        }
    }

    private void empty(LayoutInflater inflater, int title, int body, boolean action) {
        View v = inflater.inflate(R.layout.view_empty, feed, false);
        ((TextView) v.findViewById(R.id.emptyTitle)).setText(title);
        ((TextView) v.findViewById(R.id.emptyBody)).setText(body);
        View button = v.findViewById(R.id.emptyAction);
        button.setVisibility(action ? View.VISIBLE : View.GONE);
        button.setOnClickListener(x -> startActivity(new Intent(this, DiscoverActivity.class)));
        feed.addView(v);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private boolean visible(Match m) {
        if (filter == null) return true;
        for (Follow f : app().store().follows()) if (f.key().equals(filter)) return f.covers(m);
        return true;
    }

    private boolean hasLive(Follow f) {
        for (Match m : snapshot.matches) if (m.isLive() && f.covers(m)) return true;
        return false;
    }

    private boolean isFollowed(String key) {
        for (Follow f : app().store().follows()) if (f.key().equals(key)) return true;
        return false;
    }

    private static String today() {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(LocalDate.now());
    }
}
