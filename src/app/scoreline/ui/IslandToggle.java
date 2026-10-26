package app.scoreline.ui;

import android.Manifest;
import android.content.pm.PackageManager;

import java.util.List;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Store;
import app.scoreline.live.LiveService;
import app.scoreline.live.Scheduler;
import app.scoreline.live.Tracker;
import app.scoreline.model.Match;

/** The "Island" button's logic, shared by match cards and the match screen. */
final class IslandToggle {
    private IslandToggle() {}

    /** Whether the button should read as on. */
    /**
     * On when this match owns the island now, or is pinned for later. A followed match that's on
     * but showing as a card in the shade reads as off: tapping it swaps it into the island.
     */
    static boolean isOn(App app, Match m) {
        Store s = app.store();
        long now = System.currentTimeMillis();
        if (!Tracker.isDue(m, now)) return s.isPinned(m.key());
        List<Match> wanted = Tracker.wanted(app.feed().last(), s, now);
        return !wanted.isEmpty() && wanted.get(0).key().equals(m.key());
    }

    /** Whether the island would show this match right now. */
    static boolean isActive(Match m) {
        return Tracker.isDue(m, System.currentTimeMillis());
    }

    /** Only live and upcoming matches can go in the island. */
    static boolean offered(Match m) {
        return Tracker.canShow(m, System.currentTimeMillis());
    }

    static void toggle(BaseActivity a, Match m) {
        App app = a.app();
        Store store = app.store();
        if (isOn(app, m)) {
            store.hide(m.key());
            LiveService.sync(a);
            a.toast(R.string.island_removed);
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        store.pin(m);
        if (isActive(m)) {
            if (LiveService.pin(a, m.key())) a.toast(R.string.island_added);
            else a.toast(R.string.island_failed);
        } else {
            Scheduler.kickoffAt(a, m.start);
            a.toast(R.string.island_scheduled, Ui.when(a, m.start - Tracker.LEAD_IN));
        }
    }
}
