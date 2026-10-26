package app.scoreline.live;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Images;
import app.scoreline.core.Ui;
import app.scoreline.data.Store;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Team;

/**
 * Keeps the island current while followed matches are on. Runs as a foreground service whose
 * notification is the island itself, polls ESPN every 30 seconds (slower with the screen off) and
 * stops as soon as nothing is left to show.
 */
public final class LiveService extends Service {
    private static final String ACTION_SYNC = "app.scoreline.action.SYNC";
    private static final String ACTION_PIN = "app.scoreline.action.PIN";
    private static final String ACTION_HIDE = "app.scoreline.action.HIDE";
    private static final String EXTRA_KEY = "key";

    private static final int SERVICE_ID = 1;
    private static final long TICK = 30_000;
    private static final long TICK_SCREEN_OFF = 75_000;
    private static final long FEED_EVERY = 4 * 60_000;
    /** The island shows the expanded island layout for five seconds. */
    private static final long MOMENT = 5_000;
    private static final long FINAL_HOLD = 5 * 60_000;

    /** Re-evaluates what belongs in the island. Returns false if Android refused a background start. */
    public static boolean sync(Context c) {
        return start(c, new Intent(c, LiveService.class).setAction(ACTION_SYNC));
    }

    /** Puts a match in the island (from a button the user tapped). */
    public static boolean pin(Context c, String matchKey) {
        return start(c, new Intent(c, LiveService.class).setAction(ACTION_PIN).putExtra(EXTRA_KEY, matchKey));
    }

