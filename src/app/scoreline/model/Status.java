package app.scoreline.model;

/**
 * The words for where a match is, shared by every surface (app pills, the island chip and its
 * capsule, notification text) so one match reads the same everywhere and every sport follows the
 * same pattern: a full form in plain words, and a compact form that fits after a score.
 *
 * <p>The parser's {@link Match#clock} is a terse token ("End Q3", "▲5", "Break"); this class is
 * the only place that turns it into display text.
 */
public final class Status {
    private Status() {}

    /** The island chip and Live Update text hold about nine characters. */
    public static final int CHIP = 9;

    /**
     * Plain words for a live match: "67'", "Half-time", "Q3 5:12", "End of Q3", "Top 5th",
     * "10.3 ov", "Lap 34/56". Finished and other states give the result word.
     */
    public static String full(Match m) {
        if (m.state == Match.State.POST) return finished(m);
        if (m.state == Match.State.OFF) return m.status;
        if (m.state == Match.State.PRE) return "";
        String c = m.clock;
        switch (m.sport) {
            case SOCCER:
                if ("HT".equals(c)) return "Half-time";
                if ("Pens".equals(c)) return "Penalties";
                if ("Break".equals(c)) return m.period >= 4 ? "Before penalties" : "Before extra time";
                return c;
            case BASKETBALL:
            case FOOTBALL:
            case HOCKEY:
                if ("Half".equals(c)) return "Half-time";
                if ("SO".equals(c)) return "Shootout";
                if (c.startsWith("End ")) return "End of " + c.substring(4);
                return c;
            case BASEBALL: {
                String n = ordinal(m.period);
                if (c.startsWith("▲")) return "Top " + n;
                if (c.startsWith("▼")) return "Bottom " + n;
                if (c.startsWith("Mid")) return "Mid " + n;
                if (c.startsWith("End")) return "End of " + n;
                return c;
            }
            default:
                return c.isEmpty() ? m.status : c;
        }
    }

    /**
     * The period alone, short enough to follow a score in the chip: "67'", "HT", "Q3", "OT", "P2",
     * "▲5". Breaks show the period just played.
     */
    public static String compact(Match m) {
        if (m.state == Match.State.POST) return finished(m);
        if (m.state != Match.State.LIVE) return "";
        String c = m.clock;
        switch (m.sport) {
            case SOCCER:
                if ("Break".equals(c)) return m.period >= 4 ? "Pens" : "ET";
                return c;
            case BASKETBALL:
            case FOOTBALL:
            case HOCKEY: {
                if ("Half".equals(c)) return "HT";
                if (c.startsWith("End ")) return c.substring(4);
                int space = c.indexOf(' ');
                return space < 0 ? c : c.substring(0, space);
            }
            case BASEBALL:
                if (c.startsWith("Mid") || c.startsWith("End")) return ordinal(m.period);
                return c;
            default:
                return c;
        }
    }

    /** "FT" in football, "Final" in every other sport. */
    public static String finished(Match m) {
        return m.sport == Sport.SOCCER ? "FT" : "Final";
    }

    /** {@code a + " " + b}, or just {@code a} when both won't fit in the chip. */
    public static String fit(String a, String b) {
        if (b == null || b.isEmpty()) return a;
        String both = a + " " + b;
        return both.length() <= CHIP ? both : a;
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th"
                : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }
}
