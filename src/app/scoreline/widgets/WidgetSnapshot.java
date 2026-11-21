package app.scoreline.widgets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.scoreline.model.Match;

/** A bounded, restart-safe copy of the user's followed/pinned match feed. */
public final class WidgetSnapshot {
    public static final WidgetSnapshot EMPTY = new WidgetSnapshot(Collections.emptyList(), 0, true);
    public final List<Match> matches;
    /** Last successful source update, never the time a refresh button was pressed. */
    public final long updatedAt;
    public final boolean stale;

    public WidgetSnapshot(List<Match> matches, long updatedAt, boolean stale) {
        this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
        this.updatedAt = Math.max(0, updatedAt);
        this.stale = stale;
    }

    /** A static launcher must label a live score as delayed when publication has stopped. */
    public boolean delayed(Match match, long now) {
        long checked = match != null && match.checkedAt > 0 ? match.checkedAt : updatedAt;
        long maxAge = match != null && match.isLive() ? 5 * 60_000L : 60 * 60_000L;
        return stale || (match != null && match.updatesDelayed) || checked <= 0 || now - checked > maxAge;
    }
}
