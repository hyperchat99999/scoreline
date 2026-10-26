package app.scoreline.ui;

import android.app.Activity;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.scoreline.App;

/** Edge-to-edge layout, inset handling and a tiny background-work helper shared by every screen. */
abstract class BaseActivity extends Activity {
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean alive;
    private boolean resumed;

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    protected boolean isScreenResumed() { return resumed; }

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

    /** Runs {@code work} off the main thread and delivers the result if the screen still exists. */
    protected <T> void background(Supplier<T> work, Consumer<T> done) {
        WORK.execute(() -> {
            T result = work.get();
            main.post(() -> {
                if (alive) done.accept(result);
            });
        });
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
