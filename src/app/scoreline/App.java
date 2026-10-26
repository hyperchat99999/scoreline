package app.scoreline;

import android.app.Application;

import java.io.File;

import app.scoreline.core.Http;
import app.scoreline.core.Images;
import app.scoreline.data.Espn;
import app.scoreline.data.Feed;
import app.scoreline.data.Store;
import app.scoreline.live.Notifications;
import app.scoreline.live.Scheduler;

/** Owns the app-wide singletons. Kept deliberately small: no DI framework for five objects. */
public final class App extends Application {
    private Store store;
    private Espn espn;
    private Feed feed;
    private Images images;

    @Override
    public void onCreate() {
        super.onCreate();
        Http http = new Http(new File(getCacheDir(), "http"));
        store = new Store(this);
        espn = new Espn(http);
        feed = new Feed(espn, store);
        images = new Images(http);
        Notifications.createChannels(this);
        Scheduler.ensure(this);
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

    public Images images() {
        return images;
    }
}
