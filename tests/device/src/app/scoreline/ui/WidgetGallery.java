package app.scoreline.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;
import app.scoreline.widgets.SportsWidgetProvider;
import app.scoreline.widgets.SportsWidgetProvider.Size;
import app.scoreline.widgets.WidgetMoments;
import app.scoreline.widgets.WidgetMoments.Kind;
import app.scoreline.widgets.WidgetSnapshot;

/**
 * Every widget size, day and night, plus each big-moment banner, rendered through a real
 * AppWidgetHostView at a real launcher's measured cell sizes. TEST fixtures only; never published.
 */
public final class WidgetGallery extends Instrumentation {
    private App app;
    private Activity screen;
    private AppWidgetProviderInfo info;
    private int captures;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        runOnMainSync(() -> app = (App) getTargetContext().getApplicationContext());
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            ComponentName name = new ComponentName(app, SportsWidgetProvider.class);
            for (AppWidgetProviderInfo candidate : app.getSystemService(AppWidgetManager.class).getInstalledProviders())
                if (candidate.provider.equals(name)) info = candidate;
            if (info == null) throw new AssertionError("Launcher widget is not registered");
            screen = startActivitySync(new Intent(app, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Match soccer = soccer(Match.State.LIVE), kickoff = soccer(Match.State.PRE), cricket = cricket(), race = race();
            for (Match m : new Match[] {soccer, kickoff, cricket, race}) warm(m);
            SystemClock.sleep(2500);
            for (boolean night : new boolean[] {false, true}) {
                String suffix = night ? "-night" : "-day";
                for (Match m : new Match[] {soccer, kickoff, cricket, race}) {
                    page(night, "TEST " + m.sport.path + " · " + m.state, List.of(
                            new Cell(m, Size.LARGE, null), new Cell(m, Size.MEDIUM, null), new Cell(m, Size.SMALL, null)));
                    save("sizes-" + m.sport.path + "-" + m.state.name().toLowerCase() + suffix);
                }
                page(night, "TEST moments · football and cricket", List.of(
                        new Cell(soccer, Size.MEDIUM, WidgetMoments.sample(soccer, Kind.GOAL, "home", "Saka 67'", 1)),
                        new Cell(cricket, Size.MEDIUM, WidgetMoments.sample(cricket, Kind.WICKET, "home", "", 1)),
                        new Cell(cricket, Size.SMALL, WidgetMoments.sample(cricket, Kind.SIX, "away", "", 6))));
                save("moments-a" + suffix);
                page(night, "TEST moments · start, finish, racing", List.of(
                        new Cell(kickoff, Size.MEDIUM, WidgetMoments.sample(kickoff, Kind.KICKOFF, "", "", 0)),
                        new Cell(soccer, Size.MEDIUM, WidgetMoments.sample(soccer, Kind.FINISH, "home", "", 0)),
                        new Cell(race, Size.SMALL, WidgetMoments.sample(race, Kind.LEADER, "", "L. Norris", 0))));
                save("moments-b" + suffix);
            }
            result.putString("stream", "\nCaptured " + captures + " widget gallery pages.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (screen != null) runOnMainSync(() -> screen.finish());
        }
        finish(code, result);
    }

    private static final class Cell {
        final Match match; final Size size; final WidgetMoments.Moment moment;
        Cell(Match match, Size size, WidgetMoments.Moment moment) { this.match = match; this.size = size; this.moment = moment; }
    }

    private Match base(Sport sport, Match.State state) {
        Match match = new Match();
        match.id = "GALLERY_TEST_" + sport + "_" + state;
        match.sport = sport;
        match.state = state;
        match.start = state == Match.State.PRE ? System.currentTimeMillis() + 5 * 3600_000L : System.currentTimeMillis() - 3600_000L;
        match.checkedAt = System.currentTimeMillis();
        return match;
    }

    private Match soccer(Match.State state) {
        Match match = base(Sport.SOCCER, state);
        match.league = "eng.1"; match.leagueName = "TEST Premier League"; match.venue = "Emirates Stadium";
        List<Team> teams = new ArrayList<>();
        for (Follow f : Catalog.POPULAR) if (f.sport == Sport.SOCCER && f.team != null) teams.add(f.team);
        Team home = teams.get(0), away = teams.get(Math.min(1, teams.size() - 1));
        boolean live = state == Match.State.LIVE;
        match.home = new Match.Side(home, live ? "2" : "", "", false);
        match.away = new Match.Side(away, live ? "1" : "", "", false);
        if (live) { match.period = 2; match.clock = "67'"; match.clockSeconds = 67 * 60; match.summary = "TEST second half"; }
        return match;
    }

    private Match cricket() {
        Match match = base(Sport.CRICKET, Match.State.LIVE);
        Team home = Catalog.CRICKET_NATIONS.get(0), away = Catalog.CRICKET_NATIONS.get(1);
        match.league = "test-series"; match.leagueName = "TEST T20 series";
        match.home = new Match.Side(home, "180/4", "20.0", false); match.away = new Match.Side(away, "167/6", "18.2", false);
        match.period = 2; match.clock = "18.2 ov"; match.oversLimit = 20; match.battingTeamId = away.id;
        match.summary = "TEST need 14 from 10 balls";
        return match;
    }

    private Match race() {
        Match match = base(Sport.RACING, Match.State.LIVE);
        match.league = "f1"; match.leagueName = "TEST Formula 1";
        match.title = "British Grand Prix"; match.session = "Race"; match.period = 34; match.laps = 52; match.clock = "Lap 34/52";
        match.standings.add(new Match.Standing(1, "Lando Norris", "L. Norris", ""));
        match.standings.add(new Match.Standing(2, "Max Verstappen", "M. Verstappen", ""));
        match.standings.add(new Match.Standing(3, "Oscar Piastri", "O. Piastri", ""));
        match.summary = "TEST driver order";
        return match;
    }

    private void warm(Match match) {
        if (match.home == null) return;
        for (Match.Side side : new Match.Side[] {match.home, match.away})
            for (boolean night : new boolean[] {false, true}) app.images().load(side.team.crest(night), 192);
    }

    private void page(boolean night, String title, List<Cell> cells) {
        Configuration configuration = new Configuration(screen.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context themed = new ContextThemeWrapper(screen.createConfigurationContext(configuration), R.style.Theme_Scoreline);
        runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(themed);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            root.setPadding(0, Ui.dp(themed, 40), 0, Ui.dp(themed, 16));
            // A wallpaper-like backdrop so the card edge and radius read as they do on a home screen.
            root.setBackgroundColor(night ? 0xFF2A2230 : 0xFFB9A79A);
            screen.setContentView(root);
            label(root, themed, title, night);
            int id = 300;
            for (Cell cell : cells) {
                int w = cell.size == Size.SMALL ? 160 : 323, h = cell.size == Size.LARGE ? 268 : 178;
                AppWidgetHostView host = new AppWidgetHostView(themed);
                host.setAppWidget(id, info);
                RemoteViews views = SportsWidgetProvider.render(themed,
                        new WidgetSnapshot(List.of(cell.match), cell.match.checkedAt, false), cell.match, id++, cell.size, cell.moment);
                host.updateAppWidget(views);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(themed, w), Ui.dp(themed, h));
                lp.bottomMargin = Ui.dp(themed, 14);
                root.addView(host, lp);
            }
        });
        waitForIdleSync();
        SystemClock.sleep(900);
    }

    private void label(LinearLayout parent, Context context, String value, boolean night) {
        TextView text = new TextView(context);
        text.setTextAppearance(R.style.Text_Label);
        text.setTextColor(night ? 0xFFFFFFFF : 0xFF000000);
        text.setText(value);
        text.setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 8));
        parent.addView(text);
    }

    private void save(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("Screenshot unavailable");
        File directory = new File(app.getExternalFilesDir(null), "widget-gallery");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        image.recycle();
        captures++;
    }
}
