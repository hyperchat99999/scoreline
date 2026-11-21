import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.SportsEventTracker;
import app.scoreline.model.SportsEventTracker.Kind;
import app.scoreline.model.Team;

public final class SportsEventTest {
    private static int checks;

    public static void main(String[] args) {
        for (Sport sport : Sport.values()) {
            SportsEventTracker tracker = new SportsEventTracker();
            Match pre = fixture(sport, "", "");
            pre.state = Match.State.PRE;
            pre.period = 0;
            same(tracker.observe(pre, 1, false, true), Kind.NONE, sport + " initial bind");
            Match live = fixture(sport, sport == Sport.CRICKET ? "0/0" : "0", sport == Sport.CRICKET ? "0/0" : "0");
            same(tracker.observe(live, 2, false, true), Kind.KICKOFF, sport + " kickoff from empty pre scores");
            same(tracker.observe(live, 2, false, true), Kind.NONE, sport + " repeated source sample");
            live.state = Match.State.POST;
            same(tracker.observe(live, 3, false, true), Kind.FINISH, sport + " final transition");
            live.state = Match.State.LIVE;
            same(tracker.observe(live, 4, false, true), Kind.NONE, sport + " corrected final state");
            same(tracker.observe(live, 5, false, true), Kind.NONE, sport + " correction settles");
            live.state = Match.State.POST;
            same(tracker.observe(live, 6, false, true), Kind.NONE, sport + " final never celebrated twice");
        }
        goals();
        scores();
        cricket();
        racing();
        staleAndMotion();
        System.out.println("PASS: " + checks + " all-sport event, kickoff, correction, stale and motion-baseline checks");
    }

    private static void goals() {
        SportsEventTracker tracker = new SportsEventTracker();
        tracker.observe(fixture(Sport.SOCCER, "0", "0"), 1, false, true);
        Match goal = fixture(Sport.SOCCER, "1", "0");
        same(tracker.observe(goal, 2, false, true), Kind.NONE, "Score-only soccer change is not an invented goal");
        goal.moments.add(new Match.Moment(Match.Moment.Kind.GOAL, 67, "67'", "TEST scorer", true));
        same(tracker.observe(goal, 3, false, true), Kind.NONE, "Late moment without a new score does not replay");
        Match second = fixture(Sport.SOCCER, "2", "0");
        second.moments.addAll(goal.moments);
        second.moments.add(new Match.Moment(Match.Moment.Kind.GOAL, 70, "70'", "TEST scorer", true));
        same(tracker.observe(second, 4, false, true), Kind.GOAL, "Fresh explicit new goal and score align");
        same(tracker.observe(goal, 5, false, true), Kind.NONE, "Score correction has no celebration");
        same(tracker.observe(second, 6, false, true), Kind.NONE, "Restoring corrected score does not replay a goal");
        same(tracker.observe(second, 7, false, true), Kind.NONE, "Correction recovery waits for stable source");
        Match third = fixture(Sport.SOCCER, "3", "0");
        third.moments.addAll(second.moments);
        third.moments.add(new Match.Moment(Match.Moment.Kind.GOAL, 72, "72'", "TEST scorer", true));
        same(tracker.observe(third, 8, false, true), Kind.GOAL, "A subsequent real goal can animate after recovery");
    }

    private static void scores() {
        for (Sport sport : new Sport[] {Sport.BASKETBALL, Sport.FOOTBALL, Sport.HOCKEY, Sport.BASEBALL}) {
            SportsEventTracker tracker = new SportsEventTracker();
            tracker.observe(fixture(sport, "10", "8"), 1, false, true);
            String next = sport == Sport.FOOTBALL ? "13" : sport == Sport.HOCKEY ? "11" : "12";
            same(tracker.observe(fixture(sport, next, "8"), 2, false, true), Kind.SCORE, sport + " bounded score change");
            same(tracker.observe(fixture(sport, "40", "8"), 3, false, true), Kind.NONE, sport + " large catch-up is not a single event");
            same(tracker.observe(fixture(sport, "2", "8"), 4, false, true), Kind.NONE, sport + " reset is silent");
        }
        SportsEventTracker tracker = new SportsEventTracker();
        tracker.observe(fixture(Sport.HOCKEY, "", "1"), 1, false, true);
        same(tracker.observe(fixture(Sport.HOCKEY, "1", "1"), 2, false, true), Kind.NONE, "Missing score must not act as zero");
    }

