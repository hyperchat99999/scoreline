package app.scoreline.model;

import org.json.JSONException;
import org.json.JSONObject;

import app.scoreline.core.Json;

/** Something the user follows: a team, or a whole competition. */
public final class Follow {

    public enum Kind { TEAM, COMPETITION }

    public final Kind kind;
    public final Sport sport;
    /** Competition slug; for teams, the league used to look up their schedule. */
    public final String league;
    /** For teams: the team. For competitions: name and logo only. */
    public final Team team;

    private Follow(Kind kind, Sport sport, String league, Team team) {
        this.kind = kind;
        this.sport = sport;
        this.league = league;
        this.team = team;
    }

    public static Follow team(Sport sport, String league, Team team) {
        return new Follow(Kind.TEAM, sport, league, team);
    }

    public static Follow competition(Sport sport, String league, String name, String logo, String logoDark) {
        return new Follow(Kind.COMPETITION, sport, league, new Team(league, name, name, "", logo, logoDark, ""));
    }

    public String key() {
        return kind == Kind.TEAM ? "team:" + sport.path + ":" + team.id : "comp:" + sport.path + ":" + league;
    }

    public String name() {
        return team.name;
    }

    public boolean covers(Match m) {
        if (m.sport != sport) return false;
        return kind == Kind.TEAM ? m.involves(team.id) : m.league.equals(league);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("kind", kind.name()).put("sport", sport.path).put("league", league).put("team", team.toJson());
    }

    public static Follow fromJson(JSONObject o) {
        Kind kind = "COMPETITION".equals(Json.str(o, "kind")) ? Kind.COMPETITION : Kind.TEAM;
        return new Follow(kind, Sport.of(Json.str(o, "sport")), Json.str(o, "league"), Team.fromJson(o.optJSONObject("team")));
    }
}
