package app.scoreline.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

import app.scoreline.R;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.SportsEventTracker.Kind;
import app.scoreline.model.Team;

/** Native event-view tests, including real Animator state and platform reduced-motion setting. */
public final class SpotlightSimulation extends Instrumentation {
    private Activity screen;
    private SportsSpotlight card;
    private LinearLayout parent;
    private int checks;
    private float savedScale;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        runOnMainSync(() -> { });
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        savedScale = Settings.Global.getFloat(getTargetContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        try {
            screen = startActivitySync(new Intent(getTargetContext(), SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                parent = new LinearLayout(screen);
                parent.setOrientation(LinearLayout.VERTICAL);
                parent.setGravity(android.view.Gravity.CENTER_VERTICAL);
                parent.setPadding(0, 64, 0, 64);
                parent.setBackgroundColor(screen.getColor(R.color.bg));
                TextView label = new TextView(screen);
                label.setText("TEST sports event spotlight");
                label.setTextAppearance(R.style.Text_Title);
                label.setPadding(48, 24, 48, 24);
                parent.addView(label);
                screen.setContentView(parent);
            });
            scale(1f);
            for (Sport sport : Sport.values()) sport(sport);
            lifecycle();
            reducedMotion();
            result.putString("stream", "\nChecks: " + checks + "\nFailures: 0\nAll seven animated sport views, lifecycle and system motion-off checks.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "\nChecks: " + checks + "\nFailure: " + android.util.Log.getStackTraceString(error));
        } finally {
            scale(savedScale);
            if (screen != null) runOnMainSync(() -> screen.finish());
        }
        finish(code, result);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private void newCard() {
        runOnMainSync(() -> {
            if (card != null) parent.removeView(card);
            card = new SportsSpotlight(screen);
            parent.addView(card);
            card.setActive(true);
        });
        waitForIdleSync();
    }

    private void bind(Match match, long stamp, boolean stale) {
        runOnMainSync(() -> card.bind(match, stamp, stale, () -> { }));
        waitForIdleSync();
    }

    private void sport(Sport sport) throws Exception {
        newCard();
        long stamp = System.currentTimeMillis();
        Match before = fixture(sport, false);
        bind(before, stamp, false);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE && !card.isAnimatingEvent(), sport + " initial source has no celebration"));
        Match after = fixture(sport, true);
        // No waitForIdleSync here: a running animation keeps the main thread busy until it ends,
        // so waiting for idle would only ever see the finished state. Poll for it starting instead.
        runOnMainSync(() -> card.bind(after, stamp + 1000, false, () -> { }));
        boolean[] started = {false};
        for (int i = 0; i < 40 && !started[0]; i++) {
            runOnMainSync(() -> started[0] = card.isAnimatingEvent());
            if (!started[0]) SystemClock.sleep(15);
        }
        runOnMainSync(() -> {
            Kind expected = sport == Sport.SOCCER ? Kind.GOAL : sport == Sport.CRICKET ? Kind.WICKET : sport == Sport.RACING ? Kind.LAP : Kind.SCORE;
            check(card.lastEvent() == expected, sport + " genuine transition kind");
            check(card.isAnimatingEvent(), sport + " starts a native event animator");
            check(card.findViewById(R.id.spotlightEventArt).getVisibility() == View.VISIBLE, sport + " event illustration visible");
        });
        capture(sport.name().toLowerCase(java.util.Locale.ROOT));
        bind(after, stamp + 1000, false);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE, sport + " rebind does not create a second event"));
        SystemClock.sleep(1100);
        runOnMainSync(() -> check(!card.isAnimatingEvent(), sport + " animation ends without a loop"));
        if (sport == Sport.CRICKET) runOnMainSync(() -> {
            check(((TextView) card.findViewById(R.id.spotlightHomeScore)).getText().toString().equals("184/3"), "Cricket home innings preserved");
            check(((TextView) card.findViewById(R.id.spotlightAwayScore)).getText().toString().equals("154/5"), "Cricket away innings preserved");
        });
        if (sport == Sport.RACING) runOnMainSync(() -> {
            check(card.findViewById(R.id.spotlightBoard).getVisibility() == View.GONE, "F1 does not invent head-to-head sides");
            check(((TextView) card.findViewById(R.id.spotlightRaceOrder)).getText().toString().contains("TEST Driver"), "F1 driver source displayed");
        });
    }

    private void lifecycle() {
        newCard();
        long stamp = System.currentTimeMillis();
        bind(fixture(Sport.BASKETBALL, false), stamp, false);
        bind(fixture(Sport.BASKETBALL, true), stamp + 1000, false);
        SystemClock.sleep(100);
        runOnMainSync(() -> { card.setActive(false); check(!card.isAnimatingEvent(), "Pause cancels native motion"); card.setActive(true); });
        bind(fixture(Sport.BASKETBALL, false), stamp + 2000, false);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE && !card.isAnimatingEvent(), "Resume consumes a quiet baseline"));
        bind(fixture(Sport.BASKETBALL, true), stamp + 3000, true);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE && !card.isAnimatingEvent(), "Stale source never starts motion"));
        newCard();
        bind(fixture(Sport.HOCKEY, false), stamp, false);
        bind(fixture(Sport.HOCKEY, true), stamp + 1000, false);
        SystemClock.sleep(100);
        runOnMainSync(() -> { parent.removeView(card); check(!card.isAnimatingEvent(), "Detach cancels active event animator"); });
    }

    private void reducedMotion() {
        scale(0f);
        check(!ValueAnimator.areAnimatorsEnabled(), "System animator-off preference reached this process");
        newCard();
        long stamp = System.currentTimeMillis();
        bind(fixture(Sport.BASKETBALL, false), stamp, false);
        bind(fixture(Sport.BASKETBALL, true), stamp + 1000, false);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE && !card.isAnimatingEvent(), "Motion-off retains score information without animation"));
        scale(1f);
        bind(fixture(Sport.BASKETBALL, true), stamp + 2000, false);
        runOnMainSync(() -> check(card.lastEvent() == Kind.NONE, "Re-enabling animations does not replay an old score"));
    }

    private void scale(float value) {
        getUiAutomation().adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try { Settings.Global.putFloat(getTargetContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, value); }
        finally { getUiAutomation().dropShellPermissionIdentity(); }
        long until = SystemClock.uptimeMillis() + 2500;
        while (SystemClock.uptimeMillis() < until && ValueAnimator.areAnimatorsEnabled() != (value > 0)) SystemClock.sleep(50);
    }

    private Match fixture(Sport sport, boolean changed) {
        Match match = new Match();
        match.sport = sport;
        match.id = "SPOTLIGHT_TEST_" + sport;
        match.league = "test";
        match.leagueName = "TEST " + sport;
        match.state = Match.State.LIVE;
        match.start = System.currentTimeMillis() - 60 * 60_000;
        match.period = 2;
        match.clock = "67'";
        String home = sport == Sport.CRICKET ? "184/3" : sport == Sport.BASKETBALL ? (changed ? "114" : "112")
                : sport == Sport.FOOTBALL ? (changed ? "17" : "14") : changed ? "2" : "1";
        String away = sport == Sport.CRICKET ? (changed ? "154/5" : "150/4") : sport == Sport.BASKETBALL ? "108" : "1";
        match.home = new Match.Side(new Team("home", "TEST Home", "TEST Home", "HOM", "", "", "2455D6"), home, "20", false);
        match.away = new Match.Side(new Team("away", "TEST Away", "TEST Away", "AWY", "", "", "E23B4A"), away, changed ? "16.3" : "16.2", false);
        if (sport == Sport.SOCCER && changed) match.moments.add(new Match.Moment(Match.Moment.Kind.GOAL, 67, "67'", "TEST scorer", true));
        if (sport == Sport.CRICKET) { match.battingTeamId = "away"; match.oversLimit = 20; match.clock = "16.3 ov"; }
        if (sport == Sport.BASKETBALL || sport == Sport.FOOTBALL) { match.period = 4; match.clock = "Q4 2:30"; }
        if (sport == Sport.HOCKEY) { match.period = 3; match.clock = "P3 2:30"; }
        if (sport == Sport.BASEBALL) { match.period = 7; match.clock = "Top 7"; }
        if (sport == Sport.RACING) {
            match.home = null; match.away = null; match.title = "TEST Grand Prix"; match.session = "Race";
            match.period = changed ? 35 : 34; match.laps = 56; match.clock = "Lap " + match.period + "/56";
            match.standings.add(new Match.Standing(1, "TEST Driver One", "TEST Driver", ""));
        }
        return match;
    }

    private void capture(String name) throws Exception {
        File directory = new File(getTargetContext().getExternalFilesDir(null), "spotlight-tests");
        directory.mkdirs();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
