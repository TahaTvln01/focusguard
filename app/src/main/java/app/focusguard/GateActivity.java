package app.focusguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

/** Shown over an app whose daily goal is exceeded: type the phrase, then ask for up to 10 minutes. */
public final class GateActivity extends Activity {

    private static final String PHRASE = "Acil işim var, beni içeri al";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        build();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        build();
    }

    private void build() {
        final int a = Store.clamp(getIntent().getIntExtra("a", 0), 0, 1);
        final String p = Store.PFX[a];
        final SharedPreferences st = getSharedPreferences(Store.STATE, MODE_PRIVATE);
        SharedPreferences prefs = getSharedPreferences(Store.SETTINGS, MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setFitsSystemWindows(true);
        int pad = Ui.dp(this, 28);
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.text(this, "Günlük hedefini aştın", 26, Ui.WHITE, true));
        TextView info = Ui.text(this, Store.NAME[a] + " · bugün " + Store.dur(st.getLong("use_" + p, 0))
                + " / hedef " + Store.dur(Store.goalMs(prefs, p)), 15, Ui.GREY, false);
        info.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        box.addView(info);

        // Step 1: the phrase.
        final LinearLayout one = new LinearLayout(this);
        one.setOrientation(LinearLayout.VERTICAL);
        one.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView h1 = Ui.text(this, "Girmek için \"" + PHRASE + "\" yaz", 14, Ui.GREY, false);
        h1.setGravity(Gravity.CENTER);
        h1.setPadding(0, 0, 0, Ui.dp(this, 10));
        one.addView(h1);
        final EditText phrase = Ui.field(this, PHRASE, false, 40);
        one.addView(phrase, new LinearLayout.LayoutParams(MATCH, WRAP));
        final Button next = Ui.pill(this, "Devam", Ui.GREEN);
        Ui.able(next, false);
        one.addView(next, top(20));
        box.addView(one, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Step 2: how many minutes.
        final LinearLayout two = new LinearLayout(this);
        two.setOrientation(LinearLayout.VERTICAL);
        two.setGravity(Gravity.CENTER_HORIZONTAL);
        two.setVisibility(View.GONE);
        TextView h2 = Ui.text(this, "Kaç dakika istiyorsun? (en fazla " + Store.MAX_GRANT_MIN + ")", 14, Ui.GREY, false);
        h2.setPadding(0, 0, 0, Ui.dp(this, 10));
        two.addView(h2);
        final NumberPicker mins = Ui.wheel(this, 1, Store.MAX_GRANT_MIN, 5);
        LinearLayout wheelRow = new LinearLayout(this);
        wheelRow.setGravity(Gravity.CENTER);
        wheelRow.addView(mins);
        wheelRow.addView(Ui.text(this, "  dk", 16, Ui.GREY, false));
        two.addView(wheelRow);
        final Button ok = Ui.pill(this, "İzin ver", Ui.GREEN);
        two.addView(ok, top(20));
        box.addView(two, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button cancel = Ui.pill(this, "Vazgeç", 0);
        cancel.setOnClickListener(v -> home());
        box.addView(cancel, top(12));

        phrase.addTextChangedListener(new Watch(() ->
                Ui.able(next, phrase.getText().toString().trim().equalsIgnoreCase(PHRASE))));
        next.setOnClickListener(v -> {
            // Hide the keyboard and cross-fade to the second step.
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(phrase.getWindowToken(), 0);
            one.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                one.setVisibility(View.GONE);
                two.setAlpha(0f);
                two.setVisibility(View.VISIBLE);
                two.animate().alpha(1f).setDuration(200);
            });
        });
        ok.setOnClickListener(v -> {
            int m = mins.getValue();
            if (m < 1 || m > Store.MAX_GRANT_MIN) return;
            long ms = m * 60000L;
            st.edit().putLong("grant_total_" + p, ms).putLong("grant_" + p, System.currentTimeMillis() + ms).apply();
            finish(); // back to the app underneath
        });

        setContentView(box);
        box.setAlpha(0f);
        box.setTranslationY(Ui.dp(this, 24));
        box.animate().alpha(1f).translationY(0f).setDuration(250);
    }

    private LinearLayout.LayoutParams top(int marginDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, Ui.dp(this, 46));
        l.topMargin = Ui.dp(this, marginDp);
        return l;
    }

    @Override
    public void onBackPressed() {
        home();
    }

    private void home() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
        finish();
    }

    /** TextWatcher that only cares that something changed. */
    static final class Watch implements TextWatcher {
        private final Runnable r;
        Watch(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}
