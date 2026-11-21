package app.scoreline.widgets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.scoreline.model.Match;
import app.scoreline.model.MatchRef;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;

/** Fixture-driven source, identity, selection and saved-score freshness checks. */
public final class WidgetTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        roundTrip();
        corruption();
        selection();
        freshness();
        System.out.println("PASS: " + checks + " widget codec/selection/freshness checks");
    }

    private static Match fixture(Sport sport, String id, Match.State state, long start) {
        Match match = new Match();
        match.sport = sport; match.id = id; match.league = sport == Sport.RACING ? "f1" : "test.league";
        match.leagueName = "TEST league"; match.title = "TEST event"; match.state = state; match.start = start;
        match.home = new Match.Side(new Team("home", "TEST Home", "Home", "HOM", "https://example.com/home.png", "", "123456"), "0", "10.3", false);
        match.away = new Match.Side(new Team("away", "TEST Away", "Away", "AWY", "", "", "654321"), "", "", true);
        match.home.periods.add("0"); match.away.periods.add("2"); match.period = 2; match.laps = 57;
        match.oversLimit = 20; match.battingTeamId = "home"; match.clock = "10.3 ov"; match.clockSeconds = 123.5;
        match.session = "Qualifying"; match.checkedAt = 8_000; match.venue = "TEST venue";
        Match.Standing leader = new Match.Standing(1, "TEST Driver", "T. Driver", "GB");
        leader.id = "driver1"; leader.gap = "+1.2"; leader.color = 0xff123456;
        match.standings.add(leader);
        return match;
    }

    private static void roundTrip() {
        for (Sport sport : Sport.values()) {
            Match input = fixture(sport, sport == Sport.RACING ? "999:1234" : "999", Match.State.LIVE, 12_000);
            WidgetSnapshot output = WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(new WidgetSnapshot(List.of(input), 10_000, false)));
            check(output.matches.size() == 1, "Every sport survives a restart");
            Match match = output.matches.get(0);
            check(match.key().equals(input.key()), "Exact sport/league/event/session identity survives");
            check(MatchRef.from(match).encode().equals(MatchRef.from(input).encode()), "Cold deep link keeps full descriptor");
            check(match.home.score.equals("0") && match.away.score.isEmpty(), "A source zero differs from an unpublished score");
            check(match.home.overs.equals("10.3") && match.home.periods.equals(List.of("0")), "Cricket overs and periods survive");
            check(match.period == 2 && match.clockSeconds == 123.5 && match.laps == 57, "Sport-specific clock rules survive");
            check(match.standings.get(0).id.equals("driver1") && match.standings.get(0).gap.equals("+1.2"), "F1 published driver order survives");
            check(output.updatedAt == 10_000 && !output.stale && match.checkedAt == 8_000, "Refresh time is distinct from source time");
            input.home.periods.add("999");
            check(match.home.periods.size() == 1, "Published values do not share mutable source lists");
            boolean immutable = false;
            try { output.matches.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "Snapshot list is immutable");
        }
    }

    private static void corruption() throws Exception {
        check(WidgetSnapshotCodec.decode("not JSON").matches.isEmpty(), "Corruption starts empty");
        check(WidgetSnapshotCodec.decode("{\"version\":2}").matches.isEmpty(), "Unsupported versions fail closed");
        Match input = fixture(Sport.SOCCER, "999", Match.State.PRE, 12_000);
        JSONObject root = new JSONObject(WidgetSnapshotCodec.encode(new WidgetSnapshot(List.of(input), 10_000, true)));
        JSONArray array = root.getJSONArray("matches");
        array.put("bad row"); array.put(new JSONObject().put("ref", "racing/f1/../bad"));
        check(WidgetSnapshotCodec.decode(root.toString()).matches.size() == 1, "Malformed rows do not discard good cached matches");
        array.getJSONObject(0).put("state", "INVENTED");
        check(WidgetSnapshotCodec.decode(root.toString()).matches.isEmpty(), "Unknown state never invents a scheduled match");
        List<Match> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(fixture(Sport.SOCCER, "match" + i, Match.State.PRE, i));
        check(WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(new WidgetSnapshot(many, 1, false))).matches.size() == 24, "Persisted payload is bounded");
    }

    private static void selection() {
        Match pinnedLive = fixture(Sport.SOCCER, "pinnedLive", Match.State.LIVE, 20);
        Match live = fixture(Sport.CRICKET, "live", Match.State.LIVE, 10);
        Match pinnedNext = fixture(Sport.RACING, "next:race", Match.State.PRE, 50);
        Match next = fixture(Sport.BASKETBALL, "next", Match.State.PRE, 30);
        Match old = fixture(Sport.FOOTBALL, "old", Match.State.POST, 1);
        Match result = fixture(Sport.HOCKEY, "result", Match.State.POST, 5);
        Match off = fixture(Sport.BASEBALL, "off", Match.State.OFF, 8);
        Set<String> pins = new HashSet<>(List.of(pinnedLive.key(), pinnedNext.key()));
        List<Match> shuffled = new ArrayList<>(List.of(off, next, old, live, result, pinnedNext, pinnedLive));
        List<Match> ordered = WidgetSelection.ordered(shuffled, pins, 20);
        check(ordered.equals(List.of(pinnedLive, live, pinnedNext, next, result, old, off)), "Priority is pinned live, live, pinned upcoming, upcoming, latest result");
        check(WidgetSelection.selected(ordered, next.key()) == next, "Saved selection uses identity, not unstable index");
        Collections.reverse(shuffled);
        check(WidgetSelection.selected(WidgetSelection.ordered(shuffled, pins, 20), next.key()) == next, "Source reorder does not move a selected instance");
        check(WidgetSelection.selected(ordered, "removed") == pinnedLive, "Removed selection falls back to the best candidate");
        check(WidgetSelection.next(ordered, off.key()) == pinnedLive, "Cycle wraps independently");
        check(WidgetSelection.selected(List.of(), "") == null && WidgetSelection.next(List.of(), "") == null, "Empty widgets have no fabricated candidate");
        Match sameIdElsewhere = fixture(Sport.SOCCER, "pinnedLive", Match.State.LIVE, 20);
        sameIdElsewhere.league = "another.league";
        check(WidgetSelection.ordered(List.of(pinnedLive, sameIdElsewhere), pins).size() == 2, "Provider event IDs do not collapse across leagues");
        Match fresher = fixture(Sport.SOCCER, "pinnedLive", Match.State.POST, 20); fresher.checkedAt++;
        check(WidgetSelection.ordered(List.of(fresher, pinnedLive), pins).get(0) == fresher, "Older duplicate cannot overwrite a fresher result");
        Match unknownTime = fixture(Sport.SOCCER, "undated", Match.State.PRE, 0);
        check(WidgetSelection.ordered(List.of(unknownTime, next), Set.of(), 20).get(0) == next, "An undated fixture does not replace the next known kickoff");
        long now = 24 * 3600_000L;
        Match missedStart = fixture(Sport.SOCCER, "missed", Match.State.PRE, now - 4 * 3600_000L);
        Match knownNext = fixture(Sport.SOCCER, "nextReal", Match.State.PRE, now + 3600_000L);
        Match recent = fixture(Sport.SOCCER, "recent", Match.State.POST, now - 2 * 3600_000L);
        check(WidgetSelection.ordered(List.of(missedStart, recent, knownNext), Set.of(missedStart.key()), now)
                .equals(List.of(knownNext, recent, missedStart)), "An overdue pinned PRE cannot outrank a real next fixture or result");
        check(WidgetSelection.overdue(missedStart, now) && missedStart.state == Match.State.PRE, "Overdue display is derived without mutating provider state");
        missedStart.start = now - 2 * 3600_000L;
        check(!WidgetSelection.overdue(missedStart, now), "A recently delayed kickoff retains the tracking tolerance");
    }

    private static void freshness() {
        Match live = fixture(Sport.SOCCER, "live", Match.State.LIVE, 1); live.checkedAt = 1_000;
        WidgetSnapshot fresh = new WidgetSnapshot(List.of(live), 1_000, false);
        check(!fresh.delayed(live, 1_001), "Fresh source is current");
        check(fresh.delayed(live, 1_000 + 5 * 60_000L + 1), "A live launcher score ages into delayed state");
        live.state = Match.State.PRE;
        check(!fresh.delayed(live, 1_000 + 30 * 60_000L), "Upcoming snapshots have a longer freshness window");
        check(new WidgetSnapshot(List.of(live), 1_000, true).delayed(live, 1_001), "Offline source is always labeled delayed");
        live.updatesDelayed = true;
        check(fresh.delayed(live, 1_001), "Per-match provider failure cannot be hidden by a fresh feed");
        check(WidgetSnapshot.EMPTY.delayed(null, 10_000), "Missing update time is never advertised as fresh");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
