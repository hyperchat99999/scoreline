import app.scoreline.model.*;
public class StatusTest {
    public static void main(String[] args) {
        Match m = TimelineTest.match(Sport.SOCCER); m.period=2; m.clock="67'";
        same(Status.full(m), "67'"); same(Status.compact(m), "67'");
        same(Timeline.of(m).phase(m), "67' · 2nd half");
        m.clock="HT"; m.period=1; same(Status.full(m), "Half-time"); same(Status.compact(m), "HT");
        same(Timeline.of(m).phase(m), "Half-time");
        m.clock="Break"; m.period=2; same(Status.full(m), "Before extra time"); same(Status.compact(m), "ET");
        m.clock="Pens"; m.period=5; same(Status.full(m), "Penalties");

        for (Sport s : new Sport[] {Sport.BASKETBALL, Sport.FOOTBALL}) {
            m = TimelineTest.match(s); m.period=3; m.clock="Q3 5:12";
            same(Status.full(m), "Q3 5:12"); same(Status.compact(m), "Q3");
            m.clock="End Q3"; same(Status.full(m), "End of Q3"); same(Status.compact(m), "Q3");
            m.clock="Half"; same(Status.full(m), "Half-time"); same(Status.compact(m), "HT");
        }
        m = TimelineTest.match(Sport.HOCKEY); m.period=2; m.clock="P2 12:40";
        same(Status.full(m), "P2 12:40"); same(Status.compact(m), "P2");
        m.clock="End P2"; same(Status.full(m), "End of P2"); same(Status.compact(m), "P2");
        m.clock="SO"; same(Status.full(m), "Shootout"); same(Status.compact(m), "SO");

        m = TimelineTest.match(Sport.BASEBALL); m.period=5; m.clock="▲5";
        same(Status.full(m), "Top 5th"); same(Status.compact(m), "▲5");
        m.clock="▼5"; same(Status.full(m), "Bottom 5th");
        m.clock="Mid 5"; same(Status.full(m), "Mid 5th"); same(Status.compact(m), "5th");
        m.period=1; m.clock="End 1"; same(Status.full(m), "End of 1st");
        m.period=12; m.clock="▲12"; same(Status.full(m), "Top 12th");

        m = TimelineTest.match(Sport.CRICKET); m.oversLimit=20; m.period=2; m.battingTeamId="away"; m.clock="10.3 ov";
        same(Timeline.of(m).phase(m), "10.3 ov · 2nd innings");

        for (Sport s : Sport.values()) {
            m = TimelineTest.match(s); m.state=Match.State.POST;
            same(Status.full(m), s == Sport.SOCCER ? "FT" : "Final");
        }
        same(Status.fit("112–108", "Q4"), "112–108");
        same(Status.fit("2–1", "67'"), "2–1 67'");
        System.out.println("PASS: shared status words across all sports");
    }
    static void same(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
    }
}
