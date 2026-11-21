package app.scoreline.widgets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.scoreline.core.Json;
import app.scoreline.model.Match;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Team;

/** Versioned display data only. No network, bitmaps, fabricated scores or credentials. */
public final class WidgetSnapshotCodec {
    private static final int VERSION = 1;
    private WidgetSnapshotCodec() { }

    public static String encode(WidgetSnapshot snapshot) {
        try {
            JSONArray matches = new JSONArray();
            for (Match match : snapshot.matches) {
                if (matches.length() == WidgetSelection.MAX_MATCHES) break;
                if (MatchRef.fromKey(match.key()) != null) matches.put(write(match));
            }
            return new JSONObject().put("version", VERSION).put("updatedAt", snapshot.updatedAt)
                    .put("stale", snapshot.stale).put("matches", matches).toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public static WidgetSnapshot decode(String encoded) {
        if (encoded == null || encoded.length() > 256_000) return WidgetSnapshot.EMPTY;
        try {
            JSONObject root = new JSONObject(encoded);
            if (root.optInt("version") != VERSION) return WidgetSnapshot.EMPTY;
            List<Match> matches = new ArrayList<>();
            JSONArray array = root.optJSONArray("matches");
            if (array != null) for (int i = 0; i < Math.min(array.length(), WidgetSelection.MAX_MATCHES); i++) {
                Match match = read(array.optJSONObject(i));
                if (match != null) matches.add(match);
            }
            return new WidgetSnapshot(matches, root.optLong("updatedAt"), root.optBoolean("stale", true));
        } catch (JSONException | IllegalArgumentException e) {
            return WidgetSnapshot.EMPTY;
        }
    }

    private static JSONObject write(Match m) throws JSONException {
        JSONObject o = new JSONObject().put("ref", MatchRef.from(m).encode()).put("state", m.state.name())
                .put("title", text(m.title)).put("clock", text(m.clock)).put("status", text(m.status))
                .put("summary", text(m.summary)).put("venue", text(m.venue)).put("link", text(m.link))
                .put("end", m.end).put("period", m.period).put("laps", m.laps).put("oversLimit", m.oversLimit)
                .put("overtimeMinutes", m.overtimeMinutes).put("clockSeconds", Double.isFinite(m.clockSeconds) ? m.clockSeconds : 0)
                .put("battingTeamId", text(m.battingTeamId)).put("checkedAt", m.checkedAt).put("delayed", m.updatesDelayed);
        if (m.home != null) o.put("home", side(m.home));
        if (m.away != null) o.put("away", side(m.away));
        JSONArray standings = new JSONArray();
        for (Match.Standing s : m.standings) {
            if (standings.length() == 5) break;
            standings.put(new JSONObject().put("position", s.position).put("name", text(s.name)).put("short", text(s.shortName))
                    .put("flag", text(s.flag)).put("id", text(s.id)).put("gap", text(s.gap)).put("color", s.color));
        }
        return o.put("standings", standings);
    }

    private static JSONObject side(Match.Side side) throws JSONException {
        Team t = side.team;
        JSONArray periods = new JSONArray();
        for (int i = 0; i < Math.min(side.periods.size(), 20); i++) periods.put(text(side.periods.get(i)));
        return new JSONObject().put("id", text(t.id)).put("name", text(t.name)).put("short", text(t.shortName))
                .put("abbr", text(t.abbr)).put("logo", text(t.logo)).put("logoDark", text(t.logoDark)).put("color", text(t.color))
                .put("score", text(side.score)).put("overs", text(side.overs)).put("winner", side.winner).put("periods", periods);
    }

    private static Match read(JSONObject o) {
        if (o == null) return null;
        MatchRef ref = MatchRef.decode(Json.str(o, "ref"));
        if (ref == null) return null;
        Match match = new Match();
        match.sport = ref.sport; match.league = ref.league; match.id = ref.id;
        match.start = ref.start; match.leagueName = ref.leagueName; match.session = ref.session;
        try { match.state = Match.State.valueOf(Json.str(o, "state")); }
        catch (IllegalArgumentException e) { return null; }
        match.title = Json.str(o, "title"); match.clock = Json.str(o, "clock"); match.status = Json.str(o, "status");
        match.summary = Json.str(o, "summary"); match.venue = Json.str(o, "venue"); match.link = Json.str(o, "link");
        match.end = o.optLong("end"); match.period = o.optInt("period"); match.laps = o.optInt("laps");
        match.oversLimit = o.optInt("oversLimit"); match.overtimeMinutes = o.optInt("overtimeMinutes");
        match.clockSeconds = o.optDouble("clockSeconds", 0); match.battingTeamId = Json.str(o, "battingTeamId");
        match.checkedAt = Math.max(0, o.optLong("checkedAt")); match.updatesDelayed = o.optBoolean("delayed", true);
        match.home = side(o.optJSONObject("home")); match.away = side(o.optJSONObject("away"));
        JSONArray standings = o.optJSONArray("standings");
        if (standings != null) for (int i = 0; i < Math.min(standings.length(), 5); i++) {
            JSONObject s = standings.optJSONObject(i);
            if (s == null) continue;
            Match.Standing driver = new Match.Standing(s.optInt("position"), Json.str(s, "name"), Json.str(s, "short"), Json.str(s, "flag"));
            driver.id = Json.str(s, "id"); driver.gap = Json.str(s, "gap"); driver.color = s.optInt("color");
            match.standings.add(driver);
        }
        return match;
    }

    private static Match.Side side(JSONObject o) {
        if (o == null) return null;
        Team team = new Team(Json.str(o, "id"), Json.str(o, "name"), Json.str(o, "short"), Json.str(o, "abbr"),
                Json.str(o, "logo"), Json.str(o, "logoDark"), Json.str(o, "color"));
        Match.Side side = new Match.Side(team, Json.str(o, "score"), Json.str(o, "overs"), o.optBoolean("winner"));
        JSONArray periods = o.optJSONArray("periods");
        if (periods != null) for (int i = 0; i < Math.min(periods.length(), 20); i++) side.periods.add(periods.optString(i, ""));
        return side;
    }

    private static String text(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 512)); }
}
