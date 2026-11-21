package app.scoreline.live;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.data.Catalog;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.ui.CompetitionActivity;
import app.scoreline.ui.DiscoverActivity;
import app.scoreline.ui.HomeActivity;
import app.scoreline.ui.MatchActivity;
import app.scoreline.ui.OnboardingActivity;
import app.scoreline.ui.SettingsActivity;
import app.scoreline.ui.TeamActivity;

/** Emulator-only screenshot walkthrough with real provider data and restored user choices. */
public final class ScreenReview extends Instrumentation {
    private App app;
    private Activity screen;
    private File output;
    private int captures;
    private boolean narrowOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        narrowOnly = arguments != null && "true".equals(arguments.getString("narrow"));
        start();
    }

    @Override public void onStart() {
        runOnMainSync(() -> app = (App) getTargetContext().getApplicationContext());
        List<Follow> saved = new ArrayList<>(app.store().follows());
        String theme = app.store().theme();
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            if (!android.os.Build.MODEL.contains("sdk") && !android.os.Build.FINGERPRINT.contains("generic"))
                throw new IllegalStateException("Screenshot walkthrough is restricted to the isolated emulator");
            output = new File(app.getExternalFilesDir(null), "review");
            output.mkdirs();
            if (narrowOnly) finalCaptures();
            else {
            app.store().setTheme("system");
            for (Follow follow : new ArrayList<>(app.store().follows())) app.store().toggle(follow);

            open(new Intent(app, OnboardingActivity.class));
            capture("onboarding");
            ActivityMonitor picker = addMonitor(DiscoverActivity.class.getName(), null, false);
            click(R.id.start);
            Activity onboarding = screen;
            screen = waitForMonitorWithTimeout(picker, 4_000);
            removeMonitor(picker);
            if (screen == null) throw new IllegalStateException("Onboarding picker did not open");
            capture("onboarding-pick");
            click(R.id.next);
            screen = onboarding;
            capture("onboarding-permissions");

            open(new Intent(app, HomeActivity.class));
            loaded();
            capture("scores-empty");
            text("Basketball");
            loaded();
            clickDescription("Next day");
            loaded();
            capture("scores-populated");
            text("Following");
            loaded();
            capture("following-empty");
            Follow lakers = null;
            for (Follow follow : Catalog.POPULAR) if (follow.sport == Sport.BASKETBALL && follow.team.id.equals("13")) lakers = follow;
            if (lakers == null) throw new IllegalStateException("Catalog team missing");
            app.store().toggle(lakers);
            loaded();
            capture("following-populated");
            click(R.id.manage);
            capture("following-manage");

            open(new Intent(app, DiscoverActivity.class));
            capture("explore-browse");
            runOnMainSync(() -> ((EditText) screen.findViewById(R.id.query)).setText("Liverpool"));
            SystemClock.sleep(2_000);
            capture("explore-search");

            Catalog.Competition nba = Catalog.competition(Sport.BASKETBALL, "nba");
            open(CompetitionActivity.intent(app, nba));
            loaded();
            clickDescription("Next day");
            loaded();
            capture("competition-fixtures");
            text("Standings");
            loaded();
            capture("competition-standings");
            text("Teams");
            loaded();
            capture("competition-teams");
            open(TeamActivity.intent(app, lakers.sport, lakers.league, lakers.team));
            loaded();
            capture("team");
            List<Match> matches = app.browse().team(lakers.sport, lakers.league, lakers.team, false).value;
            Match chosen = null;
            for (Match match : matches) if (match.state == Match.State.POST) chosen = match;
            if (chosen == null && !matches.isEmpty()) chosen = matches.get(0);
            if (chosen == null) throw new IllegalStateException("No source match available for review");
            open(MatchActivity.intent(app, chosen));
            loaded();
            capture("match");
            open(new Intent(app, SettingsActivity.class));
            capture("settings");

            app.store().setTheme("dark");
            open(new Intent(app, HomeActivity.class));
            loaded();
            text("Basketball");
            loaded();
            clickDescription("Next day");
            loaded();
            capture("scores-dark");
            open(MatchActivity.intent(app, chosen));
            loaded();
            capture("match-dark");
            app.store().setTheme("light");
            shell("settings put system font_scale 1.35");
            shell("wm size 840x1900");
            open(TeamActivity.intent(app, lakers.sport, lakers.league, lakers.team));
            loaded();
            capture("team-narrow-large-font");
            open(MatchActivity.intent(app, chosen));
            loaded();
            capture("match-narrow-large-font");
            }
            result.putString("stream", "\nCaptured " + captures + " screens at " + output + "\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            shell("settings put system font_scale 1.0");
            shell("wm size reset");
            app.store().setTheme(theme);
            for (Follow follow : new ArrayList<>(app.store().follows())) app.store().toggle(follow);
            for (Follow follow : saved) if (!app.store().isFollowing(follow)) app.store().toggle(follow);
            if (screen != null) runOnMainSync(() -> screen.finish());
        }
        finish(code, result);
    }

    private void finalCaptures() {
        Follow lakers = null;
        for (Follow follow : Catalog.POPULAR) if (follow.sport == Sport.BASKETBALL && follow.team.id.equals("13")) lakers = follow;
        if (lakers == null) throw new IllegalStateException("Catalog team missing");
        List<Match> matches = app.browse().team(lakers.sport, lakers.league, lakers.team, false).value;
        Match chosen = null;
        for (Match match : matches) if (match.state == Match.State.POST) chosen = match;
        if (chosen == null) throw new IllegalStateException("Completed source match unavailable");
        app.store().setTheme("light");
        open(MatchActivity.intent(app, chosen));
        loaded();
        try { capture("match"); } catch (Exception e) { throw new IllegalStateException(e); }
        shell("settings put system font_scale 1.35");
        shell("wm size 840x1900");
        open(TeamActivity.intent(app, lakers.sport, lakers.league, lakers.team));
        loaded();
        try { capture("team-narrow-large-font"); } catch (Exception e) { throw new IllegalStateException(e); }
        open(MatchActivity.intent(app, chosen));
        loaded();
        try { capture("match-narrow-large-font"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void open(Intent intent) {
        screen = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }

    private void click(int id) { runOnMainSync(() -> screen.findViewById(id).performClick()); waitForIdleSync(); }

    private View find(View view, String label, boolean description) {
        if (description ? label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                : view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), label, description);
            if (found != null) return found;
        }
        return null;
    }

    private void text(String label) { clickLabel(label, false); }
    private void clickDescription(String label) { clickLabel(label, true); }
    private void clickLabel(String label, boolean description) {
        runOnMainSync(() -> {
            View target = find(screen.getWindow().getDecorView(), label, description);
            if (target == null) throw new IllegalStateException("Missing control: " + label);
            while (!target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
            target.performClick();
        });
        waitForIdleSync();
    }

    private void loaded() {
        long until = SystemClock.uptimeMillis() + 32_000;
        while (SystemClock.uptimeMillis() < until) {
            boolean[] busy = {false};
            runOnMainSync(() -> {
                View progress = screen.findViewById(R.id.hubProgress);
                View refresh = screen.findViewById(R.id.refresh);
                busy[0] = progress != null && progress.getVisibility() == View.VISIBLE || refresh != null && !refresh.isEnabled();
            });
            if (!busy[0]) break;
            SystemClock.sleep(100);
        }
        waitForIdleSync();
        SystemClock.sleep(300);
    }

    private void capture(String name) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(350);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IllegalStateException("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
        captures++;
    }

    private void shell(String command) {
        try { getUiAutomation().executeShellCommand(command).close(); }
        catch (java.io.IOException ignored) { }
        SystemClock.sleep(250);
    }
}
