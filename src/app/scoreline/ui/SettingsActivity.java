package app.scoreline.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.TextView;

import app.scoreline.R;
import app.scoreline.data.Store;
import app.scoreline.live.LiveService;
import app.scoreline.live.Scheduler;

/** A short list of settings that matter; everything else has a sensible default. */
public final class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        applyInsets(findViewById(R.id.root), findViewById(R.id.top), findViewById(R.id.content));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        Store store = app().store();

        bind(findViewById(R.id.auto), R.string.set_auto, R.string.set_auto_body, store.autoIsland(), on -> {
            store.setAutoIsland(on);
            LiveService.sync(this);
        });
        bind(findViewById(R.id.comps), R.string.set_comps, R.string.set_comps_body, store.competitionsInIsland(), on -> {
            store.setCompetitionsInIsland(on);
            LiveService.sync(this);
        });
        bind(findViewById(R.id.alerts), R.string.set_alerts, R.string.set_alerts_body, store.alerts(), store::setAlerts);

        findViewById(R.id.background).setOnClickListener(v -> {
            if (Scheduler.unrestricted(this)) return;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        });
        findViewById(R.id.notifications).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        findViewById(R.id.following).setOnClickListener(v -> startActivity(new Intent(this, DiscoverActivity.class)));

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, version));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Our own package always exists.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Scheduler.unrestricted(this);
        ((TextView) findViewById(R.id.background)).setText(Views.twoLine(this, R.string.set_background,
                ok ? R.string.set_background_ok : R.string.set_background_off));
    }

    private interface OnChange {
        void changed(boolean on);
    }

    private void bind(Switch s, int title, int body, boolean value, OnChange change) {
        s.setText(Views.twoLine(this, title, body));
        s.setChecked(value);
        s.setOnCheckedChangeListener((b, on) -> {
            Views.confirm(b);
            change.changed(on);
        });
    }
}
