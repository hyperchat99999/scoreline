"""Generates the launcher widget layouts, drawables and animations.

RemoteViews can only animate by flipping ViewFlipper children, so every animated part exists twice:
the match page (A/B, slides when the widget moves to another match), each score (A/B, rolls up when
it changes), the whole surface (scoreboard / moment banner) and the LIVE dot (pulses). Writing the
duplicates by hand is error-prone, so this script owns them. Run: python -I tools/gen_widgets.py
"""
import pathlib

RES = pathlib.Path(__file__).resolve().parent.parent / "res"
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
NO_A11Y = 'android:importantForAccessibility="no"'

# Per launcher size: padding, crest disc, team name, score, row height.
SIZES = {
    "small": dict(pad=12, crest=30, name=14, score=22, row=36, sub=10),
    "medium": dict(pad=16, crest=34, name=16, score=26, row=38, sub=11),
    "large": dict(pad=18, crest=44, name=17, score=32, row=50, sub=12),
}


def text(id_, size, color="@color/widget_foreground", extra="", font="sans-serif-medium", w="wrap_content"):
    return (f'<TextView android:id="@+id/{id_}" android:layout_width="{w}" android:layout_height="wrap_content" '
            f'android:textSize="{size}sp" android:fontFamily="{font}" android:textColor="{color}" '
            f'android:maxLines="1" android:ellipsize="end" android:includeFontPadding="false" {extra} />')


def flipper(id_, kids, inn, out, size='android:layout_width="wrap_content" android:layout_height="wrap_content"', extra=""):
    return (f'<ViewFlipper android:id="@+id/{id_}" {size} android:inAnimation="@anim/{inn}" '
            f'android:outAnimation="@anim/{out}" {extra}>\n' + "\n".join(kids) + "\n</ViewFlipper>")


def mark(prefix, s, crest):
    """A team crest on a disc tinted with the team colour; a bold monogram until the crest loads."""
    return f'''<FrameLayout android:id="@+id/{prefix}_mark{s}" android:layout_width="{crest}dp" android:layout_height="{crest}dp" android:background="@drawable/bg_widget_disc" {NO_A11Y}>
  <TextView android:id="@+id/{prefix}_badge{s}" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:background="@drawable/bg_widget_disc" android:textSize="{max(10, crest // 3)}sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@android:color/white" android:includeFontPadding="false" />
  <ImageView android:id="@+id/{prefix}_crest{s}" android:layout_width="match_parent" android:layout_height="match_parent" android:padding="{max(4, crest // 7)}dp" android:scaleType="fitCenter" android:visibility="gone" />
</FrameLayout>'''


def team_row(side, s, d):
    p = f"widget_{side}"
    score = flipper(f"{p}_score_flip{s}", [
        text(f"{p}_score{s}", d["score"], extra='android:fontFeatureSettings="tnum" android:textStyle="bold" android:gravity="end"', font="sans-serif-condensed"),
        text(f"{p}_score_alt{s}", d["score"], extra='android:fontFeatureSettings="tnum" android:textStyle="bold" android:gravity="end"', font="sans-serif-condensed"),
    ], "score_in", "score_out", extra='android:layout_marginStart="8dp" android:measureAllChildren="true"')
    return f'''<LinearLayout android:layout_width="match_parent" android:layout_height="{d["row"]}dp" android:orientation="horizontal" android:gravity="center_vertical">
{mark(p, s, d["crest"])}
<LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:layout_marginStart="{10 if d["crest"] < 36 else 12}dp" android:orientation="vertical">
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
    {text(f"{p}{s}", d["name"], w="0dp", extra='android:layout_weight="1"')}
  </LinearLayout>
  {text(f"{p}_sub{s}", d["sub"], color="@color/widget_secondary", font="sans-serif", extra='android:layout_marginTop="2dp" android:visibility="gone"', w="match_parent")}
</LinearLayout>
<ImageView android:id="@+id/{p}_serve{s}" android:layout_width="6dp" android:layout_height="6dp" android:layout_marginStart="6dp" android:src="@drawable/bg_widget_dot" android:visibility="gone" {NO_A11Y} />
{score}
</LinearLayout>'''


