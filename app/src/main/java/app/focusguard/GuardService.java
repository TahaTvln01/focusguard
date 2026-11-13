package app.focusguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
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
    private static final String[] NAME = {"Instagram", "YouTube"};
    private static final long DUMP_DELAY_MS = 6000;

    private static final long ACT_GAP_MS = 800;        // min gap between redirects
    private static final int MAX_ERRORS = 3;

    // settings
    private final boolean[] master = new boolean[2], brk = new boolean[2], counter = new boolean[2];
    private final long[] periodMs = new long[2], breakMs = new long[2];
    private boolean igReels, igSwipe, ytShorts, ytSwipe;

    // break state (per app); times are SystemClock.elapsedRealtime()
    private int fg = -1;            // target app currently in foreground (only tracked while its break is on)
    private long fgSince;
    private final long[] used = new long[2], leftAt = new long[2], breakUntil = new long[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable[] timer = {() -> onTimer(IG), () -> onTimer(YT)};
    private final Runnable refresher = () -> { try { refresh(SystemClock.elapsedRealtime()); } catch (Throwable t) { fail(); } };
    private final Runnable recheck = () -> { try { checkLeft(SystemClock.elapsedRealtime(), false); } catch (Throwable t) { fail(); } };
    private final Runnable dumper = this::dump;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try { if (fg >= 0) leave(SystemClock.elapsedRealtime()); } catch (Throwable t) { fail(); }
        }
    };
    private Overlay overlay;
    private boolean wide, receiver;
    private long lastKick, lastToast;

    // feed state
    private SharedPreferences prefs;
    private int errors;
    private int lastApp = -1;
    private long lastAct;
    private int pageBase;      // identity of the first reel/short opened (0 = none)

    @Override
    protected void onServiceConnected() {
        try {
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(this);
            overlay = new Overlay(this, prefs);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff, f);
            receiver = true;
            load();
        } catch (Throwable t) {
            fail();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String k) {
        try {
            if (k == null || k.startsWith("ov_") || k.equals("dump")) return;
            if (k.equals("dump_req")) {
                handler.removeCallbacks(dumper);
                handler.postDelayed(dumper, DUMP_DELAY_MS);
                return;
            }
            load();
        } catch (Throwable t) {
            fail();
        }
    }

    private boolean b(String key) {
        return prefs.getBoolean(key, def(key));
    }

    private void load() {
        for (int a = 0; a < 2; a++) {
            String p = PFX[a];
            master[a] = b(p);
            brk[a] = b(p + "_break");
            counter[a] = b(p + "_counter");
            periodMs[a] = clamp(prefs.getInt(p + "_period_min", DEF_PERIOD_MIN), MIN_PERIOD_MIN, MAX_PERIOD_MIN) * 60000L;
            breakMs[a] = clamp(prefs.getInt(p + "_break_sec", DEF_BREAK_SEC), MIN_BREAK_SEC, MAX_BREAK_SEC) * 1000L;
            used[a] = 0; // restart the usage period with the new settings
            leftAt[a] = 0;
            handler.removeCallbacks(timer[a]);
        }
        fg = -1;
        watchAll(false);
        refresh(SystemClock.elapsedRealtime());
        igReels = b("ig_reels");
        igSwipe = b("ig_swipe");
        ytShorts = b("yt_shorts");
        ytSwipe = b("yt_swipe");
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
        cleanup();
        super.onDestroy();
    }

    /** Fail-safe: after repeated errors turn the service off silently. */
    private void fail() {
        if (++errors < MAX_ERRORS) return;
        cleanup();
        try { disableSelf(); } catch (Throwable ignored) {}
    }

    private void cleanup() {
        try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        try { if (overlay != null) overlay.destroy(); } catch (Throwable ignored) {}
        try { if (receiver) { receiver = false; unregisterReceiver(screenOff); } } catch (Throwable ignored) {}
        try { if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this); } catch (Throwable ignored) {}
    }

    private void handle(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        long now = SystemClock.elapsedRealtime();
        int a = Rules.IG.contentEquals(p) ? IG : Rules.YT.contentEquals(p) ? YT : -1;
        if (a < 0) {
            // Other apps are only heard while a break-tracked app is in front: detect leaving it.
            if (fg < 0) { watchAll(false); return; }
            if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            if (getPackageName().contentEquals(p)) return;
            checkLeft(now, true);
            return;
        }
        if (!master[a]) return;
        boolean feeds = a == IG ? (igReels || igSwipe) : (ytShorts || ytSwipe);
        if (!feeds && !brk[a]) return;

        AccessibilityNodeInfo root = foreground(a);
        if (root == null) return;

        if (a != lastApp) {
            lastApp = a;
            pageBase = 0;
        }
        if (brk[a]) {
            if (now < breakUntil[a]) { kick(a, now); return; }
            if (fg != a) {
                if (fg >= 0) leave(now);
                enter(a, now);
            } else if (remaining(a, now) <= 0) {
                startBreak(a, now);
                return;
            }
        }
        if (a == IG) instagram(root, now); else youtube(root, now);
    }

    /** Root of the active window if it belongs to the app, else null. */
    private AccessibilityNodeInfo foreground(int a) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return PKG[a].contentEquals(root.getPackageName()) ? root : null;
    }

    // ---- forced breaks ----

    private long remaining(int a, long now) {
        return periodMs[a] - used[a] - (now - fgSince);
    }

    private void enter(int a, long now) {
        // Staying away for at least one break length (or finishing a break) starts a fresh period.
        if (breakUntil[a] != 0 || (leftAt[a] != 0 && now - leftAt[a] >= breakMs[a])) used[a] = 0;
        breakUntil[a] = 0;
        fg = a;
        fgSince = now;
        handler.removeCallbacks(timer[a]);
        handler.postDelayed(timer[a], Math.max(0, remaining(a, now)));
        watchAll(true);
        refresh(now);
    }

    private void leave(long now) {
        int a = fg;
        if (a < 0) return;
        fg = -1;
        used[a] += now - fgSince;
        leftAt[a] = now;
        handler.removeCallbacks(timer[a]);
        handler.removeCallbacks(recheck);
        watchAll(false);
        refresh(now);
    }

    /** Another window appeared: did the user leave the tracked app? */
    private void checkLeft(long now, boolean retry) {
        if (fg < 0) return;
        if (foreground(fg) == null) {
            leave(now);
        } else if (retry) { // window transition may not be finished yet
            handler.removeCallbacks(recheck);
            handler.postDelayed(recheck, 700);
        }
    }

    /** One-shot timer at the end of a usage period (covers screens that emit no events). */
    private void onTimer(int a) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (fg != a || !master[a] || !brk[a]) return;
            if (foreground(a) == null) { leave(now); return; }
            long rem = remaining(a, now);
            if (rem > 0) handler.postDelayed(timer[a], rem); else startBreak(a, now);
        } catch (Throwable t) {
            fail();
        }
    }

    private void startBreak(int a, long now) {
        breakUntil[a] = now + breakMs[a];
        used[a] = 0;
        leftAt[a] = 0;
        handler.removeCallbacks(timer[a]);
        if (fg == a) {
            fg = -1;
            watchAll(false);
        }
        lastKick = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        overlay.popup(NAME[a], breakMs[a] / 1000);
        refresh(now);
    }

    /** App opened during its break: send home again. */
    private void kick(int a, long now) {
        if (now - lastKick >= 500) {
            lastKick = now;
            performGlobalAction(GLOBAL_ACTION_HOME);
            if (counter[a]) overlay.pulse();
        }
        if (!counter[a] && now - lastToast >= 3000) {
            lastToast = now;
            long left = Math.max(1, (breakUntil[a] - now + 999) / 1000);
            Toast.makeText(this, "FocusGuard: mola — " + left + " sn kaldı", Toast.LENGTH_SHORT).show();
        }
    }

    /** Decides what the floating counter shows: time to next break (in app) or break time left. */
    private void refresh(long now) {
        if (overlay == null) return;
        handler.removeCallbacks(refresher);
        int a = fg;
        if (a >= 0 && brk[a] && counter[a] && now >= breakUntil[a]) {
            overlay.show(false, now + Math.max(0, remaining(a, now)), periodMs[a]);
            return;
        }
        int x = -1;
        long next = Long.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            if (breakUntil[i] <= now) continue;
            next = Math.min(next, breakUntil[i]);
            if (counter[i] && (x < 0 || breakUntil[i] > breakUntil[x])) x = i;
        }
        if (x >= 0) overlay.show(true, breakUntil[x], breakMs[x]); else overlay.hide();
        if (next != Long.MAX_VALUE) handler.postDelayed(refresher, next - now + 50);
        else overlay.dismissPopup();
    }

    /** While a tracked app is in front, also listen to other apps (to notice leaving). */
    private void watchAll(boolean on) {
        if (wide == on) return;
        AccessibilityServiceInfo i = getServiceInfo();
        if (i == null) return;
        i.packageNames = on ? null : PKG;
        setServiceInfo(i);
        wide = on;
    }

    /** Diagnostics requested from the settings screen: save the current IG/YT window structure. */
    private void dump() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence p = root == null ? null : root.getPackageName();
            boolean ok = p != null && (Rules.IG.contentEquals(p) || Rules.YT.contentEquals(p));
            if (ok) prefs.edit().putString("dump", Rules.dump(root)).apply();
            Toast.makeText(this, ok ? "FocusGuard: ekran kaydedildi" : "FocusGuard: Instagram/YouTube açık değil",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            fail();
        }
    }

    // ---- feeds ----

    private void instagram(AccessibilityNodeInfo root, long now) {
        if (!(igReels || igSwipe)) return;
        if (Rules.tabSelected(root, Rules.IG, Rules.IG_REELS_TAB_ID, Rules.IG_REELS_TAB_LABEL)) {
            pageBase = 0;
            if (igReels && ready(now)) {
                AccessibilityNodeInfo home = Rules.byId(root, Rules.IG, Rules.IG_HOME_TAB_ID);
                if (home == null) home = Rules.byLabel(root, Rules.IG_HOME_TAB_LABEL, Rules.BOTTOM);
                if (home == null || !Rules.click(home)) performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (igSwipe) guardPager(root, Rules.IG, Rules.IG_REEL_PAGER_ID, now); else pageBase = 0;
    }

    private void youtube(AccessibilityNodeInfo root, long now) {
        if (!(ytShorts || ytSwipe)) return;
        if (Rules.tabSelected(root, Rules.YT, null, Rules.YT_SHORTS_TAB_LABEL)) {
            pageBase = 0;
            if (ytShorts && ready(now)) {
                AccessibilityNodeInfo home = Rules.byLabel(root, Rules.YT_HOME_TAB_LABEL, Rules.BOTTOM);
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
}
