package app.scoreline.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.scoreline.core.Http;
import app.scoreline.core.Json;
import app.scoreline.model.Match;
import app.scoreline.model.MatchDetail;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Sport;
import app.scoreline.model.Standings;
import app.scoreline.model.Team;
import app.scoreline.model.Timeline;

/**
 * ESPN's public site API: endpoints and the parsing that turns each sport's JSON into {@link Match}.
 * The API is undocumented, so parsing is defensive: a malformed event is skipped, never fatal.
 */
public final class Espn {
    /** Cache ages: live polling, near-live lists, schedules, static data. */
    public static final long LIVE = 15_000;
    public static final long SHORT = 60_000;
    public static final long MEDIUM = 20 * 60_000;
    public static final long LONG = 7L * 24 * 3600_000;

    private static final String SITE = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final String TABLES = "https://site.api.espn.com/apis/v2/sports/";
    private static final String CRICKET_TICKER = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket&lang=en";
    private static final String CRICKET_SERIES = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/";
    private static final String RACE_TIMING = "https://sports.core.api.espn.com/v2/sports/racing/leagues/";
    /** Drivers given a gap to the leader: the top five, as on the island. */
    private static final int GAPS = 5;
    private static final Pattern GAP_TIME = Pattern.compile("\\+?(\\d+:)?\\d+\\.\\d+");
    private static final ZoneId ESPN_DAY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern OVERS = Pattern.compile("([\\d.]+)(?:/(\\d+))? ov");
    private static final Pattern TARGET = Pattern.compile("target (\\d+)");

    private final Http http;
    private final AtomicBoolean stale = new AtomicBoolean();
    private final ThreadLocal<Boolean> requestStale = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Long> requestTime = ThreadLocal.withInitial(() -> Long.MAX_VALUE);

    public Espn(Http http) {
        this.http = http;
    }

    /** True if any response since the last {@link #resetStale()} came from an outdated cache. */
    public boolean stale() {
        return stale.get();
    }

    public void resetStale() {
        stale.set(false);
    }

    @FunctionalInterface
    public interface Source<T> {
        T get() throws IOException;
    }

    /** Freshness belongs to this call, even while the followed feed is loading concurrently. */
    public static final class Response<T> {
        public final T value;
        public final boolean stale;
        public final long fetchedAt;

        Response(T value, boolean stale, long fetchedAt) {
            this.value = value;
            this.stale = stale;
            this.fetchedAt = fetchedAt;
        }
    }

    public <T> Response<T> request(Source<T> source) throws IOException {
        boolean previous = requestStale.get();
        long previousTime = requestTime.get();
        requestStale.set(false);
        requestTime.set(Long.MAX_VALUE);
        try {
            T value = source.get();
            return new Response<>(value, requestStale.get(), requestTime.get() == Long.MAX_VALUE ? 0 : requestTime.get());
        } finally {
            requestStale.set(previous || requestStale.get());
            requestTime.set(Math.min(previousTime, requestTime.get()));
        }
    }

    // ---- Endpoints -----------------------------------------------------------------------------

