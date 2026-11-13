package app.focusguard;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * ALL UI-detection rules live here. If Instagram/YouTube change their UI,
 * only the constants below need updating. Unknown ids/labels are simply ignored.
 */
final class Rules {
    private Rules() {}

    static final String IG = "com.instagram.android";
    static final String YT = "com.google.android.youtube";

    // ---- Instagram ----
    static final String[] IG_REELS_TAB_ID = {"clips_tab"};
    static final String[] IG_REELS_TAB_LABEL = {"Reels"};
    static final String[] IG_HOME_TAB_ID = {"feed_tab"};
    static final String[] IG_HOME_TAB_LABEL = {"Home", "Ana Sayfa"};
    /** Vertical pager of the full-screen reel viewer. */
    static final String[] IG_REEL_PAGER_ID = {"clips_viewer_view_pager"};

    // ---- YouTube ----
    static final String[] YT_SHORTS_TAB_LABEL = {"Shorts"};
    static final String[] YT_HOME_TAB_LABEL = {"Home", "Ana Sayfa"};
    /** Vertical pager of the Shorts player. */
    static final String[] YT_SHORTS_PAGER_ID = {"reel_recycler"};

    /** Bottom-bar labels are only accepted below this fraction of the window height. */
    static final float BOTTOM_BAR_FROM = 0.80f;
    /** Top-bar labels are only accepted above this fraction of the window height. */
    static final float TOP_BAR_TO = 0.18f;
    static final int ANY = 0, BOTTOM = 1, TOP = 2;

    // ---- helpers ----

    /** First node matching any of the view ids. */
    static AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String pkg, String[] ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
            if (l == null) continue;
            for (AccessibilityNodeInfo n : l) if (n != null && n.isVisibleToUser()) return n;
        }
        return null;
    }

    /** First visible node whose text/description equals one of the labels (ANY / BOTTOM bar / TOP bar). */
    static AccessibilityNodeInfo byLabel(AccessibilityNodeInfo root, String[] labels, int where) {
        Rect rb = new Rect(), nb = new Rect();
        root.getBoundsInScreen(rb);
        int minTop = rb.top + (int) (rb.height() * BOTTOM_BAR_FROM);
        int maxBottom = rb.top + (int) (rb.height() * TOP_BAR_TO);
        for (String label : labels) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(label);
            if (l == null) continue;
            for (AccessibilityNodeInfo n : l) {
                if (n == null || !n.isVisibleToUser()) continue;
                if (!eq(n.getText(), label) && !eq(n.getContentDescription(), label)) continue;
                if (where != ANY) {
                    n.getBoundsInScreen(nb);
                    if (where == BOTTOM ? nb.top < minTop : nb.bottom > maxBottom) continue;
                }
                return n;
            }
        }
        return null;
    }

    private static boolean eq(CharSequence a, String b) {
        return a != null && a.toString().trim().equalsIgnoreCase(b);
    }

    /** Is the bottom tab (found by id, else by label) currently selected? */
    static boolean tabSelected(AccessibilityNodeInfo root, String pkg, String[] ids, String[] labels) {
        AccessibilityNodeInfo n = ids == null ? null : byId(root, pkg, ids);
        if (n == null && labels != null) n = byLabel(root, labels, BOTTOM);
        if (n == null) return false;
        if (n.isSelected()) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && c.isSelected()) return true;
        }
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; i < 2 && p != null; i++, p = p.getParent()) {
            if (p.isClickable() && p.isSelected()) return true;
        }
        return false;
    }

    /** Clicks the node or its nearest clickable ancestor. */
    static boolean click(AccessibilityNodeInfo n) {
        for (int i = 0; i < 4 && n != null; i++, n = n.getParent()) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    /** Identity of the page currently filling the pager (0 = unknown). Changes when user swipes to another item. */
    static int pageKey(AccessibilityNodeInfo pager) {
        Rect pb = new Rect(), cb = new Rect();
        pager.getBoundsInScreen(pb);
        int best = 0, bestArea = 0;
        for (int i = 0; i < pager.getChildCount(); i++) {
            AccessibilityNodeInfo c = pager.getChild(i);
            if (c == null) continue;
            c.getBoundsInScreen(cb);
            if (!cb.intersect(pb)) continue;
            int area = cb.width() * cb.height();
            if (area > bestArea) {
                bestArea = area;
                best = c.hashCode();
                if (best == 0) best = 1;
            }
        }
        // Ignore until one page clearly dominates (avoids noise mid-animation).
        return bestArea * 10L >= (long) pb.width() * pb.height() * 6 ? best : 0;
    }

    /** Diagnostics: compact dump of the window (class, id, flags, short labels) so rules can be fixed. */
    static String dump(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        sb.append(root.getPackageName()).append('\n');
        int[] count = {0};
        dump(root, 0, sb, count);
        return sb.toString();
    }

    private static void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || depth > 40 || count[0] >= 400) return;
        String id = n.getViewIdResourceName();
        CharSequence t = n.getText(), ds = n.getContentDescription();
        if (id != null || t != null || ds != null || n.isClickable() || n.isSelected() || n.isScrollable()) {
            count[0]++;
            sb.append(depth).append(' ');
            String cls = String.valueOf(n.getClassName());
            sb.append(cls.substring(cls.lastIndexOf('.') + 1));
            if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));
            if (n.isClickable()) sb.append(" C");
            if (n.isSelected()) sb.append(" S");
            if (n.isScrollable()) sb.append(" R");
            if (ds != null) sb.append(" d=").append(cut(ds));
            if (t != null) sb.append(" t=").append(cut(t));
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), depth + 1, sb, count);
    }

    private static String cut(CharSequence c) {
        String s = c.toString().replace('\n', ' ');
        return '"' + (s.length() > 24 ? s.substring(0, 24) + "…" : s) + '"';
    }
}
