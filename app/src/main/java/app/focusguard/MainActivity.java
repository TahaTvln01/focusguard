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
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/** Single settings screen: one card per app (free-mode button, switches, breaks, screen time, goal). */
public final class MainActivity extends Activity {

    private static final String CONFIRM_PHRASE = "Bunu gerçekten istiyorum";
    private static final int CONFIRM_SEC = 10;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs, st;
    private TextView status;
    private Button open;
    private final TextView[] usageText = new TextView[2], freeInfo = new TextView[2];
    private final Button[] freeBtn = new Button[2];
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

            freeBtn[a].setText("Bana bi " + Store.freeLabel(Store.freeSec(prefs, p)) + " ver");
            long until = st.getLong("free_until_" + p, 0);
            freeInfo[a].setText(now < until
                    ? "Serbest mod açık: " + clock(until) + "'e kadar engeller kalktı"
                    : "Bugün kalan serbest süre: " + Store.dur(freeLeft(a)));
        }
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
        if (System.currentTimeMillis() < st.getLong("free_until_" + p, 0)) { toast("Serbest mod zaten açık"); return; }
        final long left = freeLeft(a);
        if (left < Store.MIN_FREE_SEC * 1000L) { toast("Bugünkü hakkın bitti"); return; }

        LinearLayout card = sheet("Ne kadar süre?");
        TextView info = Ui.text(this, Store.NAME[a] + "'da bu sürede tüm engeller kalkar.\nBugün kalan: " + Store.dur(left),
                14, Ui.GREY, false);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(6), 0, dp(8));
        card.addView(info);

        int last = (int) Math.min(Store.freeSec(prefs, p), left / 1000);
        final NumberPicker min = wheel(0, (int) Math.min(Store.MAX_FREE_MIN, left / 60000), last / 60);
        final NumberPicker sec = wheel(0, 59, last % 60);
        LinearLayout wheels = new LinearLayout(this);
        wheels.setGravity(Gravity.CENTER);
        wheels.addView(min);
        wheels.addView(Ui.text(this, " dk   ", 16, Ui.GREY, false));
        wheels.addView(sec);
        wheels.addView(Ui.text(this, " sn", 16, Ui.GREY, false));
        card.addView(wheels);

        final Dialog dlg = dialog(card);
        final Button ok = buttons(card, dlg, "Başlat");
        final Runnable check = () -> {
            long ms = (min.getValue() * 60L + sec.getValue()) * 1000;
            Ui.able(ok, ms >= Store.MIN_FREE_SEC * 1000L && ms <= left);
        };
        min.setOnValueChangedListener((v, o, n) -> check.run());
        sec.setOnValueChangedListener((v, o, n) -> check.run());
        check.run();
        ok.setOnClickListener(v -> {
            int total = min.getValue() * 60 + sec.getValue();
            long ms = total * 1000L;
            if (ms < Store.MIN_FREE_SEC * 1000L || ms > left) return;
            prefs.edit().putInt(p + "_free_sec", total).apply();
            st.edit().putLong("free_used_" + p, st.getLong("free_used_" + p, 0) + ms)
                    .putLong("free_total_" + p, ms)
                    .putLong("free_until_" + p, System.currentTimeMillis() + ms).apply();
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    /** Scroll wheel, like the system timer pickers. */
    private NumberPicker wheel(int min, int max, int value) {
        NumberPicker n = new NumberPicker(this);
        n.setMinValue(min);
        n.setMaxValue(Math.max(min, max));
        n.setValue(Store.clamp(value, min, Math.max(min, max)));
        n.setWrapSelectorWheel(max - min >= 3);
        n.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); // scroll only, no keyboard
        n.setTextColor(Ui.WHITE);
        n.setFormatter(v -> v < 10 ? "0" + v : String.valueOf(v));
        return n;
    }

    private void editFree(final int a) {
        final String p = Store.PFX[a];
        LinearLayout card = sheet(Store.NAME[a] + " serbest süre");
        int daily = (int) (Store.freeDailyMs(prefs, p) / 60000);
        final EditText h = Ui.field(this, "0-24", true, 2), m = Ui.field(this, "0-59", true, 2);
        h.setText(String.valueOf(daily / 60));
        m.setText(String.valueOf(daily % 60));
        card.addView(line("Günlük toplam", h, "sa", m, "dk"));

        final Dialog dlg = dialog(card);
        Button ok = buttons(card, dlg, "Kaydet");
        ok.setOnClickListener(v -> {
            int hh = Ui.num(h), mm = Ui.num(m);
            if (hh < 0 || mm < 0 || mm > 59 || hh * 60 + mm > Store.MAX_FREE_DAILY_MIN) {
                toast("Geçersiz değer");
                return;
            }
            prefs.edit().putInt(p + "_free_daily_min", hh * 60 + mm).apply();
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

        // "Give me N minutes" + its daily limit behind the three dots.
        LinearLayout free = new LinearLayout(this);
        free.setGravity(Gravity.CENTER_VERTICAL);
        free.setPadding(0, dp(10), 0, 0);
        freeBtn[a] = Ui.pill(this, "", Ui.GREEN);
        freeBtn[a].setOnClickListener(v -> askFree(a));
        free.addView(freeBtn[a], new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button more = Ui.pill(this, "⋮", 0);
        more.setTextSize(20);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(v -> editFree(a));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(46), dp(46));
        ml.leftMargin = dp(8);
        free.addView(more, ml);
        sub.addView(free);
        freeInfo[a] = Ui.text(this, "", 13, Ui.GREY, false);
        freeInfo[a].setPadding(dp(4), dp(6), 0, dp(6));
        sub.addView(freeInfo[a]);

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
