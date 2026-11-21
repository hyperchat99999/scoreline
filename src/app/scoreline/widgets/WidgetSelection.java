package app.scoreline.widgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import app.scoreline.model.Match;

/** Deterministic priorities shared by the launcher and the app's sports spotlight. */
public final class WidgetSelection {
    public static final int MAX_MATCHES = 24;
    private WidgetSelection() { }

    public static List<Match> ordered(List<Match> matches, Set<String> pinned) {
        return ordered(matches, pinned, System.currentTimeMillis());
    }

    public static List<Match> ordered(List<Match> matches, Set<String> pinned, long now) {
        LinkedHashMap<String, Match> unique = new LinkedHashMap<>();
        for (Match match : matches) {
            if (match == null || match.id.isEmpty() || match.league.isEmpty()) continue;
            Match previous = unique.get(match.key());
            if (previous == null || match.checkedAt >= previous.checkedAt) unique.put(match.key(), match);
        }
        List<Match> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingInt((Match m) -> priority(m, pinned, now))
                .thenComparingLong(m -> m.start <= 0 ? Long.MAX_VALUE
                        : m.state == Match.State.POST || m.state == Match.State.OFF ? -m.start : m.start)
                .thenComparing(Match::key));
        if (result.size() > MAX_MATCHES) return new ArrayList<>(result.subList(0, MAX_MATCHES));
        return result;
    }

    /** Matches the tracking pipeline's three-hour tolerance for a delayed kickoff. */
    public static boolean overdue(Match match, long now) {
        return match.state == Match.State.PRE && match.start > 0 && match.start <= now - 3 * 3600_000L;
    }

    private static int priority(Match match, Set<String> pinned, long now) {
        boolean pin = pinned.contains(match.key());
        if (match.isLive()) return pin ? 0 : 1;
        if (overdue(match, now)) return 6;
        if (match.state == Match.State.PRE) return pin ? 2 : 3;
        return match.state == Match.State.POST ? 4 : 5;
    }

    /** A saved key survives source reorder; removed selections fall back to the best candidate. */
    public static Match selected(List<Match> ordered, String key) {
        for (Match match : ordered) if (match.key().equals(key)) return match;
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    public static Match next(List<Match> ordered, String key) {
        Match current = selected(ordered, key);
        if (current == null) return null;
        return ordered.get((ordered.indexOf(current) + 1) % ordered.size());
    }
}
