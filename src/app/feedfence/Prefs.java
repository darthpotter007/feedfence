package app.feedfence;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings in one place. Read fresh on every check, so changes apply instantly. */
final class Prefs {
    static final String FILE = "feedfence";

    static final String BLOCK_REELS_TAB = "block_reels_tab";
    static final String COVER_REELS_BUTTON = "cover_reels_button";
    static final String SINGLE_REEL_ONLY = "single_reel_only";
    static final String HIDE_SUGGESTED = "hide_suggested";
    static final String HIDE_ADS = "hide_ads";
    static final String WALL_AT_CAUGHT_UP = "wall_at_caught_up";
    static final String HIDE_EXPLORE = "hide_explore";
    static final String DEBUG_BUTTON = "debug_button";
    static final String PAUSED_UNTIL = "paused_until";
    static final String STAT_REELS_BLOCKED = "stat_reels_blocked";
    static final String STAT_SWIPES_BLOCKED = "stat_swipes_blocked";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean defaultFor(String key) {
        // Everything protective is on by default; the debug button is off.
        return !DEBUG_BUTTON.equals(key);
    }

    static boolean on(Context c, String key) {
        return get(c).getBoolean(key, defaultFor(key));
    }

    static boolean isPaused(Context c) {
        return System.currentTimeMillis() < get(c).getLong(PAUSED_UNTIL, 0L);
    }

    static void bump(Context c, String key) {
        SharedPreferences p = get(c);
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
    }
}
