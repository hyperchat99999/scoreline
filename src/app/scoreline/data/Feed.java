package app.scoreline.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;

/**
 * Builds the timeline for everything the user follows: fetches each source once (several follows
 * can share one), merges duplicates, makes near-live matches current and trims each follow to a
 * readable handful of results and fixtures.
 */
public final class Feed {

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(Collections.emptyList(), 0, false);

        /** Sorted by start time. */
        public final List<Match> matches;
        public final long updatedAt;
        /** Some data came from cache because the network was unavailable. */
        public final boolean offline;

        Snapshot(List<Match> matches, long updatedAt, boolean offline) {
            this.matches = matches;
            this.updatedAt = updatedAt;
            this.offline = offline;
        }

        public Match find(String key) {
            for (Match m : matches) if (m.key().equals(key)) return m;
            return null;
        }
    }

    private static final long HOUR = 3600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long PAST = 4 * DAY;
    private static final long FUTURE = 21 * DAY;
    private static final int RESULTS_PER_TEAM = 2;
    private static final int FIXTURES_PER_TEAM = 3;
    private static final int PER_COMPETITION = 8;

    private final Espn espn;
    private final Store store;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile Snapshot last = Snapshot.EMPTY;

    public Feed(Espn espn, Store store) {
        this.espn = espn;
        this.store = store;
    }

    public Snapshot last() {
        return last;
    }

    /**
     * Loads the timeline. {@code fresh} shortens cache ages for an explicit refresh. Blocking: call
     * from a worker thread.
     */
    public synchronized Snapshot load(boolean fresh) {
        if (Thread.currentThread().isInterrupted()) return last;
        if (!fresh && System.currentTimeMillis() - last.updatedAt < 15_000) return last;
        List<Follow> follows = store.follows();
        long now = System.currentTimeMillis();
        espn.resetStale();

        Map<String, Callable<List<Match>>> sources = new LinkedHashMap<>();
        for (Follow f : follows) addSources(sources, f, fresh, now);
        int[] failures = {0};
        List<Match> fetched = runAll(sources.values(), failures);

        // Previously an entire F1 weekend shared one key. Preserve an existing choice for
        // the current live session, otherwise the earliest upcoming one.
        List<Match> sessions = new ArrayList<>();
        for (Match m : fetched) if (m.sport == Sport.RACING && m.id.contains(":"))
            if (m.isLive() || (m.state == Match.State.PRE && m.start >= now - 3 * HOUR)) sessions.add(m);
        sessions.sort(Comparator.comparing((Match m) -> !m.isLive()).thenComparingLong(m -> m.start));
        for (Match m : sessions) {
            String legacy = m.sport.path + "/" + m.league + "/" + m.id.substring(0, m.id.indexOf(':'));
            store.migrateRacePin(legacy, m);
        }

        Map<String, Match> byKey = new LinkedHashMap<>();
        for (Match m : fetched) {
            if (m.start < now - PAST || m.start > now + FUTURE || !covered(follows, m)) continue;
            Match prev = byKey.get(m.key());
            if (prev == null || rank(m) > rank(prev)) byKey.put(m.key(), m);
        }
        makeCurrent(byKey, now, fresh);

        List<Match> list = trim(new ArrayList<>(byKey.values()), follows, now);
        list.sort(Comparator.comparingLong(m -> m.start));
        boolean offline = espn.stale() || (failures[0] > 0 && fetched.isEmpty());
        Snapshot s = new Snapshot(Collections.unmodifiableList(list), now, offline);
        if (Thread.currentThread().isInterrupted() || !follows.equals(store.follows())) return last;
        last = s;
        return s;
    }

    // ---- Sources -------------------------------------------------------------------------------

    private void addSources(Map<String, Callable<List<Match>>> out, Follow f, boolean fresh, long now) {
        long near = fresh ? Espn.LIVE : Espn.SHORT;
        long schedule = fresh ? 5 * 60_000 : Espn.MEDIUM;
        String league = f.league;
        Sport sport = f.sport;
        if (f.kind == Follow.Kind.TEAM) {
            String id = f.team.id;
            if (sport == Sport.CRICKET) {
                out.put("cricket", () -> espn.cricket(near));
            } else if (sport == Sport.SOCCER) {
                out.put("fixtures:" + id, () -> espn.schedule(sport, league, id, true, schedule));
                out.put("results:" + id, () -> espn.schedule(sport, league, id, false, schedule));
            } else {
                out.put("schedule:" + sport.path + id, () -> espn.schedule(sport, league, id, false, schedule));
            }
            return;
        }
        switch (sport) {
            case RACING:
                out.put("board:" + league, () -> espn.scoreboard(sport, league, null, near));
                break;
            case SOCCER: {
                // Competitions play in rounds days apart: this month (and next, late in the month)
                // covers fixtures; the default board carries the live round.
                ZonedDateTime t = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York"));
                String month = String.format(Locale.ROOT, "%04d%02d", t.getYear(), t.getMonthValue());
                out.put("month:" + league + month, () -> espn.scoreboard(sport, league, month, Espn.MEDIUM));
                if (t.getDayOfMonth() > t.toLocalDate().lengthOfMonth() - 10) {
                    ZonedDateTime n = t.plusMonths(1);
                    String next = String.format(Locale.ROOT, "%04d%02d", n.getYear(), n.getMonthValue());
                    out.put("month:" + league + next, () -> espn.scoreboard(sport, league, next, Espn.MEDIUM));
                }
                out.put("board:" + league, () -> espn.scoreboard(sport, league, null, near));
                break;
            }
            default:
                // US leagues play most days: yesterday's results through the next two days.
                for (int d = -1; d <= 2; d++) {
                    String day = Espn.espnDay(now + d * DAY);
                    long age = d == 0 ? near : Espn.MEDIUM;
                    out.put("board:" + sport.path + league + day, () -> espn.scoreboard(sport, league, day, age));
                }
        }
    }

    private List<Match> runAll(Collection<Callable<List<Match>>> tasks, int[] failures) {
        List<Match> out = new ArrayList<>();
        try {
            for (Future<List<Match>> f : pool.invokeAll(tasks, 25, TimeUnit.SECONDS)) {
                try {
                    out.addAll(f.get());
                } catch (ExecutionException | CancellationException e) {
                    failures[0]++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /**
     * Team schedules and the cricket ticker lag behind the live boards, so anything around its
     * start time is re-read from its competition's scoreboard (one request per competition and day).
     */
    private void makeCurrent(Map<String, Match> byKey, long now, boolean fresh) {
        Map<String, Callable<List<Match>>> boards = new LinkedHashMap<>();
        long age = fresh ? Espn.LIVE : 30_000;
        for (Match m : byKey.values()) {
            if (m.sport == Sport.CRICKET) {
                // Multi-day Tests run for days; limited-overs games finish within hours.
                boolean around = m.isLive()
                        || (m.state == Match.State.PRE && m.start < now + 15 * 60_000 && m.start > now - 5 * DAY)
                        || (m.state == Match.State.POST && m.start > now - 12 * HOUR);
                String series = m.league;
                String name = m.leagueName;
                if (around) boards.put("cricket:" + series, () -> espn.cricketSeries(series, name, age));
                continue;
            }
            if (m.sport == Sport.RACING || m.state == Match.State.POST) continue;
            if (m.start > now + 15 * 60_000 || m.start < now - 6 * HOUR) continue;
            String day = Espn.espnDay(m.start);
            Sport sport = m.sport;
            String league = m.league;
            boards.put(sport.path + league + day, () -> espn.scoreboard(sport, league, day, age));
        }
        if (boards.isEmpty()) return;
        for (Match m : runAll(boards.values(), new int[1])) {
            Match prev = byKey.get(m.key());
            if (prev != null) byKey.put(m.key(), m);
        }
    }

    // ---- Selection -----------------------------------------------------------------------------

    /** Keeps each follow's live matches, latest results and next fixtures. */
    private static List<Match> trim(List<Match> all, List<Follow> follows, long now) {
        Set<String> keep = new HashSet<>();
        for (Follow f : follows) {
            List<Match> past = new ArrayList<>();
            List<Match> next = new ArrayList<>();
            for (Match m : all) {
                if (!f.covers(m)) continue;
                if (m.state == Match.State.LIVE) keep.add(m.key());
                else if (m.state == Match.State.PRE && m.start > now - 6 * HOUR) next.add(m);
                else past.add(m);
            }
            past.sort(Comparator.comparingLong((Match m) -> m.start).reversed());
            next.sort(Comparator.comparingLong(m -> m.start));
            boolean team = f.kind == Follow.Kind.TEAM;
            int results = team ? RESULTS_PER_TEAM : PER_COMPETITION;
            int fixtures = team ? FIXTURES_PER_TEAM : PER_COMPETITION;
            for (int i = 0; i < Math.min(results, past.size()); i++) {
                // Competitions: only recent results, not the whole month.
                if (team || past.get(i).start > now - 2 * DAY) keep.add(past.get(i).key());
            }
            for (int i = 0; i < Math.min(fixtures, next.size()); i++) keep.add(next.get(i).key());
        }
        List<Match> out = new ArrayList<>();
        for (Match m : all) if (keep.contains(m.key())) out.add(m);
        return out;
    }

    private static boolean covered(List<Follow> follows, Match m) {
        for (Follow f : follows) if (f.covers(m)) return true;
        return false;
    }

    /** Prefers the copy that knows more: live over finished over scheduled. */
    private static int rank(Match m) {
        switch (m.state) {
            case LIVE: return 3;
            case POST: return 4;
            case OFF: return 1;
            default: return 0;
        }
    }
}