def driver_row(i, s, d):
    p = f"widget_driver{i}"
    # Rows stay at most 30dp so three drivers fit a short 4x3 cell on any launcher.
    row = min(30, max(26, d["row"] - 14))
    disc = min(row - 4, max(22, d["crest"] - 12))
    size = d["name"] - (0 if i == 1 else 1)
    return f'''<LinearLayout android:id="@+id/{p}_row{s}" android:layout_width="match_parent" android:layout_height="{row}dp" android:orientation="horizontal" android:gravity="center_vertical">
  <TextView android:id="@+id/{p}_pos{s}" android:layout_width="{disc}dp" android:layout_height="{disc}dp" android:gravity="center" android:background="@drawable/bg_widget_disc" android:textSize="{max(10, disc // 2 - 1)}sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@android:color/white" android:includeFontPadding="false" android:text="{i}" />
  {text(f"{p}{s}", size, w="0dp", extra='android:layout_weight="1" android:layout_marginStart="10dp"' + ('' if i == 1 else ''))}
  {text(f"{p}_gap{s}", d["sub"] + 1, color="@color/widget_secondary", font="sans-serif-condensed", extra='android:fontFeatureSettings="tnum" android:layout_marginStart="8dp"')}
</LinearLayout>'''


def page(name, s, d):
    small = name == "small"
    kickoff = "" if small else f'''<LinearLayout android:id="@+id/widget_kickoff{s}" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginStart="12dp" android:orientation="vertical" android:gravity="end" android:visibility="gone">
    {text(f"widget_kickoff_time{s}", d["score"] - 4, font="sans-serif-condensed", extra='android:textStyle="bold" android:fontFeatureSettings="tnum"')}
    {text(f"widget_kickoff_day{s}", d["sub"] + 1, color="@color/widget_secondary", extra='android:layout_marginTop="3dp"')}
  </LinearLayout>'''
    drivers = "\n".join(driver_row(i, s, d) for i in (1, 2, 3))
    context = "" if name != "large" else f'''<LinearLayout android:id="@+id/widget_context{s}" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:orientation="vertical" android:visibility="gone">
    {text(f"widget_detail{s}", 13, color="@color/widget_secondary", font="sans-serif", w="match_parent")}
    <ProgressBar android:id="@+id/widget_progress{s}" style="?android:attr/progressBarStyleHorizontal" android:layout_width="match_parent" android:layout_height="4dp" android:layout_marginTop="7dp" android:progressDrawable="@drawable/widget_progress" android:indeterminate="false" android:max="100" android:visibility="gone" />
  </LinearLayout>'''
    return f'''<LinearLayout android:id="@+id/widget_match{s}" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center_vertical" android:background="@drawable/bg_widget_press">
  <LinearLayout android:id="@+id/widget_sides{s}" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical">
{team_row("home", s, d)}
{team_row("away", s, d)}
    </LinearLayout>
  {kickoff}
  </LinearLayout>
  <LinearLayout android:id="@+id/widget_race{s}" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
    {text(f"widget_event{s}", d["name"] + 1, w="match_parent", extra='android:layout_marginBottom="4dp" android:textStyle="bold"')}
{drivers}
  </LinearLayout>
  {text(f"widget_phase{s}", 11 if small else 13, color="@color/widget_secondary", font="sans-serif-medium", w="match_parent", extra='android:layout_marginTop="6dp" android:visibility="gone"')}
  {context}
</LinearLayout>'''


