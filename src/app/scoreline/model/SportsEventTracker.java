package app.scoreline.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Conservative, presentation-only transitions. Never infers a score from missing source fields. */
public final class SportsEventTracker {
    public enum Kind { NONE, KICKOFF, GOAL, SCORE, WICKET, LAP, LEAD, FINISH }

    private final LinkedHashMap<String, Mark> previous = new LinkedHashMap<>(32, .75f, true);

    /** A fresh sample is remembered even when motion is disabled, avoiding catch-up celebrations. */
    public Kind observe(Match match, long sourceTime, boolean stale, boolean animate) {
        if (match == null || match.id.isEmpty()) return Kind.NONE;
        Mark old = previous.get(match.key());
        if (stale || match.updatesDelayed) {
            if (old != null) old.blocked = true;
            return Kind.NONE;
        }
        if (old != null && sourceTime <= old.time) return Kind.NONE;
        Mark now = new Mark(match, sourceTime);
        if (old != null) {
            now.goals.addAll(old.goals);
            now.seenLive |= old.seenLive;
            now.seenFinal |= old.seenFinal;
        }
        previous.put(match.key(), now);
        while (previous.size() > 64) previous.remove(previous.keySet().iterator().next());
        if (old == null || old.time <= 0 || sourceTime <= 0 || !old.sameParticipants(now)) return Kind.NONE;

        // A correction/reset must settle before another change can produce motion.
        if (old.blocked) {
            now.blocked = !old.sameState(now);
            return Kind.NONE;
        }
        // Scheduled score fields are normally empty, and cricket starts in a new innings.
        // The source's first explicit pre -> live transition is sufficient for kickoff only.
        if (now.state == Match.State.LIVE && old.state == Match.State.PRE && !old.seenLive)
            return animate ? Kind.KICKOFF : Kind.NONE;
        if (regressed(old, now, match.sport)) {
            now.blocked = true;
            return Kind.NONE;
        }
        if (!animate) return Kind.NONE;
        if (now.state == Match.State.POST && old.state == Match.State.LIVE && !old.seenFinal) return Kind.FINISH;
        if (old.state != Match.State.LIVE || now.state != Match.State.LIVE) return Kind.NONE;

        if (match.sport == Sport.RACING) {
            if (!match.isRace() || now.period < old.period || match.laps <= 0 || now.period > match.laps + 1) return Kind.NONE;
            if (!old.leader.isEmpty() && !now.leader.isEmpty() && !old.leader.equals(now.leader)) return Kind.LEAD;
            return now.period > old.period && now.period - old.period <= 3 ? Kind.LAP : Kind.NONE;
        }
        if (match.sport == Sport.CRICKET) return cricket(old, now);
        int home = gain(old.home, now.home);
        int away = gain(old.away, now.away);
        if (home < 0 || away < 0 || home + away == 0) return Kind.NONE;
        int total = home + away;
        if (match.sport == Sport.SOCCER) {
            if (total != 1) return Kind.NONE;
            for (Match.Moment moment : match.moments) {
                if (moment.isGoal() && !old.goals.contains(goal(moment)) && (moment.home ? home : away) == 1) return Kind.GOAL;
            }
            return Kind.NONE;
        }
        if (match.sport == Sport.HOCKEY) return total == 1 ? Kind.SCORE : Kind.NONE;
        if (match.sport == Sport.FOOTBALL) return total == 2 || total == 3 || total == 6 || total == 7 || total == 8 ? Kind.SCORE : Kind.NONE;
        return total <= 4 ? Kind.SCORE : Kind.NONE;
    }

    private static boolean regressed(Mark old, Mark now, Sport sport) {
        if (old.state == Match.State.POST && now.state != Match.State.POST) return true;
        if (old.state == Match.State.LIVE && now.state == Match.State.PRE) return true;
        if (old.period > 0 && now.period > 0 && now.period < old.period) return true;
        if (sport == Sport.CRICKET) {
            if (old.period != now.period || !old.batting.equals(now.batting)) return true;
            int[] before = innings(old.home), after = innings(now.home);
            int[] beforeAway = innings(old.away), afterAway = innings(now.away);
            return decreases(before, after) || decreases(beforeAway, afterAway);
        }
        if (sport == Sport.RACING) return false;
        return gain(old.home, now.home) < 0 || gain(old.away, now.away) < 0;
    }

