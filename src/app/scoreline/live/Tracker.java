package app.scoreline.live;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.scoreline.data.Feed;
import app.scoreline.data.Store;
import app.scoreline.model.Match;

/** Decides which matches belong in the island right now. */
public final class Tracker {
    private Tracker() {}

    /** A followed match joins the island this long before its start, as a countdown. */
    public static final long LEAD_IN = 15 * 60_000;
    /** Matches whose start passed this long ago without ESPN marking them live are ignored. */
    private static final long LATE_START = 3 * 3600_000L;
    /**
     * Matches followed at once. The first is in the island and the second in the side island where
     * there is one (a second Live Update elsewhere); the third is a live card in the shade.
     */
    static final int MAX = 3;

    /** Live, or about to start. A "scheduled" match whose start is long past is stale data, not due. */
    public static boolean isDue(Match m, long now) {
        return m.isLive() || (m.state == Match.State.PRE && m.start - now <= LEAD_IN && m.start > now - LATE_START);
    }

    /** Could still go in the island at some point: live, or not yet started. */
    public static boolean canShow(Match m, long now) {
        return m.isLive() || (m.state == Match.State.PRE && m.start > now - LATE_START);
    }

    public static List<Match> wanted(Feed.Snapshot snapshot, Store store, long now) {
        List<Match> out = new ArrayList<>();
        for (Match m : snapshot.matches) {
            if (store.wantsInIsland(m) && isDue(m, now)) out.add(m);
        }
        // Live before a countdown, so an upcoming match never takes the island from one being
        // played; then the most recently pinned (so pinning swaps a match in); then the earliest.
        out.sort(Comparator.comparing((Match m) -> !m.isLive())
                .thenComparingLong(m -> -store.pinnedAt(m.key()))
                .thenComparingLong(m -> m.start));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    /** When the next island-worthy match starts, or 0 if none is scheduled. */
    public static long nextStart(Feed.Snapshot snapshot, Store store, long now) {
        long next = 0;
        for (Match m : snapshot.matches) {
            if (m.state != Match.State.PRE || m.start <= now || !store.wantsInIsland(m)) continue;
            if (next == 0 || m.start < next) next = m.start;
        }
        return next;
    }
}
