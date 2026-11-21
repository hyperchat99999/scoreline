package app.scoreline.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;

import app.scoreline.R;
import app.scoreline.core.Ui;

/** The same labeled primary destinations on both root screens. */
final class Navigation {
    private Navigation() {}

    static void bind(BaseActivity activity, LinearLayout bar, String selected) {
        bar.removeAllViews();
        String[] tabs = {"scores", "following", "explore"};
        int[] labels = {R.string.scores, R.string.following, R.string.explore};
        int[] icons = {R.drawable.ic_calendar, R.drawable.ic_favorite, R.drawable.ic_search};
        for (int i = 0; i < tabs.length; i++) {
            String tab = tabs[i];
            TextView button = new TextView(activity);
            button.setTextAppearance(R.style.Text_Label);
            button.setText(labels[i]);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(12);
            button.setPadding(0, Ui.dp(activity, 4), 0, 0);
            boolean on = selected.equals(tab);
            button.setSelected(on);
            button.setTextColor(activity.getColor(on ? R.color.on_primary_container : R.color.on_surface_variant));
            button.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(Ui.dp(activity, 56));
            item.setBackgroundResource(R.drawable.bg_row);
            item.setSelected(on);
            item.setContentDescription(activity.getString(labels[i]));
            item.setFocusable(true);
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(Views.tinted(activity, icons[i], activity.getColor(on ? R.color.primary : R.color.on_surface_variant)));
            icon.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 2), Ui.dp(activity, 12), Ui.dp(activity, 2));
            if (on) icon.setBackgroundResource(R.drawable.bg_btn_tonal);
            icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            button.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 28)));
            item.addView(button);
            item.setOnClickListener(v -> {
                if (on) return;
                if ("explore".equals(tab)) activity.startActivity(new Intent(activity, DiscoverActivity.class));
                else activity.startActivity(HomeActivity.intent(activity, tab)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            });
            bar.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
    }
}
