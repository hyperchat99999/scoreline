package app.scoreline.live;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.List;

import app.scoreline.App;
import app.scoreline.data.Feed;
import app.scoreline.model.Follow;
import app.scoreline.model.Match;

/** Background check: starts the island if a followed match is on, and arms the next kick-off alarm. */
public final class CheckJob extends JobService {

    private final java.util.Map<Integer, Thread> tasks = new java.util.HashMap<>();

    @Override public boolean onStartJob(JobParameters params) {
        Thread task = new Thread(() -> {
            try { run((App) getApplication()); }
            finally {
                Thread completed = Thread.currentThread();
                new android.os.Handler(getMainLooper()).post(() -> {
                    Thread active = tasks.get(params.getJobId());
                    if (active == completed && !completed.isInterrupted()) {
                        tasks.remove(params.getJobId());
                        jobFinished(params, false);
                    }
                });
            }
        }, "check");
        Thread old = tasks.put(params.getJobId(), task);
        if (old != null) old.interrupt();
        task.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread task = tasks.remove(params.getJobId());
        if (task != null) task.interrupt();
        return true;
    }

    @Override public void onDestroy() {
        for (Thread task : tasks.values()) task.interrupt();
        tasks.clear();
        super.onDestroy();
    }

    static void run(App app) {
        if (app.store().follows().isEmpty() && !app.store().hasPins()) return;
        long now = System.currentTimeMillis();
        Feed.Snapshot s = app.feed().load(false);
        if (Thread.currentThread().isInterrupted()) return;
        List<Match> wanted = Tracker.wanted(s, app.store(), now);
        if (!wanted.isEmpty() && !LiveService.sync(app)) {
            // Restricted in the background: offer a one-tap start instead of failing silently.
            for (Match m : wanted) {
                Follow f = app.store().focus(m);
                Notifications.suggest(app, m, f != null ? f.name() : m.home != null ? m.home.team.shortName : m.title);
            }
        }
        long next = Tracker.nextStart(s, app.store(), now);
        if (next > 0) Scheduler.kickoffAt(app, next);
    }
}