    /** For notifications: starting a foreground service from a tap is always allowed. */
    static PendingIntent pinIntent(Context c, String matchKey) {
        Intent i = new Intent(c, LiveService.class).setAction(ACTION_PIN).putExtra(EXTRA_KEY, matchKey);
        return PendingIntent.getForegroundService(c, ("pin" + matchKey).hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent hideIntent(Context c, String matchKey) {
        Intent i = new Intent(c, LiveService.class).setAction(ACTION_HIDE).putExtra(EXTRA_KEY, matchKey);
        return PendingIntent.getForegroundService(c, ("hide" + matchKey).hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static boolean start(Context c, Intent i) {
        try {
            c.startForegroundService(i);
            return true;
        } catch (IllegalStateException | SecurityException e) {
            // ForegroundServiceStartNotAllowedException: the app is in the background and not exempt.
            return false;
        }
    }

    /** State for one match on screen. Touched only on the worker thread. */
    private static final class Tracked {
        final int id;
        Match match;
        final String focusId;
        String scoreline;
        /** Until when the island is expanded, and what its capsule says meanwhile (null: the clock). */
        long momentUntil;
        IslandRenderer.Flash flash;
        long endedAt;
        /** Shown in the island (rather than as a card in the shade) on the last render. */
        boolean inIsland;
        IslandRenderer.Art art;
        String artFor;

        Tracked(Match match, String focusId) {
            this.id = ("live" + match.key()).hashCode();
            this.match = match;
            this.focusId = focusId;
            this.scoreline = match.scoreline();
            // Arrive expanded, so the first sight of a match is the full card.
            this.momentUntil = System.currentTimeMillis() + MOMENT;
        }
    }

    private final Map<String, Tracked> tracked = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private App app;
    private long lastFeed;
    private int failedTicks;
    private volatile int foregroundId;
    private volatile int latestStartId;
    /** The match that owns the island; the others are cards in the shade. */
    private String islandKey;
    private final Runnable tick = this::tick;
    private final Runnable settle = () -> render(System.currentTimeMillis());

    @Override
    public void onCreate() {
        super.onCreate();
        app = (App) getApplication();
        thread = new HandlerThread("live");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (foregroundId == 0) {
            startForeground(SERVICE_ID, IslandRenderer.service(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            foregroundId = SERVICE_ID;
        }
        String action = intent == null ? ACTION_SYNC : intent.getAction();
        String key = intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        worker.post(() -> {
            Store store = app.store();
            if (ACTION_PIN.equals(action) && key != null) {
                Match selected = app.feed().last().find(key);
                if (selected != null) store.pin(selected); else store.pin(key);
                Notifications.cancelSuggestion(this, key);
                lastFeed = 0;
            } else if (ACTION_HIDE.equals(action) && key != null) {
                store.hide(key);
                drop(key);
            }
            worker.removeCallbacks(tick);
            tick();
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- The loop ------------------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        Store store = app.store();
        if (now - lastFeed > FEED_EVERY || app.feed().last().updatedAt == 0) {
            app.feed().load(false);
            lastFeed = now;
        }

        List<Match> wanted = Tracker.wanted(app.feed().last(), store, now);
        Set<String> wantedKeys = new HashSet<>();
        for (Match m : wanted) wantedKeys.add(m.key());
        for (Iterator<Tracked> it = tracked.values().iterator(); it.hasNext();) {
            Tracked t = it.next();
            if (t.endedAt == 0 && !wantedKeys.contains(t.match.key())) { cancel(t.id); it.remove(); }
        }
        for (Match m : wanted) {
            wantedKeys.add(m.key());
            if (!tracked.containsKey(m.key()) && tracked.size() < Tracker.MAX) {
                Follow focus = store.focus(m);
                tracked.put(m.key(), new Tracked(m, focus != null ? focus.team.id : m.home != null ? m.home.team.id : null));
            }
        }

        boolean delayed = false;
        boolean live = false;
        for (Iterator<Tracked> it = tracked.values().iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            String key = t.match.key();
            // Gone from the ranking (unfollowed, hidden, or pushed out by more important matches);
            // a finished match is kept briefly so its result stays visible.
            if (t.endedAt == 0 && !wantedKeys.contains(key)) {
                cancel(t.id);
                it.remove();
                continue;
            }
            try {
                Match fresh = app.espn().refresh(t.match);
                if (fresh != null) {
                    react(t, fresh, now);
                    t.match = fresh;
                } else { t.match.updatesDelayed = true; }
            } catch (IOException ignored) {
                t.match.updatesDelayed = true;
            }
            delayed |= t.match.updatesDelayed;
            live |= t.match.isLive();
            boolean over = t.match.state == Match.State.POST || t.match.state == Match.State.OFF;
            if (over && t.endedAt == 0) t.endedAt = now;
            if (over && now - t.endedAt > FINAL_HOLD) {
                cancel(t.id);
                it.remove();
            }
        }

        if (tracked.isEmpty()) {
            int completedStart = latestStartId;
            main.post(() -> {
                if (completedStart == latestStartId) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    foregroundId = 0;
                    stopSelfResult(completedStart);
                }
            });
            return;
        }
        // The island goes to the highest-ranked match still on; Tracker decides the ranking.
        islandKey = null;
        for (Match m : wanted) {
            if (tracked.containsKey(m.key())) {
                islandKey = m.key();
                break;
            }
        }
        if (islandKey == null) islandKey = tracked.keySet().iterator().next();
        render(now);
        failedTicks = delayed ? Math.min(4, failedTicks + 1) : 0;
        long interval = live ? (isScreenOn() ? TICK : TICK_SCREEN_OFF) : 120_000;
        if (delayed) interval = Math.max(interval, Math.min(300_000L, TICK * (1L << failedTicks)));
        worker.postDelayed(tick, interval);
    }

    /** Detects kick-off, goals, a race's lead changes and full time between two snapshots of the same match. */
    private void react(Tracked t, Match fresh, long now) {
        Match old = t.match;
        boolean alerts = app.store().alerts() && app.store().focus(fresh) != null;
        String vs = fresh.sport.headToHead() ? fresh.home.team.shortName + " v " + fresh.away.team.shortName : fresh.title;

        if (old.state == Match.State.PRE && fresh.isLive()) moment(t, fresh.isRace() ? new IslandRenderer.Flash("LIGHTS OUT", null) : null, now);
        if (old.state == Match.State.PRE && fresh.isLive() && alerts) {
            Notifications.alert(this, fresh, getString(R.string.alert_kickoff) + " · " + vs, fresh.leagueName, art(t).focus);
        }
        String scoreline = fresh.scoreline();
        if (fresh.isLive() && !scoreline.equals(t.scoreline)) scored(t, old, fresh, alerts, now);
        if (fresh.sport == Sport.CRICKET && fresh.isLive() && wickets(fresh) > wickets(old)) {
            moment(t, new IslandRenderer.Flash("WICKET", null), now);
        }
        if (fresh.isRace() && old.isLive() && fresh.isLive()) raced(t, old, fresh, now);
        t.scoreline = scoreline;
        if (old.state != Match.State.POST && fresh.state == Match.State.POST) moment(t, null, now);
        if (old.state != Match.State.POST && fresh.state == Match.State.POST && alerts) {
            String title = getString(fresh.sport == Sport.SOCCER ? R.string.alert_final : R.string.alert_result);
            String text = fresh.sport == Sport.CRICKET && !fresh.summary.isEmpty() ? fresh.summary
                    : fresh.sport.headToHead()
                    ? fresh.home.team.shortName + " " + fresh.home.score + " – " + fresh.away.score + " " + fresh.away.team.shortName
                    : fresh.title;
            Notifications.alert(this, fresh, title, text, art(t).focus);
        }
    }

    /**
     * The island match is the service's own notification; any other followed match that's on gets
     * an ordinary card in the shade, with a button to swap it into the island.
     */
    private void render(long now) {
        long settleAt = 0;
        Tracked island = tracked.get(islandKey);
        if (island != null) {
            String key = island.match.key();
            if (!island.inIsland && island.momentUntil < now) moment(island, null, now); // arrive expanded after a swap
            boolean moment = now < island.momentUntil;
            Notification n = IslandRenderer.build(this, island.match, island.focusId, art(island), moment ? island.flash : null, moment,
                    Notifications.openMatch(this, key), hideIntent(this, key));
            startForeground(island.id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if (foregroundId != island.id && !tracked.containsValue(byId(foregroundId))) cancel(foregroundId);
            foregroundId = island.id;
            island.inIsland = true;
            if (moment) settleAt = island.momentUntil;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (Tracked t : tracked.values()) {
            if (t == island) continue;
            String key = t.match.key();
            nm.notify(t.id, IslandRenderer.card(this, t.match, t.focusId, art(t),
                    Notifications.openMatch(this, key), hideIntent(this, key), pinIntent(this, key)));
            t.inIsland = false;
        }
        // Once the expanded layout times out, put the steady one back straight away.
        worker.removeCallbacks(settle);
        if (settleAt > 0) worker.postDelayed(settle, settleAt - now + 300);
    }

    private static void moment(Tracked t, IslandRenderer.Flash flash, long now) {
        t.momentUntil = now + MOMENT;
        t.flash = flash;
    }

    private void finish() {
        for (Tracked t : tracked.values()) cancel(t.id);
        tracked.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundId = 0;
        stopSelf();
    }

    private Tracked byId(int id) {
        for (Tracked t : tracked.values()) if (t.id == id) return t;
        return null;
    }

    private void drop(String key) {
        Tracked t = tracked.remove(key);
        if (t != null) cancel(t.id);
    }

    private void cancel(int id) {
        getSystemService(NotificationManager.class).cancel(id);
    }

    /**
     * The island's crest: the followed team's dark variant (the island is always black). In cricket
     * it follows the batting side, so the crest always matches the score beside it.
     */
    private IslandRenderer.Art art(Tracked t) {
        Match m = t.match;
        String subject = m.sport == Sport.CRICKET && !m.battingTeamId.isEmpty() ? m.battingTeamId : t.focusId;
        if (t.art != null && String.valueOf(subject).equals(t.artFor)) return t.art;
        IslandRenderer.Art art = new IslandRenderer.Art();
        Images images = app.images();
        boolean complete = true;
        if (m.sport == Sport.RACING) {
            Bitmap logo = images.load("https://a.espncdn.com/i/teamlogos/leagues/500/f1.png", 96);
            art.focus = logo == null ? null : Badge.single(logo);
            art.card = logo;
            art.matchup = logo;
        } else {
            // The shade follows the system theme, so its crests do too.
            boolean night = Ui.isNight(this);
            art.matchup = Badge.versus(images.load(m.home.team.crest(night), 96), images.load(m.away.team.crest(night), 96));
            if (m.sport == Sport.CRICKET) {
                Bitmap crest = islandCrest(m.sideOf(subject).team);
                art.focus = crest == null ? null : Badge.single(crest);
                art.card = images.load(m.sideOf(subject).team.crest(night), 96);
            } else {
                if (m.sport == Sport.SOCCER) {
                    Bitmap home = islandCrest(m.home.team);
                    Bitmap away = islandCrest(m.away.team);
                    art.focus = Badge.pair(home, away);
                    complete = home != null && away != null;
                } else {
                    art.focus = Badge.single(islandCrest(m.sideOf(t.focusId).team));
                }
                art.card = images.load(m.sideOf(t.focusId).team.crest(night), 96);
            }
        }
        // Only remember complete art, so a crest missed while offline is retried.
        if (art.focus != null && complete) {
            t.art = art;
            t.artFor = String.valueOf(subject);
        }
        return art;
    }

    /** The crest drawn on the black island: the dark-background variant where there is one. */
    private Bitmap islandCrest(Team team) {
        Bitmap b = app.images().load(team.crest(true), 160);
        return b != null ? b : app.images().load(team.logo, 160);
    }

    /** A race's news: a new leader, then the final lap. Practice and qualifying reorder too often to flash. */
    private static void raced(Tracked t, Match old, Match fresh, long now) {
        String was = old.standings.isEmpty() ? "" : old.standings.get(0).name;
        String is = fresh.standings.isEmpty() ? "" : fresh.standings.get(0).name;
        if (!was.isEmpty() && !is.isEmpty() && !was.equals(is)) {
            moment(t, new IslandRenderer.Flash("NEW LEADER", null), now);
        } else if (fresh.laps > 0 && fresh.period >= fresh.laps && old.period < fresh.laps) {
            moment(t, new IslandRenderer.Flash("FINAL LAP", null), now);
        }
    }

    /**
     * One rule for every sport: a score the island announces is one rare enough to be news there.
     * Goals (football, hockey), touchdowns and field goals, runs in baseball. Basketball scores too
     * often to flash. Push alerts are kept for the biggest: goals and touchdowns.
     */
    private void scored(Tracked t, Match old, Match fresh, boolean alerts, long now) {
        int homeGain = fresh.home.scoreInt() - old.home.scoreInt();
        int awayGain = fresh.away.scoreInt() - old.away.scoreInt();
        if (homeGain <= 0 && awayGain <= 0) return;
        boolean home = homeGain >= awayGain;
        Match.Side side = home ? fresh.home : fresh.away;
        int gain = home ? homeGain : awayGain;
        String word;
        switch (fresh.sport) {
            case SOCCER:
            case HOCKEY:
                word = "GOAL";
                break;
            case FOOTBALL:
                word = gain >= 6 ? "TOUCHDOWN" : gain == 3 ? "FIELD GOAL" : null;
                break;
            case BASEBALL:
                word = gain == 1 ? "RUN" : gain + " RUNS";
                break;
            default:
                word = null;
        }
        if (word == null) return;
        moment(t, new IslandRenderer.Flash(word, side), now);
        if (!alerts || !("GOAL".equals(word) || "TOUCHDOWN".equals(word))) return;

        Match.Moment last = null;
        for (Match.Moment g : fresh.moments) if (g.isGoal() && g.home == home) last = g;
        String text = fresh.home.team.shortName + " " + fresh.home.score + " – " + fresh.away.score + " " + fresh.away.team.shortName
                + (last == null ? "" : " · " + last.player + " " + last.clock);
        int title = "GOAL".equals(word) ? R.string.alert_goal : R.string.alert_touchdown;
        Notifications.alert(this, fresh, getString(title, side.team.shortName), text, art(t).focus);
    }

    /** Wickets down across both sides, from scores like "175/6". */
    private static int wickets(Match m) {
        return wicketsOf(m.home.score) + wicketsOf(m.away.score);
    }

    private static int wicketsOf(String score) {
        int slash = score.lastIndexOf('/');
        if (slash < 0) return score.trim().matches("[0-9]+") ? 10 : 0;
        try {
            return Integer.parseInt(score.substring(slash + 1).replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isScreenOn() {
        return getSystemService(PowerManager.class).isInteractive();
    }
}
