"""Generates the simple shape and icon drawables. Run from the project root: python tools/gen_drawables.py"""
import os

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
OUT = os.path.join("res", "drawable")
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'


def write(name, body):
    with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(HEAD + body.strip() + "\n")


def shape_xml(radius, color, stroke=None, oval=False):
    kind = "oval" if oval else "rectangle"
    corners = "" if oval else f'<corners android:radius="{radius}" />'
    line = f'<stroke android:width="1dp" android:color="{stroke}" />' if stroke else ""
    return f'<shape android:shape="{kind}">{corners}<solid android:color="{color}" />{line}</shape>'


def shape(name, radius, color, stroke=None):
    write(name, shape_xml(radius, color, stroke).replace("<shape ", f"<shape {NS} ", 1))


def ripple(name, radius, color, stroke=None, oval=False):
    write(name, f'<ripple {NS} android:color="?android:attr/colorControlHighlight">'
                f'<item>{shape_xml(radius, color, stroke, oval)}</item></ripple>')


def icon(name, path, tint="?android:attr/textColorPrimary", size=24):
    write(name, f'<vector {NS} android:width="{size}dp" android:height="{size}dp" android:viewportWidth="24" '
                f'android:viewportHeight="24" android:tint="{tint}" android:autoMirrored="true">'
                f'<path android:fillColor="@android:color/white" android:pathData="{path}" /></vector>')


ripple("bg_card", "24dp", "@color/surface")
shape("bg_card_plain", "24dp", "@color/surface")
shape("bg_pill_live", "999dp", "@color/live")
shape("bg_pill_tonal", "999dp", "@color/surface_high")
ripple("bg_btn_filled", "999dp", "@color/primary")
ripple("bg_btn_tonal", "999dp", "@color/primary_container")
ripple("bg_btn_outline", "999dp", "@android:color/transparent", "@color/outline")
ripple("bg_chip", "999dp", "@android:color/transparent", "@color/outline")
ripple("bg_chip_selected", "999dp", "@color/primary_container")
ripple("bg_row", "0dp", "@android:color/transparent")
ripple("bg_icon_btn", None, "@color/surface", oval=True)
shape("bg_search", "28dp", "@color/surface")
shape("bg_island", "999dp", "@color/island")
shape("bg_capsule", "999dp", "#E53935")
shape("bg_skeleton", "8dp", "@color/skeleton")

write("bg_avatar", f'<shape {NS} android:shape="oval"><solid android:color="@color/surface" /></shape>')
write("bg_avatar_selected", f'<shape {NS} android:shape="oval"><solid android:color="@color/primary_container" />'
                            f'<stroke android:width="2dp" android:color="@color/primary" /></shape>')
write("dot_live", f'<shape {NS} android:shape="oval"><size android:width="6dp" android:height="6dp" />'
                  f'<solid android:color="@color/on_live" /></shape>')

icon("ic_search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
icon("ic_settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
icon("ic_arrow_back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
icon("ic_add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
icon("ic_check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z", size=18)
icon("ic_close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
icon("ic_chevron_right", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", "?android:attr/textColorSecondary")
icon("ic_open", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z", size=18)
icon("ic_bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
icon("ic_bolt", "M7,2v11h3v9l7,-12h-4l4,-8z")

# Island glyph: a pill with a camera dot. Outline when off, filled when on.
write("ic_island", f'<vector {NS} android:width="20dp" android:height="20dp" android:viewportWidth="24" '
                   f'android:viewportHeight="24" android:tint="?android:attr/textColorPrimary">'
                   '<path android:strokeColor="@android:color/white" android:strokeWidth="1.8" '
                   'android:pathData="M7.5,7.5h9a4.5,4.5 0,0 1,0 9h-9a4.5,4.5 0,0 1,0 -9z" />'
                   '<path android:fillColor="@android:color/white" android:pathData="M8,10a2,2 0,1 1,0 4a2,2 0,1 1,0 -4z" />'
                   '</vector>')
write("ic_island_on", f'<vector {NS} android:width="20dp" android:height="20dp" android:viewportWidth="24" '
                      f'android:viewportHeight="24" android:tint="?android:attr/textColorPrimary">'
                      '<path android:fillColor="@android:color/white" android:fillType="evenOdd" '
                      'android:pathData="M7.5,6.6h9a5.4,5.4 0,0 1,0 10.8h-9a5.4,5.4 0,0 1,0 -10.8zM8,10a2,2 0,1 0,0 4a2,2 0,1 0,0 -4z" />'
                      '</vector>')
# Status bar glyph (must stay monochrome).
write("ic_stat_scoreline", f'<vector {NS} android:width="24dp" android:height="24dp" android:viewportWidth="24" '
                           'android:viewportHeight="24"><path android:fillColor="@android:color/white" '
                           'android:fillType="evenOdd" android:pathData="M7,6.5h10a5.5,5.5 0,0 1,0 11H7a5.5,5.5 0,0 1,0 -11z'
                           'M7.6,9.6a2.4,2.4 0,1 0,0 4.8a2.4,2.4 0,1 0,0 -4.8zM14,10.75h4.5a1.25,1.25 0,0 1,0 2.5H14'
                           'a1.25,1.25 0,0 1,0 -2.5z" /></vector>')
# Launcher: a miniature island (pill, crest dot, red live capsule) on deep navy.
write("ic_launcher_fg", f'<vector {NS} android:width="108dp" android:height="108dp" android:viewportWidth="108" '
                        'android:viewportHeight="108">'
                        '<path android:fillColor="#FFFFFF" android:pathData="M38,42h32a12,12 0,0 1,0 24H38a12,12 0,0 1,0 -24z" />'
                        '<path android:fillColor="#0F1C2E" android:pathData="M38.5,47.5a6.5,6.5 0,1 1,0 13a6.5,6.5 0,1 1,0 -13z" />'
                        '<path android:fillColor="#2EC27E" android:pathData="M38.5,50a4,4 0,1 1,0 8a4,4 0,1 1,0 -8z" />'
                        '<path android:fillColor="#FF4D5E" android:pathData="M60,49h9a5,5 0,0 1,0 10h-9a5,5 0,0 1,0 -10z" />'
                        '</vector>')
write("ic_launcher_mono", f'<vector {NS} android:width="108dp" android:height="108dp" android:viewportWidth="108" '
                          'android:viewportHeight="108"><path android:fillColor="#FFFFFF" android:fillType="evenOdd" '
                          'android:pathData="M38,42h32a12,12 0,0 1,0 24H38a12,12 0,0 1,0 -24zM38.5,48a6,6 0,1 0,0 12'
                          'a6,6 0,1 0,0 -12zM60,50h9a4,4 0,0 1,0 8h-9a4,4 0,0 1,0 -8z" /></vector>')

os.makedirs(os.path.join("res", "mipmap-anydpi-v26"), exist_ok=True)
with open(os.path.join("res", "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8", newline="\n") as f:
    f.write(HEAD + f'<adaptive-icon {NS}>\n    <background android:drawable="@color/icon_bg" />\n'
                   '    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
                   '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n</adaptive-icon>\n')
print("drawables:", len(os.listdir(OUT)))
