package app.scoreline.live;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Http;
import app.scoreline.data.Espn;
import app.scoreline.data.Feed;
import app.scoreline.data.Store;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;
import app.scoreline.ui.MatchActivity;

/** Deterministic browsing/pin integration checks, with isolated preferences and labeled fixtures. */
public final class ExperienceSimulation extends Instrumentation {
    private int checks;
    private final List<File> fixtures = new ArrayList<>();
    private Activity screen;
    private final String runId = Long.toString(System.currentTimeMillis());

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        runOnMainSync(() -> { });
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            pins();
            coldMatch();
            result.putString("stream", "\nChecks: " + checks + "\nFailures: 0\n");
        } catch (Throwable e) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "\nChecks: " + checks + "\nFailure: " + android.util.Log.getStackTraceString(e));
        } finally {
            if (screen != null) runOnMainSync(() -> screen.finish());
            for (File file : fixtures) file.delete();
            getTargetContext().getSharedPreferences("scoreline-experience-test", Context.MODE_PRIVATE).edit().clear().commit();
        }
        finish(code, result);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private Match fixture() {
        Match match = new Match();
        match.sport = Sport.SOCCER;
        match.league = "simulation.league." + runId;
        match.leagueName = "TEST competition";
        match.id = "EXPERIENCE_TEST_" + runId;
        match.start = System.currentTimeMillis() + 10 * 60_000;
        match.home = new Match.Side(new Team("testhome", "TEST Home", "TEST Home", "TST", "", "", ""), "", "", false);
        match.away = new Match.Side(new Team("testaway", "TEST Away", "TEST Away", "TST", "", "", ""), "", "", false);
        return match;
    }

    private JSONObject event(Match match) throws Exception {
        JSONArray sides = new JSONArray();
        for (Match.Side side : new Match.Side[] {match.home, match.away}) sides.put(new JSONObject()
                .put("homeAway", side == match.home ? "home" : "away").put("team", new JSONObject()
                        .put("id", side.team.id).put("displayName", side.team.name).put("shortDisplayName", side.team.shortName)));
        return new JSONObject().put("id", match.id).put("date", Instant.ofEpochMilli(match.start).toString())
                .put("competitions", new JSONArray().put(new JSONObject().put("competitors", sides)
                        .put("status", new JSONObject().put("type", new JSONObject().put("state", "pre").put("name", "STATUS_SCHEDULED")))));
    }

    private void cache(File directory, String url, JSONObject body) throws Exception {
        directory.mkdirs();
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        File file = new File(directory, name.toString());
        check(!file.exists(), "Fixture must not replace existing provider data");
        Files.write(file.toPath(), body.toString().getBytes(StandardCharsets.UTF_8));
        fixtures.add(file);
    }

    private void pins() throws Exception {
        Context isolated = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("scoreline-experience-test", mode);
            }
        };
        isolated.getSharedPreferences("scoreline", Context.MODE_PRIVATE).edit().clear().commit();
        Store store = new Store(isolated);
        Match match = fixture();
        Follow follow = Follow.team(match.sport, match.league, match.home.team);
        store.toggle(follow);
        store.pin(match);
        Store restored = new Store(isolated);
        check(restored.isFollowing(follow), "Following choice survives new Store instance");
        check(restored.pinnedMatches().size() == 1, "Pin descriptor survives new Store instance");
        check(restored.pinnedMatches().get(0).start == match.start, "Pin retains the independent date descriptor");
        restored.toggle(follow);
        check(restored.follows().isEmpty() && restored.hasPins(), "Unfollowing retains an explicit browse pin");
        File directory = new File(getTargetContext().getCacheDir(), "experience-test-http");
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + match.league + "/scoreboard?dates=" + Espn.espnDay(match.start);
        cache(directory, url, new JSONObject().put("events", new JSONArray().put(event(match))));
        Feed feed = new Feed(new Espn(new Http(directory)), restored);
        Feed.Snapshot snapshot = feed.load(false);
        check(snapshot.find(match.key()) != null, "A new feed independently resolves a pin with zero follows");
        check(Tracker.wanted(snapshot, restored, System.currentTimeMillis()).size() == 1, "Browse-only pin reaches tracking selection");
        Match second = fixture();
        second.id = "SECOND_TEST";
        feed.includePinned(second);
        check(feed.last().find(match.key()) != null, "Publishing another pin preserves existing content");
        restored.hide(match.key());
        check(!restored.hasPins() && restored.pinnedMatches().isEmpty(), "Hide removes persistent pin and descriptor");
        check(Tracker.wanted(snapshot, restored, System.currentTimeMillis()).isEmpty(), "Hidden browse-only match stops tracking");
    }

    private void coldMatch() throws Exception {
        App app = (App) getTargetContext().getApplicationContext();
        Match match = fixture();
        check(app.browse().find(match.key()) == null && app.feed().last().find(match.key()) == null,
                "Cold match is outside both in-memory feeds");
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + match.league + "/summary?event=" + match.id;
        cache(new File(getTargetContext().getCacheDir(), "http"), url, new JSONObject().put("header", event(match)));
        screen = startActivitySync(MatchActivity.intent(getTargetContext(), match.key()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < until && app.browse().find(match.key()) == null) Thread.sleep(50);
        waitForIdleSync();
        check(app.browse().find(match.key()) != null, "Legacy key-only intent resolves independently from cached summary");
        runOnMainSync(() -> check(((TextView) screen.findViewById(R.id.homeName)).getText().toString().contains("TEST Home"),
                "Independent match renders its participants"));
        MatchRef restored = MatchRef.decode(MatchRef.from(app.browse().find(match.key())).encode());
        check(restored != null && restored.key().equals(match.key()), "Recreation descriptor preserves match identity");
    }
}
