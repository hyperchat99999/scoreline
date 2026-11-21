package app.scoreline.live;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;
import app.scoreline.ui.MatchActivity;

/** All-sport match-screen fixtures. No follows, settings or real provider cache entries are changed. */
public final class MatchDetailSimulation extends Instrumentation {
    private App app;
    private Activity screen;
    private int checks;
    private final List<File> files = new ArrayList<>();
    private final String runId = Long.toString(System.currentTimeMillis());

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        runOnMainSync(() -> app = (App) getTargetContext().getApplicationContext());
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            for (Sport sport : Sport.values()) {
                Match match = fixture(sport, false);
                if (sport != Sport.RACING) seed(match, true);
                open(match);
                runOnMainSync(() -> { verify(match, false); visibleText(screen.findViewById(R.id.content)); });
                capture(sport);
                if (sport != Sport.RACING) {
                    Match empty = fixture(sport, true);
                    seed(empty, false);
                    open(empty);
                    runOnMainSync(() -> verify(empty, true));
                }
            }
            seasonSoFar();
            result.putString("stream", "\nChecks: " + checks + "\nFailures: 0\nAll seven match screens; six head-to-head missing-stat variants; pre-match season totals.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "\nChecks: " + checks + "\nFailure: " + android.util.Log.getStackTraceString(error));
        } finally {
            if (screen != null) runOnMainSync(() -> screen.finish());
            for (File file : files) file.delete();
        }
        finish(code, result);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private Match fixture(Sport sport, boolean empty) {
        Match match = new Match();
        match.sport = sport;
        match.id = "DETAIL_TEST_" + runId + "_" + sport.name() + (empty ? "_EMPTY" : "");
        match.leagueName = "TEST " + sport.name();
        match.league = sport == Sport.BASKETBALL ? "nba" : sport == Sport.FOOTBALL ? "nfl"
                : sport == Sport.HOCKEY ? "nhl" : sport == Sport.BASEBALL ? "mlb"
                : sport == Sport.RACING ? "f1" : sport == Sport.CRICKET ? "test-series" : "eng.1";
        match.start = System.currentTimeMillis() - 60 * 60_000;
        match.checkedAt = System.currentTimeMillis();
        match.state = Match.State.LIVE;
        match.period = 2;
        match.clock = "67'";
        String home = sport == Sport.CRICKET ? "184/3" : sport == Sport.BASKETBALL ? "112" : "3";
        String away = sport == Sport.CRICKET ? "150/4" : sport == Sport.BASKETBALL ? "108" : "1";
        match.home = new Match.Side(new Team("testhome", "TEST Home", "TEST Home", "HOM", "", "", "2464BC"), home, sport == Sport.CRICKET ? "20" : "", false);
        match.away = new Match.Side(new Team("testaway", "TEST Away", "TEST Away", "AWY", "", "", "E23B4A"), away, sport == Sport.CRICKET ? "16.2" : "", false);
        if (sport == Sport.CRICKET) {
            match.oversLimit = 20;
            match.battingTeamId = "testaway";
            match.clock = "16.2 ov";
        } else if (sport == Sport.BASKETBALL || sport == Sport.FOOTBALL) {
            match.period = 4;
            match.clock = "Q4 2:30";
            match.clockSeconds = 150;
        } else if (sport == Sport.HOCKEY) {
            match.period = 3;
            match.clock = "P3 2:30";
            match.clockSeconds = 150;
        } else if (sport == Sport.BASEBALL) {
            match.period = 7;
            match.clock = "Top 7";
        } else if (sport == Sport.RACING) {
            match.home = null;
            match.away = null;
            match.title = "TEST Grand Prix";
            match.session = "Race";
            match.period = 34;
            match.laps = 56;
            match.clock = "Lap 34/56";
            match.standings.add(new Match.Standing(1, "TEST Driver One", "T. One", ""));
            match.standings.add(new Match.Standing(2, "TEST Driver Two", "T. Two", ""));
        }
        return match;
    }

