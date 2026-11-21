package app.scoreline.data;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.scoreline.model.Match;
import app.scoreline.model.MatchDetail;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Sport;
import app.scoreline.model.Standings;
import app.scoreline.model.Team;

/** Blocking browsing operations. These never change follows, pins or the live tracking workload. */
public final class Browse {
    public static final class Result<T> {
        public final T value;
        /** At least one source used an expired cache or an older in-memory fallback. */
        public final boolean stale;
        /** At least one requested source failed, even if other sources returned useful content. */
        public final boolean failed;
        public final boolean supported;

        public Result(T value, boolean stale, boolean failed) {
            this(value, stale, failed, true);
        }

        public Result(T value, boolean stale, boolean failed, boolean supported) {
            this.value = value;
            this.stale = stale;
            this.failed = failed;
            this.supported = supported;
        }
    }

    private static final int MAX_MATCHES = 512;
    private static final ZoneId ESPN_ZONE = ZoneId.of("America/New_York");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "scoreline-browse");
        thread.setDaemon(true);
        return thread;
    });
    private final Espn espn;
    private final LinkedHashMap<String, Match> matches = new LinkedHashMap<>(64, .75f, true);

    public Browse(Espn espn) {
        this.espn = espn;
    }

    public synchronized Match find(String key) {
        return matches.get(key);
    }

    /** Remember only source results; a failed screen request never mutates these shared matches. */
    public synchronized void remember(Match match) {
        if (match == null) return;
        Match previous = matches.get(match.key());
        if (previous == null || newer(match, previous)) matches.put(match.key(), match);
        while (matches.size() > MAX_MATCHES) matches.remove(matches.keySet().iterator().next());
    }

    public Result<List<Match>> scores(Sport sport, String league, LocalDate date, ZoneId zone, boolean fresh) {
        if (sport == Sport.CRICKET) return cricketScores(league, date, zone, fresh);
        List<String> leagues = new ArrayList<>();
        if (league == null || league.isEmpty()) {
            for (Catalog.Competition competition : Catalog.competitions(sport)) leagues.add(competition.league);
        } else if (safe(league)) {
            leagues.add(league);
        }
        if (leagues.isEmpty()) return new Result<>(List.of(), false, false, false);
        List<Callable<Result<List<Match>>>> sources = new ArrayList<>();
        long age = age(date, zone, fresh);
        for (String scope : leagues) {
            for (String day : providerDays(date, zone)) sources.add(() -> fetch(() -> espn.scoreboard(sport, scope, day, age)));
        }
        Result<List<Match>> result = combine(sources);
        return selected(result, sport, league, date, zone);
    }

    public Result<List<Match>> team(Sport sport, String league, Team team, boolean fresh) {
        if (sport == Sport.RACING || team == null || !safe(league) || !safe(team.id)) return new Result<>(List.of(), false, false, false);
        List<Callable<Result<List<Match>>>> sources = new ArrayList<>();
        long age = fresh ? 0 : Espn.MEDIUM;
        if (sport == Sport.CRICKET) {
            Result<List<Match>> ticker = fetch(() -> espn.cricket(fresh ? 0 : Espn.SHORT));
            Map<String, String> series = new LinkedHashMap<>();
            if (!"intl".equals(league)) series.put(league, "");
            for (Match match : ticker.value) if (match.involves(team.id)) series.put(match.league, match.leagueName);
            for (Map.Entry<String, String> scope : series.entrySet()) sources.add(() -> fetch(() -> espn.cricketSeries(scope.getKey(), scope.getValue(), age)));
            Result<List<Match>> boards = combine(sources);
            List<Match> all = new ArrayList<>(ticker.value);
            all.addAll(boards.value);
            return teamSelected(new Result<>(deduplicate(all), ticker.stale || boards.stale, ticker.failed || boards.failed), sport, team.id);
        }
        sources.add(() -> fetch(() -> espn.schedule(sport, league, team.id, false, age)));
        if (sport == Sport.SOCCER) sources.add(() -> fetch(() -> espn.schedule(sport, league, team.id, true, age)));
        return teamSelected(combine(sources), sport, team.id);
    }

    public Result<List<Team>> teams(Sport sport, String league) {
        return teams(sport, league, false);
    }

    public Result<List<Team>> teams(Sport sport, String league, boolean fresh) {
        if (sport == Sport.CRICKET) return new Result<>(Catalog.CRICKET_NATIONS, false, false);
        if (sport == Sport.RACING || !safe(league)) return new Result<>(List.of(), false, false, false);
        try {
            Espn.Response<List<Team>> response = espn.request(() -> espn.teams(sport, league, fresh ? 0 : Espn.LONG));
            return new Result<>(response.value, response.stale, false);
        } catch (IOException | RuntimeException e) {
            return new Result<>(List.of(), false, true);
        }
    }

    public Result<Standings> standings(Sport sport, String league) {
        return standings(sport, league, false);
    }

    public Result<Standings> standings(Sport sport, String league, boolean fresh) {
        if (sport == Sport.CRICKET || sport == Sport.RACING || !safe(league)) return new Result<>(new Standings(false, List.of()), false, false, false);
        try {
            Espn.Response<Standings> response = espn.request(() -> espn.standings(sport, league, fresh ? 0 : Espn.MEDIUM));
            return new Result<>(response.value, response.stale, false, response.value.supported);
        } catch (IOException | RuntimeException e) {
            return new Result<>(new Standings(true, List.of()), false, true);
        }
    }

    public Result<Match> resolve(MatchRef ref, boolean fresh) {
        if (ref == null) return new Result<>(null, false, false, false);
        Match cached = find(ref.key());
        if (!fresh && cached != null && cached.checkedAt > 0 && System.currentTimeMillis() - cached.checkedAt < Espn.LIVE)
            return new Result<>(cached, cached.updatesDelayed, false);
        MatchRef descriptor = ref.start == 0 && cached != null ? MatchRef.from(cached) : ref;
        try {
            Espn.Response<Match> response = espn.request(() -> espn.lookup(descriptor, fresh ? 0 : Espn.LIVE));
            if (response.value == null) return new Result<>(cached, cached != null, false);
            stamp(response.value, response.stale, response.fetchedAt);
            remember(response.value);
            Match value = find(response.value.key());
            return new Result<>(value, response.stale, false);
        } catch (IOException | RuntimeException e) {
            return new Result<>(cached, cached != null, true);
        }
    }

    public Result<MatchDetail> details(Match match) {
        if (match == null) return new Result<>(new MatchDetail(List.of(), "", ""), false, false, false);
        try {
            Espn.Response<MatchDetail> response = espn.request(() -> espn.details(match));
            return new Result<>(response.value, response.stale, false);
        } catch (IOException | RuntimeException e) {
            return new Result<>(new MatchDetail(List.of(), match.summary, match.link), false, true);
        }
    }

    /** Dates touched by the local day, measured in ESPN's Eastern calendar (including DST). */
    public static List<String> providerDays(LocalDate date, ZoneId zone) {
        LocalDate first = date.atStartOfDay(zone).withZoneSameInstant(ESPN_ZONE).toLocalDate();
        LocalDate last = date.plusDays(1).atStartOfDay(zone).minusNanos(1).withZoneSameInstant(ESPN_ZONE).toLocalDate();
        List<String> days = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) days.add(DateTimeFormatter.BASIC_ISO_DATE.format(day));
        return days;
    }

    public static boolean onDate(Match match, LocalDate date, ZoneId zone) {
        if (match.start <= 0) return false;
        LocalDate start = Instant.ofEpochMilli(match.start).atZone(zone).toLocalDate();
        if (start.equals(date)) return true;
        if (match.sport != Sport.CRICKET || !match.isLive() || date.isBefore(start) || date.isAfter(LocalDate.now(zone))) return false;
        return match.end == 0 || match.end > date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private Result<List<Match>> cricketScores(String league, LocalDate date, ZoneId zone, boolean fresh) {
        long age = age(date, zone, fresh);
        if (league != null && !league.isEmpty() && !"intl".equals(league)) {
            if (!safe(league)) return new Result<>(List.of(), false, false, false);
            List<Callable<Result<List<Match>>>> sources = new ArrayList<>();
            for (String day : providerDays(date, zone)) sources.add(() -> fetch(() -> espn.cricketSeries(league, "", day, age)));
            return selected(combine(sources), Sport.CRICKET, league, date, zone);
        }
        Result<List<Match>> ticker = fetch(() -> espn.cricket(fresh ? 0 : Espn.SHORT));
        Map<String, String> series = new LinkedHashMap<>();
        LocalDate earliest = null;
        LocalDate latest = null;
        for (Match match : ticker.value) {
            LocalDate start = Instant.ofEpochMilli(match.start).atZone(zone).toLocalDate();
            if (earliest == null || start.isBefore(earliest)) earliest = start;
            LocalDate through = match.isLive() ? LocalDate.now(zone) : start;
            if (latest == null || through.isAfter(latest)) latest = through;
            if (onDate(match, date, zone)) series.put(match.league, match.leagueName);
        }
        List<Callable<Result<List<Match>>>> sources = new ArrayList<>();
        for (Map.Entry<String, String> scope : series.entrySet()) {
            for (String day : providerDays(date, zone)) sources.add(() -> fetch(() -> espn.cricketSeries(scope.getKey(), scope.getValue(), day, age)));
        }
        Result<List<Match>> boards = combine(sources);
        List<Match> all = new ArrayList<>(ticker.value);
        all.addAll(boards.value);
        boolean covered = earliest != null && !date.isBefore(earliest) && !date.isAfter(latest);
        Result<List<Match>> result = selected(new Result<>(deduplicate(all), ticker.stale || boards.stale, ticker.failed || boards.failed), Sport.CRICKET, null, date, zone);
        return new Result<>(result.value, result.stale, result.failed, covered || !result.value.isEmpty() || ticker.failed);
    }

    private Result<List<Match>> fetch(Espn.Source<List<Match>> source) {
        try {
            Espn.Response<List<Match>> response = espn.request(source);
            List<Match> values = new ArrayList<>();
            for (Match match : response.value) {
                stamp(match, response.stale, response.fetchedAt);
                remember(match);
                Match current = find(match.key());
                values.add(current == null ? match : current);
            }
            return new Result<>(values, response.stale, false);
        } catch (IOException | RuntimeException e) {
            return new Result<>(List.of(), false, true);
        }
    }

    private Result<List<Match>> combine(List<Callable<Result<List<Match>>>> sources) {
        List<Match> all = new ArrayList<>();
        boolean stale = false;
        boolean failed = false;
        try {
            for (Future<Result<List<Match>>> future : POOL.invokeAll(sources, 28, TimeUnit.SECONDS)) {
                try {
                    Result<List<Match>> result = future.get();
                    all.addAll(result.value);
                    stale |= result.stale;
                    failed |= result.failed;
                } catch (ExecutionException | CancellationException e) {
                    failed = true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed = true;
        }
        return new Result<>(deduplicate(all), stale, failed);
    }

    private Result<List<Match>> selected(Result<List<Match>> result, Sport sport, String league, LocalDate date, ZoneId zone) {
        List<Match> all = new ArrayList<>(result.value);
        Map<String, Match> fetched = new LinkedHashMap<>();
        for (Match match : result.value) fetched.put(match.key(), match);
        boolean fallback = false;
        if (result.failed) {
            for (Match match : cached()) {
                if (!fetched.containsKey(match.key()) && match.sport == sport && (league == null || league.isEmpty() || match.league.equals(league)) && onDate(match, date, zone)) {
                    all.add(match);
                    fallback = true;
                }
            }
        }
        List<Match> selected = new ArrayList<>();
        for (Match match : deduplicate(all)) if (onDate(match, date, zone)) selected.add(match);
        return new Result<>(Collections.unmodifiableList(selected), result.stale || fallback, result.failed, result.supported);
    }

    private Result<List<Match>> teamSelected(Result<List<Match>> result, Sport sport, String teamId) {
        List<Match> all = new ArrayList<>(result.value);
        Map<String, Match> fetched = new LinkedHashMap<>();
        for (Match match : result.value) fetched.put(match.key(), match);
        boolean fallback = false;
        if (result.failed) for (Match match : cached()) {
            if (!fetched.containsKey(match.key()) && match.sport == sport && match.involves(teamId)) {
                all.add(match);
                fallback = true;
            }
        }
        List<Match> selected = new ArrayList<>();
        for (Match match : deduplicate(all)) if (match.involves(teamId)) selected.add(match);
        return new Result<>(Collections.unmodifiableList(selected), result.stale || fallback, result.failed, result.supported);
    }

    private synchronized List<Match> cached() {
        return new ArrayList<>(matches.values());
    }

    private void stamp(Match match, boolean stale, long fetchedAt) {
        match.updatesDelayed = stale;
        match.checkedAt = fetchedAt;
    }

    private static List<Match> deduplicate(List<Match> all) {
        Map<String, Match> byKey = new LinkedHashMap<>();
        for (Match match : all) {
            Match previous = byKey.get(match.key());
            if (previous == null || newer(match, previous)) byKey.put(match.key(), match);
        }
        List<Match> matches = new ArrayList<>(byKey.values());
        matches.sort(Comparator.comparingLong((Match match) -> match.start).thenComparing(Match::key));
        return matches;
    }

    private static boolean newer(Match candidate, Match previous) {
        if (candidate.updatesDelayed != previous.updatesDelayed) return !candidate.updatesDelayed;
        if (candidate.checkedAt != previous.checkedAt) return candidate.checkedAt > previous.checkedAt;
        return stateRank(candidate) >= stateRank(previous);
    }

    private static int stateRank(Match match) {
        return match.state == Match.State.POST ? 3 : match.state == Match.State.LIVE ? 2 : match.state == Match.State.OFF ? 1 : 0;
    }

    private static long age(LocalDate date, ZoneId zone, boolean fresh) {
        return fresh ? 0 : date.equals(LocalDate.now(zone)) ? Espn.SHORT : Espn.MEDIUM;
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
