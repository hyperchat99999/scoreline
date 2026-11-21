package app.scoreline.live;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import app.scoreline.R;
import app.scoreline.widgets.SportsWidgetProvider;
import app.scoreline.core.Ui;
import app.scoreline.data.Espn;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;
import app.scoreline.model.Timeline;

/**
 * Turns a match into the live notification behind the island.
 *
 * <p>Every platform gets a standard Android 16 {@link Notification.ProgressStyle} notification with
 * a short critical text, which is what Live Updates (status bar chips) are built from. On phones with
 * their own island, the same notification also carries the extras that island reads for a richer layout:
 * an icon and text on the left and a coloured capsule on the right. Other systems ignore them.
 */
final class IslandRenderer {
    private IslandRenderer() {}

    /**
     * A moment the expanded island announces ("GOAL", "TOUCHDOWN", "WICKET"). Scores flash in the
     * colour of the side that scored; a wicket is always red.
     */
    static final class Flash {
        final String word;
        /** The side that scored, or null. */
        final Match.Side side;

        Flash(String word, Match.Side side) {
            this.word = word;
            this.side = side;
        }
    }

    /** Bitmaps fetched on the worker thread before rendering. Any may be null. */
    static final class Art {
        /** The island icon, already framed by {@link Badge}. */
        Bitmap focus;
        /** The shade card's small icon: the followed side's crest, in the shade's light or dark variant. */
        Bitmap card;
        /** Both crests side by side, the large picture on either notification. */
        Bitmap matchup;
    }

    // Read by the skin's SystemUI plugin when it turns a ProgressStyle notification into an island card.
    private static final String ISLAND_LAYOUT = "progress_style_business_type";
    private static final String ISLAND_LEFT_ICON = "island.superx.leftInfo.icon";
    private static final String ISLAND_LEFT_TEXT = "island.superx.leftInfo.content";
    private static final String ISLAND_CAPSULE_TEXT = "island.superx.rightInfo.capsuleContent";
    private static final String ISLAND_CAPSULE_COLOR = "island.superx.rightInfo.capsuleBgColor";
    private static final String ISLAND_CAPSULE_TAP = "island.superx.rightInfo.clickResp";
    // AOSP Notification.EXTRA_REQUEST_PROMOTED_ONGOING, public from API 36.1.
    private static final String REQUEST_PROMOTED = "android.requestPromotedOngoing";
    private static final String GROUP = "live";

    private static final int BRAND = 0xFF2F6FED;
    private static final int GOAL = 0xFF1E9E52;
    private static final int FINISHED = 0xFF3A3A3C;
    private static final int F1_RED = 0xFFE10600;
    private static final int WICKET = 0xFFD7263D;

