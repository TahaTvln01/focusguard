package app.focusguard;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Shared look: dark surfaces, green accent, pill buttons. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF121212, CARD = 0xFF181818, SHEET = 0xFF282828, FIELD = 0xFF2A2A2A,
            GREEN = 0xFF1DB954, AMBER = 0xFFF59B23, BLUE = 0xFF3D91F4, RED = 0xFFE5484D,
            WHITE = 0xFFFFFFFF, GREY = 0xFFB3B3B3, OFF = 0xFF535353, LINE = 0xFF333333, BLACK = 0xFF000000;

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable round(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Pill button; fill == 0 draws an outlined one. */
    static Button pill(Context c, String label, int fill) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        GradientDrawable g = round(c, fill, 999);
        if (fill == 0) g.setStroke(Math.max(1, dp(c, 1)), GREY);
        b.setBackground(g);
        b.setTextColor(fill == 0 ? WHITE : BLACK);
        return press(b);
    }

    static EditText field(Context c, String hint, boolean number, int maxLen) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        e.setHint(hint);
        e.setHintTextColor(OFF);
        e.setTextColor(WHITE);
        e.setTextSize(number ? 15 : 16);
        if (number) {
            e.setTypeface(Typeface.DEFAULT_BOLD);
            e.setGravity(Gravity.CENTER);
            e.setMinWidth(dp(c, 52));
        }
        e.setBackground(round(c, FIELD, 8));
        e.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        return e;
    }

    /** Small press-down animation for anything tappable. */
    static <T extends View> T press(final T v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80);
            else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(140);
            return false;
        });
        return v;
    }

    /** Small tappable value chip (opens a wheel picker). */
    static Button chip(Context c, String label) {
        Button b = pill(c, label, FIELD);
        b.setTextColor(WHITE);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    /** Scroll wheel, like the system timer pickers. */
    static android.widget.NumberPicker wheel(Context c, int min, int max, int value) {
        android.widget.NumberPicker n = new android.widget.NumberPicker(c);
        max = Math.max(min, max);
        n.setMinValue(min);
        n.setMaxValue(max);
        n.setValue(Math.max(min, Math.min(max, value)));
        n.setWrapSelectorWheel(max - min >= 3);
        n.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS); // scroll only, no keyboard
        n.setTextColor(WHITE);
        n.setFormatter(v -> v < 10 ? "0" + v : String.valueOf(v));
        return n;
    }

    static void able(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    /** Parsed int or -1. */
    static int num(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}
