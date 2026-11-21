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
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
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
import app.scoreline.widgets.WidgetSnapshot;

/** Branded, clearly TEST-labeled visual fixtures; never published to production widget state. */
public final class WidgetVisualReview extends Instrumentation {
    private App app;
    private Activity screen;
    private SportsSpotlight card;
    private int captures;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        runOnMainSync(() -> app = (App) getTargetContext().getApplicationContext());
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            screen = startActivitySync(new Intent(app, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (Sport sport : new Sport[] {Sport.BASKETBALL, Sport.CRICKET, Sport.RACING}) {
                Match match = fixture(sport);
                for (boolean night : new boolean[] {false, true}) {
                    warm(match, night, 48);
                    page(match, night, false);
                    save("pair-" + sport.path + (night ? "-night" : "-day"));
                }
            }
            Match cricket = fixture(Sport.CRICKET);
            warm(cricket, false, 40);
            page(cricket, false, true);
            save("pair-cricket-narrow-large-font");
            result.putString("stream", "\nCaptured " + captures + " real-crest paired widget reviews.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (screen != null) runOnMainSync(() -> screen.finish());
        }
        finish(code, result);
    }

    private Match fixture(Sport sport) {
        Match match = new Match();
        match.id = "VISUAL_TEST_" + sport;
        match.sport = sport;
        match.league = sport == Sport.BASKETBALL ? "nba" : sport == Sport.RACING ? "f1" : "test-series";
        match.leagueName = sport == Sport.BASKETBALL ? "TEST NBA" : sport == Sport.CRICKET ? "TEST T20 series" : "TEST Formula 1";
        match.state = Match.State.LIVE;
        match.start = System.currentTimeMillis() - 60 * 60_000;
        match.checkedAt = System.currentTimeMillis();
        if (sport == Sport.RACING) {
            match.title = "British Grand Prix"; match.session = "Race"; match.period = 34; match.laps = 52; match.clock = "Lap 34/52";
            match.standings.add(new Match.Standing(1, "Lando Norris", "L. Norris", ""));
            match.standings.add(new Match.Standing(2, "Max Verstappen", "M. Verstappen", ""));
            match.standings.add(new Match.Standing(3, "Oscar Piastri", "O. Piastri", ""));
            match.summary = "TEST driver order";
            return match;
        }
        Team home = Catalog.CRICKET_NATIONS.get(0), away = Catalog.CRICKET_NATIONS.get(1);
        if (sport == Sport.BASKETBALL) {
            for (Follow follow : Catalog.POPULAR) {
                if (follow.sport == Sport.BASKETBALL && follow.team.id.equals("13")) home = follow.team;
                if (follow.sport == Sport.BASKETBALL && follow.team.id.equals("2")) away = follow.team;
            }
            match.home = new Match.Side(home, "108", "", false); match.away = new Match.Side(away, "104", "", false);
            match.period = 4; match.clockSeconds = 151; match.clock = "Q4 2:31"; match.summary = "TEST fourth quarter";
        } else {
            match.home = new Match.Side(home, "180/4", "20.0", false); match.away = new Match.Side(away, "167/6", "18.2", false);
            match.period = 2; match.clock = "18.2 ov"; match.oversLimit = 20; match.battingTeamId = away.id;
            match.summary = "TEST Australia need 14 from 10 balls";
        }
        return match;
    }

    private void warm(Match match, boolean night, int size) {
        if (match.home == null) return;
        // These are published identity assets, loaded off the UI thread through the existing cache.
        int pixels = Math.min(256, Ui.dp(screen, size) * 2);
        for (Match.Side side : new Match.Side[] {match.home, match.away}) {
            app.images().load(side.team.crest(night), pixels);
            app.images().load(side.team.logo, pixels);
            app.images().load(side.team.crest(night), 96);
            app.images().load(side.team.logo, 96);
            app.images().load(side.team.crest(night), 128);
        }
    }

    private void page(Match match, boolean night, boolean narrow) {
        Configuration configuration = new Configuration(screen.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        configuration.fontScale = narrow ? 1.35f : 1f;
        configuration.screenWidthDp = narrow ? 320 : 411;
        Context themed = new ContextThemeWrapper(screen.createConfigurationContext(configuration), R.style.Theme_Scoreline);
        runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(themed);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            root.setPadding(0, Ui.dp(themed, 56), 0, Ui.dp(themed, 24));
            root.setBackgroundColor(themed.getColor(R.color.bg));
            screen.setContentView(root);
            int width = narrow ? 320 : 411;
            label(root, themed, "TEST scenario · in-app spotlight");
            card = new SportsSpotlight(themed);
            root.addView(card, new LinearLayout.LayoutParams(Ui.dp(themed, width), LinearLayout.LayoutParams.WRAP_CONTENT));
            card.setActive(true);
            card.bind(match, match.checkedAt, false, () -> { });
            label(root, themed, "Same scenario · Android AppWidgetHost");
            AppWidgetProviderInfo info = null;
            ComponentName name = new ComponentName(app, SportsWidgetProvider.class);
            for (AppWidgetProviderInfo candidate : app.getSystemService(AppWidgetManager.class).getInstalledProviders())
                if (candidate.provider.equals(name)) info = candidate;
            if (info == null) throw new AssertionError("Launcher widget is not registered");
            AppWidgetHostView host = new AppWidgetHostView(themed);
            host.setAppWidget(101, info);
            host.updateAppWidget(SportsWidgetProvider.render(themed, new WidgetSnapshot(List.of(match), match.checkedAt, false), match, 101, true, 1, false));
            root.addView(host, new LinearLayout.LayoutParams(Ui.dp(themed, narrow ? 320 : 380), Ui.dp(themed, 260)));
        });
        waitForIdleSync();
        SystemClock.sleep(550);
    }

    private void label(LinearLayout parent, Context context, String value) {
        TextView text = new TextView(context);
        text.setTextAppearance(R.style.Text_Label);
        text.setText(value);
        text.setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 8));
        parent.addView(text);
    }

    private void save(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("Screenshot unavailable");
        File directory = new File(app.getExternalFilesDir(null), "widget-pairs");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        image.recycle();
        captures++;
    }
}