    private static Kind cricket(Mark old, Mark now) {
        if (now.period <= 0 || now.batting.isEmpty()) return Kind.NONE;
        boolean home = now.batting.equals(now.homeId);
        if (!home && !now.batting.equals(now.awayId)) return Kind.NONE;
        if (!(home ? old.away.equals(now.away) : old.home.equals(now.home))) return Kind.NONE;
        int beforeBalls = balls(home ? old.homeOvers : old.awayOvers);
        int afterBalls = balls(home ? now.homeOvers : now.awayOvers);
        if (beforeBalls < 0 || afterBalls < beforeBalls) return Kind.NONE;
        int[] before = innings(home ? old.home : old.away), after = innings(home ? now.home : now.away);
        if (before == null || after == null || decreases(before, after)) return Kind.NONE;
        if (after[1] - before[1] == 1) return Kind.WICKET;
        int runs = after[0] - before[0];
        return after[1] == before[1] && runs > 0 && runs <= 6 ? Kind.SCORE : Kind.NONE;
    }

    private static int gain(String before, String after) {
        if (!before.matches("[0-9]{1,3}") || !after.matches("[0-9]{1,3}")) return -1;
        return Integer.parseInt(after) - Integer.parseInt(before);
    }

    private static int[] innings(String value) {
        if (!value.matches("[0-9]{1,4}/[0-9]{1,2}")) return null;
        String[] parts = value.split("/");
        int wickets = Integer.parseInt(parts[1]);
        return wickets > 10 ? null : new int[] {Integer.parseInt(parts[0]), wickets};
    }

    private static boolean decreases(int[] old, int[] now) {
        return old != null && now != null && (now[0] < old[0] || now[1] < old[1]);
    }

    private static int balls(String overs) {
        if (!overs.matches("[0-9]{1,3}(\\.[0-5])?")) return -1;
        String[] parts = overs.split("\\.");
        return Integer.parseInt(parts[0]) * 6 + (parts.length == 2 ? Integer.parseInt(parts[1]) : 0);
    }

    private static String goal(Match.Moment moment) {
        return moment.kind + "|" + moment.home + "|" + moment.clock + "|" + moment.player;
    }

    private static final class Mark {
        final long time;
        final Match.State state;
        final int period;
        final String homeId, awayId, home, away, homeOvers, awayOvers, batting, leader;
        final Set<String> goals = new HashSet<>();
        boolean blocked, seenLive, seenFinal;

        Mark(Match match, long time) {
            this.time = time;
            state = match.state;
            period = match.period;
            homeId = match.home == null ? "" : match.home.team.id;
            awayId = match.away == null ? "" : match.away.team.id;
            home = match.home == null ? "" : match.home.score;
            away = match.away == null ? "" : match.away.score;
            homeOvers = match.home == null ? "" : match.home.overs;
            awayOvers = match.away == null ? "" : match.away.overs;
            batting = match.battingTeamId;
            leader = match.standings.isEmpty() ? "" : match.standings.get(0).id.isEmpty()
                    ? match.standings.get(0).name : match.standings.get(0).id;
            seenLive = match.isLive() || match.state == Match.State.POST;
            seenFinal = match.state == Match.State.POST;
            for (Match.Moment moment : match.moments) if (moment.isGoal()) goals.add(goal(moment));
        }

        boolean sameParticipants(Mark other) { return homeId.equals(other.homeId) && awayId.equals(other.awayId); }
        boolean sameState(Mark other) {
            return state == other.state && period == other.period && home.equals(other.home) && away.equals(other.away)
                    && batting.equals(other.batting) && leader.equals(other.leader);
        }
    }
}