    /**
     * Before kick-off ESPN's boxscore holds each side's season totals. The screen must call them that,
     * read a negative goal difference as negative, and count fewer goals against as the better side.
     */
    private void seasonSoFar() throws Exception {
        for (Sport sport : new Sport[] {Sport.SOCCER, Sport.BASKETBALL, Sport.FOOTBALL, Sport.HOCKEY, Sport.BASEBALL}) {
            Match match = fixture(sport, false);
            match.id = match.id + "_PRE";
            match.state = Match.State.PRE;
            match.start = System.currentTimeMillis() + 5 * 3600_000L;
            match.clock = "";
            match.period = 0;
            match.home = new Match.Side(match.home.team, "", "", false);
            match.away = new Match.Side(match.away.team, "", "", false);
            String[][] season = sport == Sport.SOCCER
                    ? new String[][] {{"goalDifference", "Goal Difference", "8", "-2"}, {"goalsConceded", "Goals Against", "13", "12"}}
                    : sport == Sport.BASKETBALL
                    ? new String[][] {{"streak", "Streak", "W1", "L3"}, {"avgPointsAgainst", "Points Allowed", "113.2", "109.4"}}
                    : sport == Sport.FOOTBALL
                    ? new String[][] {{"totalPointsPerGame", "Points Per Game", "27.8", "23.4"}, {"yardsPerGameAllowed", "Yards Allowed", "377.3", "347.3"}}
                    : sport == Sport.HOCKEY
                    ? new String[][] {{"avgGoals", "Goals Per Game", "3.86", "2.32"}, {"avgGoalsAgainst", "Goals Against", "3.23", "2.67"}}
                    : new String[][] {{"batting.avg", "Batting Average", ".300", ".250"}, {"pitching.ERA", "ERA", "4.20", "3.10"}};
            JSONArray sides = new JSONArray();
            for (int side = 0; side < 2; side++) {
                JSONArray stats = new JSONArray();
                for (String[] stat : season) stats.put(new JSONObject().put("name", stat[0]).put("label", stat[1]).put("displayValue", stat[2 + side]));
                sides.put(new JSONObject().put("team", new JSONObject().put("id", side == 0 ? "testhome" : "testaway")).put("statistics", stats));
            }
            write(match, new JSONObject().put("boxscore", new JSONObject().put("teams", sides)));
            open(match);
            runOnMainSync(() -> {
                LinearLayout details = screen.findViewById(R.id.details);
                visibleText(details);
                check(contains(details, app.getString(R.string.season_stats)) && !contains(details, app.getString(R.string.match_stats)),
                        "Pre-match provider numbers are titled as the season so far");
                LinearLayout card = (LinearLayout) details.getChildAt(1);
                LinearLayout difference = (LinearLayout) ((LinearLayout) card.getChildAt(1)).getChildAt(1);

                check(((TextView) difference.getChildAt(1)).getText().toString().equals(season[0][3]), sport + " source stat text is preserved");
                check(((TextView) difference.getChildAt(0)).getTypeface().isBold() && !((TextView) difference.getChildAt(1)).getTypeface().isBold(),
                        sport + " higher comparable value is emphasized");
                if (sport == Sport.SOCCER || sport == Sport.BASKETBALL) {
                    check(((LinearLayout) card.getChildAt(1)).getChildAt(2) instanceof TextView,
                            sport + " signed comparison has a directional label, no proportional bar");
                } else {
                    LinearLayout differenceBar = (LinearLayout) ((LinearLayout) card.getChildAt(1)).getChildAt(2);
                    check(((LinearLayout.LayoutParams) differenceBar.getChildAt(0).getLayoutParams()).weight
                            > ((LinearLayout.LayoutParams) differenceBar.getChildAt(1).getLayoutParams()).weight,
                            sport + " bar describes the actual larger value");
                }
                LinearLayout against = (LinearLayout) ((LinearLayout) card.getChildAt(2)).getChildAt(1);
                check(!((TextView) against.getChildAt(0)).getTypeface().isBold() && ((TextView) against.getChildAt(1)).getTypeface().isBold(),
                        sport + " smaller conceded value is emphasized");
                LinearLayout againstBar = (LinearLayout) ((LinearLayout) card.getChildAt(2)).getChildAt(2);
                check(((LinearLayout.LayoutParams) againstBar.getChildAt(0).getLayoutParams()).weight
                        > ((LinearLayout.LayoutParams) againstBar.getChildAt(1).getLayoutParams()).weight,
                        sport + " conceded bar shows amounts while bold marks the smaller better value");
            });
        }
    }

