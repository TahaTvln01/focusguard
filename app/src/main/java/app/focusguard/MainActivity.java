package app.focusguard;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Date;

/** Single settings screen: one card per app (free mode, screen time, switches, breaks, goal) + general card. */
public final class MainActivity extends Activity {

    private static final String CONFIRM_PHRASE = "Bunu gerçekten istiyorum";
    private static final int CONFIRM_SEC = 10;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String HELP_MASTER = "Bu uygulama için FocusGuard'ı tamamen açar ya da kapatır. "
            + "Kapalıyken hiçbir kural uygulanmaz ve ekran süresi sayılmaz.";
    private static final String HELP_TAB = "Bu sekmeye girdiğinde seni hemen ana sayfaya geri gönderir. "
            + "Mesajlar, arama ve profil etkilenmez.";
    private static final String HELP_SWIPE = "Sana gönderilen ya da kendin açtığın videoyu izleyebilirsin. "
            + "Bir sonrakine kaydırdığın anda oynatıcı kapanır; böylece akışa kapılmazsın.";
    private static final String HELP_BREAK = "Uygulamayı belirlediğin süre kadar kullanınca seni ana ekrana atar ve "
            + "mola bitene kadar uygulamayı açtırmaz.\n\nUygulamadan en az mola süresi kadar uzak kalırsan sayaç sıfırlanır.\n\n"
            + "Ekrandaki yuvarlak sayaç kalan süreyi gösterir: sürükleyip kenara taşıyabilir, alttaki ✕ üzerine bırakarak gizleyebilirsin.";
    private static final String HELP_GOAL = "Bugünkü ekran süren hedefi geçince uygulama yalnızca onay cümlesini yazarsan "
            + "ve her seferinde en fazla " + Store.MAX_GRANT_MIN + " dakikalığına açılır.\n\n"
            + "Bu ek sürede zorunlu molalar uygulanmaz; Reels/Shorts kuralları açıksa geçerli kalır.";
    private static final String HELP_FREE = "Seçtiğin süre boyunca bu uygulamadaki tüm engeller ve molalar kalkar. "
            + "Mavi sayaç kalan süreyi gösterir; süre bitince normal kurallar geri gelir, ek mola verilmez.\n\n"
            + "⋮ ile günlük toplam serbest süreyi ayarlarsın. Erken çıkarsan kullanmadığın süre geri eklenir.";
    private static final String HELP_NOTIFY = "Yuvarlak sayacı gizlediğinde kalan süre bildirim olarak geri sayar. "
            + "Bildirim izni gerekir. Durum çubuğunda canlı sayaç olarak görünmesi telefonun Android sürümüne bağlıdır; "
            + "desteklenmiyorsa bildirim panelinde görünür.";

    private SharedPreferences prefs, st;
    private TextView status;
    private Button open;
    private final Ring[] ring = new Ring[2];
    private final TextView[] usageText = new TextView[2], goalText = new TextView[2], freeInfo = new TextView[2],
            freeLeftText = new TextView[2];
    private final Button[] freeBtn = new Button[2], periodChip = new Button[2], breakChip = new Button[2],
            goalChip = new Button[2];
    private final View[] freeIdle = new View[2], freeActive = new View[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Store.SETTINGS, MODE_PRIVATE);
        st = getSharedPreferences(Store.STATE, MODE_PRIVATE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(24), dp(16), dp(24));
        box.setLayoutTransition(changing());

        box.addView(Ui.text(this, "FocusGuard", 32, Ui.WHITE, true));
        status = Ui.text(this, "", 14, Ui.GREY, false);
        status.setPadding(0, dp(4), 0, dp(12));
        box.addView(status);

