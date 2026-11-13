package app.focusguard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private static final int BG = 0xFF121212, CARD = 0xFF181818, FIELD = 0xFF2A2A2A,
            GREEN = 0xFF1DB954, WHITE = 0xFFFFFFFF, GREY = 0xFFB3B3B3, OFF = 0xFF535353, LINE = 0xFF333333, BLACK = 0xFF000000;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs;
    private float d;
    private TextView status;
    private Button copy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(GuardService.PREFS, MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(24), dp(16), dp(24));

        box.addView(text("FocusGuard", 30, WHITE, true));
        status = text("", 14, GREY, false);
        status.setPadding(0, dp(4), 0, dp(16));
        box.addView(status);

        Button open = pill("Erişilebilirlik ayarlarını aç", true);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {}
        });
        box.addView(open, new LinearLayout.LayoutParams(WRAP, dp(48)));

        box.addView(card("Instagram", "ig", new String[][]{
                {"ig_reels", "Reels sekmesi → ana sayfa"},
                {"ig_following", "Ana akış → yalnızca takip edilenler"},
                {"ig_swipe", "Açılan reel izlenir, sonrakine kaydırma engellenir"},
        }));
        box.addView(card("YouTube", "yt", new String[][]{
                {"yt_shorts", "Shorts sekmesi → ana sayfa"},
                {"yt_swipe", "Açılan short izlenir, sonrakine kaydırma engellenir"},
                {"yt_ads", "Atlanabilir reklamları otomatik atla"},
        }));

        TextView diag = text("Tanılama: bir özellik çalışmıyorsa düğmeye bas, 6 sn içinde Instagram/YouTube'da ilgili ekranı aç. "
                + "Ekranın yapısı (düğme adları, kısaltılmış yazılar) yalnızca bu telefonda saklanır; kopyalayıp geliştiriciye gönderebilirsin.",
                13, GREY, false);
        diag.setPadding(0, dp(24), 0, dp(12));
        box.addView(diag);
        Button rec = pill("Tanılama kaydı al", false);
        rec.setOnClickListener(v -> {
            prefs.edit().putLong("dump_req", System.currentTimeMillis()).apply();
            Toast.makeText(this, "6 sn içinde ilgili ekranı aç", Toast.LENGTH_SHORT).show();
        });
        box.addView(rec, new LinearLayout.LayoutParams(WRAP, dp(44)));
        copy = pill("Kaydı kopyala", false);
        copy.setOnClickListener(v -> {
            try {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("FocusGuard", prefs.getString("dump", "")));
                Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, dp(44));
        cl.topMargin = dp(8);
        box.addView(copy, cl);

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
        status.setTextColor(enabled ? GREEN : GREY);
        copy.setVisibility(prefs.contains("dump") ? View.VISIBLE : View.GONE);
    }

    /** One app: title + master switch, a divider, then its extra settings and forced-break settings. */
    private View card(String title, String p, String[][] items) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 12));
        c.setPadding(dp(16), dp(6), dp(16), dp(12));

        final LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        for (String[] it : items) sub.addView(toggle(it[1], it[0], 15, false, null));

        final LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setPadding(0, dp(4), 0, dp(8));
        times.addView(text("Her ", 15, GREY, false));
        times.addView(number(p + "_period_min", GuardService.DEF_PERIOD_MIN,
                GuardService.MIN_PERIOD_MIN, GuardService.MAX_PERIOD_MIN));
        times.addView(text(" dk kullanımda ", 15, GREY, false));
        times.addView(number(p + "_break_sec", GuardService.DEF_BREAK_SEC,
                GuardService.MIN_BREAK_SEC, GuardService.MAX_BREAK_SEC));
        times.addView(text(" sn mola", 15, GREY, false));
        more.addView(times);
        more.addView(toggle("Ekranda sayaç göster (sürükleyerek taşınır)", p + "_counter", 15, false, null));

        final Switch[] brk = new Switch[1];
        sub.addView(toggle("Zorunlu mola", p + "_break", 15, false, on -> enable(more, on), brk));
        sub.addView(more);

        final Switch[] master = new Switch[1];
        c.addView(toggle(title, p, 22, true, on -> {
            enable(sub, on);
            if (on) enable(more, brk[0].isChecked());
        }, master));
        enable(sub, master[0].isChecked());
        if (master[0].isChecked()) enable(more, brk[0].isChecked());

        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1)));
        ll.bottomMargin = dp(4);
        c.addView(line, ll);
        c.addView(sub);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private interface OnToggle { void set(boolean on); }

    private View toggle(String label, String key, int sp, boolean bold, OnToggle extra) {
        return toggle(label, key, sp, bold, extra, null);
    }

    /** Row: label | short vertical bar | switch (the bar is shorter than the row, so bars never join). */
    private View toggle(String label, final String key, int sp, boolean bold, final OnToggle extra, Switch[] out) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));

        TextView t = text(label, sp, WHITE, bold);
        t.setPadding(0, dp(8), 0, dp(8));
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));

        View bar = new View(this);
        bar.setBackgroundColor(OFF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(22));
        bl.leftMargin = dp(12);
        bl.rightMargin = dp(12);
        row.addView(bar, bl);

        final Switch s = new Switch(this);
        s.setThumbTintList(new ColorStateList(STATES, new int[]{WHITE, GREY}));
        s.setTrackTintList(new ColorStateList(STATES, new int[]{GREEN, OFF}));
        s.setChecked(prefs.getBoolean(key, GuardService.def(key)));
        s.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (extra != null) extra.set(on);
        });
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        if (out != null) out[0] = s;
        return row;
    }

    /** Numeric field; saved as soon as the value is inside [min, max]. */
    private EditText number(final String key, int def, final int min, final int max) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        e.setText(String.valueOf(prefs.getInt(key, def)));
        e.setTextColor(WHITE);
        e.setTextSize(15);
        e.setTypeface(Typeface.DEFAULT_BOLD);
        e.setGravity(Gravity.CENTER);
        e.setBackground(round(FIELD, 8));
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        e.setMinWidth(dp(56));
        e.setHint(min + "-" + max);
        e.setHintTextColor(OFF);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    boolean ok = v >= min && v <= max;
                    e.setTextColor(ok ? WHITE : 0xFFE22134);
                    if (ok) prefs.edit().putInt(key, v).apply();
                } catch (Throwable ignored) {}
            }
        });
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

    private Button pill(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(28), 0, dp(28), 0);
        GradientDrawable g = round(filled ? GREEN : 0, 999);
        if (!filled) g.setStroke(Math.max(1, dp(1)), GREY);
        b.setBackground(g);
        b.setTextColor(filled ? BLACK : WHITE);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    private int dp(int v) {
        return (int) (v * d + 0.5f);
    }
}