    private static void cricket() {
        SportsEventTracker tracker = new SportsEventTracker();
        Match old = fixture(Sport.CRICKET, "184/3", "150/4");
        tracker.observe(old, 1, false, true);
        Match runs = fixture(Sport.CRICKET, "184/3", "154/4");
        same(tracker.observe(runs, 2, false, true), Kind.SCORE, "Cricket runs stay in the same innings");
        Match wicket = fixture(Sport.CRICKET, "184/3", "154/5");
        same(tracker.observe(wicket, 3, false, true), Kind.WICKET, "One observed wicket can animate");
        Match reset = fixture(Sport.CRICKET, "0/0", "154/5");
        reset.period = 3;
        reset.battingTeamId = "home";
        same(tracker.observe(reset, 4, false, true), Kind.NONE, "New innings never invent a wicket or runs");
        same(tracker.observe(reset, 5, false, true), Kind.NONE, "New innings settles");
        Match newRuns = fixture(Sport.CRICKET, "4/0", "154/5");
        newRuns.period = 3;
        newRuns.battingTeamId = "home";
        same(tracker.observe(newRuns, 6, false, true), Kind.SCORE, "Next innings can animate after a stable baseline");
        SportsEventTracker unknown = new SportsEventTracker();
        Match bad = fixture(Sport.CRICKET, "184/3", "150/4");
        bad.battingTeamId = "unknown";
        unknown.observe(bad, 1, false, true);
        bad = fixture(Sport.CRICKET, "184/3", "150/5");
        bad.battingTeamId = "unknown";
        same(unknown.observe(bad, 2, false, true), Kind.NONE, "Unknown batting side is not inferred");
    }

    private static void racing() {
        SportsEventTracker tracker = new SportsEventTracker();
        Match first = fixture(Sport.RACING, "", "");
        tracker.observe(first, 1, false, true);
        Match lap = fixture(Sport.RACING, "", "");
        lap.period++;
        same(tracker.observe(lap, 2, false, true), Kind.LAP, "F1 lap increment");
        Match leader = fixture(Sport.RACING, "", "");
        leader.period++;
        leader.standings.clear();
        leader.standings.add(new Match.Standing(1, "TEST Two", "T. Two", ""));
        same(tracker.observe(leader, 3, false, true), Kind.LEAD, "F1 source leader change");
        Match reset = fixture(Sport.RACING, "", "");
        reset.period = 1;
        same(tracker.observe(reset, 4, false, true), Kind.NONE, "F1 lap reset is silent");
        SportsEventTracker practice = new SportsEventTracker();
        first.session = "Practice 1";
        practice.observe(first, 1, false, true);
        lap.session = "Practice 1";
        same(practice.observe(lap, 2, false, true), Kind.NONE, "Practice ordering does not claim a race lap event");
    }

    private static void staleAndMotion() {
        SportsEventTracker tracker = new SportsEventTracker();
        Match first = fixture(Sport.BASKETBALL, "10", "8");
        tracker.observe(first, 10, false, true);
        same(tracker.observe(fixture(Sport.BASKETBALL, "12", "8"), 9, false, true), Kind.NONE, "Older sample is ignored");
        same(tracker.observe(fixture(Sport.BASKETBALL, "12", "8"), 11, true, true), Kind.NONE, "Stale sample is silent");
        same(tracker.observe(fixture(Sport.BASKETBALL, "12", "8"), 12, false, true), Kind.NONE, "Fresh catch-up after stale is silent");
        same(tracker.observe(fixture(Sport.BASKETBALL, "12", "8"), 13, false, true), Kind.NONE, "Stale recovery settles");
        same(tracker.observe(fixture(Sport.BASKETBALL, "14", "8"), 14, false, false), Kind.NONE, "Motion off still consumes the event baseline");
        same(tracker.observe(fixture(Sport.BASKETBALL, "14", "8"), 15, false, true), Kind.NONE, "Re-enabling motion never replays an event");
        same(tracker.observe(fixture(Sport.BASKETBALL, "16", "8"), 16, false, true), Kind.SCORE, "Subsequent fresh score can animate");
    }

    private static Match fixture(Sport sport, String home, String away) {
        Match match = new Match();
        match.id = "TEST";
        match.league = "test";
        match.sport = sport;
        match.state = Match.State.LIVE;
        match.period = 2;
        match.home = new Match.Side(new Team("home", "TEST Home", "Home", "HOM", "", "", ""), home, "20", false);
        match.away = new Match.Side(new Team("away", "TEST Away", "Away", "AWY", "", "", ""), away, "16.2", false);
        match.battingTeamId = "away";
        if (sport == Sport.RACING) {
            match.session = "Race";
            match.period = 34;
            match.laps = 56;
            match.home = null;
            match.away = null;
            match.standings.add(new Match.Standing(1, "TEST One", "T. One", ""));
        }
        return match;
    }

    private static void same(Kind actual, Kind expected, String message) {
        checks++;
        if (actual != expected) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
}
