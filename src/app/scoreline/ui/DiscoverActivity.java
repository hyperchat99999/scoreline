package app.scoreline.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.scoreline.core.Ui;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.scoreline.R;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;

/**
 * Browse by sport, or search every team and competition Scoreline knows. Also the second step of
 * onboarding, where it gains a "Continue" bar.
 */
public final class DiscoverActivity extends BaseActivity {
    static final String EXTRA_ONBOARDING = "onboarding";
    private static final int MAX_RESULTS = 40;

    private LinearLayout list;
    private LinearLayout sports;
    private EditText query;
    private TextView next;
    private Sport sport = Sport.SOCCER;
    private boolean onboarding;

    /** Every team we've loaded, keyed by follow key, for search. Filled in the background. */
    private final Map<String, Follow> index = new LinkedHashMap<>();
    private int indexPending;
    private ExecutorService indexer;
    private int indexFailed;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable renderSearch = this::render;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_discover);
        onboarding = getIntent().getBooleanExtra(EXTRA_ONBOARDING, false);
        if (state != null) sport = Sport.of(state.getString("sport"));
        list = findViewById(R.id.list);
        sports = findViewById(R.id.sports);
        query = findViewById(R.id.query);
        next = findViewById(R.id.next);
        View bottom = findViewById(R.id.bottom);
        View navigation = findViewById(R.id.bottomNavigation);
        navigation.setVisibility(onboarding ? View.GONE : View.VISIBLE);
        if (!onboarding) Navigation.bind(this, (LinearLayout) navigation, "explore");
        applyInsets(findViewById(R.id.root), findViewById(R.id.top), onboarding ? bottom : navigation);
        if (!onboarding) ((TextView) findViewById(R.id.heading)).setText(R.string.explore);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        if (!onboarding) {
            // As a tab, Explore gets the same big heading and indent as Scores and Following.
            findViewById(R.id.back).setVisibility(View.GONE);
            TextView heading = findViewById(R.id.heading);
            heading.setTextAppearance(R.style.Text_Display);
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) heading.getLayoutParams();
            lp.setMarginStart(Ui.dp(this, 16));
            heading.setLayoutParams(lp);
            View row = (View) heading.getParent();
            android.view.ViewGroup.LayoutParams rowLp = row.getLayoutParams();
            rowLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            row.setLayoutParams(rowLp);
            row.setPadding(row.getPaddingLeft(), Ui.dp(this, 28), row.getPaddingRight(), Ui.dp(this, 8));
        }
        if (onboarding) {
            ((TextView) findViewById(R.id.heading)).setText(R.string.pick_title);
            findViewById(R.id.intro).setVisibility(View.VISIBLE);
            bottom.setVisibility(View.VISIBLE);
            next.setOnClickListener(v -> {
                setResult(RESULT_OK);
                finish();
            });
            updateNext();
        }

        View clear = findViewById(R.id.clear);
        clear.setOnClickListener(v -> query.setText(""));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (s.length() > 0) buildIndex();
                searchHandler.removeCallbacks(renderSearch);
                searchHandler.postDelayed(renderSearch, 180);
            }
        });
        query.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) hideKeyboard();
            return action == EditorInfo.IME_ACTION_SEARCH;
        });

        for (Follow f : Catalog.POPULAR) index.put(f.key(), f);
        for (Team t : Catalog.CRICKET_NATIONS) {
            Follow f = Follow.team(Sport.CRICKET, "intl", t);
            index.put(f.key(), f);
        }
        renderSports();
        render();
    }

    @Override
    protected void onDestroy() {
        searchHandler.removeCallbacksAndMessages(null);
        if (indexer != null) indexer.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Follow state may have changed on a competition page.
        render();
        updateNext();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("sport", sport.path);
        super.onSaveInstanceState(state);
    }

    // ---- Rendering -----------------------------------------------------------------------------

    private void renderSports() {
        sports.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (Sport s : Catalog.sports()) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_chip, sports, false);
            chip.setText(s.label);
            boolean on = s == sport;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            chip.setTextColor(getColor(on ? R.color.on_primary_container : R.color.on_surface));
            chip.setSelected(on);
            Views.sportIcon(chip, s, on);
            chip.setOnClickListener(v -> {
                sport = s;
                if (query.length() > 0) query.setText("");
                renderSports();
                render();
            });
            sports.addView(chip);
        }
    }

    private void render() {
        list.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        String q = normalise(query.getText().toString());
        if (q.isEmpty()) {
            browse(inflater);
        } else {
            search(inflater, q);
        }
    }

    /** Teams first (that's what most people follow), then the sport's competitions. */
    private void browse(LayoutInflater inflater) {
        List<Follow> teams = Catalog.popular(sport);
        if (!teams.isEmpty()) {
            list.addView(FollowRows.section(inflater, list, getString(sport == Sport.CRICKET ? R.string.all_teams : R.string.popular_teams)));
            for (Follow f : teams) list.addView(FollowRows.row(inflater, list, app(), f, FollowRows.subtitle(this, f), null, this::updateNext));
        }
        List<Catalog.Competition> comps = Catalog.competitions(sport);
        if (!comps.isEmpty()) {
            list.addView(FollowRows.section(inflater, list, getString(R.string.competitions)));
            for (Catalog.Competition c : comps) {
                Runnable open = () -> startActivity(CompetitionActivity.intent(this, c));
                list.addView(FollowRows.row(inflater, list, app(), c.follow(), null, open, this::updateNext));
            }
        }
    }

    private void search(LayoutInflater inflater, String q) {
        List<View> rows = new ArrayList<>();
        for (Catalog.Competition c : Catalog.COMPETITIONS) {
            if (!normalise(c.name).contains(q)) continue;
            Runnable open = () -> startActivity(CompetitionActivity.intent(this, c));
            rows.add(FollowRows.row(inflater, list, app(), c.follow(), getString(c.sport.label), open, this::updateNext));
        }
        List<Follow> teams;
        synchronized (index) {
            teams = new ArrayList<>(index.values());
        }
        for (Follow f : teams) {
            if (rows.size() >= MAX_RESULTS) break;
            Team t = f.team;
            if (normalise(t.name).contains(q) || normalise(t.shortName).contains(q) || normalise(t.abbr).equals(q)) {
                rows.add(FollowRows.row(inflater, list, app(), f, FollowRows.subtitle(this, f), null, this::updateNext));
            }
        }
        if (rows.isEmpty()) {
            TextView none = (TextView) FollowRows.section(inflater, list, indexPending > 0
                    ? getString(R.string.loading) : getString(R.string.no_results, query.getText().toString().trim()));
            none.setTextColor(getColor(R.color.on_surface_variant));
            list.addView(none);
            if (indexPending == 0 && indexFailed > 0) searchStatus(inflater);
            return;
        }
        for (View r : rows) list.addView(r);
        searchStatus(inflater);
    }

    private void searchStatus(LayoutInflater inflater) {
        if (indexPending == 0 && indexFailed == 0) return;
        TextView status = (TextView) FollowRows.section(inflater, list, getString(indexPending > 0 ? R.string.index_loading : R.string.search_partial));
        status.setMinHeight(app.scoreline.core.Ui.dp(this, 48));
        if (indexPending == 0) status.setOnClickListener(v -> {
            if (indexer != null) indexer.shutdownNow();
            indexer = null;
            indexFailed = 0;
            buildIndex();
            render();
        });
        list.addView(status);
    }

    // ---- Search index --------------------------------------------------------------------------

    /** Loads every browsable competition's teams once (cached for a week) so search finds any club. */
    private void buildIndex() {
        if (indexer != null) return;
        indexer = Executors.newFixedThreadPool(4);
        for (Catalog.Competition c : Catalog.COMPETITIONS) {
            if (!c.browsable) continue;
            indexPending++;
            indexer.execute(() -> {
                List<Team> teams;
                boolean failed = false;
                try {
                    teams = app().espn().teams(c.sport, c.league);
                } catch (IOException | RuntimeException e) {
                    teams = new ArrayList<>();
                    failed = true;
                }
                synchronized (index) {
                    for (Team t : teams) {
                        Follow f = Follow.team(c.sport, c.league, t);
                        index.putIfAbsent(f.key(), f);
                    }
                }
                boolean loadFailed = failed;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    indexPending--;
                    if (loadFailed) indexFailed++;
                    if (query.length() > 0) {
                        searchHandler.removeCallbacks(renderSearch);
                        searchHandler.postDelayed(renderSearch, 150);
                    }
                });
            });
        }
    }

    private static String normalise(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private void updateNext() {
        if (!onboarding || next == null) return;
        int n = app().store().follows().size();
        next.setText(n == 0 ? getString(R.string.continue_plain) : getString(R.string.continue_n, n));
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    static Intent onboardingIntent(Context c) {
        return new Intent(c, DiscoverActivity.class).putExtra(EXTRA_ONBOARDING, true);
    }
}