    /** Matches in a competition; {@code dates} is "YYYYMMDD", "YYYYMM" or null for the current round. */
    public List<Match> scoreboard(Sport sport, String league, String dates, long maxAge) throws IOException {
        if (sport == Sport.CRICKET) return cricketSeries(league, "", dates, maxAge);
        String url = SITE + sport.path + "/" + league + "/scoreboard" + (dates == null ? "" : "?dates=" + dates);
        JSONObject root = json(url, maxAge);
        String name = leagueName(sport, league, Json.first(root, "leagues"));
        List<Match> out = new ArrayList<>();
        for (JSONObject e : Json.list(root, "events")) {
            if (sport == Sport.RACING) {
                for (JSONObject session : Json.list(e, "competitions")) {
                    Match m = race(e, session, league, name);
                    if (m != null) {
                    overtime(m, Json.obj(root, "season"));
                    out.add(m);
                }
                }
            } else {
                Match m = event(e, sport, league, name);
                if (m != null) {
                    overtime(m, Json.obj(root, "season"));
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** A team's results ({@code fixtures} false) or upcoming games across all competitions. */
    public List<Match> schedule(Sport sport, String league, String teamId, boolean fixtures, long maxAge) throws IOException {
        String scope = sport == Sport.SOCCER ? "all" : league;
        String url = SITE + sport.path + "/" + scope + "/teams/" + teamId + "/schedule"
                + (fixtures && sport == Sport.SOCCER ? "?fixture=true" : "");
        JSONObject root = json(url, maxAge);
        List<Match> out = new ArrayList<>();
        for (JSONObject e : Json.list(root, "events")) {
            JSONObject lg = e.optJSONObject("league");
            String slug = Json.str(lg, "slug");
            if (slug.isEmpty()) slug = league;
            Match m = event(e, sport, slug, leagueName(sport, slug, lg));
            if (m != null) {
                    overtime(m, Json.obj(root, "season"));
                    out.add(m);
                }
        }
        return out;
    }

    /** Every cricket match ESPN's live ticker currently carries (today's, plus recent and next). */
    public List<Match> cricket(long maxAge) throws IOException {
        JSONObject root = json(CRICKET_TICKER, maxAge);
        List<Match> out = new ArrayList<>();
        for (JSONObject lg : Json.list(Json.first(root, "sports"), "leagues")) {
            String name = Json.str(lg, "name");
            for (JSONObject e : Json.list(lg, "events")) {
                Match m = cricketEvent(e, Json.str(lg, "id"), name);
                if (m != null) {
                    overtime(m, Json.obj(root, "season"));
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * One cricket series' matches. Slower to discover than the ticker but authoritative: the
     * ticker has been seen stuck on "Scheduled" for a match that was in its final over.
     */
    public List<Match> cricketSeries(String seriesId, String seriesName, long maxAge) throws IOException {
        return cricketSeries(seriesId, seriesName, null, maxAge);
    }

    public List<Match> cricketSeries(String seriesId, String seriesName, String dates, long maxAge) throws IOException {
        JSONObject root = json(CRICKET_SERIES + seriesId + "/scoreboard"
                + (dates == null ? "" : "?dates=" + dates), maxAge);
        String name = seriesName.isEmpty() ? Json.str(Json.first(root, "leagues"), "name") : seriesName;
        List<Match> out = new ArrayList<>();
        for (JSONObject e : Json.list(root, "events")) {
            Match m = cricketSeriesEvent(e, seriesId, name);
            if (m != null) {
                    overtime(m, Json.obj(root, "season"));
                    out.add(m);
                }
        }
        return out;
    }

    /** All teams in a competition, alphabetically. */
    public List<Team> teams(Sport sport, String league) throws IOException {
        return teams(sport, league, LONG);
    }

    public List<Team> teams(Sport sport, String league, long maxAge) throws IOException {
        JSONObject root = json(SITE + sport.path + "/" + league + "/teams", maxAge);
        List<Team> out = new ArrayList<>();
        for (JSONObject t : Json.list(Json.first(Json.first(root, "sports"), "leagues"), "teams")) {
            JSONObject team = t.optJSONObject("team");
            if (team != null) out.add(team(team, sport));
        }
        out.sort(Comparator.comparing(x -> x.name));
        return out;
    }

    /** A fresh copy of {@code m} for live tracking, or null if ESPN no longer lists it. */
    public Match refresh(Match m) throws IOException {
        Response<Match> response = request(() -> refreshInternal(m));
        Match result = response.value;
        if (result != null) {
            result.updatesDelayed = response.stale;
            result.checkedAt = response.fetchedAt;
        }
        return result;
    }

    /** Resolve an event without a followed-feed entry. F1 identities retain their session suffix. */
    public Match lookup(MatchRef ref, long maxAge) throws IOException {
        if (ref == null) return null;
        if (ref.sport == Sport.RACING) {
            long start = ref.start;
            String weekend = ref.id.split(":", 2)[0];
            if (start == 0) start = Json.time(Json.str(json(RACE_TIMING + ref.league + "/events/" + weekend, MEDIUM), "date"));
            if (start == 0) return null;
            List<Match> sessions = scoreboard(ref.sport, ref.league, day(start, ZoneOffset.UTC).substring(0, 6), maxAge);
            Match exact = find(sessions, ref.id);
            if (exact != null) return exact;
            // Old notification keys identified a whole weekend. Pick its live/next session once.
            if (!ref.id.contains(":")) {
                Match chosen = null;
                for (Match match : sessions) {
                    if (!match.id.startsWith(weekend + ":")) continue;
                    if (match.isLive()) return match;
                    if (match.state == Match.State.PRE && (chosen == null || chosen.state != Match.State.PRE || match.start < chosen.start)) chosen = match;
                    else if (chosen == null || (chosen.state != Match.State.PRE && match.start > chosen.start)) chosen = match;
                }
                return chosen;
            }
            return null;
        }
        if (ref.start > 0) {
            try {
                Match match = find(scoreboard(ref.sport, ref.league, espnDay(ref.start), maxAge), ref.id);
                if (match != null) return match;
            } catch (IOException ignored) {
                // A direct event summary also works when a dated board is unavailable.
            }
        }
        JSONObject root = summary(ref.sport, ref.league, ref.id, maxAge);
        JSONObject header = root.optJSONObject("header");
        if (header == null || !ref.id.equals(Json.str(header, "id"))) return null;
        Match match = ref.sport == Sport.CRICKET
                ? cricketSeriesEvent(header, ref.league, ref.leagueName)
                : event(header, ref.sport, ref.league, leagueName(ref.sport, ref.league, Json.first(header, "leagues")));
        if (match != null) {
            if (match.venue.isEmpty()) match.venue = Json.str(Json.obj(root, "gameInfo", "venue"), "fullName");
            if (match.link.isEmpty()) match.link = link(header);
        }
        return match;
    }

    public Standings standings(Sport sport, String league) throws IOException {
        return standings(sport, league, MEDIUM);
    }

    public Standings standings(Sport sport, String league, long maxAge) throws IOException {
        if (sport == Sport.CRICKET || sport == Sport.RACING) return new Standings(false, List.of());
        return parseStandings(json(TABLES + sport.path + "/" + league + "/standings", maxAge), sport);
    }

    public MatchDetail details(Match match) throws IOException {
        if (match.sport == Sport.RACING) return new MatchDetail(List.of(), match.summary, match.link);
        return parseDetails(summary(match.sport, match.league, match.id, match.isLive() ? LIVE : MEDIUM), match);
    }

    private JSONObject summary(Sport sport, String league, String id, long maxAge) throws IOException {
        String base = sport == Sport.CRICKET ? CRICKET_SERIES + league : SITE + sport.path + "/" + league;
        return json(base + "/summary?event=" + id, maxAge);
    }

    private Match refreshInternal(Match m) throws IOException {
        List<Match> pool;
        switch (m.sport) {
            case CRICKET: {
                Match hit = find(cricketSeries(m.league, m.leagueName, LIVE), m.id);
                if (hit != null) return hit;
                pool = cricket(LIVE);
                break;
            }
            case RACING: {
                Match hit = find(scoreboard(Sport.RACING, m.league, null, LIVE), m.id);
                if (hit != null && hit.state != Match.State.PRE) timing(hit);
                return hit;
            }
            default:
                pool = scoreboard(m.sport, m.league, day(m.start, ESPN_DAY), LIVE);
                Match hit = find(pool, m.id);
                if (hit != null) return hit;
                pool = scoreboard(m.sport, m.league, day(m.start, ZoneOffset.UTC), LIVE);
        }
        return find(pool, m.id);
    }

    /** ESPN groups US-sport and football scoreboards by US Eastern date. */
    public static String espnDay(long ms) {
        return day(ms, ESPN_DAY);
    }

    // ---- Parsing: head-to-head sports ----------------------------------------------------------

    private Match event(JSONObject e, Sport sport, String league, String leagueName) {
        JSONObject comp = Json.first(e, "competitions");
        if (comp == null) return null;
        Match m = new Match();
        m.id = Json.str(e, "id");
        m.sport = sport;
        m.league = league;
        m.leagueName = leagueName;
        m.start = Json.time(Json.str(e, "date"));
        if (m.start == 0) m.start = Json.time(Json.str(comp, "date"));
        if (m.id.isEmpty() || m.start == 0) return null;
        JSONObject status = comp.optJSONObject("status");
        applyStatus(m, status != null ? status : e.optJSONObject("status"));
        for (JSONObject c : Json.list(comp, "competitors")) {
            Match.Side side = side(c, sport);
            if ("away".equals(Json.str(c, "homeAway"))) m.away = side;
            else if (m.home == null) m.home = side;
            else m.away = side;
        }
        if (!validSides(m)) return null;
        if (sport == Sport.SOCCER) moments(m, comp);
        overtime(m, Json.obj(e, "season"));
        m.venue = Json.str(Json.obj(comp, "venue"), "fullName");
        m.link = link(e);
        return m;
    }

    private static Match.Side side(JSONObject c, Sport sport) {
        JSONObject t = c.optJSONObject("team");
        Object score = c.opt("score");
        String display = score instanceof JSONObject ? Json.str((JSONObject) score, "displayValue") : Json.str(c, "score");
        Match.Side side = new Match.Side(team(t != null ? t : c, sport), display, "", Json.bool(c, "winner"));
        for (JSONObject l : Json.list(c, "linescores")) {
            String v = Json.str(l, "displayValue");
            if (v.isEmpty() && l.opt("value") instanceof Number) v = String.valueOf(Math.round(l.optDouble("value")));
            if (!v.isEmpty()) side.periods.add(v);
        }
        return side;
    }

    static Team team(JSONObject t, Sport sport) {
        String logo = Json.str(t, "logo");
        String dark = Json.str(t, "logoDark");
        for (JSONObject l : Json.list(t, "logos")) {
            JSONArray rel = l.optJSONArray("rel");
            String href = Json.str(l, "href");
            if (rel != null && rel.toString().contains("\"dark\"")) {
                if (dark.isEmpty()) dark = href;
            } else if (logo.isEmpty()) {
                logo = href;
            }
        }
        // Football crests have a published dark variant at a predictable path.
        if (dark.isEmpty() && sport == Sport.SOCCER && logo.contains("/teamlogos/soccer/500/")) {
            dark = logo.replace("/500/", "/500-dark/");
        }
        return new Team(Json.str(t, "id"), Json.str(t, "displayName"), Json.str(t, "shortDisplayName"),
                Json.str(t, "abbreviation"), logo, dark, Json.str(t, "color"));
    }

    /** Only infer overtime when ESPN identifies the season phase. */
    private static void overtime(Match m, JSONObject season) {
        if (m.overtimeMinutes > 0 || season == null) return;
        int type = season.optInt("type", 0);
        if (type != 2 && type != 3) return;
        if (m.sport == Sport.HOCKEY && "nhl".equals(m.league)) m.overtimeMinutes = type == 3 ? 20 : 5;
        if (m.sport == Sport.FOOTBALL && "nfl".equals(m.league)) m.overtimeMinutes = type == 3 ? 15 : 10;
    }

    private static void applyStatus(Match m, JSONObject status) {
        JSONObject type = Json.obj(status, "type");
        String state = Json.str(type, "state");
        String name = Json.str(type, "name");
        m.status = Json.str(type, "shortDetail");
        m.period = status == null ? 0 : status.optInt("period");
        m.clockSeconds = status == null ? 0 : status.optDouble("clock", 0);
        if (name.contains("POSTPONED") || (name.contains("CANCELED") || name.contains("CANCELLED")) || name.contains("ABANDONED") || name.contains("FORFEIT")) {
            m.state = Match.State.OFF;
        } else {
            m.state = "in".equals(state) ? Match.State.LIVE : "post".equals(state) ? Match.State.POST : Match.State.PRE;
        }
        m.clock = m.state == Match.State.LIVE ? liveClock(m, name, Json.str(status, "displayClock")) : "";
    }

    private static String liveClock(Match m, String name, String display) {
        int p = m.period;
        switch (m.sport) {
            case SOCCER:
                if (name.contains("HALFTIME")) return "HT";
                if (name.contains("SHOOTOUT")) return "Pens";
                if (name.contains("END_OF_REGULATION") || name.contains("END_OF_EXTRATIME")) return "Break";
                return display.replace("'+", "+");
            case BASKETBALL:
            case FOOTBALL: {
                String q = p <= 4 ? "Q" + p : (p == 5 ? "OT" : (p - 4) + "OT");
                if (name.contains("HALFTIME")) return "Half";
                if (name.contains("END_PERIOD")) return "End " + q;
                return q + " " + display;
            }
            case HOCKEY: {
                if (name.contains("SHOOTOUT")) return "SO";
                String q = p <= 3 ? "P" + p : "OT";
                if (name.contains("END_PERIOD")) return "End " + q;
                return q + " " + display;
            }
            case BASEBALL: {
                String d = m.status;
                String n = d.replaceAll("\\D+", "");
                if (d.startsWith("Top")) return "▲" + n;
                if (d.startsWith("Bot")) return "▼" + n;
                if (d.startsWith("Mid")) return "Mid " + n;
                if (d.startsWith("End")) return "End " + n;
                return d;
            }
            default:
                return display;
        }
    }

    private static void moments(Match m, JSONObject comp) {
        for (JSONObject d : Json.list(comp, "details")) {
            boolean goal = Json.bool(d, "scoringPlay");
            boolean red = Json.bool(d, "redCard");
            if ((!goal && !red) || Json.bool(d, "shootout")) continue;
            String clock = Json.str(Json.obj(d, "clock"), "displayValue").replace("'+", "+");
            JSONObject who = Json.first(d, "athletesInvolved");
            String player = Json.str(who, "shortName");
            if (player.isEmpty()) player = Json.str(who, "displayName");
            Match.Moment.Kind kind = red ? Match.Moment.Kind.RED_CARD
                    : Json.bool(d, "ownGoal") ? Match.Moment.Kind.OWN_GOAL
                    : Json.bool(d, "penaltyKick") ? Match.Moment.Kind.PENALTY
                    : Match.Moment.Kind.GOAL;
            boolean home = Json.str(Json.obj(d, "team"), "id").equals(m.home.team.id);
            m.moments.add(new Match.Moment(kind, Timeline.minute(clock), clock, player, home));
        }
    }

    // ---- Parsing: cricket ----------------------------------------------------------------------

    private static Match cricketEvent(JSONObject e, String leagueId, String leagueName) {
        Match m = new Match();
        m.sport = Sport.CRICKET;
        m.id = Json.str(e, "id");
        m.league = leagueId;
        m.leagueName = leagueName;
        m.title = Json.str(e, "title").replaceAll("\\s*\\((D/N|N)\\)$", "");
        m.start = Json.time(Json.str(e, "date"));
        m.end = Json.time(Json.str(e, "endDate"));
        if (m.id.isEmpty() || m.start == 0) return null;
        JSONObject full = e.optJSONObject("fullStatus");
        String state = Json.str(e, "status");
        if (full != null && full.optJSONObject("type") != null) applyStatus(m, full);
        else m.state = "in".equals(state) ? Match.State.LIVE : "post".equals(state) ? Match.State.POST : Match.State.PRE;
        m.status = Json.str(Json.obj(full, "type"), "shortDetail");
        String summary = Json.str(full, "longSummary");
        if (summary.isEmpty()) summary = Json.str(full, "summary");
        m.summary = summary;
        m.period = full == null ? 0 : full.optInt("period");
        m.battingTeamId = Json.str(full, "battingTeamId");
        m.venue = Json.str(e, "location");

        int limit = 0;
        for (JSONObject c : Json.list(e, "competitors")) {
            String raw = Json.str(c, "score");
            int paren = raw.indexOf(" (");
            String score = (paren < 0 ? raw : raw.substring(0, paren)).trim();
            String overs = "";
            Matcher o = OVERS.matcher(raw);
            if (o.find()) {
                overs = o.group(1);
                if (o.group(2) != null) limit = Integer.parseInt(o.group(2));
            }
            Match.Side side = new Match.Side(team(c, Sport.CRICKET), score, overs, Json.bool(c, "winner"));
            if ("away".equals(Json.str(c, "homeAway"))) m.away = side;
            else if (m.home == null) m.home = side;
            else m.away = side;
        }
        if (!validSides(m)) return null;
        if (limit == 0) limit = oversFor(Json.str(Json.obj(e, "class"), "generalClassCard"));
        m.oversLimit = limit;
        Match.Side batting = m.battingTeamId.equals(m.away.team.id) ? m.away : m.home;
        if (m.state == Match.State.LIVE && !batting.overs.isEmpty()) m.clock = batting.overs + " ov";
        m.link = Json.str(e, "link");
        if (m.link.isEmpty()) m.link = link(e);
        if (m.link.isEmpty()) m.link = "https://www.espncricinfo.com/";
        return m;
    }

    private static Match cricketSeriesEvent(JSONObject e, String seriesId, String seriesName) {
        JSONObject comp = Json.first(e, "competitions");
        if (comp == null) return null;
        Match m = new Match();
        m.sport = Sport.CRICKET;
        m.id = Json.str(e, "id");
        m.league = seriesId;
        m.leagueName = seriesName;
        m.title = Json.str(comp, "description");
        m.start = Json.time(Json.str(e, "date"));
        if (m.start == 0) m.start = Json.time(Json.str(comp, "date"));
        m.end = Json.time(Json.str(e, "endDate"));
        if (m.end == 0) m.end = Json.time(Json.str(comp, "endDate"));
        if (m.id.isEmpty() || m.start == 0) return null;
        JSONObject status = comp.optJSONObject("status");
        JSONObject type = Json.obj(status, "type");
        applyStatus(m, status);
        m.status = Json.str(type, "shortDetail");
        m.summary = Json.str(status, "summary");
        m.period = status == null ? 0 : status.optInt("period");
        m.venue = Json.str(Json.obj(comp, "venue"), "fullName");

        int limit = 0;
        int target = 0;
        for (JSONObject c : Json.list(comp, "competitors")) {
            JSONObject t = c.optJSONObject("team");
            String raw = Json.str(c, "score");
            int paren = raw.indexOf(" (");
            String overs = "";
            Matcher o = OVERS.matcher(raw);
            if (o.find()) {
                overs = o.group(1);
                if (o.group(2) != null) limit = Integer.parseInt(o.group(2));
            }
            Matcher tg = TARGET.matcher(raw);
            if (tg.find()) target = Integer.parseInt(tg.group(1));
            Match.Side side = new Match.Side(team(t != null ? t : c, Sport.CRICKET), (paren < 0 ? raw : raw.substring(0, paren)).trim(),
                    overs, Json.bool(c, "winner"));
            for (JSONObject l : Json.list(c, "linescores")) {
                if (Json.bool(l, "isBatting") && l.optInt("isCurrent") == 1) m.battingTeamId = side.team.id;
            }
            if ("away".equals(Json.str(c, "homeAway"))) m.away = side;
            else if (m.home == null) m.home = side;
            else m.away = side;
        }
        if (!validSides(m)) return null;
        if (limit == 0) limit = oversFor(Json.str(Json.obj(comp, "class"), "generalClassCard"));
        m.oversLimit = limit;
        Match.Side batting = m.away.team.id.equals(m.battingTeamId) ? m.away : m.home;
        if (m.state == Match.State.LIVE && !batting.overs.isEmpty()) m.clock = batting.overs + " ov";
        if (m.summary.isEmpty() && m.state == Match.State.LIVE && target > 0 && limit > 0) m.summary = equation(batting, target, limit);
        m.link = link(e);
        if (m.link.isEmpty()) m.link = "https://www.espncricinfo.com/";
        return m;
    }

    /** "India need 12 runs from 9 balls", for when the feed doesn't say. */
    private static String equation(Match.Side batting, int target, int limit) {
        try {
            int runs = Integer.parseInt(batting.score.split("/")[0].trim());
            String[] o = batting.overs.split("\\.");
            int balls = Integer.parseInt(o[0]) * 6 + (o.length > 1 ? Integer.parseInt(o[1]) : 0);
            int need = target - runs;
            int left = limit * 6 - balls;
            if (need <= 0 || left <= 0) return "";
            return batting.team.name + " need " + need + (need == 1 ? " run" : " runs") + " from " + left + (left == 1 ? " ball" : " balls");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int oversFor(String format) {
        if (format.startsWith("T20")) return 20;
        if (format.equals("ODI") || format.startsWith("List A")) return 50;
        return 0;
    }

    // ---- Parsing: motor racing -----------------------------------------------------------------

    private static Match race(JSONObject e, JSONObject pick, String league, String leagueName) {
        Match m = new Match();
        m.sport = Sport.RACING;
        String sessionId = Json.str(pick, "id");
        if (sessionId.isEmpty()) sessionId = Json.str(Json.obj(pick, "type"), "abbreviation");
        if (sessionId.isEmpty()) return null;
        m.id = Json.str(e, "id") + ":" + sessionId;
        m.league = league;
        m.leagueName = leagueName;
        m.title = Json.str(e, "name");
        m.venue = Json.str(Json.obj(e, "circuit"), "fullName");
        m.start = Json.time(Json.str(pick, "date"));
        if (Json.str(e, "id").isEmpty() || m.start == 0) return null;
        m.session = session(Json.str(Json.obj(pick, "type"), "abbreviation"));
        applyStatus(m, pick.optJSONObject("status"));
        if (m.state == Match.State.LIVE) m.clock = m.isRace() && m.period > 0 ? "Lap " + m.period : shortSession(m.session);
        List<JSONObject> drivers = new ArrayList<>(Json.list(pick, "competitors"));
        drivers.sort(Comparator.comparingInt(c -> c.optInt("order", 99)));
        for (JSONObject c : drivers) {
            JSONObject a = c.optJSONObject("athlete");
            int position = c.optInt("order", 0);
            if (position <= 0 || a == null) continue;
            Match.Standing s = new Match.Standing(position, Json.str(a, "displayName"), Json.str(a, "shortName"),
                    Json.str(Json.obj(a, "flag"), "href"));
            s.id = Json.str(c, "id");
            m.standings.add(s);
        }
        m.link = link(e);
        return m;
    }

    /**
     * What the scoreboard leaves out, from ESPN's race timing: team colours, the race distance and
     * the top five's gaps to the leader. Only for a session someone follows or has open, so the
     * extra requests stay few. Each piece is best effort; without it the island shows the order alone.
     */
    private void timing(Match m) {
        int colon = m.id.indexOf(':');
        if (colon < 0) return;
        String base = RACE_TIMING + m.league + "/events/" + m.id.substring(0, colon) + "/competitions/" + m.id.substring(colon + 1);
        long maxAge = m.isLive() ? LIVE : LONG;
        try {
            // Team colours don't change during a session, so this is fetched once.
            for (JSONObject c : Json.list(json(base, LONG), "competitors")) {
                String hex = Json.str(Json.obj(c, "vehicle"), "teamColor");
                if (!hex.matches("[0-9A-Fa-f]{6}")) continue;
                for (Match.Standing s : m.standings) {
                    if (s.id.equals(Json.str(c, "id"))) s.color = 0xFF000000 | Integer.parseInt(hex, 16);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        if (m.isRace()) {
            try {
                m.laps = (int) stat(json(base + "/statistics", LONG), "laps").optDouble("value", 0);
            } catch (IOException | RuntimeException ignored) {
            }
            if (m.laps > 0 && m.isLive() && m.period > 0) m.clock = "Lap " + Math.min(m.period, m.laps) + "/" + m.laps;
        }
        for (int i = 1; i < Math.min(GAPS, m.standings.size()); i++) {
            Match.Standing s = m.standings.get(i);
            if (s.id.isEmpty()) continue;
            try {
                s.gap = gap(Json.str(stat(json(base + "/competitors/" + s.id + "/statistics", maxAge), "behindTime"), "displayValue"), m.isRace());
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    /** A named statistic from an ESPN stats document (a competition's or a driver's), or an empty object. */
    private static JSONObject stat(JSONObject root, String name) {
        JSONObject splits = root.optJSONObject("splits");
        for (JSONObject category : Json.list(splits != null ? splits : root, "categories")) {
            for (JSONObject s : Json.list(category, "stats")) if (name.equals(Json.str(s, "name"))) return s;
        }
        return new JSONObject();
    }

    /**
     * "+13.078" becomes "+13.0" in a race, where gaps are read in tenths; practice and qualifying
     * keep thousandths. Anything else ("+1 Lap") is shown as given.
     */
    static String gap(String raw, boolean race) {
        String g = raw.trim();
        if (!GAP_TIME.matcher(g).matches()) return g;
        if (!g.startsWith("+")) g = "+" + g;
        return race ? g.substring(0, g.indexOf('.') + 2) : g;
    }

    private static String session(String abbr) {
        switch (abbr) {
            case "FP1": return "Practice 1";
            case "FP2": return "Practice 2";
            case "FP3": return "Practice 3";
            case "Qual": return "Qualifying";
            case "SQ":
            case "SS": return "Sprint Qualifying";
            case "Sprint": return "Sprint";
            default: return abbr.isEmpty() ? "Race" : abbr;
        }
    }

    public static String shortSession(String session) {
        switch (session) {
            case "Practice 1": return "FP1";
            case "Practice 2": return "FP2";
            case "Practice 3": return "FP3";
            case "Qualifying": return "Quali";
            case "Sprint Qualifying": return "SQ";
            default: return session;
        }
    }

    // ---- Optional tables and match information --------------------------------------------------

    static Standings parseStandings(JSONObject root, Sport sport) {
        List<Standings.Group> groups = new ArrayList<>();
        if (root.optJSONObject("standings") != null) standingsGroups(root, sport, "", groups);
        else for (JSONObject child : Json.list(root, "children")) standingsGroups(child, sport, "", groups);
        return new Standings(true, groups);
    }

    private static void standingsGroups(JSONObject node, Sport sport, String parent, List<Standings.Group> out) {
        if (node == null) return;
        String name = Json.str(node, "name");
        if (name.isEmpty()) name = parent;
        else if (!parent.isEmpty()) name = parent + " · " + name;
        JSONObject table = node.optJSONObject("standings");
        List<JSONObject> entries = Json.list(table, "entries");
        if (!entries.isEmpty()) {
            Map<String, Standings.Column> columns = new LinkedHashMap<>();
            for (JSONObject entry : entries) {
                for (JSONObject stat : Json.list(entry, "stats")) {
                    String key = Json.str(stat, "name");
                    if (key.isEmpty() || key.equals("rank") || key.equals("playoffSeed") || key.equals("rankChange")) continue;
                    String label = Json.str(stat, "abbreviation");
                    if (label.isEmpty()) label = Json.str(stat, "shortDisplayName");
                    if (label.isEmpty()) label = Json.str(stat, "displayName");
                    if (!label.isEmpty() && !Json.str(stat, "displayValue").isEmpty()) columns.putIfAbsent(key, new Standings.Column(key, label));
                }
            }
            List<String> priorities = sport == Sport.SOCCER
                    ? List.of("gamesPlayed", "wins", "ties", "losses", "pointDifferential", "points")
                    : sport == Sport.HOCKEY ? List.of("gamesPlayed", "wins", "losses", "otLosses", "points")
                    : List.of("wins", "losses", "ties", "winPercent", "gamesBehind", "points");
            List<Standings.Column> ordered = new ArrayList<>(columns.values());
            ordered.sort(Comparator.comparingInt(column -> {
                int index = priorities.indexOf(column.name);
                return index < 0 ? Integer.MAX_VALUE : index;
            }));
            List<Standings.Row> rows = new ArrayList<>();
            for (JSONObject entry : entries) {
                JSONObject team = entry.optJSONObject("team");
                if (team == null || Json.str(team, "id").isEmpty() || Json.str(team, "displayName").isEmpty()) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (JSONObject stat : Json.list(entry, "stats")) values.put(Json.str(stat, "name"), Json.str(stat, "displayValue"));
                String rank = values.getOrDefault("rank", "");
                if (rank.isEmpty()) rank = values.getOrDefault("playoffSeed", "");
                List<String> cells = new ArrayList<>();
                for (Standings.Column column : ordered) cells.add(values.getOrDefault(column.name, ""));
                rows.add(new Standings.Row(rank, team(team, sport), cells));
            }
            rows.sort(Comparator.comparingInt(row -> rankNumber(row.rank)));
            if (!rows.isEmpty()) out.add(new Standings.Group(name, ordered, rows));
        }
        for (JSONObject child : Json.list(node, "children")) standingsGroups(child, sport, name, out);
    }

    private static int rankNumber(String rank) {
        try {
            int number = Integer.parseInt(rank);
            return number > 0 ? number : Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    static MatchDetail parseDetails(JSONObject root, Match match) {
        Map<String, String[]> statistics = new LinkedHashMap<>();
        if (match.home != null && match.away != null) {
            for (JSONObject side : Json.list(root.optJSONObject("boxscore"), "teams")) {
                String id = Json.str(Json.obj(side, "team"), "id");
                int index = match.home.team.id.equals(id) ? 1 : match.away.team.id.equals(id) ? 2 : -1;
                if (index < 0) continue;
                List<String[]> values = new ArrayList<>();
                for (JSONObject category : Json.list(side, "statistics")) {
                    if (match.sport != Sport.BASEBALL || category.optJSONArray("stats") == null) {
                        String label = Json.str(category, "label");
                        if (label.isEmpty()) label = Json.str(category, "displayName");
                        values.add(new String[] {Json.str(category, "name"), label, Json.str(category, "displayValue")});
                        continue;
                    }
                    String group = Json.str(category, "name");
                    for (JSONObject item : Json.list(category, "stats")) {
                        String name = group + "." + Json.str(item, "name");
                        // Keep the comparison concise, with batting and pitching identities distinct.
                        if (!List.of("batting.runs", "batting.hits", "batting.homeRuns", "batting.RBIs",
                                "batting.avg", "batting.OPS", "batting.strikeouts", "batting.runnersLeftOnBase",
                                "pitching.ERA", "pitching.earnedRuns", "pitching.walks", "pitching.hits",
                                "pitching.strikeouts", "fielding.errors").contains(name)) continue;
                        values.add(new String[] {name, Json.str(category, "displayName") + " / " + Json.str(item, "displayName"),
                                Json.str(item, "displayValue")});
                    }
                }
                for (String[] stat : values) {
                    String key = stat[0], label = stat[1], value = stat[2];
                    if (key.isEmpty() || label.isEmpty() || value.isEmpty()) continue;
                    String[] pair = statistics.get(key);
                    if (pair == null) {
                        pair = new String[] {label, "", "", key};
                        statistics.put(key, pair);
                    }
                    pair[index] = value;
                }
            }
        }
        List<MatchDetail.Stat> stats = new ArrayList<>();
        for (String[] pair : statistics.values()) stats.add(new MatchDetail.Stat(pair[3], pair[0], pair[1], pair[2]));
        String summary = "";
        String link = match.link;
        JSONObject article = root.optJSONObject("article");
        if (match.id.equals(Json.str(article, "gameId"))) {
            summary = Json.str(article, "description");
            String articleLink = Json.str(Json.obj(article, "links", "web"), "href");
            if (!articleLink.isEmpty()) link = articleLink;
        }
        if (summary.isEmpty()) summary = match.summary;
        return new MatchDetail(stats, summary, link);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private JSONObject json(String url, long maxAge) throws IOException {
        Http.Body body = http.get(url, maxAge);
        requestTime.set(Math.min(requestTime.get(), body.fetchedAt));
        if (body.stale) { stale.set(true); requestStale.set(true); }
        try {
            return new JSONObject(body.text());
        } catch (JSONException e) {
            throw new IOException("Unreadable response from " + url, e);
        }
    }

    private static String leagueName(Sport sport, String league, JSONObject lg) {
        Catalog.Competition known = Catalog.competition(sport, league);
        if (known != null) return known.name;
        for (String key : new String[] {"abbreviation", "shortName", "name"}) {
            String v = Json.str(lg, key);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String link(JSONObject e) {
        String fallback = "";
        for (JSONObject l : Json.list(e, "links")) {
            String rel = String.valueOf(l.optJSONArray("rel"));
            String href = Json.str(l, "href");
            if (rel.contains("\"summary\"") || rel.contains("\"event\"")) return href;
            if (fallback.isEmpty()) fallback = href;
        }
        return fallback;
    }

    private static Match find(List<Match> pool, String id) {
        for (Match m : pool) if (m.id.equals(id)) return m;
        return null;
    }

    private static boolean validSides(Match match) {
        return match.home != null && match.away != null && !match.home.team.id.isEmpty()
                && !match.away.team.id.isEmpty() && !match.home.team.name.isEmpty() && !match.away.team.name.isEmpty();
    }

    private static String day(long ms, ZoneId zone) {
        return BASIC.format(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate());
    }
}
