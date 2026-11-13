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
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Event-driven guard for Instagram/YouTube. No polling: it reacts to accessibility events of the two apps
 * and uses a single one-shot timer for the next deadline (break, goal, free-mode end).
 *
 * Per app, in priority order:
 *   1. free mode  – "give me N minutes": everything in that app is allowed until it ends
 *   2. break      – app is sent to the home screen
 *   3. daily goal – once exceeded the app opens only through GateActivity (typed phrase, max 10 min grants);
 *                   feed rules still apply, periodic breaks do not
 *   4. normal     – feed rules + optional periodic breaks
 */
public final class GuardService extends AccessibilityService {

    private static final int IG = 0, YT = 1;
    private static final int ALLOW = 1, ALLOW_ALL = 2, BLOCK = 0;
    private static final long ACT_GAP_MS = 800;   // min gap between redirects
    private static final long KICK_GAP_MS = 500;
    private static final long GATE_GAP_MS = 1500;
    private static final int MAX_ERRORS = 3;

    // settings
    private final boolean[] master = new boolean[2], brk = new boolean[2], goalOn = new boolean[2];
    private final long[] periodMs = new long[2], breakMs = new long[2], goalMs = new long[2];
    private boolean igReels, igSwipe, ytShorts, ytSwipe;

    // persisted state (wall-clock ms)
    private final long[] freeUntil = new long[2], freeTotal = new long[2], freeNoted = new long[2];
    private final long[] grantUntil = new long[2], grantTotal = new long[2], usage = new long[2];
    private int day;

    // runtime
    private int fg = -1;                 // tracked app currently in the foreground
    private long fgSince, periodSince;
    private final long[] used = new long[2], leftAt = new long[2], breakUntil = new long[2];
    private long nextAt, popupEnd, lastKick, lastGate, lastAct;
    private int lastApp = -1, pageBase, errors;
    private boolean wide, receiver;