def header(name, d):
    small = name == "small"
    live = f'''<LinearLayout android:id="@+id/widget_live" android:layout_width="wrap_content" android:layout_height="{20 if small else 22}dp" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="7dp" android:paddingEnd="8dp" android:background="@drawable/bg_widget_pill_live">
  {flipper("widget_pulse", [
        f'<ImageView android:layout_width="6dp" android:layout_height="6dp" android:src="@drawable/bg_widget_dot_live" {NO_A11Y} />',
        f'<ImageView android:layout_width="6dp" android:layout_height="6dp" android:src="@drawable/bg_widget_dot_live" android:alpha="0.25" {NO_A11Y} />',
    ], "pulse_in", "pulse_out", size='android:layout_width="6dp" android:layout_height="6dp"', extra='android:autoStart="false"')}
  {text("widget_live_text", 10 if small else 11, color="@android:color/white", extra='android:layout_marginStart="5dp" android:textStyle="bold" android:letterSpacing="0.06" android:fontFeatureSettings="tnum"')}
</LinearLayout>'''
    state = text("widget_state", 10 if small else 11, color="@color/widget_on_tonal",
                 extra=f'android:layout_height="{20 if small else 22}dp" android:gravity="center" android:paddingStart="8dp" android:paddingEnd="8dp" android:background="@drawable/bg_widget_pill" android:letterSpacing="0.06" android:textStyle="bold"')
    state = state.replace('android:layout_height="wrap_content" android:textSize', 'android:textSize', 1)
    pill = flipper("widget_state_flip", [state, live], "fade_in", "fade_out")
    league = "" if small else text("widget_league", 13, color="@color/widget_secondary", w="0dp", extra='android:layout_weight="1" android:layout_marginStart="8dp" android:layout_marginEnd="8dp"')
    spacer = '<FrameLayout android:layout_width="0dp" android:layout_height="1dp" android:layout_weight="1" />' if small else ""
    return f'''<LinearLayout android:id="@+id/widget_open" android:layout_width="match_parent" android:layout_height="{24 if small else 28}dp" android:orientation="horizontal" android:gravity="center_vertical">
  <ImageView android:id="@+id/widget_sport" android:layout_width="{18 if small else 20}dp" android:layout_height="{18 if small else 20}dp" android:src="@drawable/ic_sport_soccer" {NO_A11Y} />
  {league}{spacer}
  {pill}
</LinearLayout>'''


def footer(name):
    if name == "small":
        return ""
    return f'''<LinearLayout android:id="@+id/widget_footer" android:layout_width="match_parent" android:layout_height="34dp" android:orientation="horizontal" android:gravity="center_vertical">
  {text("widget_updated", 12, color="@color/widget_secondary", font="sans-serif", w="0dp", extra='android:layout_weight="1" android:layout_height="match_parent" android:gravity="center_vertical"').replace('android:layout_height="wrap_content" ', '', 1)}
  <ImageView android:id="@+id/widget_next" android:layout_width="34dp" android:layout_height="34dp" android:padding="8dp" android:src="@drawable/ic_widget_next" android:background="@drawable/bg_widget_icon" android:contentDescription="@string/widget_next" android:visibility="gone" />
  {flipper("widget_refresh_flip", [
        '<ImageView android:id="@+id/widget_refresh" android:layout_width="34dp" android:layout_height="34dp" android:padding="8dp" android:src="@drawable/ic_widget_refresh" android:background="@drawable/bg_widget_icon_tonal" android:contentDescription="@string/widget_refresh" />',
        '<FrameLayout android:layout_width="34dp" android:layout_height="34dp" android:background="@drawable/bg_widget_icon_tonal"><ProgressBar android:id="@+id/widget_refreshing" android:layout_width="18dp" android:layout_height="18dp" android:layout_gravity="center" android:indeterminate="true" android:indeterminateTint="@color/widget_on_tonal" /></FrameLayout>',
    ], "pop_in", "fade_out", size='android:layout_width="34dp" android:layout_height="34dp" android:layout_marginStart="4dp"')}
</LinearLayout>'''


def upnext(name):
    if name != "large":
        return ""
    rows = "\n".join(f'''<LinearLayout android:id="@+id/widget_up{i}" android:layout_width="match_parent" android:layout_height="24dp" android:orientation="horizontal" android:gravity="center_vertical" android:background="@drawable/bg_widget_press" android:visibility="gone">
    {text(f"widget_up{i}_time", 12, color="@color/widget_secondary", extra='android:minWidth="78dp" android:layout_marginEnd="10dp" android:fontFeatureSettings="tnum"')}
    {text(f"widget_up{i}_teams", 13, w="0dp", extra='android:layout_weight="1"')}
  </LinearLayout>''' for i in (1, 2))
    return f'''<LinearLayout android:id="@+id/widget_upnext" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:layout_marginTop="6dp" android:visibility="gone">
  <FrameLayout android:layout_width="match_parent" android:layout_height="1dp" android:layout_marginBottom="6dp" android:background="@color/widget_outline" />
{rows}
</LinearLayout>'''


