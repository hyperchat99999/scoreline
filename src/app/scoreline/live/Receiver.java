package app.scoreline.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms scheduling after boot or an update, and handles the kick-off alarm. */
public final class Receiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        c.getSharedPreferences("scheduler", Context.MODE_PRIVATE).edit().remove("at").apply();
        if (Scheduler.ACTION_KICKOFF.equals(action)) {
            // Try to go live straight away; if Android refuses, the check job will offer a tap to start.
            if (!LiveService.sync(c)) Scheduler.checkNow(c);
        } else {
            Scheduler.ensure(c);
            Scheduler.checkNow(c);
        }
    }
}