    private void write(Match match, JSONObject body) throws Exception {
        String url = "https://site.api.espn.com/apis/site/v2/sports/" + match.sport.path + "/" + match.league + "/summary?event=" + match.id;
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        File file = new File(new File(app.getCacheDir(), "http"), name.toString());
        check(!file.exists(), "Fixture must not replace real provider cache");
        Files.write(file.toPath(), body.toString().getBytes(StandardCharsets.UTF_8));
        files.add(file);
    }

    private void seed(Match match, boolean statistics) throws Exception {
        String base = match.sport == Sport.CRICKET ? "https://site.web.api.espn.com/apis/site/v2/sports/cricket/"
                : "https://site.api.espn.com/apis/site/v2/sports/" + match.sport.path + "/";
        String url = base + match.league + "/summary?event=" + match.id;
        JSONArray sides = new JSONArray();
        // Provider ordering is deliberately away-first, with an unrelated team in between.
        if (statistics) for (String id : new String[] {"testaway", "unrelated", "testhome"}) {
            sides.put(new JSONObject().put("team", new JSONObject().put("id", id))
                    .put("statistics", new JSONArray().put(new JSONObject().put("name", "test_stat")
                            .put("label", "TEST statistic").put("displayValue", id.equals("testhome") ? "7" : id.equals("testaway") ? "11" : "99"))));
        }
        JSONObject body = new JSONObject().put("boxscore", new JSONObject().put("teams", sides));
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        File file = new File(new File(app.getCacheDir(), "http"), name.toString());
        check(!file.exists(), "Fixture must not replace real provider cache");
        Files.write(file.toPath(), body.toString().getBytes(StandardCharsets.UTF_8));
        files.add(file);
    }

