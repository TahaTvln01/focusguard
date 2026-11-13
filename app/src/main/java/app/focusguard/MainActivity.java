package app.focusguard;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/** Single settings screen: free-mode button, then one card per app (switches, breaks, screen time, goal). */
public final class MainActivity extends Activity {

    private static final String CONFIRM_PHRASE = "Bunu gerçekten istiyorum";
    private static final int CONFIRM_SEC = 10;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs, st;
    private TextView status, freeInfo;
    private Button open;
    private final TextView[] usageText = new TextView[2];
    private final View[] barFill = new View[2], barRest = new View[2];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Store.SETTINGS, MODE_PRIVATE);
        st = getSharedPreferences(Store.STATE, MODE_PRIVATE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(24), dp(16), dp(24));

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
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(WRAP, dp(48));
        ol.bottomMargin = dp(12);
        box.addView(open, ol);

        // Free mode: "give me N minutes" + its settings behind the three dots.
        LinearLayout free = new LinearLayout(this);
        free.setGravity(Gravity.CENTER_VERTICAL);
        Button give = Ui.pill(this, "Bana bi dakika ver", Ui.GREEN);
        give.setOnClickListener(v -> askFree());
        free.addView(give, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button more = Ui.pill(this, "⋮", 0);
        more.setTextSize(20);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(v -> editFree());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(48));
        ml.leftMargin = dp(8);
        free.addView(more, ml);
        box.addView(free);
        freeInfo = Ui.text(this, "", 13, Ui.GREY, false);
        freeInfo.setPadding(dp(4), dp(6), 0, 0);
        box.addView(freeInfo);

        box.addView(card(0, new String[][]{
                {"ig_reels", "Reels sekmesi → ana sayfa"},
                {"ig_swipe", "Açılan reel izlenir, sonrakine kaydırma engellenir"},
        }));
        box.addView(card(1, new String[][]{
                {"yt_shorts", "Shorts sekmesi → ana sayfa"},
                {"yt_swipe", "Açılan short izlenir, sonrakine kaydırma engellenir"},
        }));

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(box);
        setContentView(sv);
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
        status.setText(enabled ? "● Aktif" : "● Kapalı — erişilebilirlik iznini açın");
        status.setTextColor(enabled ? Ui.GREEN : Ui.GREY);
        open.setVisibility(enabled ? View.GONE : View.VISIBLE);
        refresh();
    }

    /** Today's screen time per app and the free-mode line. */
    private void refresh() {
        Store.roll(st);
        long now = System.currentTimeMillis();
        for (int a = 0; a < 2; a++) {
            if (usageText[a] == null) continue; // card not built yet
            String p = Store.PFX[a];
            long use = st.getLong("use_" + p, 0);
            boolean goal = prefs.getBoolean(p + "_goal", false);
            long goalMs = Store.goalMs(prefs, p);
            usageText[a].setText("Bugün: " + Store.dur(use) + (goal ? " / hedef " + Store.dur(goalMs) : ""));
            float f = goal ? Math.min(1f, use / (float) goalMs) : 0f;
            barFill[a].setBackgroundColor(f >= 1f ? Ui.RED : Ui.GREEN);
            ((LinearLayout.LayoutParams) barFill[a].getLayoutParams()).weight = f;
            ((LinearLayout.LayoutParams) barRest[a].getLayoutParams()).weight = 1f - f;
            barFill[a].requestLayout();
        }
        long freeUntil = st.getLong("free_until", 0), lockUntil = st.getLong("lock_until", 0);
        if (now < freeUntil) {
            freeInfo.setText("Serbest mod açık: " + clock(freeUntil) + "'e kadar tüm engeller kalktı");
        } else if (now < lockUntil) {
            freeInfo.setText("Serbest mod bitti: " + clock(lockUntil) + "'e kadar zorunlu mola");
        } else {
            freeInfo.setText("Bugün kalan: " + Store.dur(freeLeft()) + " · sonrasında "
                    + Store.dur(Store.freeBreakMs(prefs)) + " zorunlu mola");
        }
    }

    private String clock(long at) {
        return DateFormat.getTimeFormat(this).format(new Date(at));
    }

    private long freeLeft() {
        return Math.max(0, Store.freeDailyMs(prefs) - st.getLong("free_used", 0));
    }

    // ---- free mode ----

    private void askFree() {
        Store.roll(st);
        final long now = System.currentTimeMillis();
        if (now < st.getLong("free_until", 0)) { toast("Serbest mod zaten açık"); return; }
        if (now < st.getLong("lock_until", 0)) { toast("Önce zorunlu mola bitmeli"); return; }
        final int max = (int) (freeLeft() / 60000);
        if (max < 1) { toast("Bugünkü hakkın bitti"); return; }

        LinearLayout card = sheet("Kaç dakika istiyorsun?");
        TextView info = Ui.text(this, "Bu sürede tüm engeller kalkar. Bugün en fazla " + max + " dk.", 14, Ui.GREY, false);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(6), 0, dp(14));
        card.addView(info);
        final EditText mins = Ui.field(this, "1-" + max, true, 4);
        card.addView(mins, new LinearLayout.LayoutParams(dp(96), WRAP));
        final Dialog dlg = dialog(card);
        final Button ok = buttons(card, dlg, "Başlat");
        Ui.able(ok, false);
        mins.addTextChangedListener(new GateActivity.Watch(() -> {
            int m = Ui.num(mins);
            Ui.able(ok, m >= 1 && m <= max);
        }));
        ok.setOnClickListener(v -> {
            int m = Ui.num(mins);
            if (m < 1 || m > max) return;
            long ms = m * 60000L, t = System.currentTimeMillis();
            st.edit().putLong("free_used", st.getLong("free_used", 0) + ms)
                    .putLong("free_total", ms)
                    .putLong("lock_until", t + ms + Store.freeBreakMs(prefs))
                    .putLong("free_until", t + ms).apply();
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    private void editFree() {
        LinearLayout card = sheet("Serbest mod ayarları");
        int daily = (int) (Store.freeDailyMs(prefs) / 60000);

        final EditText brk = Ui.field(this, "0-" + Store.MAX_FREE_BREAK_MIN, true, 3);
        brk.setText(String.valueOf(Store.freeBreakMs(prefs) / 60000));
        card.addView(line("Bitince zorunlu mola", brk, "dk", null, null));

        final EditText h = Ui.field(this, "0-24", true, 2), m = Ui.field(this, "0-59", true, 2);
        h.setText(String.valueOf(daily / 60));
        m.setText(String.valueOf(daily % 60));
        card.addView(line("Günlük toplam", h, "sa", m, "dk"));

        final Dialog dlg = dialog(card);
        Button ok = buttons(card, dlg, "Kaydet");
        ok.setOnClickListener(v -> {
            int b = Ui.num(brk), hh = Ui.num(h), mm = Ui.num(m);
            if (b < 0 || b > Store.MAX_FREE_BREAK_MIN || hh < 0 || mm < 0 || mm > 59
                    || hh * 60 + mm > Store.MAX_FREE_DAILY_MIN) {
                toast("Geçersiz değer");
                return;
            }
            prefs.edit().putInt("free_break_min", b).putInt("free_daily_min", hh * 60 + mm).apply();
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    // ---- app card ----

    /** One app: icon + title + master switch, a divider, today's screen time, then its settings. */
    private View card(final int a, String[][] items) {
        final String p = Store.PFX[a];
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(this, Ui.CARD, 12));
        c.setPadding(dp(16), dp(6), dp(16), dp(12));

        final LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout times = row();
        final LinearLayout goal = row();
        final Switch[] master = new Switch[1], brk = new Switch[1], gl = new Switch[1];
        final Runnable sync = () -> {
            boolean on = master[0].isChecked();
            enable(sub, on);
            if (on) {
                enable(times, brk[0].isChecked());
                enable(goal, gl[0].isChecked());
            }
            refresh();
        };

        for (String[] it : items) sub.addView(toggle(it[1], it[0], null, 15, null, null));

        sub.addView(toggle("Zorunlu mola", p + "_break", null, 15, sync, brk));
        times.addView(Ui.text(this, "Her ", 15, Ui.GREY, false));
        times.addView(number(p + "_period_min", Store.DEF_PERIOD_MIN, 1, Store.MAX_PERIOD_MIN));
        times.addView(Ui.text(this, " dk kullanımda ", 15, Ui.GREY, false));
        times.addView(number(p + "_break_sec", Store.DEF_BREAK_SEC, Store.MIN_BREAK_SEC, Store.MAX_BREAK_SEC));
        times.addView(Ui.text(this, " sn mola", 15, Ui.GREY, false));
        sub.addView(times);

        sub.addView(toggle("Günlük ekran süresi hedefi", p + "_goal", null, 15, sync, gl));
        goal.addView(Ui.text(this, "Hedef ", 15, Ui.GREY, false));
        goal.addView(number(p + "_goal_h", 1, 0, 23));
        goal.addView(Ui.text(this, " sa ", 15, Ui.GREY, false));
        goal.addView(number(p + "_goal_m", 0, 0, 59));
        goal.addView(Ui.text(this, " dk", 15, Ui.GREY, false));
        sub.addView(goal);

        c.addView(toggle(Store.NAME[a], p, Store.PKG[a], 24, sync, master));

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        c.addView(line, new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1))));

        usageText[a] = Ui.text(this, "", 14, Ui.GREY, false);
        usageText[a].setPadding(0, dp(10), 0, dp(6));
        c.addView(usageText[a]);
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(this, Ui.LINE, 2));
        barFill[a] = new View(this);
        barRest[a] = new View(this);
        bar.addView(barFill[a], new LinearLayout.LayoutParams(0, MATCH, 0f));
        bar.addView(barRest[a], new LinearLayout.LayoutParams(0, MATCH, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(MATCH, dp(4));
        bl.bottomMargin = dp(4);
        c.addView(bar, bl);

        c.addView(sub);
        sync.run();

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(2), 0, dp(10));
        return r;
    }

    /**
     * Row: [app icon] label | short vertical bar | switch. The bar is shorter than the row, so bars never join.
     * Turning a switch off always goes through the confirmation popup.
     */
    private View toggle(final String label, final String key, String iconPkg, int sp, final Runnable after, Switch[] out) {
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

        View bar = new View(this);
        bar.setBackgroundColor(Ui.OFF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(22));
        bl.leftMargin = dp(12);
        bl.rightMargin = dp(12);
        row.addView(bar, bl);

        final Switch s = new Switch(this);
        s.setThumbTintList(new ColorStateList(STATES, new int[]{Ui.WHITE, Ui.GREY}));
        s.setTrackTintList(new ColorStateList(STATES, new int[]{Ui.GREEN, Ui.OFF}));
        s.setChecked(prefs.getBoolean(key, Store.def(key)));
        final boolean[] confirmed = {false};
        s.setOnCheckedChangeListener((v, on) -> {
            if (!on && !confirmed[0]) {
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
            if (on == prefs.getBoolean(key, Store.def(key))) return;
            prefs.edit().putBoolean(key, on).apply();
            if (after != null) after.run();
        });
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        if (out != null) out[0] = s;
        return row;
    }

    /** Numeric setting; saved as soon as the value is inside [min, max]. */
    private EditText number(final String key, int def, final int min, final int max) {
        final EditText e = Ui.field(this, min + "-" + max, true, 4);
        e.setText(String.valueOf(prefs.getInt(key, def)));
        e.addTextChangedListener(new GateActivity.Watch(() -> {
            int v = Ui.num(e);
            boolean ok = v >= min && v <= max;
            e.setTextColor(ok ? Ui.WHITE : Ui.RED);
            if (ok && v != prefs.getInt(key, def)) {
                prefs.edit().putInt(key, v).apply();
                refresh();
            }
        }));
        return e;
    }

    /** Enables/disables a whole group and dims it when disabled. */
    private void enable(ViewGroup g, boolean on) {
        g.setAlpha(on ? 1f : 0.35f);
        enableAll(g, on);
    }

    private void enableAll(View v, boolean on) {
        v.setEnabled(on);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) enableAll(g.getChildAt(i), on);
    }

    // ---- dialogs ----

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
            @Override public void onTick(long left) {
                count.setText(String.valueOf((left + 999) / 1000));
            }
            @Override public void onFinish() {
                count.setText("0");
                count.setTextColor(Ui.GREY);
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

    private Dialog dialog(LinearLayout card) {
        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(card);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(dp(320), WRAP);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
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

    /** "label  [a] ua  [b] ub" settings line (b optional). */
    private View line(String label, EditText a, String ua, EditText b, String ub) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(14), 0, 0);
        r.addView(Ui.text(this, label, 15, Ui.WHITE, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(a);
        r.addView(Ui.text(this, " " + ua + " ", 15, Ui.GREY, false));
        if (b != null) {
            r.addView(b);
            r.addView(Ui.text(this, " " + ub, 15, Ui.GREY, false));
        }
        r.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
        return r;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
