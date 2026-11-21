package app.scoreline.widgets;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.data.Store;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;

/** Isolated persistence + real RemoteViews/AppWidgetHostView integration, with no network or installs. */
public final class WidgetSimulation extends Instrumentation {
    private int checks;
    private int logoFixtures;
    private final String prefix = "widget-simulation-" + System.currentTimeMillis() + "-";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            repository();
            preloadLogos();
            runOnMainSync(this::remoteViews);
            result.putString("stream", "\nChecks: " + checks + "\nFailures: 0\nLogo fixtures: " + logoFixtures + "/4\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "\nChecks: " + checks + "\nFailure: " + android.util.Log.getStackTraceString(error));
        } finally {
            for (String name : new String[] {"scoreline", "scoreline_widgets"})
                getTargetContext().getSharedPreferences(prefix + name, Context.MODE_PRIVATE).edit().clear().commit();
        }
        finish(code, result);
    }

    private Context isolated() {
        return new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
    }

    private Match match(Sport sport, String id) {
        Match match = new Match(); match.sport = sport; match.id = id; match.league = "test.league";
        match.leagueName = "TEST league"; match.title = "TEST fixture";
        match.start = System.currentTimeMillis(); match.checkedAt = match.start; match.state = Match.State.LIVE;
        String home = "TEST Arsenal", away = "TEST Chelsea", homeScore = "2", awayScore = "1", color = "EF0107";
        String homeOvers = "", awayOvers = "";
        match.clock = "67'"; match.period = 2;
        match.summary = "Arsenal lead by one";
        match.leagueName = "TEST Premier League";
        switch (sport) {
            case CRICKET:
                home = "TEST India"; away = "TEST Australia"; homeScore = "180/4"; awayScore = "167/6";
                homeOvers = "20.0"; awayOvers = "18.2"; color = "1769CE";
                match.leagueName = "TEST T20 series"; match.clock = "18.2 ov"; match.oversLimit = 20; match.battingTeamId = "away";
                match.summary = "Australia need 14 from 10 balls";
                break;
            case BASKETBALL:
                home = "TEST Lakers"; away = "TEST Celtics"; homeScore = "108"; awayScore = "104"; color = "B4541E";
                match.leagueName = "TEST NBA"; match.clock = "Q4 2:31"; match.period = 4; match.clockSeconds = 151;
                match.summary = "Fourth quarter";
                break;
            case FOOTBALL:
                home = "TEST Chiefs"; away = "TEST Bills"; homeScore = "27"; awayScore = "24"; color = "7946A0";
                match.leagueName = "TEST NFL"; match.clock = "Q4 4:32"; match.period = 4; match.clockSeconds = 272;
                match.summary = "Fourth quarter";
                break;
            case HOCKEY:
                home = "TEST Rangers"; away = "TEST Bruins"; homeScore = "3"; awayScore = "2"; color = "226CA5";
                match.leagueName = "TEST NHL"; match.clock = "P2 6:14"; match.period = 2; match.clockSeconds = 374;
                match.summary = "Second period";
                break;
            case BASEBALL:
                home = "TEST Yankees"; away = "TEST Red Sox"; homeScore = "4"; awayScore = "5"; color = "397146";
                match.leagueName = "TEST MLB"; match.clock = "▲7"; match.period = 7;
                match.summary = "Top of the seventh";
                break;
            case RACING:
                match.leagueName = "TEST Formula 1"; match.title = "TEST British Grand Prix"; match.session = "Race";
                match.clock = "Lap 34/52"; match.period = 34; match.laps = 52;
                match.summary = "Silverstone";
                break;
            default: break;
        }
        match.home = new Match.Side(new Team("home", home, home.substring(5), home.substring(5, 8).toUpperCase(java.util.Locale.ROOT), "", "", color), homeScore, homeOvers, false);
        match.away = new Match.Side(new Team("away", away, away.substring(5), away.substring(5, 8).toUpperCase(java.util.Locale.ROOT), "", "", ""), awayScore, awayOvers, false);
        if (sport == Sport.CRICKET) {
            match.home = new Match.Side(new Team("home", home, "India", "IND", logo("cricket", "6"), "", color), homeScore, homeOvers, false);
            match.away = new Match.Side(new Team("away", away, "Australia", "AUS", logo("cricket", "2"), "", ""), awayScore, awayOvers, false);
        } else if (sport == Sport.BASKETBALL) {
            match.home = new Match.Side(new Team("home", home, "Lakers", "LAL", logo("nba", "lal"), "", color), homeScore, homeOvers, false);
            match.away = new Match.Side(new Team("away", away, "Celtics", "BOS", logo("nba", "bos"), "", ""), awayScore, awayOvers, false);
        }
        if (sport == Sport.RACING) { match.home = null; match.away = null; }
        return match;
    }

    private static String logo(String sport, String id) { return "https://a.espncdn.com/i/teamlogos/" + sport + "/500/" + id + ".png"; }

    private void preloadLogos() throws InterruptedException {
        String[] urls = {logo("cricket", "6"), logo("cricket", "2"), logo("nba", "lal"), logo("nba", "bos")};
        CountDownLatch ready = new CountDownLatch(urls.length);
        AtomicInteger loaded = new AtomicInteger();
        App app = (App) getTargetContext().getApplicationContext();
        for (String url : urls) app.images().loadAsync(url, WidgetLogos.PIXELS, bitmap -> {
            if (bitmap != null) loaded.incrementAndGet();
            ready.countDown();
        });
        check(ready.await(30, TimeUnit.SECONDS), "Logo cache warming completes outside the receiver/render thread");
        logoFixtures = loaded.get();
    }

    private void repository() {
        Context context = isolated();
        Store store = new Store(context);
        WidgetRepository repository = new WidgetRepository(context, store);
        Match first = match(Sport.SOCCER, "first");
        Match second = match(Sport.SOCCER, "second"); second.start++;
        Follow follow = Follow.team(first.sport, first.league, first.home.team);
        store.toggle(follow);
        repository.publish(List.of(first, second), false, first.checkedAt);
        check(repository.selected(101).key().equals(first.key()), "First instance starts at priority selection");
        repository.cycle(101);
        check(repository.selected(101).key().equals(second.key()) && repository.selected(202).key().equals(first.key()), "Instances cycle independently");
        WidgetRepository restored = new WidgetRepository(context, store);
        check(restored.selected(101).key().equals(second.key()), "Per-instance key survives process-equivalent repository restart");
        check(restored.snapshot().matches.size() == 2, "Cached scores survive restart");
        first.home.periods.add("999");
        check(restored.snapshot().matches.get(0).home.periods.isEmpty(), "Repository owns detached source matches");
        repository.publish(List.of(), true, first.checkedAt + 10_000);
        check(repository.snapshot().matches.size() == 2 && repository.snapshot().updatedAt == first.checkedAt, "Failed refresh preserves last source timestamp and scores");
        check(repository.snapshot().stale, "Offline publication exposes delayed state");
        store.hide(first.key());
        check(repository.ordered().size() == 2, "Hiding an island leaves followed scores in the sports widget");
        store.pin(second);
        store.toggle(follow);
        check(repository.ordered().size() == 1 && repository.selected(101).key().equals(second.key()), "Unfollow removes cached matches while preserving an explicit pin");
        store.hide(second.key());
        check(repository.ordered().isEmpty(), "Hidden/unpinned score disappears immediately, before another fetch");
        repository.publish(List.of(first), false, first.checkedAt);
        check(repository.ordered().isEmpty(), "Unfollowed source cannot repopulate stale spotlight data");
        store.toggle(follow);
        repository.publish(List.of(first), false, first.checkedAt);
        Match later = match(Sport.SOCCER, "first"); later.checkedAt = first.checkedAt + 10_000;
        later.home = new Match.Side(first.home.team, "3", "", false);
        repository.publishMatch(later);
        repository.publish(List.of(first), false, first.checkedAt);
        check(repository.ordered().get(0).home.score.equals("3"), "An older feed response cannot overwrite a service score update");
        repository.refreshRequested();
        check(repository.isRefreshRequested(), "Refresh request is immediately visible without changing score freshness");
        repository.publish(List.of(later), false, later.checkedAt);
        check(!repository.isRefreshRequested(), "Completed source publication clears pending refresh label");
        repository.remove(new int[] {101});
        check(context.getSharedPreferences("scoreline_widgets", Context.MODE_PRIVATE).getString("selected:101", "").isEmpty(), "Deleted instance releases its selection");
        check(!store.isPinned(second.key()) && store.isFollowing(follow), "Widget operations preserve follow and pin choices");
    }

    private void remoteViews() {
        Context context = getTargetContext();
        Bitmap review = Bitmap.createBitmap(1200, Sport.values().length * 280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(review);
        canvas.drawColor(Color.rgb(100, 100, 100));
        ComponentName provider = new ComponentName(context, SportsWidgetProvider.class);
        AppWidgetProviderInfo info = null;
        for (AppWidgetProviderInfo candidate : context.getSystemService(AppWidgetManager.class).getInstalledProviders())
            if (candidate.provider.equals(provider)) info = candidate;
        check(info != null && info.updatePeriodMillis == 0, "Native provider is registered without a new polling loop");
        check(info.previewLayout == R.layout.widget_sports_preview, "Launcher picker has a real preview layout");
        for (boolean night : new boolean[] {false, true}) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            Context themed = context.createConfigurationContext(config);
            for (Sport sport : Sport.values()) for (boolean expanded : new boolean[] {false, true}) {
                Match match = match(sport, "render");
                if (sport == Sport.RACING) {
                    match.standings.add(new Match.Standing(1, "Lando Norris", "L. Norris", ""));
                    match.standings.add(new Match.Standing(2, "Max Verstappen", "M. Verstappen", ""));
                    match.standings.add(new Match.Standing(3, "Oscar Piastri", "O. Piastri", ""));
                }
                WidgetSnapshot snapshot = new WidgetSnapshot(List.of(match), match.checkedAt, false);
                RemoteViews views = SportsWidgetProvider.render(themed, snapshot, match, 101, expanded, 2, false);
                View direct = views.apply(themed, new FrameLayout(themed));
                android.widget.ViewFlipper liveDot = direct.findViewById(R.id.widget_pulse);
                check(liveDot == null || !liveDot.isAutoStart(), "Live indicator has no continuous animation loop");
                check(direct.findViewById(R.id.widget_match) != null, "RemoteViews applies actual " + sport + " layout");
                AppWidgetHostView host = new AppWidgetHostView(themed);
                host.setAppWidget(101, info);
                host.updateAppWidget(views);
                int width = Math.round(300 * themed.getResources().getDisplayMetrics().density);
                int height = Math.round((expanded ? 260 : 180) * themed.getResources().getDisplayMetrics().density);
                host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                host.layout(0, 0, width, height);
                check(host.findViewById(R.id.widget_match) != null, "Native host renders " + sport + " without an error view");
                within(host, R.id.widget_phase, R.id.widget_match, "Phase fits compact/expanded host content (" + sport + (expanded ? " large" : " medium") + (night ? " night" : " day") + ")");
                within(host, sport == Sport.RACING ? R.id.widget_race : R.id.widget_sides, R.id.widget_match, "Scoreboard fits host content (" + sport + (expanded ? " large" : " medium") + (night ? " night" : " day") + ")");
                within(host, R.id.widget_context, R.id.widget_match, "Expanded match context fits host content (" + sport + (expanded ? " large" : " medium") + (night ? " night" : " day") + ")");
                TextView updated = host.findViewById(R.id.widget_updated);
                check(updated.getText().toString().startsWith(themed.getString(R.string.widget_updated, "")), "Freshness is explicit on the real remote surface");
                canvas.save();
                canvas.translate((night ? 600 : 0) + (expanded ? 300 : 0), sport.ordinal() * 280);
                canvas.scale(300f / width, 300f / width);
                host.draw(canvas);
                canvas.restore();
                save(host, context, "widget-" + sport.path + (night ? "-night" : "-day") + (expanded ? "-expanded" : "-compact") + ".png");
                if (sport == Sport.CRICKET && expanded) {
                    // Overs sit on their own line under the side's name, so the score digits stay large.
                    TextView overs = host.findViewById(R.id.widget_home_sub);
                    check(overs.getVisibility() == View.VISIBLE && overs.getText().toString().contains("20.0 ov"), "Expanded cricket preserves overs as overs");
                }
                if (sport == Sport.CRICKET || sport == Sport.BASKETBALL) {
                    ImageView crest = host.findViewById(R.id.widget_home_crest);
                    boolean cached = ((App) themed.getApplicationContext()).images().cached(match.home.team.crest(night), 192) != null;
                    check(crest.getVisibility() == (cached ? View.VISIBLE : View.GONE), "Real team crest replaces its monogram when cached");
                }
                if (sport == Sport.RACING) {
                    // The position has its own column beside the driver, like a timing tower.
                    TextView position = host.findViewById(R.id.widget_driver1_pos);
                    TextView driver = host.findViewById(R.id.widget_driver1);
                    check(position.getText().toString().equals("1") && !driver.getText().toString().isEmpty()
                            && host.findViewById(R.id.widget_sides).getVisibility() == View.GONE, "F1 shows driver positions rather than a team score");
                }
            }
            RemoteViews empty = SportsWidgetProvider.render(themed, WidgetSnapshot.EMPTY, null, 101, true, 0, true);
            View view = empty.apply(themed, new FrameLayout(themed));
            check(view.findViewById(R.id.widget_empty).getVisibility() == View.VISIBLE, "Zero follows has an actionable empty state");
            Match overdue = match(Sport.SOCCER, "overdue");
            overdue.state = Match.State.PRE;
            overdue.start = System.currentTimeMillis() - 4 * 3600_000L;
            View saved = SportsWidgetProvider.render(themed, new WidgetSnapshot(List.of(overdue), overdue.checkedAt, false), overdue,
                    101, false, 1, false).apply(themed, new FrameLayout(themed));
            check(((TextView) saved.findViewById(R.id.widget_state)).getText().toString().equals(themed.getString(R.string.widget_overdue)),
                    "An old scheduled source is awaiting an update, not labeled up next");
            if (night && logoFixtures == 4) {
                Match fallback = match(Sport.CRICKET, "dark-fallback");
                Team original = fallback.home.team;
                Team unavailableDark = new Team(original.id, original.name, original.shortName, original.abbr, original.logo,
                        "https://invalid.example.test/dark.png", original.color);
                fallback.home = new Match.Side(unavailableDark, fallback.home.score, fallback.home.overs, false);
                View withCrest = SportsWidgetProvider.render(themed, new WidgetSnapshot(List.of(fallback), fallback.checkedAt, false),
                        fallback, 101, true, 1, false).apply(themed, new FrameLayout(themed));
                check(withCrest.findViewById(R.id.widget_home_crest).getVisibility() == View.VISIBLE,
                        "Dark widgets immediately use a cached light crest when the preferred variant is unavailable");
            }
        }
        Configuration large = new Configuration(context.getResources().getConfiguration());
        large.fontScale = 1.5f;
        Context largeContext = context.createConfigurationContext(large);
        for (Sport sport : Sport.values()) for (boolean expanded : new boolean[] {false, true}) {
            Match match = match(sport, "large-text");
            match.standings.add(new Match.Standing(1, "TEST Leader", "T. Leader", ""));
            match.standings.add(new Match.Standing(2, "TEST Second", "T. Second", ""));
            match.standings.add(new Match.Standing(3, "TEST Third", "T. Third", ""));
            RemoteViews views = SportsWidgetProvider.render(largeContext, new WidgetSnapshot(List.of(match), match.checkedAt, false), match, 101, expanded, 2, false);
            AppWidgetHostView host = new AppWidgetHostView(largeContext);
            host.setAppWidget(101, info);
            host.updateAppWidget(views);
            int width = Math.round(300 * largeContext.getResources().getDisplayMetrics().density);
            int height = Math.round((expanded ? 260 : 180) * largeContext.getResources().getDisplayMetrics().density);
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            host.layout(0, 0, width, height);
            within(host, sport == Sport.RACING ? R.id.widget_race : R.id.widget_sides, R.id.widget_match, "Scoreboard fits with large text (" + sport + (expanded ? " large" : " medium") + ")");
            within(host, R.id.widget_phase, R.id.widget_match, "Phase fits with large text (" + sport + (expanded ? " large" : " medium") + ")");
            within(host, R.id.widget_context, R.id.widget_match, "Context fits with large text (" + sport + (expanded ? " large" : " medium") + ")");
        }
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "widget-review.png"))) {
            review.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException e) {
            throw new AssertionError("Unable to save native host review", e);
        } finally { review.recycle(); }
    }

    private void save(View view, Context context, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException e) { throw new AssertionError("Unable to save " + name, e); }
        finally { bitmap.recycle(); }
    }

    private void within(View root, int childId, int parentId, String message) {
        View child = root.findViewById(childId);
        View parent = root.findViewById(parentId);
        // Some lines exist only at some sizes (the match context is large-only); absent is fine.
        if (child == null || child.getVisibility() != View.VISIBLE) return;
        check(child.getTop() >= 0 && child.getBottom() <= parent.getHeight(), message + ": " + child.getTop() + ".." + child.getBottom() + " in " + parent.getHeight());
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