def moment(name):
    small = name == "small"
    crest = 46 if small else 64
    title = 30 if small else 40
    mark_xml = f'''<FrameLayout android:id="@+id/widget_moment_mark" android:layout_width="{crest}dp" android:layout_height="{crest}dp" android:background="@drawable/bg_widget_moment_disc" {NO_A11Y}>
      <TextView android:id="@+id/widget_moment_badge" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:textSize="{crest // 3}sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@android:color/black" />
      <ImageView android:id="@+id/widget_moment_crest" android:layout_width="match_parent" android:layout_height="match_parent" android:padding="{crest // 7}dp" android:scaleType="fitCenter" android:visibility="gone" />
    </FrameLayout>'''
    align = "center" if small else "start|center_vertical"

    def word(id_, size):
        # Shrinks to fit ("NEW LEADER" on a 2x2) instead of ellipsizing; fixed height keeps the flip steady.
        auto = (f'android:gravity="{align}" android:textStyle="bold" android:autoSizeTextType="uniform" '
                f'android:autoSizeMinTextSize="14sp" android:autoSizeMaxTextSize="{size}sp"')
        return text(id_, size, color="@android:color/white", font="sans-serif-condensed", w="match_parent", extra=auto).replace(
            'android:layout_height="wrap_content"', f'android:layout_height="{size + 8}dp"', 1)
    words = flipper("widget_moment_words", [word("widget_moment_title", title), word("widget_moment_detail", title - (10 if small else 14))],
                    "pop_in", "fade_out", size='android:layout_width="match_parent" android:layout_height="wrap_content"',
                    extra='android:autoStart="true" android:flipInterval="1700" android:measureAllChildren="true"')
    score = text("widget_moment_score", 12 if small else 15, color="@android:color/white", extra='android:alpha="0.9" android:layout_marginTop="4dp" android:fontFeatureSettings="tnum"')
    if small:
        body = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="12dp">
    {mark_xml}
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:orientation="vertical" android:gravity="center_horizontal">{words}</LinearLayout>
    {score}
  </LinearLayout>'''
    else:
        body = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="22dp" android:paddingEnd="18dp">
    {mark_xml}
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:layout_marginStart="16dp" android:orientation="vertical">
      {words}
      {score}
    </LinearLayout>
  </LinearLayout>'''
    art = 120 if small else 190
    return f'''<FrameLayout android:id="@+id/widget_moment" android:layout_width="match_parent" android:layout_height="match_parent" android:background="@drawable/bg_widget_moment">
  <ImageView android:id="@+id/widget_moment_art" android:layout_width="{art}dp" android:layout_height="{art}dp" android:layout_gravity="end|center_vertical" android:layout_marginEnd="-{art // 5}dp" android:alpha="0.16" android:src="@drawable/ic_sport_soccer" android:tint="@android:color/white" {NO_A11Y} />
  {body}
</FrameLayout>'''


def empty(name):
    small = name == "small"
    body = "" if small else text("widget_empty_body", 13, color="@color/widget_secondary", font="sans-serif", w="match_parent", extra='android:layout_marginTop="4dp" android:maxLines="2" android:text="@string/widget_empty_body"').replace('android:maxLines="1" ', '', 1)
    return f'''<LinearLayout android:id="@+id/widget_empty" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center_vertical" android:background="@drawable/bg_widget_press" android:visibility="gone">
  {text("widget_empty_title", 16 if small else 19, w="match_parent", extra='android:maxLines="2" android:text="@string/widget_empty_title"').replace('android:maxLines="1" ', '', 1)}
  {body}
  {text("widget_empty_action", 13, color="@color/widget_on_tonal", extra='android:layout_marginTop="10dp" android:layout_height="32dp" android:gravity="center" android:paddingStart="14dp" android:paddingEnd="14dp" android:background="@drawable/bg_widget_pill" android:text="@string/widget_empty_action"').replace('android:layout_height="wrap_content" android:textSize', 'android:textSize', 1)}
</LinearLayout>'''


