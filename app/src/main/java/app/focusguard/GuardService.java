package app.focusguard;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class GuardService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String PREFS = "p";
    static final String[] KEYS = {"s1", "s2", "s3", "s4", "s5"};

    private static final long ACT_GAP_MS = 800;        // min gap between redirects
    private static final long MENU_WAIT_MS = 1500;     // wait for IG "Following" menu
    private static final long FOLLOW_GRACE_MS = 20000; // don't re-force Following within this window
    private static final int MAX_ERRORS = 3;

    private final boolean[] on = new boolean[KEYS.length];
    private SharedPreferences prefs;
    private int errors;
    private String lastPkg = "";
    private long lastAct;
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

    private void load() {
        for (int i = 0; i < KEYS.length; i++) on[i] = prefs.getBoolean(KEYS[i], true);
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
        try { if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /** Fail-safe: after repeated errors turn the service off silently. */
    private void fail() {
        if (++errors < MAX_ERRORS) return;
        try { disableSelf(); } catch (Throwable ignored) {}
    }

    private void handle(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        boolean ig = Rules.IG.equals(pkg);
        if (!ig && !Rules.YT.equals(pkg)) return;
        if (ig ? !(on[0] || on[1] || on[2]) : !(on[3] || on[4])) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) return;

        if (!pkg.equals(lastPkg)) {
            lastPkg = pkg;
            pageBase = 0;
            menuDeadline = 0;
        }
        long now = SystemClock.uptimeMillis();
        if (ig) instagram(root, now); else youtube(root, now);
    }

    private void instagram(AccessibilityNodeInfo root, long now) {
        if (Rules.tabSelected(root, Rules.IG, Rules.IG_REELS_TAB_ID, Rules.IG_REELS_TAB_LABEL)) {
            pageBase = 0;
            if (on[0] && ready(now)) {
                AccessibilityNodeInfo home = Rules.byId(root, Rules.IG, Rules.IG_HOME_TAB_ID);
                if (home == null) home = Rules.byLabel(root, Rules.IG_HOME_TAB_LABEL, true);
                if (home == null || !Rules.click(home)) performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (on[2]) guardPager(root, Rules.IG, Rules.IG_REEL_PAGER_ID, now); else pageBase = 0;
        if (on[1]) forceFollowing(root, now);
    }

    private void youtube(AccessibilityNodeInfo root, long now) {
        if (Rules.tabSelected(root, Rules.YT, null, Rules.YT_SHORTS_TAB_LABEL)) {
            pageBase = 0;
            if (on[3] && ready(now)) {
                AccessibilityNodeInfo home = Rules.byLabel(root, Rules.YT_HOME_TAB_LABEL, true);
                if (home == null || !Rules.click(home)) performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (on[4]) guardPager(root, Rules.YT, Rules.YT_SHORTS_PAGER_ID, now); else pageBase = 0;
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
