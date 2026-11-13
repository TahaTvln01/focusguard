package app.focusguard;

import android.content.SharedPreferences;

import java.util.Calendar;

/** Preference files, keys and defaults shared by the service and the screens. Everything stays on the device. */
final class Store {
    private Store() {}

    /** User settings (switches, durations). */
    static final String SETTINGS = "p";
    /** Runtime state: today's screen time, free-mode / extra-time grants, counter position. */
    static final String STATE = "s";

    static final String[] PFX = {"ig", "yt"};
    static final String[] PKG = {Rules.IG, Rules.YT};
    static final String[] NAME = {"Instagram", "YouTube"};

    static final int DEF_PERIOD_MIN = 5, MAX_PERIOD_MIN = 600;
    static final int DEF_BREAK_SEC = 40, MIN_BREAK_SEC = 5, MAX_BREAK_SEC = 3600;
    static final int DEF_FREE_SEC = 300, MIN_FREE_SEC = 10, MAX_FREE_MIN = 60;
    static final int DEF_FREE_DAILY_MIN = 30, MAX_FREE_DAILY_MIN = 1440;
    static final int MAX_GRANT_MIN = 10;

    /** Default of every switch: blocking on, forced breaks and daily goal off. */
    static boolean def(String key) {
        return !(key.endsWith("_break") || key.endsWith("_goal"));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static long periodMs(SharedPreferences p, String pfx) {
        return clamp(p.getInt(pfx + "_period_min", DEF_PERIOD_MIN), 1, MAX_PERIOD_MIN) * 60000L;
    }

    static long breakMs(SharedPreferences p, String pfx) {
        return clamp(p.getInt(pfx + "_break_sec", DEF_BREAK_SEC), MIN_BREAK_SEC, MAX_BREAK_SEC) * 1000L;
    }

    static long goalMs(SharedPreferences p, String pfx) {
        int m = clamp(p.getInt(pfx + "_goal_h", 1), 0, 23) * 60 + clamp(p.getInt(pfx + "_goal_m", 0), 0, 59);
        return Math.max(1, m) * 60000L;
    }

    /** Last chosen "give me N minutes" length, in seconds. */
    static int freeSec(SharedPreferences p, String pfx) {
        return clamp(p.getInt(pfx + "_free_sec", DEF_FREE_SEC), MIN_FREE_SEC, MAX_FREE_MIN * 60 + 59);
    }

    static long freeDailyMs(SharedPreferences p, String pfx) {
        return clamp(p.getInt(pfx + "_free_daily_min", DEF_FREE_DAILY_MIN), 0, MAX_FREE_DAILY_MIN) * 60000L;
    }

    /** "5 dakika" / "5 dk 30 sn" / "45 saniye". */
    static String freeLabel(int sec) {
        int m = sec / 60, s = sec % 60;
        return s == 0 ? m + " dakika" : m == 0 ? s + " saniye" : m + " dk " + s + " sn";
    }

    static int today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Starts a new day: clears today's screen time and used free-mode budget. Returns today's key. */
    static int roll(SharedPreferences st) {
        int t = today();
        if (st.getInt("day", 0) != t) {
            st.edit().putInt("day", t).putLong("use_ig", 0).putLong("use_yt", 0)
                    .putLong("free_used_ig", 0).putLong("free_used_yt", 0).apply();
        }
        return t;
    }

    static String dur(long ms) {
        long m = ms / 60000;
        if (m < 1) return ms > 0 ? "<1 dk" : "0 dk";
        return m >= 60 ? m / 60 + " sa " + m % 60 + " dk" : m + " dk";
    }
}