def layout(name):
    d = SIZES[name]
    pad = d["pad"]
    pages = flipper("widget_pages", [page(name, "", d), page(name, "_b", d)], "page_in", "page_out",
                    size='android:layout_width="match_parent" android:layout_height="match_parent"', extra='android:measureAllChildren="false"')
    main = f'''<LinearLayout android:id="@+id/widget_main" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:paddingStart="{pad}dp" android:paddingEnd="{pad - (4 if name != "small" else 0)}dp" android:paddingTop="{pad - 2}dp" android:paddingBottom="{pad - (8 if name == "medium" else 6 if name == "large" else 0)}dp">
{header(name, d)}
<FrameLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:layout_marginTop="{4 if name != "large" else 6}dp" android:paddingEnd="{4 if name != "small" else 0}dp">
{pages}
{empty(name)}
</FrameLayout>
{upnext(name)}
{footer(name)}
</LinearLayout>'''
    surface = flipper("widget_moment_flip", [main, moment(name)], "moment_in", "moment_out",
                      size='android:layout_width="match_parent" android:layout_height="match_parent"')
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_widgets.py; edit the script, not this file. -->
<FrameLayout {A} android:id="@android:id/background" android:layout_width="match_parent" android:layout_height="match_parent" android:theme="@android:style/Theme.DeviceDefault.DayNight" android:clipToOutline="true" android:background="@drawable/bg_widget">
<ImageView android:id="@+id/widget_glow" android:layout_width="match_parent" android:layout_height="match_parent" android:scaleType="fitXY" {NO_A11Y} />
{surface}
</FrameLayout>
'''


def preview():
    """Static picker preview: a believable live match without network crests."""
    d = SIZES["medium"]

    def row(abbr, name, score, color):
        return f'''<LinearLayout android:layout_width="match_parent" android:layout_height="{d["row"]}dp" android:gravity="center_vertical">
  <TextView android:layout_width="{d["crest"]}dp" android:layout_height="{d["crest"]}dp" android:gravity="center" android:background="@drawable/bg_widget_disc" android:backgroundTint="{color}" android:text="{abbr}" android:textSize="12sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@android:color/white" />
  <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:layout_marginStart="12dp" android:text="{name}" android:textSize="{d["name"]}sp" android:fontFamily="sans-serif-medium" android:textColor="@color/widget_foreground" />
  <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{score}" android:textSize="{d["score"]}sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@color/widget_foreground" />
</LinearLayout>'''
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_widgets.py; edit the script, not this file. -->
<FrameLayout {A} android:layout_width="match_parent" android:layout_height="match_parent" android:theme="@android:style/Theme.DeviceDefault.DayNight" android:clipToOutline="true" android:background="@drawable/bg_widget">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="16dp">
  <LinearLayout android:layout_width="match_parent" android:layout_height="28dp" android:gravity="center_vertical">
    <ImageView android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/ic_sport_soccer" android:tint="@color/widget_accent" />
    <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:layout_marginStart="8dp" android:text="Premier League" android:textSize="13sp" android:fontFamily="sans-serif-medium" android:textColor="@color/widget_secondary" />
    <TextView android:layout_width="wrap_content" android:layout_height="22dp" android:gravity="center" android:paddingStart="9dp" android:paddingEnd="9dp" android:background="@drawable/bg_widget_pill_live" android:text="● LIVE 67'" android:textSize="11sp" android:textStyle="bold" android:textColor="@android:color/white" />
  </LinearLayout>
  <LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="vertical" android:gravity="center_vertical">
{row("ARS", "Arsenal", "2", "#DB0007")}
{row("CHE", "Chelsea", "1", "#034694")}
  </LinearLayout>
  <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Saka 64' · Updated just now" android:textSize="12sp" android:textColor="@color/widget_secondary" />
</LinearLayout>
</FrameLayout>
'''


