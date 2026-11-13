package app.focusguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating round countdown + "break time" popup, drawn as accessibility overlays (no extra permission).
 * The counter snaps to the nearest side edge; dragging it to the bottom hides it until the app is opened again.
 */
final class Overlay {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int SIZE_DP = 58, MARGIN_DP = 6, CLOSE_ZONE_DP = 150;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences st;
    private final int size, margin;
    private Dial dial;
    private WindowManager.LayoutParams lp;
    private boolean hiding, dismissed;
    private TextView closer;
    private ValueAnimator snap;
    private View popup;
    /** Called when the counter is tapped (not dragged). */
    Runnable onTap;

    Overlay(Context c, SharedPreferences state) {
        ctx = c;
        st = state;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        size = Ui.dp(c, SIZE_DP);
        margin = Ui.dp(c, MARGIN_DP);
    }

    /** Shows/updates the counter running down to endAt (System.currentTimeMillis). */
    void show(int color, long endAt, long total) {
        if (dismissed) return;
        if (dial != null && !hiding && dial.target == color && Math.abs(dial.endAt - endAt) < 100) return;
        if (dial == null) {
            dial = new Dial(ctx);
            dial.color = dial.target = color;
            lp = new WindowManager.LayoutParams(WRAP, WRAP,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            DisplayMetrics m = ctx.getResources().getDisplayMetrics();
            lp.x = st.getBoolean("ov_right", true) ? m.widthPixels - size - margin : margin;
            lp.y = Math.min(st.getInt("ov_y", Ui.dp(ctx, 120)), Math.max(margin, m.heightPixels - size - margin));
            dial.setOnTouchListener(new Drag());
            dial.setAlpha(0f);
            wm.addView(dial, lp);
            dial.animate().alpha(1f).setDuration(200);
        } else if (hiding) {
            hiding = false;
            dial.animate().cancel();
            dial.animate().alpha(1f).setDuration(150).withEndAction(null);
        }
        dial.endAt = endAt;
        dial.total = total;
        dial.fadeTo(color);
        dial.invalidate();
    }

    void hide() {
        if (dial == null || hiding) return;
        hiding = true;
        final Dial v = dial;
        v.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (hiding && dial == v) removeDial();
        });
    }

    /** The user hid the counter by dragging it down; call when the app is opened again. */
    void restore() {
        dismissed = false;
    }

    /** Small bump to say "still on a break". */
    void pulse() {
        if (dial == null) return;
        dial.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                .withEndAction(() -> { if (dial != null) dial.animate().scaleX(1f).scaleY(1f).setDuration(160); });
    }

    void popup(String app, long ms) {
        long sec = (ms + 999) / 1000;
        card("Mola vakti", app + " için " + (sec >= 120 ? Store.dur(ms) : sec + " sn")
                + " mola.\nTelefonu kullanmaya devam edebilirsin.", "Tamam", Ui.AMBER, null, false);
    }

    /** Popup with an action button and "Vazgeç". */
    void ask(String title, String message, String action, Runnable onAction) {
        card(title, message, action, Ui.BLUE, onAction, true);
    }

    private void card(String title, String message, String action, int color, final Runnable onAction, boolean cancel) {
        dismissPopup();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(ctx, 28), Ui.dp(ctx, 24), Ui.dp(ctx, 28), Ui.dp(ctx, 20));
        card.setMinimumWidth(Ui.dp(ctx, 280));
        card.setBackground(Ui.round(ctx, Ui.SHEET, 20));
        card.addView(Ui.text(ctx, title, 24, Ui.WHITE, true));

        TextView m = Ui.text(ctx, message, 15, Ui.GREY, false);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 20));
        card.addView(m);

        Button ok = Ui.pill(ctx, action, color);
        ok.setOnClickListener(v -> {
            dismissPopup();
            if (onAction != null) onAction.run();
        });
        card.addView(ok, new LinearLayout.LayoutParams(WRAP, Ui.dp(ctx, 46)));
        if (cancel) {
            Button no = Ui.pill(ctx, "Vazgeç", 0);
            no.setOnClickListener(v -> dismissPopup());
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(WRAP, Ui.dp(ctx, 46));
            nl.topMargin = Ui.dp(ctx, 10);
            card.addView(no, nl);
        }

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
        removeCloser();
        removeDial();
    }

    private void removeDial() {
        if (snap != null) snap.cancel();
        if (dial == null) return;
        View v = dial;
        dial = null;
        hiding = false;
        try { wm.removeViewImmediate(v); } catch (Throwable ignored) {}
    }

    // ---- drag: move, snap to the nearest side, or drop on the bottom ✕ to hide ----

    private void showCloser() {
        if (closer != null) return;
        closer = Ui.text(ctx, "✕", 20, Ui.WHITE, true);
        closer.setGravity(Gravity.CENTER);
        closer.setBackground(Ui.round(ctx, 0xE6282828, 999));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = Ui.dp(ctx, 56);
        wm.addView(closer, p);
    }

    private void removeCloser() {
        if (closer == null) return;
        View v = closer;
        closer = null;
        try { wm.removeViewImmediate(v); } catch (Throwable ignored) {}
    }

    private boolean overCloser() {
        return lp.y + size / 2 > ctx.getResources().getDisplayMetrics().heightPixels - Ui.dp(ctx, CLOSE_ZONE_DP);
    }

    private void snapToEdge() {
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        final boolean right = lp.x + size / 2 >= m.widthPixels / 2;
        int to = right ? m.widthPixels - size - margin : margin;
        lp.y = Store.clamp(lp.y, margin, Math.max(margin, m.heightPixels - size - Ui.dp(ctx, CLOSE_ZONE_DP)));
        st.edit().putBoolean("ov_right", right).putInt("ov_y", lp.y).apply();
        if (snap != null) snap.cancel();
        snap = ValueAnimator.ofInt(lp.x, to).setDuration(180);
        snap.addUpdateListener(a -> {
            try {
                lp.x = (Integer) a.getAnimatedValue();
                if (dial != null) wm.updateViewLayout(dial, lp);
            } catch (Throwable ignored) {}
        });
        snap.start();
    }

    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (snap != null) snap.cancel();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.abs(dx) + Math.abs(dy) < margin * 2) return true;
                        moved = true;
                        showCloser();
                        lp.x = Math.max(0, startX + (int) dx);
                        lp.y = Math.max(0, startY + (int) dy);
                        if (dial != null) wm.updateViewLayout(dial, lp);
                        if (closer != null) closer.setBackground(Ui.round(ctx, overCloser() ? Ui.RED : 0xE6282828, 999));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        removeCloser();
                        if (!moved) {
                            if (e.getActionMasked() == MotionEvent.ACTION_UP && onTap != null) onTap.run();
                            return true;
                        }
                        if (overCloser()) {
                            dismissed = true;
                            removeDial();
                        } else {
                            snapToEdge();
                        }
                        return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /** Round counter: progress ring with the remaining time inside. Redraws once per second on its own. */
    private final class Dial extends View {
        long endAt, total;
        int color, target;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator fade;

        Dial(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
        }

        void fadeTo(int to) {
            if (to == target) return;
            target = to;
            if (fade != null) fade.cancel();
            fade = ValueAnimator.ofArgb(color, to).setDuration(300);
            fade.addUpdateListener(a -> { color = (Integer) a.getAnimatedValue(); invalidate(); });
            fade.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, rad = cx - 4.5f * d;
            long rem = Math.max(0, endAt - System.currentTimeMillis());
            float frac = total > 0 ? Math.min(1f, rem / (float) total) : 0f;

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF0181818);
            c.drawCircle(cx, cy, cx, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, rad, p);
            int col = color;
            if (rem < 10000) { // gentle pulse in the last 10 s
                float k = 0.55f + 0.45f * (float) Math.abs(Math.cos(rem / 1000.0 * Math.PI));
                col = (col & 0x00FFFFFF) | ((int) (255 * k) << 24);
            }
            p.setColor(col);
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, -90, 360 * frac, false, p);

            long s = (rem + 999) / 1000;
            boolean hours = s >= 3600;
            String t = hours ? s / 3600 + ":" + two(s / 60 % 60) : s / 60 + ":" + two(s % 60);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(15 * d);
            float base = cy - (p.descent() + p.ascent()) / 2;
            c.drawText(t, cx, hours ? base - 3 * d : base, p);
            if (hours) {
                p.setTextSize(8 * d);
                p.setColor(0xFFB3B3B3);
                c.drawText("sa:dk", cx, base + 8 * d, p);
            }

            // Next frame: once per second (aligned to the tick), faster only for the final pulse.
            if (rem > 0) postInvalidateDelayed(rem < 10000 ? 66 : rem % 1000 + 5);
        }

        private String two(long v) {
            return v < 10 ? "0" + v : String.valueOf(v);
        }
    }
}
