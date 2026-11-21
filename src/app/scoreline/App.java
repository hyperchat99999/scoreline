package app.scoreline;

import android.app.Application;

import java.io.File;

import app.scoreline.core.Http;
import app.scoreline.core.Images;
import app.scoreline.data.Espn;
import app.scoreline.data.Feed;
import app.scoreline.data.Browse;
import app.scoreline.data.Store;
import app.scoreline.live.Notifications;
import app.scoreline.live.Scheduler;
import app.scoreline.widgets.WidgetRepository;

/** Owns the app-wide singletons. Kept deliberately small: no DI framework for five objects. */
public final class App extends Application {
    private Store store;
    private Espn espn;
    private Feed feed;
    private Images images;
    private Browse browse;
    private WidgetRepository widgets;

    @Override
    public void onCreate() {
        super.onCreate();
        Http http = new Http(new File(getCacheDir(), "http"));
        store = new Store(this);
        widgets = new WidgetRepository(this, store);
        espn = new Espn(http);
        browse = new Browse(espn);
        feed = new Feed(espn, store);
        feed.setListener(snapshot -> {
            try { widgets.publish(snapshot.matches, snapshot.offline, snapshot.updatedAt); }
            catch (RuntimeException e) { android.util.Log.w("Scoreline", "Could not refresh sports widget", e); }
        });
        images = new Images(http);
        Notifications.createChannels(this);
        Scheduler.ensure(this);
        store.addListener(() -> Scheduler.checkNow(this));
        new Thread(() -> http.prune(21L * 24 * 3600_000), "prune").start();
    }

    public Store store() {
        return store;
    }

    public Espn espn() {
        return espn;
    }

    public Feed feed() {
        return feed;
    }

    public Browse browse() { return browse; }
    public WidgetRepository widgets() { return widgets; }

    public Images images() {
        return images;
    }
}
