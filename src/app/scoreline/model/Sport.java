package app.scoreline.model;

import app.scoreline.R;

/** Sports Scoreline understands, keyed by ESPN's URL segment. */
public enum Sport {
    SOCCER("soccer", R.string.sport_soccer),
    CRICKET("cricket", R.string.sport_cricket),
    BASKETBALL("basketball", R.string.sport_basketball),
    RACING("racing", R.string.sport_racing),
    FOOTBALL("football", R.string.sport_football),
    HOCKEY("hockey", R.string.sport_hockey),
    BASEBALL("baseball", R.string.sport_baseball);

    public final String path;
    public final int label;

    Sport(String path, int label) {
        this.path = path;
        this.label = label;
    }

    /** Head-to-head sports where a match has two sides and a score. */
    public boolean headToHead() {
        return this != RACING;
    }

    public static Sport of(String path) {
        for (Sport s : values()) if (s.path.equals(path)) return s;
        return SOCCER;
    }
}