def preview_tile():
    d = SIZES["small"]

    def row(abbr, score, color):
        return f'''<LinearLayout android:layout_width="match_parent" android:layout_height="{d["row"]}dp" android:gravity="center_vertical">
  <TextView android:layout_width="{d["crest"]}dp" android:layout_height="{d["crest"]}dp" android:gravity="center" android:background="@drawable/bg_widget_disc" android:backgroundTint="{color}" android:text="{abbr}" android:textSize="10sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@android:color/white" />
  <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:layout_marginStart="10dp" android:text="{abbr}" android:textSize="{d["name"]}sp" android:fontFamily="sans-serif-medium" android:textColor="@color/widget_foreground" />
  <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="{score}" android:textSize="{d["score"]}sp" android:fontFamily="sans-serif-condensed" android:textStyle="bold" android:textColor="@color/widget_foreground" />
</LinearLayout>'''
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_widgets.py; edit the script, not this file. -->
<FrameLayout {A} android:layout_width="match_parent" android:layout_height="match_parent" android:theme="@android:style/Theme.DeviceDefault.DayNight" android:clipToOutline="true" android:background="@drawable/bg_widget">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="12dp">
  <LinearLayout android:layout_width="match_parent" android:layout_height="24dp" android:gravity="center_vertical">
    <ImageView android:layout_width="18dp" android:layout_height="18dp" android:src="@drawable/ic_sport_soccer" android:tint="@color/widget_accent" />
    <FrameLayout android:layout_width="0dp" android:layout_height="1dp" android:layout_weight="1" />
    <TextView android:layout_width="wrap_content" android:layout_height="20dp" android:gravity="center" android:paddingStart="8dp" android:paddingEnd="8dp" android:background="@drawable/bg_widget_pill_live" android:text="● 67'" android:textSize="10sp" android:textStyle="bold" android:textColor="@android:color/white" />
  </LinearLayout>
  <LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="vertical" android:gravity="center_vertical">
{row("ARS", "2", "#DB0007")}
{row("CHE", "1", "#034694")}
  </LinearLayout>
</LinearLayout>
</FrameLayout>
'''


def shape(body):
    return f'<?xml version="1.0" encoding="utf-8"?>\n{body}\n'


R = "@android:dimen/system_app_widget_background_radius"
DRAWABLES = {
    "bg_widget": f'<shape {A} android:shape="rectangle"><corners android:radius="{R}" /><solid android:color="@color/widget_surface" /></shape>',
    "bg_widget_moment": f'<shape {A} android:shape="rectangle"><corners android:radius="{R}" /><solid android:color="@android:color/white" /></shape>',
    "bg_widget_disc": f'<shape {A} android:shape="oval"><solid android:color="@android:color/white" /></shape>',
    "bg_widget_moment_disc": f'<shape {A} android:shape="oval"><solid android:color="@android:color/white" /></shape>',
    "bg_widget_dot": f'<shape {A} android:shape="oval"><solid android:color="@color/widget_accent" /></shape>',
    "bg_widget_dot_live": f'<shape {A} android:shape="oval"><solid android:color="@android:color/white" /></shape>',
    "bg_widget_pill": f'<shape {A} android:shape="rectangle"><corners android:radius="999dp" /><solid android:color="@color/widget_tonal" /></shape>',
    "bg_widget_pill_live": f'<shape {A} android:shape="rectangle"><corners android:radius="999dp" /><solid android:color="@color/widget_live" /></shape>',
    "bg_widget_press": f'<ripple {A} android:color="@color/widget_ripple"><item android:id="@android:id/mask"><shape android:shape="rectangle"><corners android:radius="14dp" /><solid android:color="@android:color/white" /></shape></item></ripple>',
    "bg_widget_icon": f'<ripple {A} android:color="@color/widget_ripple"><item android:id="@android:id/mask"><shape android:shape="oval"><solid android:color="@android:color/white" /></shape></item></ripple>',
    "bg_widget_icon_tonal": f'<ripple {A} android:color="@color/widget_ripple"><item><shape android:shape="oval"><solid android:color="@color/widget_tonal" /></shape></item></ripple>',
    "widget_progress": f'''<layer-list {A}>
  <item android:id="@android:id/background"><shape><corners android:radius="2dp" /><solid android:color="@color/widget_track" /></shape></item>
  <item android:id="@android:id/progress"><clip><shape><corners android:radius="2dp" /><solid android:color="@color/widget_accent" /></shape></clip></item>
