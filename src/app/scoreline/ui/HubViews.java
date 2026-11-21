package app.scoreline.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import app.scoreline.App;
import app.scoreline.R;
import app.scoreline.core.Ui;
import app.scoreline.data.Browse;
import app.scoreline.model.Match;
import app.scoreline.model.Standings;
import app.scoreline.model.Team;
import app.scoreline.ui.widget.CrestView;

/** Small native view helpers shared by competition and team hubs. */
final class HubViews {
    private HubViews() {}

    static void responsiveIdentity(View root) {
        Context context = root.getContext();
        android.content.res.Configuration configuration = context.getResources().getConfiguration();
        if (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.15f) return;
        View follow = root.findViewById(R.id.hubFollow);
        LinearLayout identity = root.findViewById(R.id.hubIdentity);
        LinearLayout parent = (LinearLayout) identity.getParent();
        identity.removeView(follow);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(Ui.dp(context, 20));
        params.topMargin = Ui.dp(context, 8);
        parent.addView(follow, parent.indexOfChild(root.findViewById(R.id.hubContext)) + 1, params);
    }

    static final class Load<T> {
        T value;
        boolean loaded;
        boolean loading;
        boolean failed;
        boolean stale;
        boolean supported = true;

        Load(T empty) { value = empty; }

        void accept(Browse.Result<T> result, boolean keepPrevious) {
            loading = false;
            loaded = true;
            failed = result == null || result.failed;
            stale = result != null && result.stale;
            supported = result == null || result.supported;
            if (result != null && !keepPrevious) value = result.value;
        }
    }

