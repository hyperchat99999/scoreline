package app.scoreline.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Wakes Scoreline when a followed match is about to start: a periodic check every 15 minutes as a
 * safety net, plus an inexact alarm shortly before the next known kick-off.
 */
public final class Scheduler {
    private Scheduler() {}

    static final int JOB_PERIODIC = 1;
    static final int JOB_NOW = 2;
    static final String ACTION_KICKOFF = "app.scoreline.action.KICKOFF";

    public static void ensure(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) != null) return;
        js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, CheckJob.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    /** Runs a check as soon as the system allows. */
    public static void checkNow(Context c) {
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExpedited(true)
                .build());
    }

    /** Arms a wake-up just before {@code startMs}, replacing any earlier one. */
    public static synchronized void kickoffAt(Context c, long startMs) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Receiver.class).setAction(ACTION_KICKOFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = Math.max(System.currentTimeMillis() + 60_000, startMs - Tracker.LEAD_IN);
        android.content.SharedPreferences p = c.getSharedPreferences("scheduler", Context.MODE_PRIVATE);
        long previous = p.getLong("at", 0);
        if (previous > System.currentTimeMillis() && previous <= at) return;
        p.edit().putLong("at", at).apply();
        c.getSystemService(AlarmManager.class).setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000, pi);
    }

    /** Whether Android lets us start the island from the background (battery optimisation off). */
    public static boolean unrestricted(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }
}