    private void open(Match match) {
        if (screen != null) runOnMainSync(() -> screen.finish());
        app.browse().remember(match);
        screen = startActivitySync(MatchActivity.intent(app, match).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < until) {
            boolean[] loaded = {false};
            runOnMainSync(() -> {
                LinearLayout details = screen.findViewById(R.id.details);
                loaded[0] = details.getVisibility() == View.VISIBLE && details.getChildCount() >= 2;
                if (!loaded[0] && (match.sport == Sport.RACING || match.sport == Sport.CRICKET)) {
                    try {
                        java.lang.reflect.Field field = MatchActivity.class.getDeclaredField("detailUpdated");
                        field.setAccessible(true);
                        loaded[0] = field.getLong(screen) > 0;
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
            });
            if (loaded[0]) return;
            SystemClock.sleep(25);
        }
        throw new AssertionError("Match details did not complete: " + match.sport);
    }

    private void verify(Match match, boolean empty) {
        LinearLayout details = screen.findViewById(R.id.details);
        if (match.sport == Sport.RACING) {
            check(screen.findViewById(R.id.board).getVisibility() == View.GONE, "F1 has no head-to-head board");
            check(screen.findViewById(R.id.raceTitle).getVisibility() == View.VISIBLE, "F1 session remains useful");
            LinearLayout order = screen.findViewById(R.id.moments);
            check(order.getChildCount() == 2, "F1 publishes both fixture drivers");
            check(((TextView) order.getChildAt(0).findViewById(R.id.name)).getText().toString().equals("TEST Driver One"), "F1 leader remains first");
            check(((TextView) order.getChildAt(1).findViewById(R.id.name)).getText().toString().equals("TEST Driver Two"), "F1 second driver remains second");
            check(details.getVisibility() == View.GONE, "F1 omits an irrelevant empty statistics section");
            return;
        }
        check(screen.findViewById(R.id.board).getVisibility() == View.VISIBLE, match.sport + " scoreboard visible");
        check(((TextView) screen.findViewById(R.id.homeName)).getText().toString().contains("TEST Home"), match.sport + " home identity correct");
        check(((TextView) screen.findViewById(R.id.awayName)).getText().toString().contains("TEST Away"), match.sport + " away identity correct");
        if (match.sport != Sport.CRICKET) {
            TextView score = screen.findViewById(R.id.score);
            check(score.getMaxLines() == 1 && score.getLineCount() == 1, match.sport + " central score stays on one line");
            check(score.getText().toString().equals(match.home.score + " – " + match.away.score), match.sport + " central score preserves both source values");
        }
        if (match.sport == Sport.CRICKET) {
            check(((TextView) screen.findViewById(R.id.homeInnings)).getText().toString().equals("184/3"), "Cricket home innings score stays with home");
            check(((TextView) screen.findViewById(R.id.awayInnings)).getText().toString().equals("150/4"), "Cricket away innings score stays with away");
            check(screen.findViewById(R.id.score).getVisibility() == View.GONE, "Cricket does not duplicate scores in a central board");
        }
        if (empty) {
            check(match.sport == Sport.CRICKET ? details.getVisibility() == View.GONE
                    : contains(details, app.getString(R.string.match_stats_empty)), match.sport + " missing statistics has an appropriate empty state");
            check(!contains(details, "99"), "Missing statistics never become fabricated values");
            return;
        }
        // Title, then one card: a header of crests and names, then a row per statistic (values over a split bar).
        LinearLayout card = (LinearLayout) details.getChildAt(1);
        LinearLayout header = (LinearLayout) card.getChildAt(0);
        LinearLayout row = (LinearLayout) card.getChildAt(1);
        LinearLayout values = (LinearLayout) row.getChildAt(1);
        LinearLayout bar = (LinearLayout) row.getChildAt(2);
        String home = match.home.team.abbr.isEmpty() ? match.home.team.shortName : match.home.team.abbr;
        String away = match.away.team.abbr.isEmpty() ? match.away.team.shortName : match.away.team.abbr;
        check(((TextView) header.getChildAt(1)).getText().toString().equals(home), match.sport + " home statistics header is left");
        check(((TextView) header.getChildAt(2)).getText().toString().equals(away), match.sport + " away statistics header is right");
        check(((TextView) values.getChildAt(0)).getText().toString().equals("7"), match.sport + " provider home stat matches home identity");
        check(((TextView) values.getChildAt(1)).getText().toString().equals("11"), match.sport + " provider away stat matches away identity");
        check(values.getChildAt(0).getLeft() == 0 && values.getChildAt(1).getRight() == values.getWidth(),
                match.sport + " home value sits at the left edge, away at the right");
        for (int i = 0; i < 2; i++) {
            android.graphics.drawable.GradientDrawable fill = (android.graphics.drawable.GradientDrawable) bar.getChildAt(i).getBackground();
            double foreground = android.graphics.Color.luminance(fill.getColor().getDefaultColor());
            double background = android.graphics.Color.luminance(screen.getColor(R.color.surface));
            check((Math.max(foreground, background) + .05) / (Math.min(foreground, background) + .05) >= 3,
                    match.sport + " chart fill contrasts with the actual card");
        }
        float homeShare = ((LinearLayout.LayoutParams) bar.getChildAt(0).getLayoutParams()).weight;
        float awayShare = ((LinearLayout.LayoutParams) bar.getChildAt(1).getLayoutParams()).weight;
        check(Math.abs(homeShare - 7f / 18f) < .01f && Math.abs(awayShare - 11f / 18f) < .01f, match.sport + " split bar is in proportion 7 to 11");
    }

    /** Inspect laid-out glyphs, not only the backing string that previously hid cricket clipping. */
    private void visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (layout != null && text.length() > 0) {
                check(layout.getLineEnd(layout.getLineCount() - 1) == text.length(), "All visible text is laid out: " + text.getText());
                check(layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom(),
                        "Text is not vertically clipped: " + text.getText());
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) visibleText(((ViewGroup) view).getChildAt(i));
    }

    private boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }

    private void capture(Sport sport) throws Exception {
        File directory = new File(app.getExternalFilesDir(null), "detail-tests");
        directory.mkdirs();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(directory, sport.name().toLowerCase(java.util.Locale.ROOT) + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
