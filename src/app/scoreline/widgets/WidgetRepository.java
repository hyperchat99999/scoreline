package app.scoreline.widgets;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.scoreline.data.Store;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.MatchRef;

/** Shared persisted source for app spotlights and launcher instances, independent of screen life. */
public final class WidgetRepository {
    private final Context context;
    private final Store store;
    private final SharedPreferences prefs;
    private WidgetSnapshot current;
    private final WidgetMoments moments;

    public WidgetRepository(Context context, Store store) {
        // Keep a ContextWrapper intact in isolated instrumentation tests.
        this.context = context;
        this.store = store;
        prefs = context.getSharedPreferences("scoreline_widgets", Context.MODE_PRIVATE);
        current = WidgetSnapshotCodec.decode(prefs.getString("snapshot", ""));
        moments = new WidgetMoments(context);
        store.addListener(() -> {
            synchronized (WidgetRepository.this) { persist(filtered(current)); }
            SportsWidgetProvider.updateAll(context);
        });
    }

    /** A stale/failed refresh keeps the last successful update time, including after restart. */
    public void publish(List<Match> matches, boolean stale, long updatedAt) {
        synchronized (this) {
            long successful = stale ? current.updatedAt : Math.max(current.updatedAt, updatedAt);
            List<Match> incoming = new ArrayList<>(matches);
            // A live-service update may have completed after an older feed request began.
            for (Match previous : current.matches) for (int i = 0; i < incoming.size(); i++) {
                Match next = incoming.get(i);
                if (next.key().equals(previous.key()) && previous.checkedAt > next.checkedAt) incoming.set(i, previous);
            }
            if (stale) for (Match previous : current.matches)
                if (incoming.stream().noneMatch(m -> m.key().equals(previous.key()))) incoming.add(previous);
            observe(incoming, stale);
            persist(filtered(new WidgetSnapshot(incoming, successful, stale)));
            prefs.edit().remove("refreshRequested").apply();
        }
        SportsWidgetProvider.updateAll(context);
    }

    public void publishMatch(Match match) {
        if (match == null) return;
        synchronized (this) {
            List<Match> matches = new ArrayList<>(current.matches);
            Match previous = matches.stream().filter(m -> m.key().equals(match.key())).findFirst().orElse(null);
            if (previous != null && previous.checkedAt > match.checkedAt) return;
            if (previous != null) moments.observe(previous, match, false, System.currentTimeMillis());
            matches.removeIf(m -> m.key().equals(match.key()));
            matches.add(match);
            // One successful score update does not claim that every cached source was refreshed.
            long updated = current.updatedAt > 0 ? current.updatedAt : match.checkedAt;
            persist(filtered(new WidgetSnapshot(matches, updated, current.stale)));
        }
        SportsWidgetProvider.updateAll(context);
    }

    /** A goal, wicket or other big moment still on the widget, or null. */
    public WidgetMoments.Moment moment(long now) { return moments.current(now); }

    /** Compares each fresher sample with the saved one; a goal is noticed once, here, not per render. */
    private void observe(List<Match> incoming, boolean stale) {
        long now = System.currentTimeMillis();
        for (Match next : incoming) for (Match previous : current.matches) {
            if (previous.key().equals(next.key()) && next != previous) moments.observe(previous, next, stale, now);
        }
    }

    public synchronized WidgetSnapshot snapshot() {
        return WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(filtered(current)));
    }

    public synchronized List<Match> ordered() { return snapshot().matches; }

    public synchronized Match selected(int widgetId) {
        return WidgetSelection.selected(ordered(), prefs.getString("selected:" + widgetId, ""));
    }

    public synchronized void cycle(int widgetId) {
        Match next = WidgetSelection.next(ordered(), prefs.getString("selected:" + widgetId, ""));
        if (next != null) prefs.edit().putString("selected:" + widgetId, next.key()).apply();
    }

    public synchronized void remove(int[] widgetIds) {
        SharedPreferences.Editor editor = prefs.edit();
        for (int id : widgetIds) editor.remove("selected:" + id);
        editor.apply();
    }

    public void refreshRequested() { prefs.edit().putLong("refreshRequested", System.currentTimeMillis()).apply(); }

    public boolean isRefreshRequested() {
        long requested = prefs.getLong("refreshRequested", 0);
        return requested > 0 && System.currentTimeMillis() - requested < 25_000L;
    }

    private WidgetSnapshot filtered(WidgetSnapshot snapshot) {
        Set<String> pinned = new HashSet<>();
        for (MatchRef ref : store.pinnedMatches()) pinned.add(ref.key());
        List<Follow> follows = store.follows();
        List<Match> eligible = new ArrayList<>();
        for (Match match : snapshot.matches) {
            if (pinned.contains(match.key()) || follows.stream().anyMatch(f -> f.covers(match))) eligible.add(match);
        }
        return new WidgetSnapshot(WidgetSelection.ordered(eligible, pinned), snapshot.updatedAt, snapshot.stale);
    }

    private void persist(WidgetSnapshot snapshot) {
        String encoded = WidgetSnapshotCodec.encode(snapshot);
        current = WidgetSnapshotCodec.decode(encoded); // Never retain a mutable Feed/Tracker match.
        prefs.edit().putString("snapshot", encoded).apply();
    }
}
