package app.scoreline.model;

import org.json.JSONException;
import org.json.JSONObject;

import app.scoreline.core.Json;

/** Small persisted/navigation descriptor; no current followed-feed membership is required. */
public final class MatchRef {
    public final Sport sport;
    public final String league;
    public final String id;
    public final long start;
    public final String leagueName;
    public final String session;

    public MatchRef(Sport sport, String league, String id, long start, String leagueName, String session) {
        this.sport = sport;
        this.league = league;
        this.id = id;
        this.start = start;
        this.leagueName = leagueName;
        this.session = session;
    }

    public static MatchRef from(Match match) {
        return new MatchRef(match.sport, match.league, match.id, match.start, match.leagueName, match.session);
    }

    /** Existing notification keys remain accepted, including the complete F1 session suffix. */
    public static MatchRef fromKey(String key) {
        if (key == null) return null;
        String[] parts = key.split("/", -1);
        if (parts.length != 3 || !safe(parts[1]) || !safe(parts[2])) return null;
        Sport sport = null;
        for (Sport candidate : Sport.values()) if (candidate.path.equals(parts[0])) sport = candidate;
        return sport == null ? null : new MatchRef(sport, parts[1], parts[2], 0, "", "");
    }

    public String key() {
        return sport.path + "/" + league + "/" + id;
    }

    public String encode() {
        try {
            return new JSONObject().put("key", key()).put("start", start)
                    .put("leagueName", leagueName).put("session", session).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MatchRef decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        if (!encoded.startsWith("{")) return fromKey(encoded);
        try {
            JSONObject object = new JSONObject(encoded);
            MatchRef key = fromKey(Json.str(object, "key"));
            return key == null ? null : new MatchRef(key.sport, key.league, key.id,
                    Math.max(0, object.optLong("start", 0)), Json.str(object, "leagueName"), Json.str(object, "session"));
        } catch (JSONException e) {
            return null;
        }
    }

    private static boolean safe(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
