package app.scoreline.data;

import app.scoreline.core.Http;
import app.scoreline.model.Match;
import app.scoreline.model.MatchDetail;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Sport;
import app.scoreline.model.Standings;
import app.scoreline.model.Team;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only fixture checks. No public provider or Android runtime is needed. */
public final class BrowseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        dates();
        references();
        tables();
        details();
        comparisons();
        baseballDetails();
        parsers();
        cache();
        freshness();
        System.out.println("PASS: " + checks + " browse date, identity, parser, grouping, cache and request-freshness checks");
    }

    private static void dates() {
        ZoneId india = ZoneId.of("Asia/Kolkata");
        LocalDate day = LocalDate.of(2026, 11, 15);
        same(Browse.providerDays(day, india), List.of("20261114", "20261115"));
        same(Browse.providerDays(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York")), List.of("20261101"));
        same(Browse.providerDays(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York")), List.of("20260308"));
        same(Browse.providerDays(day, ZoneId.of("Pacific/Kiritimati")), List.of("20261114", "20261115"));
        same(Browse.providerDays(day, ZoneId.of("Pacific/Pago_Pago")), List.of("20261115", "20261116"));
        Match match = match("boundary", "2026-11-14T18:30:00Z");
        check(Browse.onDate(match, day, india), "Local midnight belongs to the new day");
        check(!Browse.onDate(match, day.minusDays(1), india), "Do not use UTC day for local selection");
        match.start--;
        check(!Browse.onDate(match, day, india), "Millisecond before local midnight belongs to yesterday");
        match.sport = Sport.CRICKET;
        match.start = Instant.parse("2026-11-12T04:00:00Z").toEpochMilli();
        // Ends 17:30 on the 17th in India, so the 18th is after it whatever today's date is.
        match.end = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli();
        match.state = Match.State.LIVE;
        check(Browse.onDate(match, day, india), "A live Test remains visible after its start day");
        check(!Browse.onDate(match, LocalDate.of(2026, 11, 18), india), "Test end dates are bounded");
        match.state = Match.State.POST;
        check(!Browse.onDate(match, day, india), "Completed tests must not repeat until their scheduled end");
        match.start = 0;
        check(!Browse.onDate(match, day, india), "Missing dates never become epoch fixtures");
    }

    private static void references() {
        Match match = match("600057447:401839123", "2026-10-30T18:30:00Z");
        match.sport = Sport.RACING;
        match.league = "f1";
        match.leagueName = "Formula 1";
        match.session = "Practice 1";
        MatchRef ref = MatchRef.decode(MatchRef.from(match).encode());
        same(ref.key(), match.key());
        same(ref.start, match.start);
        same(ref.session, "Practice 1");
        same(MatchRef.fromKey(match.key()).id, "600057447:401839123");
        same(MatchRef.decode(match.key()).key(), match.key());
        check(MatchRef.fromKey("unknown/f1/123") == null, "Unknown sport must not silently become soccer");
        check(MatchRef.fromKey("soccer/eng.1/") == null, "Missing event id rejected");
        check(MatchRef.fromKey("soccer/../123") == null, "Path components rejected");
        check(MatchRef.fromKey("soccer/eng.1/123/extra") == null, "Extra key segments rejected");
        check(MatchRef.decode("{broken") == null, "Malformed saved descriptor is recoverable");
        check(MatchRef.decode("{\"key\":\"soccer/eng.1/123\",\"start\":-10}").start == 0, "Negative timestamp discarded");
    }

    private static void tables() throws Exception {
        JSONObject root = new JSONObject("""
                {"name":"League","children":[
                  {"name":"Eastern Conference","standings":{"entries":[
                    {"team":{"id":"2","displayName":"Second"},"stats":[
                      {"name":"wins","abbreviation":"W","displayValue":"7"},
                      {"name":"playoffSeed","displayValue":"2"}]},
                    {"team":{"id":"1","displayName":"First"},"stats":[
                      {"name":"playoffSeed","displayValue":"1"},
                      {"name":"losses","shortDisplayName":"L","displayValue":"3"},
                      {"name":"wins","abbreviation":"W","displayValue":"8"}]},
                    {"stats":[{"name":"wins","abbreviation":"W","displayValue":"99"}]}]}},
                  {"name":"Western Conference","children":[{"name":"Pacific","standings":{"entries":[
                    {"team":{"id":"3","displayName":"Third"},"stats":[
                      {"name":"points","displayName":"Points","displayValue":"12"}]}]}}]}
                ]}
                """);
        Standings standings = Espn.parseStandings(root, Sport.BASKETBALL);
        same(standings.groups.size(), 2);
        same(standings.groups.get(0).name, "Eastern Conference");
        same(standings.groups.get(1).name, "Western Conference · Pacific");
        Standings.Group east = standings.groups.get(0);
        same(east.rows.size(), 2);
        same(east.columns.get(0).label, "W");
        same(east.columns.get(1).label, "L");
        same(east.rows.get(0).rank, "1");
        same(east.rows.get(0).values, List.of("8", "3"));
        same(east.rows.get(1).values, List.of("7", ""));
        same(standings.groups.get(1).rows.get(0).rank, "");
        same(Espn.parseStandings(new JSONObject("{\"children\":[null,7,{}]}"), Sport.SOCCER).groups.size(), 0);
    }

    private static void details() throws Exception {
        Match match = match("123", "2026-11-14T12:00:00Z");
        match.link = "https://www.espn.com/game/123";
        JSONObject root = new JSONObject("""
                {"boxscore":{"teams":[
                  {"team":{"id":"away"},"statistics":[{"name":"shots","label":"Shots","displayValue":"8"}]},
                  {"team":{"id":"other"},"statistics":[{"name":"shots","label":"Shots","displayValue":"90"}]},
                  {"team":{"id":"home"},"statistics":[{"name":"shots","label":"Shots","displayValue":"12"},
                    {"name":"fouls","label":"Fouls","displayValue":"0"},{"name":"absent","label":"Missing"}]}]},
                 "article":{"gameId":"123","description":"Provider recap."}}
                """);
        MatchDetail detail = Espn.parseDetails(root, match);
        same(detail.stats.size(), 2);
        same(detail.stats.get(0).home, "12");
        same(detail.stats.get(0).away, "8");
        same(detail.stats.get(1).home, "0");
        same(detail.stats.get(1).away, "");
        same(detail.summary, "Provider recap.");
        // The provider's stat name survives, so the screen knows fewer fouls or goals against is better.
        same(detail.stats.get(0).key, "shots");
        check(!detail.stats.get(0).lowerIsBetter(), "More shots is better");
        check(detail.stats.get(1).lowerIsBetter(), "Fewer fouls is better");
        check(new MatchDetail.Stat("goalsConceded", "Goals Against", "13", "12").lowerIsBetter(), "Fewer goals against is better");
        check(!new MatchDetail.Stat("Shots", "1", "2").lowerIsBetter(), "A stat without a provider name keeps more-is-better");
        root.getJSONObject("article").put("gameId", "different");
        same(Espn.parseDetails(root, match).summary, "");
        same(match.home.score, "");
    }

    private static void comparisons() {
        MatchDetail.Stat streak = new MatchDetail.Stat("streak", "Streak", "L3", "W1");
        check(streak.comparisonValue("W1") > streak.comparisonValue("L3"), "Winning beats losing streak");
        check(streak.comparisonValue("L1") > streak.comparisonValue("L3"), "Shorter losing streak is better");
        check(streak.comparisonValue("W3") > streak.comparisonValue("W1"), "Longer winning streak is better");
        check(Double.isNaN(streak.comparisonValue("3")), "Unknown streak stays neutral");
        MatchDetail.Stat record = new MatchDetail.Stat("Last Ten Games", "Last Ten", "3-7", "7-3");
        same(record.comparisonValue("3-7"), .3);
        check(Double.isNaN(record.comparisonValue("0-0")), "Empty record stays neutral");
        MatchDetail.Stat scalar = new MatchDetail.Stat("", "Value", "", "");
        same(scalar.comparisonValue(".224"), .224);
        same(scalar.comparisonValue("-2"), -2.0);
        same(scalar.comparisonValue("47.5%"), 47.5);
        for (String value : List.of("N/A", "--", "35-81", "4-20", "rank 2", "", "NaN"))
            check(Double.isNaN(scalar.comparisonValue(value)), "No invented scalar from " + value);
        for (String key : List.of("fieldGoalsMadeAttempted", "threePointFieldGoalsMadeAttempted", "freeThrowsMadeAttempted",
                "thirdDownEff", "fourthDownEff", "powerPlay")) {
            MatchDetail.Stat rate = new MatchDetail.Stat(key, "Rate", "", "");
            check(rate.comparisonValue("4-12") < rate.comparisonValue("6/11"), key + " compares success rate");
            check(Double.isNaN(rate.comparisonValue("0/0")), key + " missing attempts stays unknown");
            check(Double.isNaN(rate.comparisonValue("5/4")), key + " impossible conversion stays unknown");
        }
        check(streak.isSignedComparison(), "Streaks have no proportional share");
        check(new MatchDetail.Stat("goalDifference", "GD", "8", "2").isSignedComparison(), "Positive goal differences also use directional labels");
        MatchDetail.Stat yards = new MatchDetail.Stat("totalPenaltiesYards", "Penalties", "8-75", "5-40");
        check(yards.isCountAndYards() && Double.isNaN(yards.comparisonValue("8-75")), "Count/yard compounds do not invent a ranking");
        MatchDetail.Stat time = new MatchDetail.Stat("possessionTime", "Possession", "", "");
        check(time.comparisonValue("30:59") > time.comparisonValue("30:01"), "Possession includes seconds");
        for (String key : List.of("avgPointsAgainst", "avgTotalTurnovers", "technicalFouls", "yardsPerGameAllowed",
                "fumblesLost", "avgGoalsAgainst", "avgShotsAgainst", "penaltyMinutes", "pitching.ERA", "fielding.errors", "batting.strikeouts"))
            check(new MatchDetail.Stat(key, key, "1", "2").lowerIsBetter(), key + " prefers fewer");
        check(!new MatchDetail.Stat("pitching.strikeouts", "Pitching strikeouts", "1", "2").lowerIsBetter(), "Pitching strikeouts prefer more");
    }

    private static void baseballDetails() throws Exception {
        Match match = match("123", "2026-11-14T12:00:00Z");
        match.sport = Sport.BASEBALL;
        JSONObject root = new JSONObject("""
            {"boxscore":{"teams":[
              {"team":{"id":"away"},"statistics":[{"name":"batting","displayName":"Batting","stats":[
                {"name":"avg","displayName":"Batting Average","displayValue":".300"},
                {"name":"strikeouts","displayName":"Strikeouts","displayValue":"8"}]}]},
              {"team":{"id":"home"},"statistics":[{"name":"batting","displayName":"Batting","stats":[
                {"name":"avg","displayName":"Batting Average","displayValue":".250"},
                {"name":"strikeouts","displayName":"Strikeouts","displayValue":"5"},
                {"name":"isQualified","displayName":"Qualified","displayValue":"1"}]},
                {"name":"pitching","displayName":"Pitching","stats":[
                {"name":"strikeouts","displayName":"Strikeouts","displayValue":"10"}]}]}]}}
            """);
        MatchDetail detail = Espn.parseDetails(root, match);
        same(detail.stats.size(), 3);
        same(detail.stats.get(0).key, "batting.avg");
        same(detail.stats.get(0).home, ".250");
        same(detail.stats.get(0).away, ".300");
        same(detail.stats.get(2).key, "pitching.strikeouts");
        same(detail.stats.get(2).away, "");
        check(detail.stats.get(1).lowerIsBetter() && !detail.stats.get(2).lowerIsBetter(), "Batting/pitching directions stay distinct");
    }

    private static void parsers() throws Exception {
        Method event = Espn.class.getDeclaredMethod("event", JSONObject.class, Sport.class, String.class, String.class);
        event.setAccessible(true);
        Espn espn = new Espn(new Http(Files.createTempDirectory("scoreline-parser").toFile()));
        JSONObject malformed = new JSONObject("{\"id\":\"123\",\"date\":\"broken\",\"competitions\":[{}]}");
        check(event.invoke(espn, malformed, Sport.SOCCER, "eng.1", "League") == null, "Malformed dates skipped");
        check(event.invoke(espn, new JSONObject("{}"), Sport.SOCCER, "eng.1", "League") == null, "Missing competitions skipped");
        Method race = Espn.class.getDeclaredMethod("race", JSONObject.class, JSONObject.class, String.class, String.class);
        race.setAccessible(true);
        JSONObject weekend = new JSONObject("{\"id\":\"600\",\"name\":\"Grand Prix\"}");
        JSONObject session = new JSONObject("{\"id\":\"401\",\"date\":\"2026-11-14T12:00Z\",\"type\":{\"abbreviation\":\"FP1\"}}");
        Match first = (Match) race.invoke(null, weekend, session, "f1", "Formula 1");
        session.put("id", "402").getJSONObject("type").put("abbreviation", "Qual");
        Match qualifying = (Match) race.invoke(null, weekend, session, "f1", "Formula 1");
        check(!first.key().equals(qualifying.key()), "Weekend sessions must not collapse");
        same(first.session, "Practice 1");
        same(qualifying.session, "Qualifying");
        session.remove("id");
        same(((Match) race.invoke(null, weekend, session, "f1", "Formula 1")).id, "600:Qual");
    }

    private static void cache() throws Exception {
        Browse browse = new Browse(new Espn(new Http(Files.createTempDirectory("scoreline-browse-cache").toFile())));
        Match fresh = match("cached", "2026-11-14T12:00:00Z");
        fresh.checkedAt = 100;
        fresh.state = Match.State.LIVE;
        browse.remember(fresh);
        Match stale = match("cached", "2026-11-14T12:00:00Z");
        stale.checkedAt = 200;
        stale.updatesDelayed = true;
        browse.remember(stale);
        check(browse.find(fresh.key()) == fresh, "Stale response must not overwrite fresher live match");
        Match olderCache = match("cached", "2026-11-14T12:00:00Z");
        olderCache.checkedAt = 90;
        browse.remember(olderCache);
        check(browse.find(fresh.key()) == fresh, "An older scheduled cache must not overwrite a live source");
        Match correction = match("cached", "2026-11-14T12:00:00Z");
        correction.checkedAt = 300;
        correction.state = Match.State.OFF;
        browse.remember(correction);
        check(browse.find(fresh.key()) == correction, "A genuinely newer provider correction remains possible");
        for (int i = 0; i < 520; i++) browse.remember(match("other" + i, "2026-11-14T12:00:00Z"));
        check(browse.find(fresh.key()) == null, "Browse match cache is bounded");
        check(!browse.standings(Sport.CRICKET, "intl").supported, "Cricket table unsupported state is explicit");
        check(!browse.teams(Sport.RACING, "f1").supported, "F1 team list unsupported state is explicit");
        check(!browse.team(Sport.RACING, "f1", fresh.home.team, false).supported, "F1 team schedule unsupported state is explicit");
    }

    private static void freshness() throws Exception {
        Path cache = Files.createTempDirectory("scoreline-freshness");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean offline = new AtomicBoolean();
        server.createContext("/cache", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(offline.get() ? 503 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/live", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Espn espn = new Espn(new Http(cache.toFile()));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            json(espn, base + "/cache");
            offline.set(true);
            check(espn.request(() -> json(espn, base + "/cache")).stale, "Expired fallback scoped to its request");
            check(espn.stale(), "Legacy feed stale flag still works");
            check(!espn.request(() -> json(espn, base + "/live")).stale, "Previous stale request must not poison a fresh result");
            List<Callable<Espn.Response<JSONObject>>> calls = List.of(
                    () -> espn.request(() -> json(espn, base + "/cache")),
                    () -> espn.request(() -> json(espn, base + "/live")));
            List<Future<Espn.Response<JSONObject>>> results = pool.invokeAll(calls);
            check(results.get(0).get().stale, "Concurrent stale response labeled stale");
            check(!results.get(1).get().stale, "Concurrent fresh response remains fresh");
            check(espn.request(() -> {
                json(espn, base + "/cache");
                check(!espn.request(() -> json(espn, base + "/live")).stale, "Nested fresh request keeps own freshness");
                return true;
            }).stale, "Nested request retains outer stale state");
            try {
                espn.request(() -> { throw new IOException("Fixture failure"); });
                throw new AssertionError("Failure swallowed");
            } catch (IOException expected) { checks++; }
            check(!espn.request(() -> json(espn, base + "/live")).stale, "Failed request must not poison subsequent freshness");
        } finally {
            pool.shutdownNow();
            server.stop(0);
            try (var files = Files.list(cache)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(cache);
        }
    }

    private static JSONObject json(Espn espn, String url) throws IOException {
        try {
            Method method = Espn.class.getDeclaredMethod("json", String.class, long.class);
            method.setAccessible(true);
            return (JSONObject) method.invoke(espn, url, 0L);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw new IOException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }

    private static Match match(String id, String time) {
        Match match = new Match();
        match.id = id;
        match.league = "eng.1";
        match.start = Instant.parse(time).toEpochMilli();
        match.home = new Match.Side(new Team("home", "Home", "Home", "HOM", "", "", ""), "", "", false);
        match.away = new Match.Side(new Team("away", "Away", "Away", "AWY", "", "", ""), "", "", false);
        return match;
    }

    private static void same(Object actual, Object expected) {
        check(actual.equals(expected), "Expected " + expected + "; got " + actual);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
