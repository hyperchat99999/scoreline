package app.scoreline.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.scoreline.App;
import app.scoreline.R;

/** Edge-to-edge layout, inset handling and a tiny background-work helper shared by every screen. */
abstract class BaseActivity extends Activity {
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean alive;
    private boolean resumed;
    private String appliedTheme;
    private final CopyOnWriteArrayList<Future<?>> requests = new CopyOnWriteArrayList<>();

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (appliedTheme != null && !appliedTheme.equals(app().store().theme())) recreate();
    }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    protected boolean isScreenResumed() { return resumed; }

    @Override protected void attachBaseContext(Context base) {
        String theme = ((App) base.getApplicationContext()).store().theme();
        appliedTheme = theme;
        if (!"system".equals(theme)) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | ("dark".equals(theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        alive = true;
        // Android 15+ is always edge-to-edge; earlier versions need asking.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) edgeToEdge();
    }

    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        getWindow().setDecorFitsSystemWindows(false);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        for (Future<?> request : requests) request.cancel(true);
        requests.clear();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    protected App app() {
        return (App) getApplication();
    }

    /** Pads {@code top} below the status bar and {@code bottom} above the navigation bar (and keyboard). */
    protected void applyInsets(View root, View top, View bottom) {
        applyInsets(root, top, bottom, null);
    }

    protected void applyInsets(View root, View top, View bottom, Consumer<Insets> also) {
        int topPad = top == null ? 0 : top.getPaddingTop();
        int bottomPad = bottom == null ? 0 : bottom.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            if (top != null) top.setPadding(top.getPaddingLeft(), topPad + bars.top, top.getPaddingRight(), top.getPaddingBottom());
            if (bottom != null) bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(), bottom.getPaddingRight(), bottomPad + bars.bottom);
            if (also != null) also.accept(bars);
            return insets;
        });
    }

    /**
     * For screens that scroll right up to the top edge: a strip in the page colour behind the status bar
     * that fades in once {@code scroller} moves, so text never runs under the clock and battery icons.
     */
    protected void statusScrim(View scroller) {
        android.widget.FrameLayout content = findViewById(android.R.id.content);
        View scrim = new View(this);
        scrim.setBackgroundColor(getColor(R.color.bg));
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(scrim, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, android.view.Gravity.TOP));
        scrim.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp.height != top) {
                lp.height = top;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        int threshold = Math.round(8 * getResources().getDisplayMetrics().density);
        boolean[] shown = {false};
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            boolean show = y > threshold;
            if (show == shown[0]) return;
            shown[0] = show;
            scrim.animate().alpha(show ? 1f : 0f).setDuration(160).start();
        });
    }

    /** Runs {@code work} off the main thread and delivers the result if the screen still exists. */
    protected <T> Future<?> background(Supplier<T> work, Consumer<T> done) {
        return background(work, done, e -> toast(R.string.request_failed));
    }

    protected <T> Future<?> background(Supplier<T> work, Consumer<T> done, Consumer<RuntimeException> failed) {
        FutureTask<Void> request = new FutureTask<Void>(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                if (!Thread.currentThread().isInterrupted()) main.post(() -> { if (alive) failed.accept(e); });
                return null;
            }
            if (!Thread.currentThread().isInterrupted()) main.post(() -> {
                if (alive) done.accept(result);
            });
            return null;
        }) {
            @Override protected void done() { requests.remove(this); }
        };
        requests.add(request);
        WORK.execute(request);
        return request;
    }

    protected void openUrl(String url) {
        Uri uri = Uri.parse(url == null ? "" : url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            toast(R.string.link_unavailable);
            return;
        }
        launch(new Intent(Intent.ACTION_VIEW, uri));
    }

    protected void launch(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { toast(R.string.link_unavailable); }
    }

    protected void postDelayed(Runnable r, long ms) {
        if (resumed) main.postDelayed(r, ms);
    }

    protected void removeCallbacks(Runnable r) {
        main.removeCallbacks(r);
    }

    protected void toast(int res, Object... args) {
        Toast.makeText(this, getString(res, args), Toast.LENGTH_SHORT).show();
    }
}
