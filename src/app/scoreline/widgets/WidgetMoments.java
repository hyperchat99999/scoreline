package app.scoreline.widgets;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import app.scoreline.R;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;

/**
 * The launcher's big moments: goal, wicket, four, six, touchdown, new leader, kick-off, full time.
 *
 * Detected once, when a fresher sample of the same match is published, and saved with an expiry, so
 * the many re-renders that follow (crest loads, resizes, the other widget size) all draw the same
 * banner without replaying or losing it. Conservative like the in-app tracker: a correction, a stale
 * source or a long gap between samples never celebrates.
 */
public final class WidgetMoments {
    /** How long the banner owns the widget before the scoreboard returns. */
    public static final long SHOW_MS = 7_000;
    /** Samples further apart than this are catch-up, not news. */
    private static final long MAX_GAP = 10 * 60_000L;

    public enum Kind { KICKOFF, GOAL, TOUCHDOWN, FIELD_GOAL, RUNS, WICKET, FOUR, SIX, LEADER, FINAL_LAP, FINISH }

    /** One saved banner. {@code side} is "home", "away" or "" (the match itself, a driver, a draw). */
    public static final class Moment {
        public final String key;
        public final Kind kind;
        public final String side;
        public final String detail;
        public final int amount;
        public final long until;

        Moment(String key, Kind kind, String side, String detail, int amount, long until) {
            this.key = key; this.kind = kind; this.side = side; this.detail = detail; this.amount = amount; this.until = until;
        }

        public Match.Side side(Match match) {
            return "home".equals(side) ? match.home : "away".equals(side) ? match.away : null;
        }
    }

    /** A banner built by hand, for previews and instrumentation; never saved. */
    public static Moment sample(Match match, Kind kind, String side, String detail, int amount) {
        return new Moment(match.key(), kind, side, detail, amount, System.currentTimeMillis() + SHOW_MS);
    }

    private final SharedPreferences prefs;

    WidgetMoments(Context context) {
        prefs = context.getSharedPreferences("scoreline_widget_moment", Context.MODE_PRIVATE);
    }

    /** The banner to show now, or null. */
    public synchronized Moment current(long now) {
        try {
            JSONObject o = new JSONObject(prefs.getString("moment", "{}"));
            if (o.optLong("until") <= now) return null;
            return new Moment(o.getString("key"), Kind.valueOf(o.getString("kind")), o.optString("side"),
                    o.optString("detail"), o.optInt("amount"), o.getLong("until"));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Compares two samples of one match; saves and returns a moment when the newer one is news. */
    synchronized Moment observe(Match before, Match after, boolean stale, long now) {
        Moment moment = detect(before, after, stale, now);
        if (moment != null) {
            try {
                prefs.edit().putString("moment", new JSONObject().put("key", moment.key).put("kind", moment.kind.name())
                        .put("side", moment.side).put("detail", moment.detail).put("amount", moment.amount)
                        .put("until", moment.until).toString()).apply();
            } catch (JSONException ignored) { }
        }
        return moment;
    }

    static Moment detect(Match before, Match after, boolean stale, long now) {
        if (before == null || after == null || stale || after.updatesDelayed) return null;
        if (!before.key().equals(after.key()) || after.checkedAt <= before.checkedAt || before.checkedAt <= 0) return null;
        if (after.checkedAt - before.checkedAt > MAX_GAP || now - after.checkedAt > MAX_GAP) return null;
        if (after.sport.headToHead() && (before.home == null || after.home == null || before.away == null || after.away == null
                || !before.home.team.id.equals(after.home.team.id) || !before.away.team.id.equals(after.away.team.id))) return null;
        long until = now + SHOW_MS;
        String key = after.key();
        if (before.state == Match.State.PRE && after.state == Match.State.LIVE) return new Moment(key, Kind.KICKOFF, "", "", 0, until);
        if (before.state == Match.State.LIVE && after.state == Match.State.POST) {
            String side = after.home != null && after.home.winner ? "home" : after.away != null && after.away.winner ? "away" : "";
            if (side.isEmpty() && after.sport.headToHead()) {
                int home = number(after.home.score), away = number(after.away.score);
                if (home >= 0 && away >= 0 && home != away) side = home > away ? "home" : "away";
            }
            String detail = after.sport == Sport.RACING && !after.standings.isEmpty() ? after.standings.get(0).shortName : "";
            return new Moment(key, Kind.FINISH, side, detail, 0, until);
        }
        if (before.state != Match.State.LIVE || after.state != Match.State.LIVE) return null;
        if (after.sport == Sport.RACING) return race(before, after, key, until);
        if (after.sport == Sport.CRICKET) return cricket(before, after, key, until);

        int home = gain(before.home.score, after.home.score), away = gain(before.away.score, after.away.score);
        if (home < 0 || away < 0 || home + away == 0 || (home > 0 && away > 0)) return null;
        String side = home > 0 ? "home" : "away";
        int points = home + away;
        switch (after.sport) {
            case SOCCER:
            case HOCKEY:
                return points == 1 ? new Moment(key, Kind.GOAL, side, scorer(after, home > 0), 1, until) : null;
            case FOOTBALL:
                if (points >= 6 && points <= 8) return new Moment(key, Kind.TOUCHDOWN, side, "", points, until);
                return points == 3 ? new Moment(key, Kind.FIELD_GOAL, side, "", 3, until) : null;
            case BASEBALL:
                return points <= 4 ? new Moment(key, Kind.RUNS, side, "", points, until) : null;
            default:
                return null; // Basketball scores far too often for a takeover; its digits roll instead.
        }
    }

    private static Moment race(Match before, Match after, String key, long until) {
        if (!after.isRace() || before.standings.isEmpty() || after.standings.isEmpty()) return null;
        Match.Standing was = before.standings.get(0), is = after.standings.get(0);
        String a = was.id.isEmpty() ? was.name : was.id, b = is.id.isEmpty() ? is.name : is.id;
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b) && after.period >= before.period)
            return new Moment(key, Kind.LEADER, "", is.shortName, 0, until);
        if (after.laps > 0 && after.period >= after.laps && before.period < after.laps && before.period > 0)
            return new Moment(key, Kind.FINAL_LAP, "", is.shortName, 0, until);
        return null;
    }

