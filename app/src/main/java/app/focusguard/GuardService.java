package app.focusguard;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public final class GuardService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String PREFS = "p";
    static final int DEF_PERIOD_MIN = 5, MIN_PERIOD_MIN = 1, MAX_PERIOD_MIN = 600;
    static final int DEF_BREAK_SEC = 40, MIN_BREAK_SEC = 5, MAX_BREAK_SEC = 3600;

    /** Default of every switch: everything on except forced breaks. */
    static boolean def(String key) {
        return !key.endsWith("_break");
    }

    private static final int IG = 0, YT = 1;
    private static final String[] PKG = {Rules.IG, Rules.YT};
    private static final String[] PFX = {"ig", "yt"};

    private static final long ACT_GAP_MS = 800;        // min gap between redirects
    private static final long MENU_WAIT_MS = 1500;     // wait for IG "Following" menu
    private static final long FOLLOW_GRACE_MS = 20000; // don't re-force Following within this window
    private static final int MAX_ERRORS = 3;

    // settings
    private final boolean[] master = new boolean[2], brk = new boolean[2];
    private final long[] periodMs = new long[2], breakMs = new long[2];
    private boolean igReels, igFollowing, igSwipe, ytShorts, ytSwipe, ytAds;

    // break state (per app)
    private final long[] start = new long[2], seen = new long[2], breakUntil = new long[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable[] timer = {() -> onTimer(IG), () -> onTimer(YT)};
    private long lastKick, lastToast;

    // feed state
    private SharedPreferences prefs;
    private int errors;
    private int lastApp = -1;
    private long lastAct, lastSkip;
    private int pageBase;      // identity of the first reel/short opened (0 = none)
    private long menuDeadline; // >0 while waiting for the IG menu
    private long lastFollow = -FOLLOW_GRACE_MS;
    private int followFails;

    @Override
    protected void onServiceConnected() {
        try {
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(this);
            load();
        } catch (Throwable t) {
            fail();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String k) {
        try { load(); } catch (Throwable t) { fail(); }
    }

    private boolean b(String key) {
        return prefs.getBoolean(key, def(key));
    }

    private void load() {
        for (int a = 0; a < 2; a++) {
            String p = PFX[a];
            master[a] = b(p);
            brk[a] = b(p + "_break");
            periodMs[a] = clamp(prefs.getInt(p + "_period_min", DEF_PERIOD_MIN), MIN_PERIOD_MIN, MAX_PERIOD_MIN) * 60000L;
            breakMs[a] = clamp(prefs.getInt(p + "_break_sec", DEF_BREAK_SEC), MIN_BREAK_SEC, MAX_BREAK_SEC) * 1000L;
            start[a] = 0; // restart the usage period with the new settings
            handler.removeCallbacks(timer[a]);
        }
        igReels = b("ig_reels");
        igFollowing = b("ig_following");
        igSwipe = b("ig_swipe");
        ytShorts = b("yt_shorts");
        ytSwipe = b("yt_swipe");
        ytAds = b("yt_ads");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            handle(e);
        } catch (Throwable t) {
            fail();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null);
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /** Fail-safe: after repeated errors turn the service off silently. */
    private void fail() {
        if (++errors < MAX_ERRORS) return;
        try {
            handler.removeCallbacksAndMessages(null);
            disableSelf();
        } catch (Throwable ignored) {}
    }

    private void handle(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        int a = Rules.IG.contentEquals(p) ? IG : Rules.YT.contentEquals(p) ? YT : -1;
        if (a < 0 || !master[a]) return;
        boolean feeds = a == IG ? (igReels || igFollowing || igSwipe) : (ytShorts || ytSwipe || ytAds);
        if (!feeds && !brk[a]) return;

        AccessibilityNodeInfo root = foreground(a);
        if (root == null) return;

        if (a != lastApp) {
            lastApp = a;
            pageBase = 0;
            menuDeadline = 0;
        }
        long now = SystemClock.uptimeMillis();
        if (brk[a] && breakGate(a, now)) return;
        if (a == IG) instagram(root, now); else youtube(root, now);
    }

    /** Root of the active window if it belongs to the app, else null. */
    private AccessibilityNodeInfo foreground(int a) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return PKG[a].contentEquals(root.getPackageName()) ? root : null;
    }

    // ---- forced breaks ----

    /** Returns true if the app is (now) on a break and the event must not be processed further. */
    private boolean breakGate(int a, long now) {
        if (now < breakUntil[a]) {
            kick(a, now);
            return true;
        }
        // New usage period on first use, or when the user stayed away at least one break length.
        if (start[a] == 0 || now - seen[a] > breakMs[a]) {
            start[a] = now;
            handler.removeCallbacks(timer[a]);
            handler.postDelayed(timer[a], periodMs[a]);
        }
        seen[a] = now;
        if (now - start[a] >= periodMs[a]) {
            startBreak(a, now);
            return true;
        }
        return false;
    }

    /** One-shot timer at the end of a usage period (covers screens that emit no events). */
    private void onTimer(int a) {
        try {
            long now = SystemClock.uptimeMillis();
            if (!master[a] || !brk[a] || start[a] == 0 || now - start[a] < periodMs[a]) return;
            if (foreground(a) != null) startBreak(a, now);
        } catch (Throwable t) {
            fail();
        }
    }

    private void startBreak(int a, long now) {
        breakUntil[a] = now + breakMs[a];
        start[a] = 0;
        handler.removeCallbacks(timer[a]);
        kick(a, now);
    }

    private void kick(int a, long now) {
        if (now - lastKick >= 500) {
            lastKick = now;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
        if (now - lastToast >= 3000) {
            lastToast = now;
            long left = Math.max(1, (breakUntil[a] - now + 999) / 1000);
            Toast.makeText(this, "FocusGuard: mola — " + left + " sn kaldı", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- feeds ----

    private void instagram(AccessibilityNodeInfo root, long now) {
        if (!(igReels || igFollowing || igSwipe)) return;
        if (Rules.tabSelected(root, Rules.IG, Rules.IG_REELS_TAB_ID, Rules.IG_REELS_TAB_LABEL)) {
            pageBase = 0;
            if (igReels && ready(now)) {
                AccessibilityNodeInfo home = Rules.byId(root, Rules.IG, Rules.IG_HOME_TAB_ID);
                if (home == null) home = Rules.byLabel(root, Rules.IG_HOME_TAB_LABEL, true);
                if (home == null || !Rules.click(home)) performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (igSwipe) guardPager(root, Rules.IG, Rules.IG_REEL_PAGER_ID, now); else pageBase = 0;
        if (igFollowing) forceFollowing(root, now);
    }

    private void youtube(AccessibilityNodeInfo root, long now) {
        if (ytAds && now - lastSkip >= 1000) {
            AccessibilityNodeInfo skip = Rules.byId(root, Rules.YT, Rules.YT_SKIP_AD_ID);
            if (skip != null) {
                lastSkip = now;
                Rules.click(skip);
            }
        }
        if (!(ytShorts || ytSwipe)) return;
        if (Rules.tabSelected(root, Rules.YT, null, Rules.YT_SHORTS_TAB_LABEL)) {
            pageBase = 0;
            if (ytShorts && ready(now)) {
                AccessibilityNodeInfo home = Rules.byLabel(root, Rules.YT_HOME_TAB_LABEL, true);
                if (home == null || !Rules.click(home)) performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (ytSwipe) guardPager(root, Rules.YT, Rules.YT_SHORTS_PAGER_ID, now); else pageBase = 0;
    }

    private boolean ready(long now) {
        if (now - lastAct < ACT_GAP_MS) return false;
        lastAct = now;
        return true;
    }

    /** First item may be watched; moving to another item closes the viewer. */
    private void guardPager(AccessibilityNodeInfo root, String pkg, String[] ids, long now) {
        AccessibilityNodeInfo pager = Rules.byId(root, pkg, ids);
        if (pager == null) { pageBase = 0; return; }
        int key = Rules.pageKey(pager);
        if (key == 0) return;
        if (pageBase == 0) { pageBase = key; return; }
        if (key != pageBase && ready(now)) {
            pageBase = 0;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /** IG home: tap logo -> "Following". Gives up after repeated failures. */
    private void forceFollowing(AccessibilityNodeInfo root, long now) {
        if (followFails >= 3) return;
        if (menuDeadline != 0) {
            if (now <= menuDeadline) {
                AccessibilityNodeInfo item = Rules.byLabel(root, Rules.IG_FOLLOWING_LABEL, false);
                if (item != null && Rules.click(item)) {
                    menuDeadline = 0;
                    followFails = 0;
                    lastFollow = now;
                }
                return;
            }
            menuDeadline = 0;
            followFails++;
            lastFollow = now;
            return;
        }
        if (now - lastFollow < FOLLOW_GRACE_MS) return;
        if (!Rules.tabSelected(root, Rules.IG, Rules.IG_HOME_TAB_ID, Rules.IG_HOME_TAB_LABEL)) return;
        AccessibilityNodeInfo logo = Rules.byId(root, Rules.IG, Rules.IG_LOGO_ID);
        if (logo == null) return;
        if (Rules.click(logo)) {
            menuDeadline = now + MENU_WAIT_MS;
        } else {
            followFails++;
            lastFollow = now;
        }
    }
}
