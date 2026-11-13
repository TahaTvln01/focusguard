package app.focusguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating countdown pill + "break time" popup. Uses accessibility overlay windows (no extra permission). */
final class Overlay {
    static final int USE = 0xFF1DB954, BREAK = 0xFFF59B23;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final float d;
    private Pill pill;
    private WindowManager.LayoutParams lp;
    private boolean hiding;
    private View popup;

    Overlay(Context c, SharedPreferences p) {
        ctx = c;
        prefs = p;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        d = c.getResources().getDisplayMetrics().density;
    }

    /** Shows/updates the pill counting down to endAt (elapsedRealtime). */
    void show(boolean brk, long endAt, long total) {
        int color = brk ? BREAK : USE;
        if (pill == null) {
            pill = new Pill(ctx, d);
            pill.color = color;
            lp = new WindowManager.LayoutParams(WRAP, WRAP,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = prefs.getInt("ov_x", (int) (16 * d));
            lp.y = prefs.getInt("ov_y", (int) (96 * d));
            pill.setOnTouchListener(new Drag());
            pill.setAlpha(0f);
            wm.addView(pill, lp);
            pill.animate().alpha(1f).setDuration(200);
        } else if (hiding) {
            hiding = false;
            pill.animate().cancel();
            pill.animate().alpha(1f).setDuration(150).withEndAction(null);
        }
        pill.endAt = endAt;
        pill.total = total;
        pill.fadeTo(color);
        pill.invalidate();
    }

    void hide() {
        if (pill == null || hiding) return;
        hiding = true;
        final Pill v = pill;
        v.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (hiding && pill == v) removePill();
        });
    }

    /** Small bump to say "still on a break". */
    void pulse() {
        if (pill == null) return;
        pill.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120)
                .withEndAction(() -> { if (pill != null) pill.animate().scaleX(1f).scaleY(1f).setDuration(160); });
    }

    void popup(String app, long sec) {
        dismissPopup();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(20));
        card.setMinimumWidth(dp(280));
        card.setBackground(round(0xFF282828, 20));

        TextView t = new TextView(ctx);
        t.setText("Mola vakti");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);

        TextView m = new TextView(ctx);
        m.setText(app + " için " + sec + " sn mola.\nTelefonu kullanmaya devam edebilirsin.");
        m.setTextColor(0xFFB3B3B3);
        m.setTextSize(15);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(8), 0, dp(20));
        card.addView(m);

        Button ok = new Button(ctx);
        ok.setText("Tamam");
        ok.setAllCaps(false);
        ok.setTextColor(0xFF000000);
        ok.setTextSize(15);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setBackground(round(BREAK, 999));
        ok.setStateListAnimator(null);
        ok.setPadding(dp(40), 0, dp(40), 0);
        ok.setOnClickListener(v -> { try { dismissPopup(); } catch (Throwable ignored) {} });
        card.addView(ok, new LinearLayout.LayoutParams(WRAP, dp(46)));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WRAP, WRAP,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        p.dimAmount = 0.6f;
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        wm.addView(card, p);
        popup = card;
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200);
    }

    void dismissPopup() {
        if (popup == null) return;
        View v = popup;
        popup = null;
        try { wm.removeViewImmediate(v); } catch (Throwable ignored) {}
    }

    void destroy() {
        dismissPopup();
        removePill();
    }

    private void removePill() {
        if (pill == null) return;
        View v = pill;
        pill = null;
        hiding = false;
        try { wm.removeViewImmediate(v); } catch (Throwable ignored) {}
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

    /** Drag to move; position is remembered. */
    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = Math.max(0, startX + (int) (e.getRawX() - downX));
                        lp.y = Math.max(0, startY + (int) (e.getRawY() - downY));
                        if (pill != null) wm.updateViewLayout(pill, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        prefs.edit().putInt("ov_x", lp.x).putInt("ov_y", lp.y).apply();
                        return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /** Pill: progress ring + m:ss. Redraws itself; no external ticking needed. */
    private static final class Pill extends View {
        long endAt, total;
        int color;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator fade;

        Pill(Context c, float density) {
            super(c);
            d = density;
            p.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void fadeTo(int to) {
            if (to == color) return;
            if (fade != null) fade.cancel();
            fade = ValueAnimator.ofArgb(color, to).setDuration(300);
            fade.addUpdateListener(a -> { color = (Integer) a.getAnimatedValue(); invalidate(); });
            fade.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (96 * d), (int) (36 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long rem = Math.max(0, endAt - SystemClock.elapsedRealtime());
            float frac = total > 0 ? Math.min(1f, rem / (float) total) : 0f;

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF0181818);
            c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, h / 2, h / 2, p);

            float cx = h / 2 + 2 * d, cy = h / 2, rad = h / 2 - 9 * d;
            p.setStrokeWidth(3 * d);
            c.drawCircle(cx, cy, rad, p);
            int col = color;
            if (rem < 10000) { // gentle pulse in the last 10 s
                float k = 0.55f + 0.45f * (float) Math.abs(Math.cos(rem / 1000.0 * Math.PI));
                col = (col & 0x00FFFFFF) | ((int) (255 * k) << 24);
            }
            p.setColor(col);
            p.setStrokeCap(Paint.Cap.ROUND);
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, -90, 360 * frac, false, p);

            long s = (rem + 999) / 1000;
            String t = s >= 3600
                    ? s / 3600 + ":" + two(s / 60 % 60) + ":" + two(s % 60)
                    : s / 60 + ":" + two(s % 60);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTextSize((s >= 3600 ? 13 : 15) * d);
            c.drawText(t, h + 4 * d, cy - (p.descent() + p.ascent()) / 2, p);

            if (rem > 0) postInvalidateDelayed(rem < 10000 ? 50 : 250);
        }

        private static String two(long v) {
            return v < 10 ? "0" + v : String.valueOf(v);
        }
    }
}
