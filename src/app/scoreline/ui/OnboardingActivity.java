package app.scoreline.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;

import app.scoreline.R;
import app.scoreline.live.Scheduler;
import app.scoreline.ui.widget.CrestView;

/** Three steps: see what the island does, pick teams, grant the two permissions it needs. */
public final class OnboardingActivity extends BaseActivity {
    private static final int PICK = 1;
    private static final int DEMO_STEP = 2400;

    /** Crest, left text, capsule text, capsule colour: one real-looking island per sport. */
    private static final Object[][] DEMOS = {
            {"https://a.espncdn.com/i/teamlogos/soccer/500-dark/86.png", "RMA", "1 – 1", "64'", 0xFF5B3FD0},
            {"https://a.espncdn.com/i/teamlogos/soccer/500-dark/86.png", "RMA", "2 – 1", "GOAL", 0xFF1E9E52},
            {"https://a.espncdn.com/i/teamlogos/cricket/500/6.png", "IND", "IND 69/4", "10.3 ov", 0xFF1F3FD6},
            {"https://a.espncdn.com/i/teamlogos/leagues/500/f1.png", "F1", "P1 Leclerc", "Lap 34", 0xFFE10600},
            {"https://a.espncdn.com/i/teamlogos/nba/500-dark/lal.png", "LAL", "98 – 95", "Q4 2:31", 0xFF552583},
    };

    private View intro;
    private View perms;
    private CrestView mockCrest;
    private TextView mockLeft;
    private TextView mockCapsule;
    private int demo;
    private final Runnable cycle = this::nextDemo;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_onboarding);
        intro = findViewById(R.id.intro);
        perms = findViewById(R.id.perms);
        int pad = intro.getPaddingTop();
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            for (View page : new View[] {intro, perms}) page.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });

        mockCrest = findViewById(R.id.mockCrest);
        mockLeft = findViewById(R.id.mockLeft);
        mockCapsule = findViewById(R.id.mockCapsule);
        showDemo(0, false);

        findViewById(R.id.start).setOnClickListener(v -> startActivityForResult(DiscoverActivity.onboardingIntent(this), PICK));
        findViewById(R.id.allowNotifications).setOnClickListener(v ->
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2));
        findViewById(R.id.allowBackground).setOnClickListener(v -> {
            if (!Scheduler.unrestricted(this)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        });
        findViewById(R.id.done).setOnClickListener(v -> {
            app().store().setOnboarded();
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        postDelayed(cycle, DEMO_STEP);
        updatePermissions();
    }

    @Override
    protected void onPause() {
        removeCallbacks(cycle);
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK) {
            intro.setVisibility(View.GONE);
            perms.setAlpha(0f);
            perms.setVisibility(View.VISIBLE);
            perms.animate().alpha(1f).setDuration(220).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        updatePermissions();
    }

    private void updatePermissions() {
        boolean notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permission(findViewById(R.id.allowNotifications), notif);
        permission(findViewById(R.id.allowBackground), Scheduler.unrestricted(this));
    }

    private void permission(TextView b, boolean granted) {
        b.setText(granted ? R.string.allowed : R.string.allow);
        b.setEnabled(!granted);
        b.setBackgroundResource(granted ? R.drawable.bg_btn_tonal : R.drawable.bg_btn_filled);
        int fg = getColor(granted ? R.color.on_primary_container : R.color.on_primary);
        b.setTextColor(fg);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(granted ? Views.tinted(this, R.drawable.ic_check, fg) : null, null, null, null);
    }

    // ---- The live island demo ------------------------------------------------------------------

    private void nextDemo() {
        showDemo((demo + 1) % DEMOS.length, true);
        postDelayed(cycle, DEMO_STEP);
    }

    private void showDemo(int index, boolean animate) {
        demo = index;
        Object[] d = DEMOS[index];
        Runnable apply = () -> {
            mockCrest.set((String) d[0], (String) d[1], "");
            mockLeft.setText((String) d[2]);
            mockCapsule.setText((String) d[3]);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(999f);
            pill.setColor((int) d[4]);
            mockCapsule.setBackground(pill);
        };
        if (!animate) {
            apply.run();
            return;
        }
        View mock = findViewById(R.id.mock);
        mock.animate().alpha(0.15f).scaleX(0.96f).scaleY(0.96f).setDuration(160).withEndAction(() -> {
            apply.run();
            mock.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        }).start();
    }
}
