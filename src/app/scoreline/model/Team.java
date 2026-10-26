package app.scoreline.model;

import org.json.JSONException;
import org.json.JSONObject;

import app.scoreline.core.Json;

/** A club, franchise or national side. */
public final class Team {
    public final String id;
    public final String name;
    public final String shortName;
    public final String abbr;
    public final String logo;
    /** Variant drawn for dark backgrounds; may be empty. */
    public final String logoDark;
    /** Primary colour as ESPN reports it ("144992"); may be empty. */
    public final String color;

    public Team(String id, String name, String shortName, String abbr, String logo, String logoDark, String color) {
        this.id = id;
        this.name = name;
        this.shortName = shortName.isEmpty() ? name : shortName;
        this.abbr = abbr.isEmpty() ? name.substring(0, Math.min(3, name.length())).toUpperCase() : abbr;
        this.logo = logo;
        this.logoDark = logoDark;
        this.color = color;
    }

    /** The crest to draw on a dark surface (the island, dark theme). */
    public String crest(boolean dark) {
        return dark && !logoDark.isEmpty() ? logoDark : logo;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("short", shortName).put("abbr", abbr)
                .put("logo", logo).put("logoDark", logoDark).put("color", color);
    }

    static Team fromJson(JSONObject o) {
        return new Team(Json.str(o, "id"), Json.str(o, "name"), Json.str(o, "short"), Json.str(o, "abbr"),
                Json.str(o, "logo"), Json.str(o, "logoDark"), Json.str(o, "color"));
    }
}
