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
    /** Instagram logo at top-left of the home feed (opens Following/Favorites menu). */
    static final String[] IG_LOGO_ID = {"title_logo", "action_bar_title_logo", "title_logo_chevron"};
    /** "Following" entry of that menu. */
    static final String[] IG_FOLLOWING_LABEL = {"Following", "Takip Ettiklerin", "Takip Edilenler", "Takip"};

    // ---- YouTube ----
    static final String[] YT_SHORTS_TAB_LABEL = {"Shorts"};
    static final String[] YT_HOME_TAB_LABEL = {"Home", "Ana Sayfa"};
    /** Vertical pager of the Shorts player. */
    static final String[] YT_SHORTS_PAGER_ID = {"reel_recycler"};

    /** Bottom-bar labels are only accepted below this fraction of the window height. */
    static final float BOTTOM_BAR_FROM = 0.80f;

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

    /** First visible node whose text/description equals one of the labels (optionally bottom bar only). */
    static AccessibilityNodeInfo byLabel(AccessibilityNodeInfo root, String[] labels, boolean bottomOnly) {
        Rect rb = new Rect(), nb = new Rect();
        root.getBoundsInScreen(rb);
        int minTop = rb.top + (int) (rb.height() * BOTTOM_BAR_FROM);
        for (String label : labels) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(label);
            if (l == null) continue;
            for (AccessibilityNodeInfo n : l) {
                if (n == null || !n.isVisibleToUser()) continue;
                if (!eq(n.getText(), label) && !eq(n.getContentDescription(), label)) continue;
                if (bottomOnly) {
                    n.getBoundsInScreen(nb);
                    if (nb.top < minTop) continue;
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
        if (n == null && labels != null) n = byLabel(root, labels, true);
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
}
