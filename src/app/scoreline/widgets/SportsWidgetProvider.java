package app.scoreline.widgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.SportVisuals;
import app.scoreline.core.Ui;
import app.scoreline.live.Scheduler;
import app.scoreline.model.Match;
import app.scoreline.model.Sport;
import app.scoreline.model.Status;
import app.scoreline.model.Team;
import app.scoreline.model.Timeline;
import app.scoreline.ui.DiscoverActivity;
import app.scoreline.ui.HomeActivity;
import app.scoreline.ui.MatchActivity;

/**
 * The launcher scoreboard. Broadcasts only render cached state or ask the existing job to check.
 *
 * Motion: RemoteViews can only animate by flipping ViewFlipper children, and only when the host
 * re-applies onto views it already shows. So each widget remembers which copy of the match page and
 * of each score is on screen, writes new values into the hidden copy, flips to it, and sends that as
 * a partial update. The layout is chosen from the widget's real size rather than a size map, so a
 * single RemoteViews can be partially applied.
 */
public class SportsWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_REFRESH = "app.scoreline.widgets.REFRESH";
    private static final String ACTION_NEXT = "app.scoreline.widgets.NEXT";
    private static final long SPINNER_TIMEOUT = 25_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final LruCache<String, Bitmap> GLOWS = new LruCache<>(16);

    /** The three launcher sizes; each is its own generated layout. */
    public enum Size {
        SMALL(R.layout.widget_sports_small), MEDIUM(R.layout.widget_sports), LARGE(R.layout.widget_sports_large);
        final int layout;
        Size(int layout) { this.layout = layout; }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        update(context, manager, widgetIds, true);
        Scheduler.checkNow(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        update(context, manager, new int[] {widgetId}, true);
    }

    @Override public void onDeleted(Context context, int[] widgetIds) {
        repository(context).remove(widgetIds);
        SharedPreferences.Editor editor = views(context).edit();
        for (int id : widgetIds) editor.remove("v:" + id);
        editor.apply();
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) || ACTION_NEXT.equals(action)) {
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            AppWidgetManager manager = context.getSystemService(AppWidgetManager.class);
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
            if (info == null || !info.provider.getPackageName().equals(context.getPackageName())) return;
            if (ACTION_NEXT.equals(action)) repository(context).cycle(widgetId);
            else {
                repository(context).refreshRequested();
                Scheduler.checkNow(context);
                Context app = context.getApplicationContext();
                MAIN.postDelayed(() -> updateAll(app), SPINNER_TIMEOUT + 200);
            }
            update(context, manager, new int[] {widgetId}, false);
            return;
        }
        super.onReceive(context, intent);
    }

    /** The launcher owns confirmation and placement; false means use its widget picker. */
    public static boolean pin(Context context) {
        AppWidgetManager manager = context.getSystemService(AppWidgetManager.class);
        if (!manager.isRequestPinAppWidgetSupported()) return false;
        return manager.requestPinAppWidget(new ComponentName(context, SportsWidgetProvider.class), null, null);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = context.getSystemService(AppWidgetManager.class);
        List<Integer> all = new ArrayList<>();
        for (Class<?> provider : new Class<?>[] {SportsWidgetProvider.class, ScoreTileProvider.class})
            for (int id : manager.getAppWidgetIds(new ComponentName(context, provider))) all.add(id);
        if (all.isEmpty()) return;
        int[] ids = new int[all.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = all.get(i);
        update(context, manager, ids, false);
    }

    private static WidgetRepository repository(Context context) {
        return ((App) context.getApplicationContext()).widgets();
    }

    private static SharedPreferences views(Context context) {
        return context.getSharedPreferences("scoreline_widget_views", Context.MODE_PRIVATE);
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids, boolean full) {
        WidgetRepository repository = repository(context);
        WidgetSnapshot snapshot = repository.snapshot();
        long now = System.currentTimeMillis();
        WidgetMoments.Moment moment = repository.moment(now);
        Match momentMatch = null;
        if (moment != null) for (Match m : snapshot.matches) if (m.key().equals(moment.key)) momentMatch = m;
        boolean requested = repository.isRefreshRequested();
        boolean dark = Ui.isNight(context);
        long build = buildStamp(context);
        for (int id : ids) {
            Size size = size(manager, id);
            Match selected = repository.selected(id);
            State state = State.load(views(context), id);
            boolean partial = !full && state.size == size && state.build == build;
            String key = selected == null ? "" : selected.key();
            if (partial && !state.key.isEmpty() && !key.isEmpty() && !key.equals(state.key)) state.page ^= 1;
            else if (partial && key.equals(state.key) && selected != null) {
                if (!score(context, selected, selected.home).equals(state.home)) state.homeCopy ^= 1;
                if (!score(context, selected, selected.away).equals(state.away)) state.awayCopy ^= 1;
            }
            state.key = key;
            state.home = selected == null ? "" : score(context, selected, selected.home);
            state.away = selected == null ? "" : score(context, selected, selected.away);
            state.size = size;
            state.build = build;
            state.save(views(context), id);
            RemoteViews views = render(context, snapshot, selected, id, size, state,
                    momentMatch == null ? null : moment, momentMatch, requested, now);
            if (partial) manager.partiallyUpdateAppWidget(id, views);
            else manager.updateAppWidget(id, views);
            WidgetLogos.request(context, selected, dark);
            if (momentMatch != null) WidgetLogos.request(context, momentMatch, dark);
        }
        if (momentMatch != null) {
            Context app = context.getApplicationContext();
            MAIN.postDelayed(() -> updateAll(app), moment.until - now + 150);
        }
    }

    /** Portrait size on this launcher: a 2-column tile, a 4×2 scoreboard, or 4×3 and taller. */
    private static Size size(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        boolean tile = info != null && info.provider.getClassName().equals(ScoreTileProvider.class.getName());
        if (width <= 0) return tile ? Size.SMALL : Size.MEDIUM;
        if (width < 220) return Size.SMALL;
        return height >= 235 ? Size.LARGE : Size.MEDIUM;
    }

    private static long buildStamp(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime; }
        catch (Exception e) { return 0; }
    }

    /** What one widget currently shows, so the next update knows which copies to flip. */
    static final class State {
        int page, homeCopy, awayCopy;
        String key = "", home = "", away = "";
        Size size;
        long build;

        static State load(SharedPreferences prefs, int id) {
            State s = new State();
            String[] f = prefs.getString("v:" + id, "").split("\u001f", -1);
            if (f.length < 8) return s;
            try {
                s.page = Integer.parseInt(f[0]) & 1; s.homeCopy = Integer.parseInt(f[1]) & 1; s.awayCopy = Integer.parseInt(f[2]) & 1;
                s.key = f[3]; s.home = f[4]; s.away = f[5]; s.size = Size.valueOf(f[6]); s.build = Long.parseLong(f[7]);
            } catch (IllegalArgumentException e) { return new State(); }
            return s;
        }

        void save(SharedPreferences prefs, int id) {
            prefs.edit().putString("v:" + id, String.join("\u001f", Integer.toString(page), Integer.toString(homeCopy),
                    Integer.toString(awayCopy), key, home, away, size.name(), Long.toString(build))).apply();
        }
    }

    // ---- Rendering --------------------------------------------------------------------------------

    /** Kept for instrumentation: renders one size with no motion state and no moment. */
    public static RemoteViews render(Context context, WidgetSnapshot snapshot, Match match, int widgetId,
                                     boolean expanded, int count, boolean requested) {
        State state = new State();
        return render(context, snapshot, match, widgetId, expanded ? Size.LARGE : Size.MEDIUM, state, null, null,
                requested, System.currentTimeMillis());
    }

    /** For instrumentation galleries: one size, optionally showing a moment banner for {@code match}. */
    public static RemoteViews render(Context context, WidgetSnapshot snapshot, Match match, int widgetId, Size size,
                                     WidgetMoments.Moment moment) {
        return render(context, snapshot, match, widgetId, size, new State(), moment, moment == null ? null : match,
                false, System.currentTimeMillis());
    }

    public static RemoteViews render(Context context, WidgetSnapshot snapshot, Match match, int widgetId, Size size,
                                     State state, WidgetMoments.Moment moment, Match momentMatch, boolean requested, long now) {
        RemoteViews v = new RemoteViews(context.getPackageName(), size.layout);
        boolean dark = Ui.isNight(context);
        PendingIntent scores = activity(context, widgetId, "scores", new Intent(context, HomeActivity.class).putExtra("tab", "scores"));
        v.setOnClickPendingIntent(R.id.widget_open, scores);
        v.setOnClickPendingIntent(R.id.widget_refresh_flip, broadcast(context, widgetId, ACTION_REFRESH));
        v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, widgetId, ACTION_NEXT));
        v.setDisplayedChild(R.id.widget_refresh_flip, requested ? 1 : 0);
        int count = snapshot.matches.size();
        v.setViewVisibility(R.id.widget_next, count > 1 ? View.VISIBLE : View.GONE);

        // Big moments take the whole surface; the scoreboard underneath stays current.
        v.setDisplayedChild(R.id.widget_moment_flip, moment != null ? 1 : 0);
        if (moment != null) moment(context, v, widgetId, moment, momentMatch, dark);

        int accent = match == null ? context.getColor(R.color.widget_accent) : SportVisuals.ink(context, match.sport);
        v.setInt(R.id.widget_sport, "setColorFilter", accent);
        if (match == null) {
            empty(context, v, widgetId, requested);
            return v;
        }
        v.setViewVisibility(R.id.widget_empty, View.GONE);
        v.setViewVisibility(R.id.widget_pages, View.VISIBLE);
        v.setImageViewResource(R.id.widget_sport, WidgetMoments.sportGlyph(match.sport));
        v.setTextViewText(R.id.widget_league, match.leagueName.isEmpty() ? context.getString(match.sport.label) : match.leagueName);
        v.setImageViewBitmap(R.id.widget_glow, glow(context, match, dark));

        boolean delayed = snapshot.delayed(match, now);
        boolean overdue = WidgetSelection.overdue(match, now);
        pill(context, v, match, delayed, overdue);

        PendingIntent open = activity(context, widgetId, match.key(), MatchActivity.intent(context, match));
        String title = match.sport == Sport.RACING ? match.title : context.getString(R.string.widget_vs,
                match.home == null ? "" : match.home.team.name, match.away == null ? "" : match.away.team.name);
        for (String s : new String[] {"", "_b"}) {
            v.setOnClickPendingIntent(id(context, "widget_match" + s), open);
            v.setContentDescription(id(context, "widget_match" + s), context.getString(R.string.widget_open_match, title));
            // Every page keeps its score copies aligned, so a page flip never also rolls the digits.
            v.setDisplayedChild(id(context, "widget_home_score_flip" + s), state.homeCopy);
            v.setDisplayedChild(id(context, "widget_away_score_flip" + s), state.awayCopy);
        }
        v.setDisplayedChild(R.id.widget_pages, state.page);
        String s = state.page == 0 ? "" : "_b";
        if (match.sport == Sport.RACING) race(context, v, s, match, size);
        else sides(context, v, s, match, size, state, dark);
        phase(context, v, s, match, size);
        footer(context, v, match, snapshot, size, delayed, requested);
        if (size == Size.LARGE) upNext(context, v, widgetId, snapshot.matches, match);
        return v;
    }

    private static void empty(Context context, RemoteViews v, int widgetId, boolean requested) {
        PendingIntent explore = activity(context, widgetId, "explore", new Intent(context, DiscoverActivity.class));
        v.setViewVisibility(R.id.widget_pages, View.GONE);
        v.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        v.setOnClickPendingIntent(R.id.widget_empty, explore);
        v.setContentDescription(R.id.widget_empty, context.getString(R.string.widget_empty_action));
        v.setImageViewResource(R.id.widget_sport, R.drawable.glyph_trophy);
        v.setTextViewText(R.id.widget_league, context.getString(R.string.app_name));
        v.setViewVisibility(R.id.widget_state_flip, View.GONE);
        v.setImageViewBitmap(R.id.widget_glow, null);
        v.setTextViewText(R.id.widget_updated, requested ? context.getString(R.string.widget_requested) : "");
        v.setOnClickPendingIntent(R.id.widget_updated, explore);
    }

    private static void pill(Context context, RemoteViews v, Match match, boolean delayed, boolean overdue) {
        v.setViewVisibility(R.id.widget_state_flip, View.VISIBLE);
        if (match.isLive() && !delayed) {
            String clock = Status.compact(match);
            v.setTextViewText(R.id.widget_live_text, clock.isEmpty() || clock.length() > 7 || match.sport == Sport.RACING
                    ? context.getString(R.string.widget_live) : context.getString(R.string.widget_live_clock, clock));
            v.setDisplayedChild(R.id.widget_state_flip, 1);
            return;
        }
        String text;
        if (match.isLive()) text = context.getString(R.string.widget_delayed_pill);
        else if (overdue) text = context.getString(R.string.widget_overdue);
        else if (match.state == Match.State.PRE) text = Ui.day(context, match.start).toUpperCase(java.util.Locale.getDefault());
        else if (match.state == Match.State.POST) text = Status.finished(match).toUpperCase(java.util.Locale.getDefault());
        else text = context.getString(R.string.widget_unavailable);
        if (match.state == Match.State.PRE && match.start <= 0) text = context.getString(R.string.widget_upcoming);
        v.setTextViewText(R.id.widget_state, text);
        v.setDisplayedChild(R.id.widget_state_flip, 0);
    }

    private static void sides(Context context, RemoteViews v, String s, Match match, Size size, State state, boolean dark) {
        v.setViewVisibility(id(context, "widget_sides" + s), View.VISIBLE);
        v.setViewVisibility(id(context, "widget_race" + s), View.GONE);
        boolean pre = match.state == Match.State.PRE;
        boolean largeText = context.getResources().getConfiguration().fontScale > 1.3f;
        Match.Side winner = match.state != Match.State.POST ? null : match.home != null && match.home.winner ? match.home
                : match.away != null && match.away.winner ? match.away : leader(match);
        int fg = context.getColor(R.color.widget_foreground), muted = context.getColor(R.color.widget_secondary);
        for (int i = 0; i < 2; i++) {
            Match.Side side = i == 0 ? match.home : match.away;
            String p = i == 0 ? "widget_home" : "widget_away";
            int copy = i == 0 ? state.homeCopy : state.awayCopy;
            team(context, v, p, s, side, match, size, largeText, dark);
            int score = id(context, p + (copy == 0 ? "_score" : "_score_alt") + s);
            v.setViewVisibility(id(context, p + "_score_flip" + s), pre && size != Size.SMALL ? View.GONE : View.VISIBLE);
            v.setTextViewText(score, score(context, match, side));
            boolean lost = winner != null && side != winner;
            v.setTextColor(score, lost ? muted : fg);
            v.setTextColor(id(context, p + s), lost ? muted : fg);
            // The launcher re-applies onto the views it already shows, so every size is set every time.
            v.setTextViewTextSize(score, TypedValue.COMPLEX_UNIT_SP, scoreSize(match, size, largeText));
        }
        boolean kickoff = pre && size != Size.SMALL;
        v.setViewVisibility(id(context, "widget_kickoff" + s), kickoff ? View.VISIBLE : View.GONE);
        if (kickoff) {
            v.setTextViewText(id(context, "widget_kickoff_time" + s), match.start > 0 ? Ui.time(context, match.start) : "TBC");
            v.setTextViewText(id(context, "widget_kickoff_day" + s), context.getString(match.sport == Sport.SOCCER || match.sport == Sport.FOOTBALL
                    ? R.string.widget_kickoff_label : R.string.widget_starts_label));
        }
    }

    private static void team(Context context, RemoteViews v, String p, String s, Match.Side side, Match match,
                             Size size, boolean largeText, boolean dark) {
        if (side == null) return;
        Team team = side.team;
        boolean shortName = size == Size.SMALL || largeText || match.sport == Sport.CRICKET;
        v.setTextViewText(id(context, p + s), shortName ? team.abbr : team.shortName);
        int color = teamColor(context, team, match.sport, dark);
        v.setColorStateList(id(context, p + "_mark" + s), "setBackgroundTintList", ColorStateList.valueOf(withAlpha(color, dark ? 0x52 : 0x2E)));
        v.setColorStateList(id(context, p + "_badge" + s), "setBackgroundTintList", ColorStateList.valueOf(color));
        v.setTextColor(id(context, p + "_badge" + s), Color.luminance(color) > .5 ? 0xFF1B1B1F : Color.WHITE);
        v.setTextViewText(id(context, p + "_badge" + s), team.abbr.substring(0, Math.min(3, team.abbr.length())));
        Bitmap crest = WidgetLogos.cached(context, team, dark);
        int image = id(context, p + "_crest" + s), badge = id(context, p + "_badge" + s);
        if (crest != null) {
            v.setImageViewBitmap(image, crest);
            v.setViewVisibility(image, View.VISIBLE);
            v.setViewVisibility(badge, View.GONE);
        } else {
            v.setViewVisibility(image, View.GONE);
            v.setViewVisibility(badge, View.VISIBLE);
        }
        // Cricket: overs under the name (large only; the pill carries them elsewhere), a dot on the batting side.
        String sub = "";
        boolean batting = match.sport == Sport.CRICKET && match.isLive() && team.id.equals(match.battingTeamId) && size != Size.SMALL;
        if (match.sport == Sport.CRICKET && size == Size.LARGE) {
            if (!side.overs.isEmpty()) sub = context.getString(R.string.widget_overs, side.overs);
            else if (match.isLive() && side.score.isEmpty()) sub = context.getString(R.string.widget_yet_to_bat);
        }
        v.setTextViewText(id(context, p + "_sub" + s), sub);
        v.setViewVisibility(id(context, p + "_sub" + s), sub.isEmpty() ? View.GONE : View.VISIBLE);
        v.setViewVisibility(id(context, p + "_serve" + s), batting ? View.VISIBLE : View.GONE);
    }

    private static void race(Context context, RemoteViews v, String s, Match match, Size size) {
        v.setViewVisibility(id(context, "widget_sides" + s), View.GONE);
        v.setViewVisibility(id(context, "widget_race" + s), View.VISIBLE);
        v.setViewVisibility(id(context, "widget_kickoff" + s), View.GONE);
        v.setTextViewText(id(context, "widget_event" + s), match.title.isEmpty() ? match.leagueName : match.title);
        // With large system text a third driver would push the board out of the cell; two stay readable.
        boolean bigText = context.getResources().getConfiguration().fontScale > 1.3f;
        int rows = size == Size.LARGE && !bigText ? 3 : 2;
        int accent = SportVisuals.accent(Sport.RACING);
        for (int i = 1; i <= 3; i++) {
            String p = "widget_driver" + i;
            boolean show = i <= rows && (i == 1 || match.standings.size() >= i);
            v.setViewVisibility(id(context, p + "_row" + s), show ? View.VISIBLE : View.GONE);
            if (!show) continue;
            if (match.standings.isEmpty()) {
                v.setTextViewText(id(context, p + s), context.getString(match.state == Match.State.PRE
                        ? R.string.widget_pending_session : R.string.widget_driver_waiting));
                v.setTextViewText(id(context, p + "_gap" + s), "");
                v.setViewVisibility(id(context, p + "_pos" + s), View.GONE);
                continue;
            }
            Match.Standing driver = match.standings.get(i - 1);
            int color = driver.color != 0 ? driver.color | 0xFF000000 : accent;
            v.setViewVisibility(id(context, p + "_pos" + s), View.VISIBLE);
            v.setTextViewText(id(context, p + "_pos" + s), Integer.toString(driver.position > 0 ? driver.position : i));
            v.setColorStateList(id(context, p + "_pos" + s), "setBackgroundTintList", ColorStateList.valueOf(color));
            v.setTextColor(id(context, p + "_pos" + s), Color.luminance(color) > .5 ? 0xFF15151E : Color.WHITE);
            v.setTextViewText(id(context, p + s), driver.shortName.isEmpty() ? driver.name : driver.shortName);
            v.setTextViewText(id(context, p + "_gap" + s), i == 1 && match.isLive() ? context.getString(R.string.widget_leader) : driver.gap);
        }
    }

    /** The clock or situation line: the tile's only context, the big scoreboard's headline. */
    private static void phase(Context context, RemoteViews v, String s, Match match, Size size) {
        String phase;
        if (match.state == Match.State.PRE) phase = match.start > 0 ? Ui.when(context, match.start) : context.getString(R.string.widget_waiting);
        else if (match.sport == Sport.RACING) phase = join(match.session, Timeline.of(match).phase(match));
        else if (match.sport == Sport.CRICKET && !match.summary.isEmpty()) phase = match.summary;
        else phase = Status.full(match);
        if (size == Size.LARGE && match.state == Match.State.PRE) phase = match.venue;
        boolean show = size != Size.MEDIUM && !phase.isEmpty();
        v.setTextViewText(id(context, "widget_phase" + s), phase);
        v.setViewVisibility(id(context, "widget_phase" + s), show ? View.VISIBLE : View.GONE);
        if (size != Size.LARGE) return;
        Timeline timeline = Timeline.of(match);
        // A sampled source clock is useful progress; an invented percentage isn't.
        boolean progress = match.isLive() && !timeline.indeterminate && timeline.value > 0 && timeline.max > 0
                && (match.sport != Sport.RACING || match.isRace());
        String detail = match.state == Match.State.LIVE && match.sport != Sport.CRICKET && !match.summary.isEmpty() ? match.summary : "";
        v.setViewVisibility(id(context, "widget_context" + s), progress || !detail.isEmpty() ? View.VISIBLE : View.GONE);
        v.setTextViewText(id(context, "widget_detail" + s), detail);
        v.setViewVisibility(id(context, "widget_detail" + s), detail.isEmpty() ? View.GONE : View.VISIBLE);
        v.setViewVisibility(id(context, "widget_progress" + s), progress ? View.VISIBLE : View.GONE);
        if (progress) {
            v.setProgressBar(id(context, "widget_progress" + s), timeline.max, Math.min(timeline.value, timeline.max), false);
            v.setColorStateList(id(context, "widget_progress" + s), "setProgressTintList",
                    ColorStateList.valueOf(SportVisuals.ink(context, match.sport)));
        }
    }

    private static void footer(Context context, RemoteViews v, Match match, WidgetSnapshot snapshot, Size size,
                               boolean delayed, boolean requested) {
        long updatedAt = match.checkedAt > 0 ? match.checkedAt : snapshot.updatedAt;
        String time = updatedAt > 0 ? Ui.time(context, updatedAt) : "";
        String text;
        if (requested) text = context.getString(R.string.widget_requested);
        else if (updatedAt <= 0) text = context.getString(R.string.widget_saved);
        else if (delayed) text = context.getString(R.string.widget_delayed, time);
        else {
            String headline = "";
            if (size == Size.MEDIUM) {
                if (match.state == Match.State.PRE) headline = match.venue;
                else if (match.sport == Sport.CRICKET || match.state == Match.State.POST) headline = match.summary;
                else if (match.sport == Sport.RACING) headline = Timeline.of(match).phase(match);
                else headline = Status.full(match);
            }
            // Freshness leads, so a long venue or summary is what gets cut, never the "Updated" time.
            String updated = context.getString(R.string.widget_updated, time);
            text = headline.isEmpty() ? updated : updated + " · " + headline;
        }
        v.setTextViewText(R.id.widget_updated, text);
    }

    private static void upNext(Context context, RemoteViews v, int widgetId, List<Match> ordered, Match selected) {
        int shown = 0;
        for (Match m : ordered) {
            if (shown == 2) break;
            if (m.key().equals(selected.key())) continue;
            shown++;
            String p = "widget_up" + shown;
            String when;
            if (m.isLive()) when = context.getString(R.string.widget_live);
            else if (m.state == Match.State.PRE) when = m.start <= 0 ? "TBC" : Ui.isToday(m.start) ? Ui.time(context, m.start) : Ui.day(context, m.start);
            else when = Status.finished(m);
            String teams = m.sport == Sport.RACING || m.home == null || m.away == null ? m.title
                    : m.state == Match.State.PRE ? m.home.team.shortName + " v " + m.away.team.shortName
                    : m.home.team.abbr + " " + m.home.score + " – " + m.away.score + " " + m.away.team.abbr;
            v.setTextViewText(id(context, p + "_time"), when);
            v.setTextColor(id(context, p + "_time"), m.isLive() ? context.getColor(R.color.widget_live) : context.getColor(R.color.widget_secondary));
            v.setTextViewText(id(context, p + "_teams"), teams);
            v.setViewVisibility(id(context, p), View.VISIBLE);
            v.setOnClickPendingIntent(id(context, p), activity(context, widgetId, m.key(), MatchActivity.intent(context, m)));
        }
        for (int i = shown + 1; i <= 2; i++) v.setViewVisibility(id(context, "widget_up" + i), View.GONE);
        v.setViewVisibility(R.id.widget_upnext, shown > 0 ? View.VISIBLE : View.GONE);
    }

    /** The takeover: team colour, crest on a white disc, the word, then the detail, flipping. */
    private static void moment(Context context, RemoteViews v, int widgetId, WidgetMoments.Moment moment, Match match, boolean dark) {
        Match.Side side = moment.side(match);
        int color;
        if (side != null) color = banner(context, side.team, match.sport);
        else if (moment.kind == WidgetMoments.Kind.FINISH) color = 0xFF2B2B30;
        else if (match.sport == Sport.RACING && !match.standings.isEmpty() && match.standings.get(0).color != 0)
            color = tone(match.standings.get(0).color | 0xFF000000, SportVisuals.accent(match.sport));
        else color = tone(SportVisuals.accent(match.sport), 0xFF2B2B30);
        if (moment.kind == WidgetMoments.Kind.WICKET && side == null) color = 0xFFC4202F;
        v.setColorStateList(R.id.widget_moment, "setBackgroundTintList", ColorStateList.valueOf(color));
        int glyph = WidgetMoments.glyph(match.sport, moment.kind);
        v.setImageViewResource(R.id.widget_moment_art, glyph);

        Bitmap crest = side == null ? null : WidgetLogos.cached(context, side.team, false);
        if (crest != null) {
            v.setImageViewBitmap(R.id.widget_moment_crest, crest);
            v.setInt(R.id.widget_moment_crest, "setColorFilter", 0);
            v.setViewVisibility(R.id.widget_moment_crest, View.VISIBLE);
            v.setViewVisibility(R.id.widget_moment_badge, View.GONE);
        } else if (side != null) {
            v.setTextViewText(R.id.widget_moment_badge, side.team.abbr);
            v.setTextColor(R.id.widget_moment_badge, color);
            v.setViewVisibility(R.id.widget_moment_badge, View.VISIBLE);
            v.setViewVisibility(R.id.widget_moment_crest, View.GONE);
        } else {
            v.setImageViewResource(R.id.widget_moment_crest, glyph);
            v.setInt(R.id.widget_moment_crest, "setColorFilter", color);
            v.setViewVisibility(R.id.widget_moment_crest, View.VISIBLE);
            v.setViewVisibility(R.id.widget_moment_badge, View.GONE);
        }

        String title = moment.kind == WidgetMoments.Kind.RUNS && moment.amount > 1
                ? context.getString(R.string.moment_runs, moment.amount) : context.getString(WidgetMoments.title(match.sport, moment.kind));
        String detail = moment.detail;
        if (detail.isEmpty()) {
            switch (moment.kind) {
                case FINISH:
                    detail = side != null ? context.getString(R.string.moment_wins, side.team.shortName)
                            : match.sport.headToHead() ? context.getString(R.string.moment_draw) : "";
                    break;
                case KICKOFF:
                    detail = match.sport.headToHead() ? match.home.team.abbr + " v " + match.away.team.abbr : match.title;
                    break;
                default:
                    detail = side == null ? "" : side.team.shortName;
            }
        }
        v.setTextViewText(R.id.widget_moment_title, title);
        v.setTextViewText(R.id.widget_moment_detail, detail.isEmpty() ? title : detail);
        String line;
        if (match.sport == Sport.RACING) line = join(match.title, Timeline.of(match).phase(match));
        else if (match.sport == Sport.CRICKET) {
            // Both innings plus overs never fit a banner line; the batting side's total is the story.
            Match.Side batting = match.home.team.id.equals(match.battingTeamId) ? match.home : match.away;
            line = batting.team.abbr + " " + batting.score + (batting.overs.isEmpty() ? "" : " (" + batting.overs + ")");
        } else if (match.home.score.isEmpty() && match.away.score.isEmpty()) {
            line = match.leagueName.isEmpty() ? context.getString(match.sport.label) : match.leagueName;
        } else line = match.home.team.abbr + " " + match.home.score + " – " + match.away.score + " " + match.away.team.abbr;
        String clock = match.isLive() && match.sport != Sport.CRICKET ? Status.compact(match) : "";
        v.setTextViewText(R.id.widget_moment_score, clock.isEmpty() || match.sport == Sport.RACING ? line : line + " · " + clock);
        v.setOnClickPendingIntent(R.id.widget_moment, activity(context, widgetId, "moment:" + match.key(), MatchActivity.intent(context, match)));
        v.setContentDescription(R.id.widget_moment, title + " " + detail);
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /** A rich banner fill under white type: the feed colour, else the crest's, else the sport's. */
    /** Runs {@code done} on the main thread once a colourless team's crest is loaded and its colour readable. */
    public static void whenCrestRead(Context context, Team team, Runnable done) { WidgetLogos.whenCrestRead(context, team, done); }

    public static int banner(Context context, Team team, Sport sport) {
        int c = tone(Ui.color(team.color, 0), 0);
        if (c == 0) c = tone(WidgetLogos.crestColor(context, team), 0);
        return c != 0 ? c : tone(SportVisuals.accent(sport), 0xFF2B2B30);
    }

    private static int tone(int color, int fallback) { return SportVisuals.tone(color, fallback); }

    /** Matches tools/gen_widgets.py SIZES; cricket's "119/6" needs a little less than a goal count. */
    private static float scoreSize(Match match, Size size, boolean largeText) {
        float base = size == Size.SMALL ? 22 : size == Size.MEDIUM ? 26 : 32;
        if (match.sport == Sport.CRICKET) base -= size == Size.SMALL ? 4 : 4;
        if (largeText) base -= size == Size.SMALL ? 2 : 6;
        return base;
    }

    static String score(Context context, Match match, Match.Side side) {
        if (side == null || side.score.isEmpty() || match.state == Match.State.PRE) return context.getString(R.string.widget_no_score);
        return side.score;
    }

    private static Match.Side leader(Match match) {
        if (match.home == null || match.away == null) return null;
        String a = match.home.score, b = match.away.score;
        if (!a.matches("[0-9]{1,3}") || !b.matches("[0-9]{1,3}")) return null;
        int home = Integer.parseInt(a), away = Integer.parseInt(b);
        return home == away ? null : home > away ? match.home : match.away;
    }

    /** A team colour that holds up as a disc and a glow on either surface; the sport's when absent. */
    static int teamColor(Team team, Sport sport, boolean dark) {
        return teamColor(null, team, sport, dark);
    }

    /** The feed's team colour, else the crest's own colour once it is loaded, else the sport's accent. */
    public static int teamColor(Context context, Team team, Sport sport, boolean dark) {
        int fallback = SportVisuals.accent(sport);
        if (context != null && (team.color == null || team.color.trim().isEmpty())) {
            int crest = WidgetLogos.crestColor(context, team);
            if (crest != 0) fallback = crest;
        }
        int color = Ui.color(team.color, fallback) | 0xFF000000;
        double l = Color.luminance(color);
        if (l > .8) color = Ui.mix(color, Color.BLACK, .35f);
        if (dark && l < .04) color = Ui.mix(color, Color.WHITE, .3f);
        return color;
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    /** The two teams' colours washed diagonally across the card, like a broadcast score bug. */
    private static Bitmap glow(Context context, Match match, boolean dark) {
        int a, b;
        if (match.sport == Sport.RACING || match.home == null || match.away == null) {
            a = !match.standings.isEmpty() && match.standings.get(0).color != 0 ? match.standings.get(0).color | 0xFF000000 : SportVisuals.accent(match.sport);
            b = a;
        } else {
            a = teamColor(context, match.home.team, match.sport, dark);
            b = teamColor(context, match.away.team, match.sport, dark);
        }
        int alpha = dark ? 0x3A : 0x24;
        if (match.isLive()) alpha += dark ? 0x10 : 0x0A;
        String key = a + ":" + b + ":" + alpha;
        Bitmap cached = GLOWS.get(key);
        if (cached != null) return cached;
        int w = 96, h = 48;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, w, h, new int[] {withAlpha(a, alpha), withAlpha(a, alpha / 4), withAlpha(b, alpha / 4), withAlpha(b, alpha)},
                new float[] {0f, .42f, .58f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(out).drawRect(0, 0, w, h, paint);
        GLOWS.put(key, out);
        return out;
    }

    private static int id(Context context, String name) {
        synchronized (IDS) {
            Integer id = IDS.get(name);
            if (id == null) {
                id = context.getResources().getIdentifier(name, "id", context.getPackageName());
                IDS.put(name, id);
            }
            return id;
        }
    }

    private static PendingIntent activity(Context context, int id, String key, Intent intent) {
        intent.setData(new Uri.Builder().scheme("scoreline").authority("widget").appendPath(Integer.toString(id)).appendPath(key).build());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent broadcast(Context context, int id, String action) {
        Intent intent = new Intent(context, SportsWidgetProvider.class).setAction(action)
                .setData(new Uri.Builder().scheme("scoreline").authority("widget").appendPath(Integer.toString(id)).appendPath(action).build())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String join(String a, String b) { return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b; }
}