    private SharedPreferences prefs, st;
    private Overlay overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = () -> { try { onTick(); } catch (Throwable t) { fail(); } };
    private final Runnable recheck = () -> { try { checkLeft(System.currentTimeMillis(), false); } catch (Throwable t) { fail(); } };
    private final SharedPreferences.OnSharedPreferenceChangeListener onSettings = (p, k) -> {
        try {
            loadSettings();
            reevaluate();
        } catch (Throwable t) {
            fail();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener onState = (p, k) -> {
        try {
            if (k == null || !(k.startsWith("free_until") || k.startsWith("grant_"))) return;
            loadState();
            reevaluate();
        } catch (Throwable t) {
            fail();
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try { if (fg >= 0) leave(System.currentTimeMillis()); } catch (Throwable t) { fail(); }
        }
    };

    @Override
    protected void onServiceConnected() {
        try {
            prefs = getSharedPreferences(Store.SETTINGS, MODE_PRIVATE);
            st = getSharedPreferences(Store.STATE, MODE_PRIVATE);
            overlay = new Overlay(this, st);
            overlay.onTap = () -> { try { offerEndFree(); } catch (Throwable t) { fail(); } };
            loadSettings();
            rollDay();
            loadState();
            prefs.registerOnSharedPreferenceChangeListener(onSettings);
            st.registerOnSharedPreferenceChangeListener(onState);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff, f);
            receiver = true;
        } catch (Throwable t) {
            fail();
        }
    }

    private boolean b(String key) {
        return prefs.getBoolean(key, Store.def(key));
    }

    private void loadSettings() {
        for (int a = 0; a < 2; a++) {
            String p = Store.PFX[a];
            master[a] = b(p);
            brk[a] = b(p + "_break");
            goalOn[a] = b(p + "_goal");
            periodMs[a] = Store.periodMs(prefs, p);
            breakMs[a] = Store.breakMs(prefs, p);
            goalMs[a] = Store.goalMs(prefs, p);
        }
        if (overlay != null) overlay.notifyHidden = prefs.getBoolean("notify_hidden", false);
        igReels = b("ig_reels");
        igSwipe = b("ig_swipe");
        ytShorts = b("yt_shorts");
        ytSwipe = b("yt_swipe");
    }

    private void loadState() {
        for (int a = 0; a < 2; a++) {
            freeUntil[a] = st.getLong("free_until_" + Store.PFX[a], 0);
            freeTotal[a] = st.getLong("free_total_" + Store.PFX[a], 0);
            grantUntil[a] = st.getLong("grant_" + Store.PFX[a], 0);
            grantTotal[a] = st.getLong("grant_total_" + Store.PFX[a], 0);
        }
    }

    /** New calendar day: today's screen time starts from zero. */
    private void rollDay() {
        int t = Store.roll(st);
        if (t == day) return;
        day = t;
        for (int a = 0; a < 2; a++) usage[a] = st.getLong("use_" + Store.PFX[a], 0);
    }

    private void reevaluate() {
        long now = System.currentTimeMillis();
        if (fg >= 0 && !master[fg]) leave(now);
        else if (fg >= 0) gate(fg, now);
        else idle(now);
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
        try {
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(onSettings);
            if (st != null) st.unregisterOnSharedPreferenceChangeListener(onState);
        } catch (Throwable ignored) {}
    }

    private void handle(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        int a = Rules.IG.contentEquals(p) ? IG : Rules.YT.contentEquals(p) ? YT : -1;
        if (a < 0) {
            // Other apps are only heard while a tracked app is in front: notice leaving it.
            if (fg < 0) watchAll(false);
            else if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) checkLeft(System.currentTimeMillis(), true);
            return;
        }
        if (!master[a]) return;
        boolean feeds = a == IG ? (igReels || igSwipe) : (ytShorts || ytSwipe);
        AccessibilityNodeInfo root = null;
        if (fg != a || feeds) { // the window is only read when it is actually needed
            root = foreground(a);
            if (root == null) return;
        }
        long now = System.currentTimeMillis();
        if (fg != a) {
            if (fg >= 0) leave(now);
            enter(a, now);
        }
        if (a != lastApp) {
            lastApp = a;
            pageBase = 0;
        }
        if (gate(a, now) != ALLOW || !feeds) return;
        if (a == IG) instagram(root, now); else youtube(root, now);
    }

