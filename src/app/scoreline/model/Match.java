package app.scoreline.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One fixture, normalised across sports. Built once by the ESPN parser and then treated as
 * read-only; a refresh produces a new instance.
 */
public final class Match {

    public enum State { PRE, LIVE, POST, OFF }

    public static final class Side {
        public final Team team;
        /** Display score: "2", "180/5". Empty before the start. */
        public final String score;
        /** Cricket overs ("10.3"), empty elsewhere. */
        public final String overs;
        public final boolean winner;
        /** Points per period in order (quarters, periods, innings); empty when not published. */
        public final List<String> periods = new ArrayList<>();

        public Side(Team team, String score, String overs, boolean winner) {
            this.team = team;
            this.score = score;
            this.overs = overs;
            this.winner = winner;
        }

        public int scoreInt() {
            try {
                return Integer.parseInt(score.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** A goal, red card or similar event on the match timeline. */
    public static final class Moment {
        public enum Kind { GOAL, PENALTY, OWN_GOAL, RED_CARD }

        public final Kind kind;
        public final int minute;
        public final String clock;
        public final String player;
        public final boolean home;

        public Moment(Kind kind, int minute, String clock, String player, boolean home) {
            this.kind = kind;
            this.minute = minute;
            this.clock = clock;
            this.player = player;
            this.home = home;
        }

        public boolean isGoal() {
            return kind != Kind.RED_CARD;
        }
    }

    /** A driver's place in a race or session. */
    public static final class Standing {
        public final int position;
        public final String name;
        public final String shortName;
        public final String flag;
        /** From the race timing feed when there is one: ESPN athlete id, gap to the leader ("+2.3"), team colour (0 if unknown). */
        public String id = "";
        public String gap = "";
        public int color;

        public Standing(int position, String name, String shortName, String flag) {
            this.position = position;
            this.name = name;
            this.shortName = shortName;
            this.flag = flag;
        }

        /** "Verstappen" from "M. Verstappen". */
        public String surname() {
            int dot = shortName.lastIndexOf(". ");
            return dot >= 0 ? shortName.substring(dot + 2) : shortName;
        }

        /** The timing-screen code: "VER" from "M. Verstappen", "HUL" from "N. Hülkenberg". */
        public String code() {
            String letters = Normalizer.normalize(surname(), Normalizer.Form.NFD).replaceAll("[^A-Za-z]", "");
            return letters.substring(0, Math.min(3, letters.length())).toUpperCase(Locale.ROOT);
        }
    }

    public boolean updatesDelayed;
    public long checkedAt;
    public String id = "";
    public Sport sport = Sport.SOCCER;
    /** ESPN league slug ("eng.1", "nba", "f1") or cricket series id. */
    public String league = "";
    public String leagueName = "";
    /** Event name for races ("United States Grand Prix"), match title for cricket ("2nd T20I"). */
    public String title = "";
    /** Session for races ("Race", "Qualifying"). */
    public String session = "";
    public long start;
    /** Published end timestamp, primarily for multi-day cricket; 0 when absent. */
    public long end;
    public State state = State.PRE;
    /** Live clock: "67'", "HT", "Q4 2:31", "10.3 ov", "Lap 34/56". */
    public String clock = "";
    /** Short status from the source: "FT", "Final", "Postponed". */
    public String status = "";
    public int period;
    /** Race or sprint distance in laps, when the timing feed gives it; 0 when unknown. */
    public int laps;
    /** Overtime period length from competition/season rules; 0 when unknown. */
    public int overtimeMinutes;
    /** Seconds left in the period for clock-down sports; elapsed seconds for football. */
    public double clockSeconds;
    public Side home;
    public Side away;
    /** One-line context: cricket equation, result text. */
    public String summary = "";
    /** Cricket: overs per innings (20, 50), 0 for unlimited. */
    public int oversLimit;
    public String battingTeamId = "";
    public String venue = "";
    public String link = "";
    public final List<Moment> moments = new ArrayList<>();
    public final List<Standing> standings = new ArrayList<>();

    /** Stable identity across refreshes. */
    public String key() {
        return sport.path + "/" + league + "/" + id;
    }

    public boolean involves(String teamId) {
        return teamId != null && ((home != null && teamId.equals(home.team.id)) || (away != null && teamId.equals(away.team.id)));
    }

    /** The side for {@code teamId}, or home when it doesn't take part. */
    public Side sideOf(String teamId) {
        if (away != null && teamId != null && teamId.equals(away.team.id)) return away;
        return home;
    }

    public Side opponentOf(String teamId) {
        return sideOf(teamId) == home ? away : home;
    }

    public boolean isLive() {
        return state == State.LIVE;
    }

    /** A Grand Prix or sprint, run over a set number of laps, as opposed to practice or qualifying. */
    public boolean isRace() {
        return sport == Sport.RACING && ("Race".equals(session) || "Sprint".equals(session));
    }

    public boolean hasScore() {
        return home != null && away != null && (!home.score.isEmpty() || !away.score.isEmpty());
    }

    /** Running score as a comparable string, used to spot goals and runs between refreshes. */
    public String scoreline() {
        return home == null || away == null ? "" : home.score + "-" + away.score;
    }
}