    private static Moment cricket(Match before, Match after, String key, long until) {
        String batting = after.battingTeamId;
        if (batting.isEmpty() || !batting.equals(before.battingTeamId) || after.period != before.period) return null;
        boolean home = batting.equals(after.home.team.id);
        if (!home && !batting.equals(after.away.team.id)) return null;
        int[] was = innings(home ? before.home.score : before.away.score);
        int[] is = innings(home ? after.home.score : after.away.score);
        if (was == null || is == null || is[0] < was[0] || is[1] < was[1]) return null;
        int balls = balls(home ? after.home.overs : after.away.overs) - balls(home ? before.home.overs : before.away.overs);
        String batSide = home ? "home" : "away";
        if (is[1] - was[1] == 1 && is[0] - was[0] <= 6) return new Moment(key, Kind.WICKET, home ? "away" : "home", "", 1, until);
        // One legal ball, one boundary: anything else could be singles adding up between samples.
        if (is[1] == was[1] && balls == 1 && is[0] - was[0] == 6) return new Moment(key, Kind.SIX, batSide, "", 6, until);
        if (is[1] == was[1] && balls == 1 && is[0] - was[0] == 4) return new Moment(key, Kind.FOUR, batSide, "", 4, until);
        return null;
    }

    private static String scorer(Match match, boolean home) {
        Match.Moment last = null;
        for (Match.Moment m : match.moments) if (m.isGoal() && m.home == home) last = m;
        if (last == null || last.player.isEmpty()) return "";
        String surname = last.player.contains(" ") ? last.player.substring(last.player.lastIndexOf(' ') + 1) : last.player;
        return last.clock.isEmpty() ? surname : surname + " " + last.clock;
    }

    private static int gain(String before, String after) {
        int a = number(before), b = number(after);
        return a < 0 || b < 0 ? -1 : b - a;
    }

    private static int number(String value) {
        return value != null && value.matches("[0-9]{1,3}") ? Integer.parseInt(value) : -1;
    }

    private static int[] innings(String value) {
        if (value == null || !value.matches("[0-9]{1,4}(/[0-9]{1,2})?")) return null;
        String[] parts = value.split("/");
        return new int[] {Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 10};
    }

    private static int balls(String overs) {
        if (overs == null || !overs.matches("[0-9]{1,3}(\\.[0-5])?")) return -100;
        String[] parts = overs.split("\\.");
        return Integer.parseInt(parts[0]) * 6 + (parts.length == 2 ? Integer.parseInt(parts[1]) : 0);
    }

    // ---- Presentation shared with the island ------------------------------------------------------

    public static int title(Sport sport, Kind kind) {
        switch (kind) {
            case GOAL: return R.string.moment_goal;
            case TOUCHDOWN: return R.string.moment_touchdown;
            case FIELD_GOAL: return R.string.moment_field_goal;
            case RUNS: return R.string.moment_run;
            case WICKET: return R.string.moment_wicket;
            case FOUR: return R.string.moment_four;
            case SIX: return R.string.moment_six;
            case LEADER: return R.string.moment_leader;
            case FINAL_LAP: return R.string.moment_final_lap;
            case FINISH:
                return sport == Sport.SOCCER ? R.string.moment_full_time : sport == Sport.RACING ? R.string.moment_chequered : R.string.moment_final;
            default:
                switch (sport) {
                    case BASKETBALL: return R.string.moment_tipoff;
                    case HOCKEY: return R.string.moment_faceoff;
                    case BASEBALL: return R.string.moment_play_ball;
                    case CRICKET: return R.string.moment_first_ball;
                    case RACING: return R.string.moment_lights_out;
                    default: return R.string.moment_kickoff;
                }
        }
    }

    public static int glyph(Sport sport, Kind kind) {
        switch (kind) {
            case GOAL: return sport == Sport.HOCKEY ? R.drawable.glyph_moment_hockey : R.drawable.glyph_moment_goal;
            case TOUCHDOWN:
            case FIELD_GOAL: return R.drawable.glyph_moment_goalpost;
            case RUNS: return R.drawable.glyph_moment_bat;
            case WICKET: return R.drawable.glyph_moment_wicket;
            case FOUR:
            case SIX: return R.drawable.glyph_moment_boundary;
            case LEADER:
            case FINAL_LAP: return R.drawable.glyph_racing;
            case FINISH: return sport == Sport.RACING ? R.drawable.glyph_racing : R.drawable.glyph_trophy;
            default: return sport == Sport.RACING ? R.drawable.glyph_moment_lights : sportGlyph(sport);
        }
    }

    public static int sportGlyph(Sport sport) {
        switch (sport) {
            case CRICKET: return R.drawable.glyph_cricket;
            case BASKETBALL: return R.drawable.glyph_basketball;
            case RACING: return R.drawable.glyph_racing;
            case FOOTBALL: return R.drawable.glyph_football;
            case HOCKEY: return R.drawable.glyph_hockey;
            case BASEBALL: return R.drawable.glyph_baseball;
            default: return R.drawable.glyph_soccer;
        }
    }
}
