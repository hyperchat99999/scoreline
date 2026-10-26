package app.scoreline.live;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.Settings;

import java.util.List;

import app.scoreline.R;
import app.scoreline.model.Match;
import app.scoreline.ui.HomeActivity;
import app.scoreline.ui.MatchActivity;

/** Notification channels and the one-off notifications: alerts and "tap to follow" reminders. */
public final class Notifications {
    private Notifications() {}

    public static final String LIVE = "live";
    public static final String ALERTS = "alerts";
    public static final String SUGGEST = "suggest";
    public static final String SERVICE = "service";
    public static final String CARDS = "cards";

    public static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel live = new NotificationChannel(LIVE, c.getString(R.string.ch_live), NotificationManager.IMPORTANCE_DEFAULT);
        live.setDescription(c.getString(R.string.ch_live_body));
        live.setSound(null, null);
        live.enableVibration(false);
        live.setShowBadge(false);
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel alerts = new NotificationChannel(ALERTS, c.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(c.getString(R.string.ch_alerts_body));
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel suggest = new NotificationChannel(SUGGEST, c.getString(R.string.ch_suggest), NotificationManager.IMPORTANCE_DEFAULT);
        suggest.setDescription(c.getString(R.string.ch_suggest_body));
        suggest.setShowBadge(false);

        NotificationChannel service = new NotificationChannel(SERVICE, c.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN);
        service.setDescription(c.getString(R.string.ch_service_body));
        service.setShowBadge(false);

        // Other followed matches on at the same time: quiet cards in the shade, never pop-ups.
        NotificationChannel cards = new NotificationChannel(CARDS, c.getString(R.string.ch_cards), NotificationManager.IMPORTANCE_LOW);
        cards.setDescription(c.getString(R.string.ch_cards_body));
        cards.setShowBadge(false);
        cards.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannels(List.of(live, alerts, suggest, service, cards));
    }

    public static boolean enabled(Context c) {
        return c.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    /**
     * Asks to post notifications. Android 13 added a permission for it; before that they're on
     * unless turned off in Settings, so that's where this goes instead (asking for a permission
     * the system doesn't have is refused without a prompt).
     */
    public static void ask(Activity a, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, requestCode);
        } else {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
        }
    }

    /** Opens the match screen with Home underneath, so Back behaves. */
    public static PendingIntent openMatch(Context c, String matchKey) {
        Intent home = new Intent(c, HomeActivity.class);
        Intent match = MatchActivity.intent(c, matchKey);
        return TaskStackBuilder.create(c).addNextIntent(home).addNextIntent(match)
                .getPendingIntent(matchKey.hashCode(), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** A heads-up for a kick-off, goal or result. Expires on its own; the island carries the rest. */
    public static void alert(Context c, Match m, String title, String text, Bitmap crest) {
        Notification.Builder b = new Notification.Builder(c, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_scoreline)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openMatch(c, m.key()))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setTimeoutAfter(10 * 60_000);
        if (crest != null) b.setLargeIcon(crest);
        c.getSystemService(NotificationManager.class).notify(("alert" + m.key() + title).hashCode(), b.build());
    }

    /**
     * Shown when Android won't let us start the island from the background. Tapping it starts the
     * island, which Android always allows from a notification.
     */
    public static void suggest(Context c, Match m, String name) {
        Notification n = new Notification.Builder(c, SUGGEST)
                .setSmallIcon(R.drawable.ic_stat_scoreline)
                .setContentTitle(c.getString(R.string.suggest_title, name))
                .setContentText(c.getString(R.string.suggest_body))
                .setContentIntent(LiveService.pinIntent(c, m.key()))
                .setAutoCancel(true)
                .setTimeoutAfter(3 * 3600_000L)
                .build();
        c.getSystemService(NotificationManager.class).notify(("suggest" + m.key()).hashCode(), n);
    }

    public static void cancelSuggestion(Context c, String matchKey) {
        c.getSystemService(NotificationManager.class).cancel(("suggest" + matchKey).hashCode());
    }
}
