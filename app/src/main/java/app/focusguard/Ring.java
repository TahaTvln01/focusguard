package app.focusguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Circular progress (today's screen time against the goal) with a short label inside. */
final class Ring extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private float shown, target = -1;
    private int color = Ui.GREEN;
    private String label = "";
    private ValueAnimator anim;

    Ring(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Animates to the new fraction (0..1). */
    void set(float fraction, int color, String label) {
        this.color = color;
        this.label = label;
        if (fraction == target) { invalidate(); return; }
        target = fraction;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, fraction).setDuration(600);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, w = 7 * d, rad = Math.min(cx, cy) - w / 2;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(Ui.LINE);
        c.drawCircle(cx, cy, rad, p);
        if (shown > 0) {
            p.setColor(color);
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, -90, 360 * Math.min(1f, shown), false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.WHITE);
        p.setTextSize(15 * d);
        c.drawText(label, cx, cy - (p.descent() + p.ascent()) / 2, p);
    }
}