    /**
     * @param flash  a moment to announce in the expanded capsule, or null.
     * @param moment use the island's expanded layout (icon, text, coloured capsule) for this post.
     * @param swap   for the side island: makes this the main island. Null for the main island.
     */
    static Notification build(Context c, Match m, String focusId, Art art, Flash flash, boolean moment,
                              PendingIntent open, PendingIntent hide, PendingIntent swap) {
        Content content = content(c, m, focusId, flash);
        Timeline t = Timeline.of(m);

        Icon crest = art.focus != null ? Icon.createWithBitmap(art.focus) : Icon.createWithResource(c, R.drawable.ic_stat_scoreline);
        Bundle extras = new Bundle();
        extras.putBoolean(REQUEST_PROMOTED, true);
        if (moment) {
            // The island shows this layout for five seconds, then drops it: right for a goal or a wicket,
            // wrong for the whole match. The caller re-posts the steady layout afterwards.
            extras.putInt(ISLAND_LAYOUT, 1);
            extras.putParcelable(ISLAND_LEFT_ICON, crest);
            extras.putCharSequence(ISLAND_LEFT_TEXT, content.left);
            extras.putCharSequence(ISLAND_CAPSULE_TEXT, content.capsule);
            extras.putInt(ISLAND_CAPSULE_COLOR, content.capsuleColor);
            extras.putParcelable(ISLAND_CAPSULE_TAP, open);
        }

        Notification.Builder b = new Notification.Builder(c, Notifications.LIVE)
                // The steady island is this icon plus the short critical text, so the icon is the crest.
                .setSmallIcon(crest)
                .setSubText(content.sub(m))
                .setContentTitle(content.title)
                .setContentText(content.body(m))
                .setColor(content.color)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // No group, and above all not a group summary: Android 17 never promotes a summary to a
                // Live Update, and the island on newer skins only takes promoted notifications.
                .setContentIntent(open)
                .setDeleteIntent(hide)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_close), c.getString(R.string.act_stop), hide).build())
                .setLargeIcon(content.large != null ? content.large : art.matchup)
                .addExtras(extras);
        if (swap != null) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_island_on), c.getString(R.string.act_swap), swap).build());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            LiveUpdate.apply(b, t, content.color, m.isLive(), content.chip);
        } else {
            // Before Android 16 there are no Live Updates: an ordinary ongoing notification, with the
            // match time as the classic progress bar under the text.
            b.setStyle(new Notification.BigTextStyle().bigText(content.body(m)))
                    .setProgress(t.max, Math.min(t.value, t.max), t.indeterminate);
        }
        return b.build();
    }

    /**
     * Takes a built island notification out of the island for one post: without the Live Update
     * request Android doesn't promote it, and the island drops it.
     */
    static void leaveIsland(Notification n) {
        n.extras.putBoolean(REQUEST_PROMOTED, false);
    }

    /**
     * A followed match that's on while two others hold the island. Deliberately not a progress or
     * promoted notification, so the island leaves it in the shade instead of fighting for the island.
     */
    static Notification card(Context c, Match m, String focusId, Art art, PendingIntent open, PendingIntent hide,
                             PendingIntent toIsland) {
        Content content = content(c, m, focusId, null);
        // Crests rather than the app's glyph: the card is about the match, not the app.
        Notification.Builder b = new Notification.Builder(c, Notifications.CARDS)
                .setSmallIcon(art.card != null ? Icon.createWithBitmap(art.card) : Icon.createWithResource(c, R.drawable.ic_stat_scoreline))
                .setSubText(content.sub(m))
                .setContentTitle(content.title)
                .setContentText(content.body(m))
                .setStyle(new Notification.BigTextStyle().bigText(content.body(m)))
                .setColor(content.color)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(open)
                .setDeleteIntent(hide)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_island_on), c.getString(R.string.act_show), toIsland).build());
        if (content.large != null || art.matchup != null) b.setLargeIcon(content.large != null ? content.large : art.matchup);
        return b.build();
    }

    /**
     * The foreground service's own notification: required by Android, kept out of the way on a
     * minimum-importance channel and in its own group so it never bundles with an island card.
     */
    static Notification service(Context c) {
        return new Notification.Builder(c, Notifications.SERVICE)
                .setSmallIcon(R.drawable.ic_stat_scoreline)
                .setContentTitle(c.getString(R.string.service_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup(GROUP)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    // ---- Text and colour -----------------------------------------------------------------------

    private static String freshness(Match m, String text) {
        if (!m.updatesDelayed) return text;
        return text + " · Updates delayed" + (m.checkedAt > 0
                ? " · last checked " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(m.checkedAt)) : "");
    }

    private static final class Content {
        /** Expanded layout: text beside the crest, and the capsule. */
        String left = "";
        String capsule = "";
        /** Steady layout and Live Update chip: at most about nine characters. */
        String chip = "";
        int capsuleColor;
        int color;
        String title = "";
        String text = "";
        /** Header text in place of the league name ("Lap 34/56"), or "". */
        String sub = "";
        /** Large icon in place of the matchup (the race leader), or null. */
        Bitmap large;

        String body(Match m) {
            return freshness(m, text);
        }

        String sub(Match m) {
            return sub.isEmpty() ? m.leagueName : sub;
        }
    }

    private static Content content(Context c, Match m, String focusId, Flash flash) {
        Content out = new Content();
        if (m.state == Match.State.OFF) {
            out.color = FINISHED;
            out.capsuleColor = FINISHED;
            out.chip = m.status.isEmpty() ? "Unavailable" : m.status;
            out.left = out.chip;
            out.capsule = out.chip;
            out.title = m.sport == Sport.RACING ? m.title + " · " + m.session
                    : m.home.team.shortName + " v " + m.away.team.shortName;
            out.text = join(out.chip, m.summary);
            return out;
        }

        if (m.sport == Sport.RACING) {
            race(c, m, flash, out);
            return out;
        }
        Match.Side us = m.sideOf(focusId);
        Match.Side them = m.opponentOf(focusId);
        out.color = Ui.capsule(SportsWidgetProvider.teamColor(c, us.team, m.sport, true));
        out.capsuleColor = out.color;

        // One chip grammar for every head-to-head sport: before the start, the opponent and the
        // time; after it, the score and then the period. The second part is dropped rather than
        // clipped when it won't fit, so the score is never cut.
        if (m.state == Match.State.PRE) {
            out.chip = prefix(them.team.abbr, chipTime(c, m.start));
        } else if (m.sport == Sport.CRICKET) {
            // Cricket's score belongs to one side at a time: the side batting, named.
            Match.Side batting = m.involves(m.battingTeamId) ? m.sideOf(m.battingTeamId) : null;
            Match.Side won = m.home.winner ? m.home : m.away.winner ? m.away : null;
            out.chip = m.state == Match.State.POST && won != null ? won.team.abbr + " won"
                    : batting != null ? prefix(batting.team.abbr, batting.score)
                    : Status.full(m);
        } else {
            out.chip = Status.fit(m.home.score + "–" + m.away.score, Status.compact(m));
        }

        if (m.sport == Sport.CRICKET) {
            cricket(c, m, us, flash, out);
            return out;
        }

        String home = m.home.team.shortName;
        String away = m.away.team.shortName;
        switch (m.state) {
            case PRE:
                out.left = c.getString(R.string.vs) + " " + them.team.abbr;
                out.capsule = Ui.time(c, m.start);
                out.title = home + " " + c.getString(R.string.vs) + " " + away;
                out.text = join(Ui.when(c, m.start), m.venue);
                break;
            case LIVE:
                out.left = m.home.score + " – " + m.away.score;
                out.capsule = flash != null ? shout(flash.word) : capsule(m);
                if (flash != null) out.capsuleColor = flash.side == null ? GOAL : Ui.capsule(SportsWidgetProvider.teamColor(c, flash.side.team, m.sport, true));
                out.title = home + "  " + m.home.score + " – " + m.away.score + "  " + away;
                out.text = m.sport == Sport.SOCCER ? join(Status.full(m), lastGoal(m)) : Timeline.of(m).phase(m);
                break;
            default:
                out.left = m.home.score + " – " + m.away.score;
                out.capsule = Status.finished(m);
                out.capsuleColor = FINISHED;
                out.title = home + "  " + m.home.score + " – " + m.away.score + "  " + away;
                out.text = m.sport == Sport.SOCCER ? join(Status.finished(m), scorerNames(m)) : Status.finished(m);
        }
        return out;
    }

    private static void cricket(Context c, Match m, Match.Side us, Flash flash, Content out) {
        Match.Side batting = m.battingTeamId.equals(m.away.team.id) ? m.away : m.home;
        out.title = total(m.home) + "  ·  " + total(m.away);
        out.text = m.summary.isEmpty() ? join(m.title, m.leagueName) : m.summary;
        switch (m.state) {
            case PRE:
                out.left = c.getString(R.string.vs) + " " + (us == m.home ? m.away : m.home).team.abbr;
                out.capsule = Ui.time(c, m.start);
                out.title = m.home.team.name + " v " + m.away.team.name;
                out.text = join(m.title, Ui.when(c, m.start));
                break;
            case LIVE:
                // Like every sport: where the match is first, then the detail (the chase equation).
                out.text = join(Timeline.of(m).phase(m), m.summary);
                out.left = !m.involves(m.battingTeamId) ? "Live cricket" : batting.team.abbr + " " + batting.score;
                out.capsule = flash != null ? shout(flash.word) : capsule(m);
                out.color = Ui.capsule(SportsWidgetProvider.teamColor(c, batting.team, m.sport, true));
                out.capsuleColor = flash != null ? WICKET : out.color;
                break;
            default:
                Match.Side won = m.home.winner ? m.home : m.away.winner ? m.away : null;
                out.left = won != null ? won.team.abbr + " won" : us.team.abbr + " " + us.score;
                out.capsule = Status.finished(m);
                out.capsuleColor = FINISHED;
        }
    }

    private static void race(Context c, Match m, Flash flash, Content out) {
        Match.Standing leader = m.standings.isEmpty() || m.standings.get(0).position != 1 ? null : m.standings.get(0);
        String code = leader == null ? "" : leader.code();
        out.color = F1_RED;
        // The capsule wears the leader's team colour once the timing feed gives it.
        out.capsuleColor = leader != null && leader.color != 0 ? Ui.capsule(leader.color) : F1_RED;
        out.title = m.title + " · " + m.session;
        // Same grammar as the team sports: what's next and when, then who leads (with the lap, in a
        // race of known distance), then the result. A finished race names its winner; a finished
        // practice or qualifying, who topped it.
        String lap = code + " " + Math.min(m.period, m.laps) + "/" + m.laps;
        out.chip = m.state == Match.State.PRE ? prefix(Espn.shortSession(m.session), chipTime(c, m.start))
                : leader == null ? (m.state == Match.State.POST ? Status.finished(m) : Espn.shortSession(m.session))
                : m.state != Match.State.POST ? (m.laps > 0 && lap.length() <= Status.CHIP ? lap : "P1 " + code)
                : m.isRace() ? code + " won"
                : prefix(Espn.shortSession(m.session), code);
        switch (m.state) {
            case PRE:
                out.left = Espn.shortSession(m.session);
                out.capsule = Ui.time(c, m.start);
                out.text = join(Ui.when(c, m.start), m.venue);
                return;
            case LIVE:
                out.left = Espn.shortSession(m.session);
                out.capsule = flash != null ? shout(flash.word) : capsule(m);
                out.sub = Status.full(m);
                out.text = Status.full(m);
                break;
            default:
                out.left = Espn.shortSession(m.session);
                out.capsule = m.isRace() && leader != null ? "WINS" : Status.finished(m);
                if (!m.isRace() || leader == null) out.capsuleColor = FINISHED;
        }
        if (leader == null) return;
        // As on a timing screen: the leader on the left of the island and as the shade's large icon,
        // the lap in the capsule on the right, and the top five with their gaps underneath.
        out.left = code;
        out.title = leader.surname() + (!m.isRace() ? " fastest" : m.state == Match.State.POST ? " wins" : " leads");
        out.text = tower(m);
        out.large = Badge.driver(code, leader.color != 0 ? leader.color : F1_RED);
    }

    /**
     * The top five as on a timing screen: "1 VER · 2 NOR +2.3 · 3 LEC +5.1 · 4 HAM +8.0 · 5 PIA +9.9".
     * No-break spaces hold each entry together, so a line never ends between "5" and "PIA".
     */
    static String tower(Match m) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(5, m.standings.size()); i++) {
            Match.Standing s = m.standings.get(i);
            rows.add((s.position + " " + s.code() + (s.gap.isEmpty() ? "" : " " + s.gap)).replace(' ', '\u00a0'));
        }
        return join(rows.toArray(new String[0]));
    }

    /** The expanded capsule: the full words when they fit ("Half-time", "Q3 5:12"), else the short form. */
    private static String capsule(Match m) {
        String words = Status.full(m);
        return words.length() <= 10 ? words : Status.compact(m);
    }

    /** {@code label + " " + value} when it fits the chip, else the value alone. */
    /** The moment as the widget and app say it: "GOAL!", "WICKET!", "SIX!"; longer phrases stay plain. */
    static String shout(String word) {
        if (word == null || word.isEmpty() || word.endsWith("!")) return word;
        switch (word) {
            case "GOAL": case "WICKET": case "TOUCHDOWN": case "SIX": case "FOUR": return word + "!";
            default: return word;
        }
    }

    private static String prefix(String label, String value) {
        if (label == null || label.isEmpty()) return value;
        String both = label + " " + value;
        return both.length() <= Status.CHIP ? both : value;
    }

    /** Kick-off time in as few characters as the user's clock format allows: "22:00", "10pm", "9:30pm". */
    private static String chipTime(Context c, long when) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(when);
        if (android.text.format.DateFormat.is24HourFormat(c)) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        }
        int hour = cal.get(java.util.Calendar.HOUR) == 0 ? 12 : cal.get(java.util.Calendar.HOUR);
        int minute = cal.get(java.util.Calendar.MINUTE);
        String ampm = cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? "am" : "pm";
        return minute == 0 ? hour + ampm : String.format(java.util.Locale.ROOT, "%d:%02d%s", hour, minute, ampm);
    }

    /** "IND 184/3": the cricket title, without overs so both sides fit on one line. */
    private static String total(Match.Side s) {
        return s.score.isEmpty() ? s.team.abbr : s.team.abbr + " " + s.score;
    }

    /** The latest goal, the one people want under the score: "⚽ Jackson 54' (P)". */
    static String lastGoal(Match m) {
        Match.Moment last = null;
        for (Match.Moment g : m.moments) if (g.isGoal()) last = g;
        if (last == null) return "";
        String tag = last.kind == Match.Moment.Kind.PENALTY ? " (P)" : last.kind == Match.Moment.Kind.OWN_GOAL ? " (OG)" : "";
        return "⚽ " + surname(last.player) + " " + last.clock + tag;
    }

    /** Scorers by surname, home side then away, mirroring the title: "Palmer, Jackson – Son". */
    static String scorerNames(Match m) {
        String home = names(m, true), away = names(m, false);
        if (home.isEmpty() && away.isEmpty()) return "";
        return (home.isEmpty() ? "—" : home) + " – " + (away.isEmpty() ? "—" : away);
    }

    private static String names(Match m, boolean home) {
        List<String> out = new ArrayList<>();
        for (Match.Moment g : m.moments) {
            if (!g.isGoal() || g.home != home) continue;
            String name = surname(g.player) + (g.kind == Match.Moment.Kind.OWN_GOAL ? " (OG)" : "");
            if (!out.contains(name)) out.add(name);
        }
        return String.join(", ", out);
    }

    /** "C. Palmer" -> "Palmer"; a single name stays as it is. */
    private static String surname(String player) {
        if (player == null) return "";
        String p = player.trim();
        int space = p.lastIndexOf(' ');
        return space < 0 ? p : p.substring(space + 1);
    }

    /** "CHE Palmer 23', Jackson 71' · TOT Son 54'": each side's scorers under its own name. */
    static String scorers(Match m) {
        return join(scorers(m, true), scorers(m, false));
    }

    /** One side's scorers, newest last, at most three. */
    private static String scorers(Match m, boolean home) {
        List<String> goals = new ArrayList<>();
        for (Match.Moment g : m.moments) {
            if (!g.isGoal() || g.home != home) continue;
            String tag = g.kind == Match.Moment.Kind.PENALTY ? " (P)" : g.kind == Match.Moment.Kind.OWN_GOAL ? " (OG)" : "";
            goals.add(g.player + " " + g.clock + tag);
        }
        if (goals.isEmpty()) return "";
        if (goals.size() > 3) goals = goals.subList(goals.size() - 3, goals.size());
        return (home ? m.home : m.away).team.abbr + " " + String.join(", ", goals);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    // ---- Progress bar --------------------------------------------------------------------------

    /**
     * Android 16's Live Update parts: the chip text and the progress style. In a class of their own
     * so older Android, where they don't exist, never loads anything that refers to them.
     */
    private static final class LiveUpdate {
        static void apply(Notification.Builder b, Timeline t, int color, boolean live, String chip) {
            b.setShortCriticalText(chip).setStyle(progress(t, color, live));
        }

        /**
         * Match time as one smooth bar in the team's colour. Nothing else goes on it: crests at its two
         * ends read as a tug-of-war rather than a clock, and progress points draw as square notches.
         * Who scored and when is in the text above the bar.
         */
        private static Notification.ProgressStyle progress(Timeline t, int color, boolean live) {
            Notification.ProgressStyle p = new Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressIndeterminate(t.indeterminate);
            // One continuous segment: period gaps read as clutter on a bar this thin, and a knob makes
            // it look like a media scrubber. The filled part is the match played so far.
            p.addProgressSegment(new Notification.ProgressStyle.Segment(Math.max(1, t.max)).setColor(color));
            return p.setProgress(Math.min(t.value, Math.max(1, t.max)));
        }
    }
}
