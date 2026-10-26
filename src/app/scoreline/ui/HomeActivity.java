package app.scoreline.ui;

import android.content.Intent;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.data.Feed;
import app.scoreline.data.Store;
import app.scoreline.live.LiveService;
import app.scoreline.live.Tracker;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.ui.widget.CrestView;

/** The timeline: what's live, what's on today, what's coming and recent results for everything followed. */
public final class HomeActivity extends BaseActivity implements Store.Listener, MatchCard.Actions {
    private static final long REFRESH_LIVE = 30_000;
    private static final long REFRESH_IDLE = 5 * 60_000;
    private static final int MAX_UPCOMING = 12;
    private static final int MAX_RESULTS = 10;

    private LinearLayout rail;
    private LinearLayout feed;
    private TextView banner;
    private TextView footer;
    private View scrim;
    private Feed.Snapshot snapshot = Feed.Snapshot.EMPTY;
    private final Map<String, MatchCard> cards = new HashMap<>();
    /** Follow key the timeline is filtered to, or null for everything. */
    private String filter;
    private boolean loading;
    private final Runnable autoRefresh = () -> refresh(false);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!app().store().onboarded()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        rail = findViewById(R.id.rail);
        feed = findViewById(R.id.feed);
        banner = findViewById(R.id.banner);
        footer = findViewById(R.id.footer);
        scrim = findViewById(R.id.statusScrim);
        View root = findViewById(R.id.root);
        View content = findViewById(R.id.content);
        applyInsets(root, findViewById(R.id.header), content, bars -> {
            ViewGroup.LayoutParams lp = scrim.getLayoutParams();
            lp.height = bars.top;
            scrim.setLayoutParams(lp);
        });

        ((TextView) findViewById(R.id.date)).setText(today());
        findViewById(R.id.search).setOnClickListener(v -> startActivity(new Intent(this, DiscoverActivity.class)));
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        ScrollView scroll = findViewById(R.id.scroll);
        float fadeOver = Ui.dp(this, 24);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> scrim.setAlpha(Math.min(1f, y / fadeOver)));

        app().store().addListener(this);
        snapshot = app().feed().last();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(snapshot.updatedAt == 0);
    }

    @Override
    protected void onPause() {
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
            render();
            refresh(false);
        });
    }

    @Override
    public void open(Match m) {
        startActivity(MatchActivity.intent(this, m.key()));
    }

    @Override
    public void toggleIsland(Match m) {
        IslandToggle.toggle(this, m);
        render();
    }

    // ---- Loading -------------------------------------------------------------------------------

    private void refresh(boolean fresh) {
        removeCallbacks(autoRefresh);
        if (loading) return;
        loading = true;
        background(() -> app().feed().load(fresh), s -> {
            loading = false;
            snapshot = s;
            render();
            if (isScreenResumed() && !Tracker.wanted(s, app().store(), System.currentTimeMillis()).isEmpty()) LiveService.sync(this);
            boolean anyLive = false;
            for (Match m : s.matches) anyLive |= m.isLive();
            postDelayed(autoRefresh, anyLive ? REFRESH_LIVE : REFRESH_IDLE);
        });
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private void render() {
        renderRail();
        renderFeed();
        banner.setVisibility(snapshot.offline ? View.VISIBLE : View.GONE);
        footer.setText(snapshot.updatedAt == 0 ? "" : getString(R.string.updated, Ui.time(this, snapshot.updatedAt)));
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
        if (app().store().follows().isEmpty()) {
            empty(inflater, R.string.empty_none_title, R.string.empty_none_body, true);
            return;
        }
        if (snapshot.updatedAt == 0) {
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