    /** Root of the active window if it belongs to the app, else null. */
    private AccessibilityNodeInfo foreground(int a) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return Store.PKG[a].contentEquals(root.getPackageName()) ? root : null;
    }

    // ---- foreground tracking (screen time) ----

    private void enter(int a, long now) {
        rollDay();
        // Staying away for at least one break length starts a fresh period.
        if (leftAt[a] != 0 && now - leftAt[a] >= breakMs[a]) used[a] = 0;
        fg = a;
        fgSince = periodSince = now;
        overlay.restore();
        watchAll(true);
    }

    private void leave(long now) {
        int a = fg;
        if (a < 0) return;
        fg = -1;
        usage[a] += Math.max(0, now - fgSince);
        used[a] += Math.max(0, now - periodSince);
        leftAt[a] = now;
        st.edit().putLong("use_" + Store.PFX[a], usage[a]).apply();
        handler.removeCallbacks(recheck);
        watchAll(false);
        idle(now);
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

    /** While a tracked app is in front, also listen to other apps (to notice leaving). */
    private void watchAll(boolean on) {
        if (wide == on) return;
        AccessibilityServiceInfo i = getServiceInfo();
        if (i == null) return;
        i.packageNames = on ? null : Store.PKG;
        setServiceInfo(i);
        wide = on;
    }

    // ---- modes ----

    /** Decides what the foreground app may do right now, updates the counter and the next deadline. */
    private int gate(int a, long now) {
        if (now < freeUntil[a]) {
            used[a] = 0;
            periodSince = now;
            overlay.show(Ui.BLUE, freeUntil[a], freeTotal[a]);
            schedule(freeUntil[a], now);
            return ALLOW_ALL;
        }
        if (freeUntil[a] != 0 && freeNoted[a] != freeUntil[a]) { // free mode just ran out: say so, keep the app open
            freeNoted[a] = freeUntil[a];
            if (now - freeUntil[a] < 5000) {
                overlay.info("Serbest mod bitti", Store.NAME[a] + " için normal kurallar geri geldi.");
            }
        }
        long end = breakUntil[a];
        if (now < end) {
            kick(a, now, end);
            return BLOCK;
        }
        long today = usage[a] + Math.max(0, now - fgSince);
        if (goalOn[a] && today >= goalMs[a]) {
            if (now < grantUntil[a]) {
                overlay.show(Ui.RED, grantUntil[a], grantTotal[a]);
                schedule(grantUntil[a], now);
                return ALLOW;
            }
            openGate(a, now);
            return BLOCK;
        }
        long next = goalOn[a] ? now + goalMs[a] - today : 0;
        if (brk[a]) {
            long rem = periodMs[a] - used[a] - (now - periodSince);
            if (rem <= 0) {
                breakUntil[a] = now + breakMs[a];
                used[a] = 0;
                periodSince = now;
                kick(a, now, breakUntil[a]);
                return BLOCK;
            }
            overlay.show(Ui.GREEN, now + rem, periodMs[a]);
            if (next == 0 || now + rem < next) next = now + rem;
        } else {
            overlay.hide();
        }
        schedule(next, now);
        return ALLOW;
    }

    /** Counter tapped during free mode: offer to end it early (unused time goes back to today's budget). */
    private void offerEndFree() {
        final int a = fg;
        if (a < 0 || System.currentTimeMillis() >= freeUntil[a]) return;
        overlay.ask("Serbest mod açık", Store.NAME[a] + " için engeller kalktı.", "Serbest modu kapat", () -> {
            try {
                Store.endFree(st, Store.PFX[a]);
            } catch (Throwable t) {
                fail();
            }
        });
    }

    /** Break: send the app to the home screen; the first time also explain with a popup. */
    private void kick(int a, long now, long end) {
        if (now - lastKick < KICK_GAP_MS) return;
        lastKick = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        if (popupEnd != end) {
            popupEnd = end;
            overlay.popup(Store.NAME[a], end - now);
        } else {
            overlay.pulse();
        }
    }

    /** Daily goal exceeded: the app opens only through the gate screen. */
    private void openGate(int a, long now) {
        if (now - lastGate < GATE_GAP_MS) return;
        lastGate = now;
        overlay.hide();
        startActivity(new Intent(this, GateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("a", a));
    }

    /** No tracked app in front: show the running break (if any) until it ends. */
    private void idle(long now) {
        long end = 0, total = 0;
        for (int a = 0; a < 2; a++) {
            long e = master[a] ? breakUntil[a] : 0;
            if (e > now && e > end) {
                end = e;
                total = breakMs[a];
            }
        }
        if (end > 0) {
            overlay.show(Ui.AMBER, end, total);
        } else {
            overlay.hide();
            overlay.dismissPopup();
        }
        schedule(end, now);
    }

    /** Single one-shot timer for the next deadline (0 = none). */
    private void schedule(long at, long now) {
        if (at == nextAt) return;
        nextAt = at;
        handler.removeCallbacks(tick);
        if (at > 0) handler.postDelayed(tick, Math.max(0, at - now) + 30);
    }

    private void onTick() {
        nextAt = 0;
        long now = System.currentTimeMillis();
        if (fg < 0) idle(now);
        else if (foreground(fg) == null) leave(now);
        else gate(fg, now);
    }

    // ---- feeds ----

    private void instagram(AccessibilityNodeInfo root, long now) {
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
    }

    private void youtube(AccessibilityNodeInfo root, long now) {
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
}
