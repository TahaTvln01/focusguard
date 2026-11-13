package app.focusguard;

import android.app.Activity;
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

public final class MainActivity extends Activity {

    private static final int BG = 0xFF121212, CARD = 0xFF181818, FIELD = 0xFF2A2A2A,
            GREEN = 0xFF1DB954, WHITE = 0xFFFFFFFF, GREY = 0xFFB3B3B3, OFF = 0xFF535353, BLACK = 0xFF000000;
    private static final int[][] STATES = {{android.R.attr.state_checked}, {}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences prefs;
    private float d;
    private TextView status;

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

        Button open = new Button(this);
        open.setText("Erişilebilirlik ayarlarını aç");
        open.setAllCaps(false);
        open.setTextColor(BLACK);
        open.setTextSize(15);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setBackground(round(GREEN, 999));
        open.setStateListAnimator(null);
        open.setPadding(dp(28), 0, dp(28), 0);
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
    }

    /** One app: title + master switch, then its extra settings and forced-break settings. */
    private View card(String title, String p, String[][] items) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 12));
        c.setPadding(dp(16), dp(8), dp(16), dp(12));

        final LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        for (String[] it : items) sub.addView(toggle(it[1], it[0], 15, false, null));

        final LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setPadding(0, 0, 0, dp(4));
        times.addView(text("Her ", 15, GREY, false));
        times.addView(number(p + "_period_min", GuardService.DEF_PERIOD_MIN,
                GuardService.MIN_PERIOD_MIN, GuardService.MAX_PERIOD_MIN));
        times.addView(text(" dk kullanımda ", 15, GREY, false));
        times.addView(number(p + "_break_sec", GuardService.DEF_BREAK_SEC,
                GuardService.MIN_BREAK_SEC, GuardService.MAX_BREAK_SEC));
        times.addView(text(" sn mola", 15, GREY, false));

        final Switch brk = toggle("Zorunlu mola", p + "_break", 15, false, on -> enable(times, on));
        sub.addView(brk);
        sub.addView(times);
        enable(times, brk.isChecked());

        Switch master = toggle(title, p, 22, true, on -> {
            enable(sub, on);
            if (on) enable(times, brk.isChecked());
        });
        enable(sub, master.isChecked());
        if (master.isChecked()) enable(times, brk.isChecked());

        c.addView(master);
        c.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private interface OnToggle { void set(boolean on); }

    private Switch toggle(String label, final String key, int sp, boolean bold, final OnToggle extra) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(WHITE);
        s.setTextSize(sp);
        if (bold) s.setTypeface(Typeface.DEFAULT_BOLD);
        s.setPadding(0, dp(12), 0, dp(12));
        s.setThumbTintList(new ColorStateList(STATES, new int[]{WHITE, GREY}));
        s.setTrackTintList(new ColorStateList(STATES, new int[]{GREEN, OFF}));
        s.setChecked(prefs.getBoolean(key, GuardService.def(key)));
        s.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (extra != null) extra.set(on);
        });
        return s;
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
    private void enable(View v, boolean on) {
        v.setEnabled(on);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        g.setAlpha(on ? 1f : 0.35f);
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof LinearLayout) enableAll((ViewGroup) c, on); else c.setEnabled(on);
        }
    }

    private void enableAll(ViewGroup g, boolean on) {
        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setEnabled(on);
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