</layer-list>''',
    "ic_widget_next": f'<vector {A} android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24" android:tint="@color/widget_foreground"><path android:fillColor="@android:color/white" android:pathData="M9.3,5.3a1,1 0,0 1,1.4 0l6,6a1,1 0,0 1,0 1.4l-6,6a1,1 0,0 1,-1.4 -1.4L14.6,12 9.3,6.7a1,1 0,0 1,0 -1.4z" /></vector>',
    "ic_widget_refresh": f'<vector {A} android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24" android:tint="@color/widget_on_tonal"><path android:fillColor="@android:color/white" android:pathData="M17.65,6.35A7.96,7.96 0,0 0,12 4a8,8 0,1 0,7.73 10h-2.08A6,6 0,1 1,12 6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z" /></vector>',
}

FI = "@android:interpolator/fast_out_slow_in"
ANIMS = {
    # Next match: the new page arrives from the end, the old one leaves towards the start.
    "page_in": f'<set {A} android:interpolator="@android:interpolator/decelerate_cubic" android:duration="380"><translate android:fromXDelta="45%" android:toXDelta="0" /><alpha android:fromAlpha="0" android:toAlpha="1" /></set>',
    "page_out": f'<set {A} android:interpolator="@android:interpolator/accelerate_cubic" android:duration="220"><translate android:fromXDelta="0" android:toXDelta="-30%" /><alpha android:fromAlpha="1" android:toAlpha="0" /></set>',
    # A changed score rolls up like a scoreboard digit.
    "score_in": f'<set {A} android:interpolator="@android:interpolator/overshoot" android:duration="460"><translate android:fromYDelta="85%" android:toYDelta="0" /><alpha android:fromAlpha="0" android:toAlpha="1" /></set>',
    "score_out": f'<set {A} android:interpolator="@android:interpolator/accelerate_quad" android:duration="220"><translate android:fromYDelta="0" android:toYDelta="-85%" /><alpha android:fromAlpha="1" android:toAlpha="0" /></set>',
    # A goal/wicket/touchdown takeover bursts in, then the scoreboard fades back.
    "moment_in": f'<set {A} android:interpolator="@android:interpolator/overshoot" android:duration="520"><scale android:fromXScale="0.82" android:toXScale="1" android:fromYScale="0.82" android:toYScale="1" android:pivotX="50%" android:pivotY="50%" /><alpha android:fromAlpha="0" android:toAlpha="1" android:interpolator="{FI}" android:duration="260" /></set>',
    "moment_out": f'<set {A} android:interpolator="{FI}" android:duration="360"><scale android:fromXScale="1" android:toXScale="1.06" android:fromYScale="1" android:toYScale="1.06" android:pivotX="50%" android:pivotY="50%" /><alpha android:fromAlpha="1" android:toAlpha="0" /></set>',
    "pop_in": f'<set {A} android:interpolator="@android:interpolator/overshoot" android:duration="420"><scale android:fromXScale="0.55" android:toXScale="1" android:fromYScale="0.55" android:toYScale="1" android:pivotX="0%" android:pivotY="50%" /><alpha android:fromAlpha="0" android:toAlpha="1" android:duration="200" /></set>',
    "fade_in": f'<alpha {A} android:interpolator="{FI}" android:duration="320" android:fromAlpha="0" android:toAlpha="1" />',
    "fade_out": f'<alpha {A} android:interpolator="{FI}" android:duration="200" android:fromAlpha="1" android:toAlpha="0" />',
    "pulse_in": f'<alpha {A} android:interpolator="{FI}" android:duration="450" android:fromAlpha="0.25" android:toAlpha="1" />',
    "pulse_out": f'<alpha {A} android:interpolator="{FI}" android:duration="450" android:fromAlpha="1" android:toAlpha="0.25" />',
}


def main():
    (RES / "anim").mkdir(exist_ok=True)
    for name, file in (("small", "widget_sports_small"), ("medium", "widget_sports"), ("large", "widget_sports_large")):
        (RES / "layout" / f"{file}.xml").write_text(layout(name), encoding="utf-8")
    (RES / "layout" / "widget_sports_preview.xml").write_text(preview(), encoding="utf-8")
    (RES / "layout" / "widget_sports_tile_preview.xml").write_text(preview_tile(), encoding="utf-8")
    for name, body in DRAWABLES.items():
        (RES / "drawable" / f"{name}.xml").write_text(shape(body), encoding="utf-8")
    for name, body in ANIMS.items():
        (RES / "anim" / f"{name}.xml").write_text(shape(body), encoding="utf-8")
    print("widget resources written")


if __name__ == "__main__":
    main()
