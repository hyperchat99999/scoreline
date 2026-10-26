package app.scoreline.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import app.scoreline.model.Follow;
import app.scoreline.model.Match;

/** The user's follows, preferences and per-match island choices, persisted in SharedPreferences. */
public final class Store {

    public interface Listener {
        void onFollowsChanged();
    }

    private static final String FOLLOWS = "follows";
    private static final String ONBOARDED = "onboarded";
    private static final String AUTO_ISLAND = "auto_island";
    private static final String COMPETITIONS_IN_ISLAND = "competitions_in_island";
    private static final String ALERTS = "alerts";
    private static final String PINNED = "pinned";
    private static final String HIDDEN = "hidden";
    /** Island choices are about one match, so they expire rather than accumulate. */
    private static final long CHOICE_TTL = 36L * 3600 * 1000;

    private final SharedPreferences prefs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private List<Follow> follows;

    public Store(Context context) {
        prefs = context.getSharedPreferences("scoreline", Context.MODE_PRIVATE);
        follows = readFollows();
    }

    // ---- Follows -------------------------------------------------------------------------------

    public synchronized List<Follow> follows() {
        return follows;
    }

    public synchronized boolean isFollowing(Follow f) {
        for (Follow x : follows) if (x.key().equals(f.key())) return true;
        return false;
    }

    /** Follows or unfollows; returns the new state. */
    public boolean toggle(Follow f) {
        boolean now;
        synchronized (this) {
            List<Follow> next = new ArrayList<>(follows);
            now = !removeKey(next, f.key());
            if (now) next.add(f);
            follows = Collections.unmodifiableList(next);
            writeFollows();
        }
        for (Listener l : listeners) l.onFollowsChanged();
        return now;
    }

    /** The first followed team taking part in {@code m}, or null. */
    public synchronized Follow focus(Match m) {
        for (Follow f : follows) if (f.kind == Follow.Kind.TEAM && f.covers(m)) return f;
        return null;
    }

    public synchronized boolean coversViaCompetition(Match m) {
        for (Follow f : follows) if (f.kind == Follow.Kind.COMPETITION && f.covers(m)) return true;
        return false;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- Preferences ---------------------------------------------------------------------------

    public boolean onboarded() {
        return prefs.getBoolean(ONBOARDED, false);
    }

    public void setOnboarded() {
        prefs.edit().putBoolean(ONBOARDED, true).apply();
    }

    public boolean autoIsland() {
        return prefs.getBoolean(AUTO_ISLAND, true);
    }

    public void setAutoIsland(boolean on) {
        prefs.edit().putBoolean(AUTO_ISLAND, on).apply();
    }

    public boolean competitionsInIsland() {
        return prefs.getBoolean(COMPETITIONS_IN_ISLAND, false);
    }

    public void setCompetitionsInIsland(boolean on) {
        prefs.edit().putBoolean(COMPETITIONS_IN_ISLAND, on).apply();
    }

    public boolean alerts() {
        return prefs.getBoolean(ALERTS, true);
    }

    public void setAlerts(boolean on) {
        prefs.edit().putBoolean(ALERTS, on).apply();
    }

    // ---- Island choices ------------------------------------------------------------------------

    public boolean isPinned(String matchKey) {
        return choices(PINNED).containsKey(matchKey);
    }

    /** When the match was pinned (later pins rank higher), or 0 if it isn't. */
    public long pinnedAt(String matchKey) {
        Long expires = choices(PINNED).get(matchKey);
        return expires == null ? 0 : prefs.getLong("pin_time:" + matchKey, expires - CHOICE_TTL);
    }

    public boolean isHidden(String matchKey) {
        return choices(HIDDEN).containsKey(matchKey);
    }

    /** User asked for this match in the island. */
    public synchronized void pin(Match match) {
        edit(HIDDEN, match.key(), false);
        Map<String, Long> map = choices(PINNED);
        map.put(match.key(), Math.max(System.currentTimeMillis(), match.start) + 7L * 24 * 3600_000);
        Set<String> set = new HashSet<>();
        for (Map.Entry<String, Long> e : map.entrySet()) set.add(e.getKey() + "|" + e.getValue());
        prefs.edit().putStringSet(PINNED, set).putLong("pin_time:" + match.key(), System.currentTimeMillis()).apply();
    }

    public synchronized void pin(String matchKey) {
        edit(HIDDEN, matchKey, false);
        edit(PINNED, matchKey, true);
    }

    /** Migrate old weekend-wide F1 pins to one specific upcoming/live session, once. */
    public synchronized void migrateRacePin(String legacyKey, Match session) {
        if (!isPinned(legacyKey)) return;
        long originalTime = pinnedAt(legacyKey);
        pin(session);
        prefs.edit().putLong("pin_time:" + session.key(), originalTime).apply();
        edit(PINNED, legacyKey, false);
        prefs.edit().remove("pin_time:" + legacyKey).apply();
    }

    /** User removed this match from the island; don't bring it back automatically. */
    public synchronized void hide(String matchKey) {
        edit(PINNED, matchKey, false);
        edit(HIDDEN, matchKey, true);
    }

    /**
     * Whether a match should be in the island right now, ignoring timing: the user pinned it, or it
     * involves a followed team (or competition, if enabled) and they haven't hidden it.
     */
    public boolean wantsInIsland(Match m) {
        String key = m.key();
        if (isPinned(key)) return true;
        if (isHidden(key) || !autoIsland()) return false;
        return focus(m) != null || (competitionsInIsland() && coversViaCompetition(m));
    }

    private Map<String, Long> choices(String name) {
        Map<String, Long> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String entry : prefs.getStringSet(name, Collections.emptySet())) {
            int bar = entry.lastIndexOf('|');
            if (bar < 0) continue;
            try {
                long expires = Long.parseLong(entry.substring(bar + 1));
                if (expires > now) out.put(entry.substring(0, bar), expires);
            } catch (NumberFormatException ignored) {
                // Drop malformed entries.
            }
        }
        return out;
    }

    private void edit(String name, String key, boolean add) {
        Map<String, Long> map = choices(name);
        if (add) map.put(key, System.currentTimeMillis() + CHOICE_TTL);
        else map.remove(key);
        Set<String> set = new HashSet<>();
        for (Map.Entry<String, Long> e : map.entrySet()) set.add(e.getKey() + "|" + e.getValue());
        prefs.edit().putStringSet(name, set).apply();
    }

    // ---- Persistence ---------------------------------------------------------------------------

    private List<Follow> readFollows() {
        List<Follow> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(FOLLOWS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Follow.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // A corrupt list starts empty rather than crashing.
        }
        return Collections.unmodifiableList(out);
    }

    private void writeFollows() {
        JSONArray a = new JSONArray();
        try {
            for (Follow f : follows) a.put(f.toJson());
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(FOLLOWS, a.toString()).apply();
    }

    private static boolean removeKey(List<Follow> list, String key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(key)) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }
}