    static void notice(View root, Load<?> state, boolean hasContent, Runnable retry) {
        TextView status = root.findViewById(R.id.hubStatus);
        TextView button = root.findViewById(R.id.hubRetry);
        int message = state.loading ? (hasContent ? R.string.hub_refreshing : R.string.loading)
                : state.failed ? (hasContent ? R.string.hub_partial : R.string.hub_load_error)
                : state.stale ? R.string.hub_stale : 0;
        root.findViewById(R.id.hubNotice).setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.hubProgress).setVisibility(state.loading ? View.VISIBLE : View.GONE);
        status.setText(message == 0 ? "" : root.getContext().getString(message));
        button.setVisibility(!state.loading && (state.failed || state.stale) ? View.VISIBLE : View.GONE);
        button.setOnClickListener(v -> retry.run());
        View refresh = root.findViewById(R.id.hubRefresh);
        refresh.setEnabled(!state.loading);
        refresh.setAlpha(state.loading ? 0.5f : 1f);
    }

    static TextView button(Context c, CharSequence label) {
        TextView v = new TextView(c);
        v.setTextAppearance(R.style.Text_Label);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(Ui.dp(c, 48));
        v.setMinWidth(Ui.dp(c, 48));
        v.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        v.setBackgroundResource(R.drawable.bg_row);
        return v;
    }

    static void tab(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setTextColor(view.getContext().getColor(selected ? R.color.on_primary_container : R.color.on_surface_variant));
        view.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
    }

    static void section(LinearLayout parent, CharSequence title) {
        Context c = parent.getContext();
        TextView section = (TextView) FollowRows.section(LayoutInflater.from(c), parent, title);
        section.setTextSize(18);
        section.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 4));
        parent.addView(section);
    }

    private static void dateHeading(LinearLayout parent, String title) {
        Context c = parent.getContext();
        TextView date = new TextView(c);
        date.setTextAppearance(R.style.Text_Overline);
        date.setText(title);
        date.setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 8));
        parent.addView(date);
    }

    static void message(LinearLayout parent, int text) {
        Context c = parent.getContext();
        TextView v = new TextView(c);
        v.setTextAppearance(R.style.Text_Body);
        v.setText(text);
        v.setTextColor(c.getColor(R.color.on_surface_variant));
        v.setPadding(Ui.dp(c, 20), Ui.dp(c, 18), Ui.dp(c, 20), Ui.dp(c, 12));
        parent.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static String date(Context c, LocalDate date) {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMMy");
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date);
    }

    /** Retains existing card/row score and island behavior; date headings keep team schedules clear. */
    static void matches(LinearLayout parent, App app, List<Match> matches, boolean dateHeadings, MatchCard.Actions actions) {
        Context c = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(c);
        LocalDate previous = null;
        LinearLayout group = null;
        for (Match match : matches) {
            LocalDate day = Instant.ofEpochMilli(match.start).atZone(ZoneId.systemDefault()).toLocalDate();
            if (dateHeadings && !day.equals(previous)) {
                dateHeading(parent, date(c, day));
                group = null;
            }
            if (match.isLive()) {
                MatchCard card = new MatchCard(inflater, parent);
                card.bind(app, match, dateHeadings, actions);
                parent.addView(card.root);
                group = null;
            } else {
                if (group == null) {
                    group = new LinearLayout(c);
                    group.setOrientation(LinearLayout.VERTICAL);
                    group.setBackgroundResource(R.drawable.bg_card_plain);
                    group.setClipToOutline(true);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    p.setMargins(Ui.dp(c, 16), Ui.dp(c, 5), Ui.dp(c, 16), Ui.dp(c, 5));
                    parent.addView(group, p);
                }
                MatchRow row = new MatchRow(inflater, group);
                row.bind(app, match, actions);
                group.addView(row.root);
            }
            previous = day;
        }
    }

    /** Rank/group/column labels and values come directly from the provider model. */
    static void standings(LinearLayout parent, Standings standings, java.util.function.Consumer<Team> open) {
        Context c = parent.getContext();
        for (Standings.Group group : standings.groups) {
            if (!group.name.isEmpty()) section(parent, group.name);
            if (group.rows.isEmpty()) continue;
            TextView hint = new TextView(c);
            hint.setTextAppearance(R.style.Text_Caption);
            hint.setText(R.string.swipe_stats);
            hint.setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), Ui.dp(c, 8));
            parent.addView(hint);
            HorizontalScrollView scroll = new HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(true);
            scroll.setFillViewport(true);
            LinearLayout table = new LinearLayout(c);
            table.setOrientation(LinearLayout.VERTICAL);
            table.setBackgroundResource(R.drawable.bg_card_plain);
            table.setClipToOutline(true);
            LinearLayout header = new LinearLayout(c);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPaddingRelative(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
            header.addView(cell(c, c.getString(R.string.hub_rank), 40, true));
            header.addView(cell(c, c.getString(R.string.hub_team), 172, true));
            int[] widths = new int[group.columns.size()];
            for (int i = 0; i < group.columns.size(); i++) {
                Standings.Column column = group.columns.get(i);
                String label = column.label.isEmpty() ? column.name : column.label;
                widths[i] = Math.max(64, Math.min(128, 24 + label.length() * 7));
                TextView cell = cell(c, label, widths[i], true);
                cell.setContentDescription(column.name.isEmpty() ? label : column.name);
                header.addView(cell);
            }
            table.addView(header);
            for (Standings.Row entry : group.rows) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Ui.dp(c, 56));
                row.setPaddingRelative(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
                row.setBackgroundResource(R.drawable.bg_row);
                row.addView(cell(c, entry.rank, 40, false));
                LinearLayout identity = new LinearLayout(c);
                identity.setGravity(Gravity.CENTER_VERTICAL);
                identity.setPaddingRelative(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
                if (entry.team != null) {
                    CrestView crest = new CrestView(c, null);
                    crest.setTeam(entry.team);
                    crest.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    identity.addView(crest, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
                    TextView name = cell(c, entry.team.name, 0, false);
                    name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    identity.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    row.setOnClickListener(v -> open.accept(entry.team));
                }
                row.addView(identity, new LinearLayout.LayoutParams(Ui.dp(c, 172), ViewGroup.LayoutParams.WRAP_CONTENT));
                for (int i = 0; i < group.columns.size(); i++) {
                    row.addView(cell(c, i < entry.values.size() ? entry.values.get(i) : "", widths[i], false));
                }
                table.addView(row);
            }
            scroll.addView(table, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
            parent.addView(scroll, p);
        }
    }

    private static TextView cell(Context c, String value, int width, boolean header) {
        TextView text = new TextView(c);
        text.setTextAppearance(header ? R.style.Text_Caption : R.style.Text_Body);
        text.setText(value);
        text.setGravity(Gravity.CENTER);
        text.setMinHeight(Ui.dp(c, 48));
        text.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        text.setFontFeatureSettings("tnum");
        text.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, width), ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }
}
