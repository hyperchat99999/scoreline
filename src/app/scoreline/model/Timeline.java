package app.scoreline.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How far through a match we are, in the sport's own units: minutes split into halves or
 * quarters, half-innings, or balls per innings. Shared by the island and the in-app progress line.
 */
public final class Timeline {
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Lengths of each period; sums to {@link #max}. */
    public final int[] segments;
    /** What each period is called on screen ("1st half", "Q3", "P2"); "" when it has no name. */
    public final String[] labels;
    public final int max;
    public final int value;
    /** No reliable measure (Test cricket, races): show activity instead. */
    public final boolean indeterminate;
    public final List<Goal> goals;

    /** A goal placed on the timeline. */
    public static final class Goal {
        /** Position in the same units as {@link #value}. */
        public final int at;
        public final boolean home;
        public final String clock;

        Goal(int at, boolean home, String clock) {
            this.at = at;
            this.home = home;
            this.clock = clock;
        }
    }

    private Timeline(int[] segments, String[] labels, int value, boolean indeterminate, List<Goal> goals) {
        this.segments = segments;
        this.labels = labels;
        int sum = 0;
        for (int s : segments) sum += s;
        this.max = Math.max(1, sum);
        this.value = Math.max(0, Math.min(value, max));
        this.indeterminate = indeterminate;
        this.goals = goals;
    }

    public float fraction() {
        return (float) value / max;
    }

    /**
     * Where a live match is, in words, for the island and screen readers: "67' · 2nd half",
     * "Half-time", "10.3 ov · 2nd innings", "Q3 5:12", "Top 5th". A clock that doesn't already
     * name its period (a football minute, cricket overs) gets the period added.
     */
    public String phase(Match m) {
        String words = Status.full(m);
        String period = labels[current()];
        boolean bare = m.isLive() && words.equals(m.clock) && (m.sport == Sport.SOCCER || m.sport == Sport.CRICKET);
        return !bare || period.isEmpty() || indeterminate ? words : words + " · " + period;
    }

    /** The period being played (the last one once the match is over). */
    public int current() {
        int end = 0;
        for (int i = 0; i < segments.length; i++) {
            end += segments[i];
            if (value < end) return i;
        }
        return segments.length - 1;
    }

    public static Timeline of(Match m) {
        List<Goal> goals = new ArrayList<>();
        int[] segments;
        String[] labels = null;
        int value;
        boolean indeterminate = false;
        switch (m.sport) {
            case SOCCER: {
                int minute = minute(m.clock);
                boolean extra = m.period > 2;
                segments = extra ? new int[] {45, 45, 15, 15} : new int[] {45, 45};
                labels = extra ? new String[] {"1st half", "2nd half", "ET", "ET"} : new String[] {"1st half", "2nd half"};
                value = "Break".equals(m.clock) ? (m.period >= 4 ? 120 : m.period == 3 ? 105 : 90)
                        : "HT".equals(m.clock) && m.period >= 3 ? 105 : minute;
                if ("Pens".equals(m.clock)) indeterminate = m.isLive();
                for (Match.Moment g : m.moments) {
                    if (g.isGoal()) goals.add(new Goal(Math.max(1, Math.min(g.minute, extra ? 120 : 90)), g.home, g.clock));
                }
                break;
            }
            case BASKETBALL: {
                int len = "wnba".equals(m.league) ? 10 : 12;
                segments = periods(m.period, len, 4, 5);
                labels = names(segments.length, 4, "Q");
                value = clockDown(m, len, 4, 5);
                break;
            }
            case FOOTBALL:
                if (m.period > 4 && m.overtimeMinutes <= 0) {
                    segments = new int[] {1}; value = 0; indeterminate = m.isLive(); break;
                }
                segments = periods(m.period, 15, 4, m.overtimeMinutes);
                labels = names(segments.length, 4, "Q");
                value = clockDown(m, 15, 4, m.overtimeMinutes);
                break;
            case HOCKEY:
                if ("SO".equals(m.clock) || (m.period > 3 && m.overtimeMinutes <= 0)) {
                    segments = new int[] {1}; value = 0; indeterminate = m.isLive(); break;
                }
                segments = periods(m.period, 20, 3, m.overtimeMinutes);
                labels = names(segments.length, 3, "P");
                value = clockDown(m, 20, 3, m.overtimeMinutes);
                break;
            case BASEBALL: {
                segments = new int[Math.max(9, m.period)];
                Arrays.fill(segments, 2);
                labels = new String[segments.length];
                for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(i + 1);
                int half = m.clock.startsWith("End") ? 2
                        : m.clock.startsWith("▼") || m.clock.startsWith("Mid") ? 1 : 0;
                value = Math.max(0, (m.period - 1) * 2 + half);
                break;
            }
            case CRICKET: {
                Match.Side batting = m.involves(m.battingTeamId) ? m.sideOf(m.battingTeamId) : null;
                int played = batting == null ? -1 : balls(batting.overs);
                if (m.oversLimit <= 0 || m.period < 1 || m.period > 2 || played < 0) {
                    segments = new int[] {1};
                    value = 0;
                    indeterminate = m.isLive();
                    break;
                }
                int balls = m.oversLimit * 6;
                segments = new int[] {balls, balls};
                labels = new String[] {"1st innings", "2nd innings"};
                value = (m.period >= 2 ? balls : 0) + played;
                break;
            }
            default: // Racing: lap counts aren't published, so show activity rather than a guess.
                segments = new int[] {1};
                value = 0;
                indeterminate = m.isLive();
        }
        if (m.state == Match.State.PRE) value = 0;
        if (m.state == Match.State.POST) value = Integer.MAX_VALUE;
        if (labels == null) {
            labels = new String[segments.length];
            Arrays.fill(labels, "");
        }
        return new Timeline(segments, labels, value, indeterminate, goals);
    }

    /** "54'" → 54, "90+3'" → 90, "HT" → 45. */
    public static int minute(String clock) {
        if (clock.startsWith("HT")) return 45;
        Matcher d = DIGITS.matcher(clock);
        return d.find() ? Integer.parseInt(d.group()) : 0;
    }

    private static int clockDown(Match m, int periodMinutes, int regulation, int overtimeMinutes) {
        if (m.period <= 0) return 0;
        int before = 0;
        for (int i = 1; i < m.period; i++) before += i <= regulation ? periodMinutes : overtimeMinutes;
        int length = m.period <= regulation ? periodMinutes : overtimeMinutes;
        int left = (int) Math.ceil(m.clockSeconds / 60.0);
        return before + Math.max(0, length - left);
    }

    /** "Q1".."Q4", then "OT", "OT2"... */
    private static String[] names(int count, int regulation, String prefix) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = i < regulation ? prefix + (i + 1) : i == regulation ? "OT" : "OT" + (i - regulation + 1);
        return out;
    }

    private static int[] periods(int period, int periodMinutes, int regulation, int overtimeMinutes) {
        int[] out = new int[Math.max(regulation, period)];
        for (int i = 0; i < out.length; i++) out[i] = i < regulation ? periodMinutes : overtimeMinutes;
        return out;
    }

    /** "10.3" overs → 63 balls. */
    private static int balls(String overs) {
        if (overs == null || !overs.matches("[0-9]+(?:\\.[0-5])?")) return -1;
        String[] p = overs.split("\\.");
        try {
            return Integer.parseInt(p[0]) * 6 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
