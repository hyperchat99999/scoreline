package app.scoreline.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Null-tolerant helpers for walking ESPN's loosely typed JSON. */
public final class Json {
    private Json() {}

    /** Follows a path of object keys; returns null if any step is missing. */
    public static JSONObject obj(JSONObject o, String... path) {
        for (String key : path) {
            if (o == null) return null;
            o = o.optJSONObject(key);
        }
        return o;
    }

    /** String at {@code key}, or "" when missing or JSON null. Numbers are stringified. */
    public static String str(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : v.toString();
    }

    /** First element of an array of objects, or null. */
    public static JSONObject first(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    public static List<JSONObject> list(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        if (a == null) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item != null) out.add(item);
        }
        return out;
    }

    /** True for JSON true and for the string "true" (ESPN uses both). */
    public static boolean bool(JSONObject o, String key) {
        return o != null && "true".equalsIgnoreCase(str(o, key));
    }

    /** Parses ESPN timestamps such as "2026-10-24T16:30Z" and "2026-10-24T07:00:00Z". */
    public static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                // Seconds are sometimes omitted: "2026-10-24T16:30Z".
                return OffsetDateTime.parse(iso.replace("Z", ":00Z")).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return 0;
            }
        }
    }
}