        open = Ui.pill(this, "Erişilebilirlik ayarlarını aç", Ui.GREEN);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {}
        });
        box.addView(open, new LinearLayout.LayoutParams(WRAP, dp(48)));

        box.addView(card(0, new String[][]{
                {"ig_reels", "Reels sekmesini kapat"},
                {"ig_swipe", "Reel'de sonrakine kaydırmayı engelle"},
        }));
        box.addView(card(1, new String[][]{
                {"yt_shorts", "Shorts sekmesini kapat"},
                {"yt_swipe", "Short'ta sonrakine kaydırmayı engelle"},
        }));

        LinearLayout general = cardBox();
        TextView gt = Ui.text(this, "Genel", 20, Ui.WHITE, true);
        gt.setPadding(0, dp(10), 0, dp(4));
        general.addView(gt);
        general.addView(toggle("Gizlenen sayacı bildirimde göster", "notify_hidden", null, 15, () -> {
            if (prefs.getBoolean("notify_hidden", false) && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }, null, HELP_NOTIFY, false));
        box.addView(general);

        TextView tip = Ui.text(this, "İpucu: bir satırdaki ? işaretine dokunarak o ayarın ne yaptığını görebilirsin. "
                + "Bir korumayı kapatmak " + CONFIRM_SEC + " sn bekleme ve onay cümlesi ister.", 13, Ui.GREY, false);
        tip.setPadding(dp(4), dp(16), dp(4), 0);
        box.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(box);
        setContentView(sv);

        // Entrance: sections slide up one after another.
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(20));
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 50L).setDuration(260);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = false;
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled = s != null && s.contains(getPackageName() + "/");
        } catch (Throwable ignored) {}
        status.setText(enabled ? "● Aktif" : "● Kapalı — çalışması için erişilebilirlik iznini aç");
        status.setTextColor(enabled ? Ui.GREEN : Ui.AMBER);
        open.setVisibility(enabled ? View.GONE : View.VISIBLE);
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /** Once a second while a free mode is running and the screen is visible. */
    private void tick() {
        refresh();
    }

    /** Screen time, goal ring, chips and the free-mode area of both cards. */
    private void refresh() {
        Store.roll(st);
        long now = System.currentTimeMillis();
        boolean running = false;
        for (int a = 0; a < 2; a++) {
            if (usageText[a] == null) continue; // card not built yet
            String p = Store.PFX[a];
            long use = st.getLong("use_" + p, 0);
            boolean goal = prefs.getBoolean(p + "_goal", false);
            long goalMs = Store.goalMs(prefs, p);
            usageText[a].setText(Store.dur(use));
            goalText[a].setText(goal ? (use >= goalMs ? "Hedef aşıldı · " : "Hedef · ") + Store.dur(goalMs) : "Hedef kapalı");
            goalText[a].setTextColor(goal && use >= goalMs ? Ui.RED : Ui.GREY);
            float f = goal ? Math.min(1f, use / (float) goalMs) : 0f;
            ring[a].set(f, f >= 1f ? Ui.RED : Ui.GREEN, goal ? "%" + (int) (f * 100) : "–");

            periodChip[a].setText(Store.span((int) (Store.periodMs(prefs, p) / 60000), "sa", "dk"));
            breakChip[a].setText(Store.span((int) (Store.breakMs(prefs, p) / 1000), "dk", "sn"));
            goalChip[a].setText(Store.span((int) (goalMs / 60000), "sa", "dk"));

            long until = st.getLong("free_until_" + p, 0);
            boolean free = now < until;
            running |= free;
            freeIdle[a].setVisibility(free ? View.GONE : View.VISIBLE);
            freeActive[a].setVisibility(free ? View.VISIBLE : View.GONE);
            if (free) {
                long s = (until - now + 999) / 1000;
                freeLeftText[a].setText("Kalan  " + s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60);
                freeInfo[a].setText("Serbest mod açık · " + clock(until) + "'e kadar engeller kalktı");
            } else {
                freeBtn[a].setText("Bana bi " + Store.freeLabel(Store.freeSec(prefs, p)) + " ver");
                freeInfo[a].setText("Bugün kalan serbest süre · " + Store.dur(freeLeft(a)));
            }
        }
        handler.removeCallbacks(ticker);
        if (running) handler.postDelayed(ticker, 1000);
    }

    private String clock(long at) {
        return DateFormat.getTimeFormat(this).format(new Date(at));
    }

    private long freeLeft(int a) {
        String p = Store.PFX[a];
        return Math.max(0, Store.freeDailyMs(prefs, p) - st.getLong("free_used_" + p, 0));
    }

    // ---- free mode ("give me N minutes"), per app ----

    private void askFree(final int a) {
        Store.roll(st);
        final String p = Store.PFX[a];
        final long left = freeLeft(a);
        if (left < Store.MIN_FREE_SEC * 1000L) {
            help("Bugünkü hakkın bitti", "Günlük serbest süreni kullandın. ⋮ ile günlük toplamı değiştirebilirsin.");
            return;
        }
        int max = (int) Math.min(left / 1000, Store.MAX_FREE_MIN * 60L);
        pick("Ne kadar süre?", Store.NAME[a] + "'da bu sürede tüm engeller kalkar.\nBugün kalan: " + Store.dur(left),
                "dk", "sn", Math.min(Store.MAX_FREE_MIN, max / 60), Math.min(Store.freeSec(prefs, p), max),
                Store.MIN_FREE_SEC, max, "Başlat", total -> {
                    long ms = total * 1000L;
                    prefs.edit().putInt(p + "_free_sec", total).apply();
                    st.edit().putLong("free_used_" + p, st.getLong("free_used_" + p, 0) + ms)
                            .putLong("free_total_" + p, ms)
                            .putLong("free_until_" + p, System.currentTimeMillis() + ms).apply();
                });
    }

    // ---- app card ----

    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD, 14));
        c.setPadding(dp(16), dp(6), dp(16), dp(12));
        c.setLayoutTransition(changing());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    /** Animates size changes when rows appear/disappear. */
    private LayoutTransition changing() {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(200);
        return t;
    }

    /** One app: icon + title + master switch, today's screen time ring, free mode, then its settings. */
    private View card(final int a, String[][] items) {
        final String p = Store.PFX[a];
        LinearLayout c = cardBox();

        final LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        sub.setLayoutTransition(changing());
        final LinearLayout times = row(), goal = row();
        final Switch[] master = new Switch[1], brk = new Switch[1], gl = new Switch[1];
        final Runnable sync = () -> {
            boolean on = master[0].isChecked();
            sub.setAlpha(on ? 1f : 0.35f);
            enableAll(sub, on);
            times.setVisibility(brk[0].isChecked() ? View.VISIBLE : View.GONE);
            goal.setVisibility(gl[0].isChecked() ? View.VISIBLE : View.GONE);
            refresh();
        };

        // Free mode. Idle: [Bana bi 5 dakika ver] [⋮] [?]   Running: [Kalan 4:12] [Serbest moddan çık]
        LinearLayout idle = new LinearLayout(this);
        idle.setGravity(Gravity.CENTER_VERTICAL);
        freeBtn[a] = Ui.pill(this, "", Ui.GREEN);
        freeBtn[a].setOnClickListener(v -> askFree(a));
        idle.addView(freeBtn[a], new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button more = Ui.pill(this, "⋮", 0);
        more.setTextSize(20);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(v -> pick(Store.NAME[a] + " günlük serbest süre",
                "Bir günde toplam ne kadar serbest mod kullanabileceğin.", "sa", "dk", 24,
                (int) (Store.freeDailyMs(prefs, p) / 60000), 0, Store.MAX_FREE_DAILY_MIN, "Kaydet",
                total -> prefs.edit().putInt(p + "_free_daily_min", total).apply()));
        idle.addView(more, left(dp(46), 8));
        idle.addView(helpButton("Bana bi dakika ver", HELP_FREE), left(dp(30), 8));
        freeIdle[a] = idle;

        LinearLayout active = new LinearLayout(this);
        active.setGravity(Gravity.CENTER_VERTICAL);
        freeLeftText[a] = Ui.text(this, "", 16, Ui.WHITE, true);
        freeLeftText[a].setGravity(Gravity.CENTER);
        freeLeftText[a].setBackground(Ui.round(this, 0x333D91F4, 999));
        active.addView(freeLeftText[a], new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button exit = Ui.pill(this, "Serbest moddan çık", 0);
        exit.setPadding(dp(16), 0, dp(16), 0);
        exit.setOnClickListener(v -> {
            Store.endFree(st, p);
            refresh();
        });
        active.addView(exit, left(WRAP, 8));
        active.setVisibility(View.GONE);
        freeActive[a] = active;

        LinearLayout freeBox = new LinearLayout(this);
        freeBox.setOrientation(LinearLayout.VERTICAL);
        freeBox.setPadding(0, dp(4), 0, 0);
        freeBox.addView(idle);
        freeBox.addView(active);
        sub.addView(freeBox);
        freeInfo[a] = Ui.text(this, "", 13, Ui.GREY, false);
        freeInfo[a].setPadding(dp(4), dp(6), 0, dp(6));
        sub.addView(freeInfo[a]);

        for (String[] it : items) {
            sub.addView(toggle(it[1], it[0], null, 15, null, null, it[0].endsWith("_swipe") ? HELP_SWIPE : HELP_TAB, true));
        }

        sub.addView(toggle("Zorunlu mola", p + "_break", null, 15, sync, brk, HELP_BREAK, true));
        times.addView(Ui.text(this, "Her ", 15, Ui.GREY, false));
        periodChip[a] = Ui.chip(this, "");
        periodChip[a].setOnClickListener(v -> pick("Kullanım süresi", "Bu kadar kullanınca mola başlar.", "sa", "dk", 10,
                (int) (Store.periodMs(prefs, p) / 60000), 1, Store.MAX_PERIOD_MIN, "Kaydet",
                total -> prefs.edit().putInt(p + "_period_min", total).apply()));
        times.addView(periodChip[a], new LinearLayout.LayoutParams(WRAP, dp(36)));
        times.addView(Ui.text(this, " kullanımda ", 15, Ui.GREY, false));
        breakChip[a] = Ui.chip(this, "");
        breakChip[a].setOnClickListener(v -> pick("Mola süresi", "Mola bu kadar sürer.", "dk", "sn", 60,
                (int) (Store.breakMs(prefs, p) / 1000), Store.MIN_BREAK_SEC, Store.MAX_BREAK_SEC, "Kaydet",
                total -> prefs.edit().putInt(p + "_break_sec", total).apply()));
        times.addView(breakChip[a], new LinearLayout.LayoutParams(WRAP, dp(36)));
        times.addView(Ui.text(this, " mola", 15, Ui.GREY, false));
        sub.addView(times);

        sub.addView(toggle("Günlük ekran süresi hedefi", p + "_goal", null, 15, sync, gl, HELP_GOAL, true));
        goal.addView(Ui.text(this, "Günde en fazla ", 15, Ui.GREY, false));
        goalChip[a] = Ui.chip(this, "");
        goalChip[a].setOnClickListener(v -> pick("Günlük hedef", "Bu süreyi geçince uygulama onay ister.", "sa", "dk", 23,
                (int) (Store.goalMs(prefs, p) / 60000), 1, 23 * 60 + 59, "Kaydet",
                total -> prefs.edit().putInt(p + "_goal_h", total / 60).putInt(p + "_goal_m", total % 60).apply()));
        goal.addView(goalChip[a], new LinearLayout.LayoutParams(WRAP, dp(36)));
        sub.addView(goal);

        c.addView(toggle(Store.NAME[a], p, Store.PKG[a], 24, sync, master, HELP_MASTER, true));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        c.addView(line, new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1))));

        // Today's screen time: ring + numbers.
        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setPadding(0, dp(14), 0, dp(10));
        ring[a] = new Ring(this);
        stats.addView(ring[a], new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.VERTICAL);
        nums.setPadding(dp(16), 0, 0, 0);
        nums.addView(Ui.text(this, "Bugünkü ekran süresi", 13, Ui.GREY, false));
        usageText[a] = Ui.text(this, "", 22, Ui.WHITE, true);
        nums.addView(usageText[a]);
        goalText[a] = Ui.text(this, "", 13, Ui.GREY, false);
        nums.addView(goalText[a]);
        stats.addView(nums);
        c.addView(stats);

        c.addView(sub);
        sync.run();
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(2), 0, dp(10));
        return r;
    }

    private LinearLayout.LayoutParams left(int width, int marginDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(width, width == WRAP ? dp(46) : width);
        l.leftMargin = dp(marginDp);
        return l;
    }

    /** Round "?" that explains a setting. */
    private View helpButton(final String title, final String message) {
        TextView q = Ui.text(this, "?", 14, Ui.GREY, true);
        q.setGravity(Gravity.CENTER);
        GradientBox.outline(this, q);
        q.setOnClickListener(v -> help(title, message));
        return Ui.press(q);
    }

    /**
     * Row: [app icon] label [?] | short vertical bar | switch. The bar is shorter than the row, so bars never join.
     * Guarded switches can only be turned off through the confirmation popup.
     */
    private View toggle(final String label, final String key, String iconPkg, int sp, final Runnable after,
                        Switch[] out, String help, final boolean guarded) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));

        if (iconPkg != null) {
            try {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(getPackageManager().getApplicationIcon(iconPkg));
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(32), dp(32));
                il.rightMargin = dp(12);
                row.addView(icon, il);
            } catch (Throwable ignored) {} // app not installed: title only
        }

        TextView t = Ui.text(this, label, sp, Ui.WHITE, iconPkg != null);
        t.setPadding(0, dp(8), 0, dp(8));
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (help != null) row.addView(helpButton(label, help), new LinearLayout.LayoutParams(dp(26), dp(26)));

        View bar = new View(this);
        bar.setBackgroundColor(Ui.OFF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(22));
        bl.leftMargin = dp(12);
        bl.rightMargin = dp(12);
        row.addView(bar, bl);

        final Switch s = new Switch(this);
        s.setThumbTintList(new ColorStateList(STATES, new int[]{Ui.WHITE, Ui.GREY}));
        s.setTrackTintList(new ColorStateList(STATES, new int[]{Ui.GREEN, Ui.OFF}));
        s.setChecked(prefs.getBoolean(key, Store.def(key) && guarded));
        final boolean[] confirmed = {false};
        s.setOnCheckedChangeListener((v, on) -> {
            if (!on && guarded && !confirmed[0]) {
                confirmed[0] = true;
                s.setChecked(true);
                confirmed[0] = false;
                confirmOff(label, () -> {
                    confirmed[0] = true;
                    s.setChecked(false);
                    confirmed[0] = false;
                });
                return;
            }
            if (on == prefs.getBoolean(key, Store.def(key) && guarded)) return;
            prefs.edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        });
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        if (out != null) out[0] = s;
        return row;
    }

    private void enableAll(View v, boolean on) {
        v.setEnabled(on);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) enableAll(g.getChildAt(i), on);
    }

    // ---- dialogs ----

    private interface OnPick { void set(int total); }

    /**
     * Two scroll wheels (big unit, small unit; total = big*60 + small), like the system timer.
     * The action button unlocks only while the total is inside [min, max].
     */
    private void pick(String title, String info, String big, String small, int maxBig, int current,
                      final int min, final int max, String action, final OnPick onPick) {
        LinearLayout card = sheet(title);
        if (info != null) {
            TextView i = Ui.text(this, info, 14, Ui.GREY, false);
            i.setGravity(Gravity.CENTER);
            i.setPadding(0, dp(6), 0, dp(4));
            card.addView(i);
        }
        current = Store.clamp(current, min, max);
        final NumberPicker a = Ui.wheel(this, 0, maxBig, current / 60), b = Ui.wheel(this, 0, 59, current % 60);
        LinearLayout wheels = new LinearLayout(this);
        wheels.setGravity(Gravity.CENTER);
        wheels.addView(a);
        wheels.addView(Ui.text(this, " " + big + "    ", 16, Ui.GREY, false));
        wheels.addView(b);
        wheels.addView(Ui.text(this, " " + small, 16, Ui.GREY, false));
        card.addView(wheels);

        final Dialog dlg = dialog(card);
        final Button ok = buttons(card, dlg, action);
        final Runnable check = () -> {
            int total = a.getValue() * 60 + b.getValue();
            Ui.able(ok, total >= min && total <= max);
        };
        a.setOnValueChangedListener((v, o, n) -> check.run());
        b.setOnValueChangedListener((v, o, n) -> check.run());
        check.run();
        ok.setOnClickListener(v -> {
            int total = a.getValue() * 60 + b.getValue();
            if (total < min || total > max) return;
            onPick.set(total);
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    private void help(String title, String message) {
        LinearLayout card = sheet(title);
        TextView m = Ui.text(this, message, 15, Ui.GREY, false);
        m.setPadding(0, dp(10), 0, dp(18));
        m.setLineSpacing(dp(3), 1f);
        card.addView(m);
        final Dialog dlg = dialog(card);
        Button ok = Ui.pill(this, "Anladım", Ui.GREEN);
        ok.setOnClickListener(v -> dlg.dismiss());
        card.addView(ok, new LinearLayout.LayoutParams(WRAP, dp(44)));
        dlg.show();
    }

    /** Popup before a switch is turned off: wait 10 s AND type the phrase, then "Kapat" unlocks. */
    private void confirmOff(String label, final Runnable onConfirm) {
        LinearLayout card = sheet("Kapatmak istediğine emin misin?");
        TextView what = Ui.text(this, label, 14, Ui.GREY, false);
        what.setGravity(Gravity.CENTER);
        what.setPadding(0, dp(6), 0, dp(12));
        card.addView(what);

        final TextView count = Ui.text(this, String.valueOf(CONFIRM_SEC), 44, Ui.GREEN, true);
        card.addView(count);

        TextView hint = Ui.text(this, "Devam etmek için \"" + CONFIRM_PHRASE + "\" yaz", 14, Ui.GREY, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, dp(8));
        card.addView(hint);

        final EditText input = Ui.field(this, CONFIRM_PHRASE, false, 40);
        card.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));

        final Dialog dlg = dialog(card);
        final Button ok = buttons(card, dlg, "Kapat");
        Ui.able(ok, false);
        final boolean[] waited = {false};
        final Runnable update = () -> Ui.able(ok, waited[0]
                && input.getText().toString().trim().equalsIgnoreCase(CONFIRM_PHRASE));
        final CountDownTimer timer = new CountDownTimer(CONFIRM_SEC * 1000L, 250) {
            private long last = -1;
            @Override public void onTick(long left) {
                long s = (left + 999) / 1000;
                if (s == last) return;
                last = s;
                count.setText(String.valueOf(s));
                count.setScaleX(1.25f); // small tick animation
                count.setScaleY(1.25f);
                count.animate().scaleX(1f).scaleY(1f).setDuration(200);
            }
            @Override public void onFinish() {
                count.setText("✓");
                waited[0] = true;
                update.run();
            }
        };
        input.addTextChangedListener(new GateActivity.Watch(update));
        ok.setOnClickListener(v -> {
            dlg.dismiss();
            onConfirm.run();
        });
        dlg.setOnDismissListener(d -> timer.cancel());
        dlg.show();
        timer.start();
    }

    private LinearLayout sheet(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(20));
        card.setBackground(Ui.round(this, Ui.SHEET, 20));
        TextView t = Ui.text(this, title, 20, Ui.WHITE, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        return card;
    }

    /** Dialog that pops in (scale + fade) and uses the system dialog exit animation. */
    private Dialog dialog(final LinearLayout card) {
        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(card);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(dp(320), WRAP);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        dlg.setOnShowListener(d -> {
            card.setAlpha(0f);
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180);
        });
        return dlg;
    }

    /** Adds "Vazgeç" + the action button; returns the action button. */
    private Button buttons(LinearLayout card, final Dialog dlg, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(20), 0, 0);
        Button cancel = Ui.pill(this, "Vazgeç", 0);
        cancel.setOnClickListener(v -> dlg.dismiss());
        row.addView(cancel, new LinearLayout.LayoutParams(WRAP, dp(44)));
        Button ok = Ui.pill(this, action, Ui.GREEN);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(WRAP, dp(44));
        ol.leftMargin = dp(12);
        row.addView(ok, ol);
        card.addView(row);
        return ok;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    /** Outlined round background for the "?" buttons. */
    private static final class GradientBox {
        static void outline(Activity c, View v) {
            android.graphics.drawable.GradientDrawable g = Ui.round(c, 0, 999);
            g.setStroke(Math.max(1, Ui.dp(c, 1)), Ui.OFF);
            v.setBackground(g);
        }
    }
}
