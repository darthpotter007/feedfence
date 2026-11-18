package app.feedfence;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The whole blocker. Watches Instagram's screen structure (view IDs, not pixels) and:
 *  1. Sends you back to Home whenever the Reels tab is opened, and covers the Reels tab button.
 *  2. Lets a reel you deliberately opened (from a DM, a post, a profile) play, but takes you
 *     back as soon as you swipe on to the next reel.
 *  3. Covers suggested posts, suggestion carousels and ads in the Home feed, and puts up a
 *     wall after "You're all caught up".
 *  4. Covers the Explore grid while leaving the search bar usable.
 */
public class FenceService extends AccessibilityService {
    static final String IG = "com.instagram.android";
    private static final String P = IG + ":id/";

    // Full-screen reel player containers (seen from the Reels tab, DMs, profiles and posts).
    private static final String[] VIEWER_IDS = {
            "clips_viewer_view_pager", "clips_view_pager", "root_clips_layout", "clips_viewer",
            "clips_video_container", "clips_media_component"
    };
    private static final String[] PAGER_ID_HINTS = {"clips_viewer_view_pager", "clips_view_pager", "clips_viewer", "root_clips_layout"};
    // Self-contained suggestion units in the Home feed (suggested accounts, suggested reels, upsells).
    private static final String[] SUGGESTION_UNIT_IDS = {
            "netego_carousel_title", "netego_bloks_view", "clips_header_title", "netego_carousel_header",
            "suggested_users_carousel", "netego_title"
    };
    // A Follow button inside a post header means the account is one you don't follow.
    private static final String[] FOLLOW_BUTTON_IDS = {
            "row_right_aligned_follow_button_stub", "inline_follow_button", "row_feed_follow_button"
    };
    private static final String[] WALL_IDS = {"end_of_feed_demarcator_container"};
    private static final String[] SEARCH_EDITOR_IDS = {"action_bar_search_edit_text", "search_edit_text", "row_search_edit_text"};
    private static final String[] BACK_IDS = {"action_bar_button_back", "action_bar_back_button", "back_button"};

    private static final long MIN_EVAL_GAP_MS = 80;
    private static final long VIEWER_GRACE_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;

    private Cover tabCover, wallCover, exploreCover, toastView, debugButton;
    private final Cover[] postCovers = new Cover[5];

    private boolean evalPending;
    private long lastEval, lastRedirect, lastSwipeExit;

    private boolean viewerActive;
    private long viewerSince;
    private int viewerWindowId = -1;
    private int viewerIndex = -1;

    // Home feed memory: headers seen on the previous check, so a suggested post stays covered
    // after its header has scrolled off the top of the screen.
    private List<Header> prevHeaders = new ArrayList<>();
    private boolean aboveIsSuggested;

    private String state = "starting";

    private final Runnable evalRunnable = new Runnable() {
        @Override public void run() { evaluate(); }
    };
    private final Runnable hideToast = new Runnable() {
        @Override public void run() { if (toastView != null) toastView.hide(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        tabCover = new Cover(makeLabel("⊘", 0xFF202020, 18), true);
        tabCover.view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toast("Reels are fenced off"); }
        });
        wallCover = new Cover(makeLabel("You're caught up on people you follow.\n\nEverything below is suggested, so it's fenced off.", 0xFF141414, 16), true);
        exploreCover = new Cover(makeLabel("Explore is fenced off.\n\nTap the search bar above to find people.", 0xFF141414, 16), true);
        // Suggested-post covers are fully opaque and touchable (Android caps the opacity of
        // see-through-to-touch overlays at 80%). Swiping on one scrolls the feed past it.
        View.OnTouchListener skipper = new View.OnTouchListener() {
            private float downY;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downY = ev.getRawY();
                } else if (a == MotionEvent.ACTION_UP) {
                    float dy = ev.getRawY() - downY;
                    if (dy < -dp(24)) scrollFeed(true);
                    else if (dy > dp(24)) scrollFeed(false);
                }
                return true;
            }
        };
        for (int i = 0; i < postCovers.length; i++) {
            postCovers[i] = new Cover(makeLabel("Suggested content hidden\nSwipe here to skip past it", 0xFF141414, 14), true);
            postCovers[i].view.setOnTouchListener(skipper);
        }
        TextView t = makeLabel("", 0xEE3B2A5C, 14);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xEE3B2A5C);
        pill.setCornerRadius(dp(24));
        t.setBackground(pill);
        toastView = new Cover(t, false);

        TextView d = makeLabel("dump", 0xDDE1306C, 12);
        GradientDrawable round = new GradientDrawable();
        round.setColor(0xDDE1306C);
        round.setCornerRadius(dp(20));
        d.setBackground(round);
        d.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doDump(); }
        });
        debugButton = new Cover(d, true);
        state = "connected";
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        hideAll();
        if (toastView != null) toastView.hide();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || wm == null) return;
        CharSequence pk = e.getPackageName();
        String pkg = pk == null ? "" : pk.toString();
        if (pkg.equals(getPackageName())) return;
        if (IG.equals(pkg) && e.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScroll(e);
        }
        schedule(e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? 0 : 50);
    }

    private void schedule(long delay) {
        long now = SystemClock.uptimeMillis();
        long earliest = Math.max(delay, MIN_EVAL_GAP_MS - (now - lastEval));
        if (evalPending) {
            if (delay == 0) { handler.removeCallbacks(evalRunnable); handler.postDelayed(evalRunnable, Math.max(0, earliest)); }
            return;
        }
        evalPending = true;
        handler.postDelayed(evalRunnable, Math.max(0, earliest));
    }

    // ------------------------------------------------------------------ main check

    private void evaluate() {
        evalPending = false;
        lastEval = SystemClock.uptimeMillis();
        try {
            evaluateInner();
        } catch (Exception ex) {
            // Instagram's tree can change underneath us mid-read; just try again on the next event.
            state = "error: " + ex;
        }
    }

    private void evaluateInner() {
        if (Prefs.isPaused(this)) { hideAll(); viewerActive = false; state = "paused"; return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !IG.equals(root.getPackageName().toString())) {
            hideAll();
            viewerActive = false;
            state = "not in Instagram";
            return;
        }
        Rect rootR = new Rect();
        root.getBoundsInScreen(rootR);
        if (rootR.height() <= 0) return;

        Tab feed = tab(root, "feed_tab");
        Tab clips = tab(root, "clips_tab");
        Tab search = tab(root, "search_tab");
        int tabTop = rootR.bottom;
        for (Tab t : new Tab[]{feed, clips, search, tab(root, "profile_tab"), tab(root, "direct_tab")}) {
            if (t != null && t.bounds.top > rootR.top + rootR.height() / 2) tabTop = Math.min(tabTop, t.bounds.top);
        }
        boolean backVisible = firstVisible(root, BACK_IDS) != null;
        Rect homeHeader = firstVisible(root, new String[]{"main_feed_action_bar"});
        boolean onReelsTab = clips != null && clips.selected && (feed == null || !feed.selected) && !backVisible;
        boolean onHome = feed != null && feed.selected && !onReelsTab;

        // 1. Reels tab -> bounce to Home.
        if (onReelsTab && Prefs.on(this, Prefs.BLOCK_REELS_TAB)) {
            redirectFromReels(feed);
        }

        // 2. A reel opened from somewhere else (DM, post, profile).
        // On Home with no back arrow we are in the feed itself (its top bar collapses while you
        // scroll, and tall inline videos look like a player), so that is never the reel viewer.
        boolean viewer = !onReelsTab && !(onHome && !backVisible) && hasFullscreenViewer(root, rootR);
        if (viewer) {
            if (!viewerActive || root.getWindowId() != viewerWindowId) {
                viewerActive = true;
                viewerSince = SystemClock.uptimeMillis();
                viewerWindowId = root.getWindowId();
                viewerIndex = -1;
            }
        } else {
            viewerActive = false;
        }

        // 3. Cover the Reels tab button so it can't be tapped.
        if (clips != null && !clips.selected && !viewer && Prefs.on(this, Prefs.COVER_REELS_BUTTON)) {
            tabCover.show(clips.bounds);
        } else {
            tabCover.hide();
        }

        // 4. Home feed: suggested posts, carousels, ads, and the "caught up" wall.
        if (onHome && !viewer && !backVisible && Prefs.on(this, Prefs.HIDE_SUGGESTED)) {
            int contentTop = homeHeader != null ? homeHeader.bottom : rootR.top;
            coverHomeFeed(root, rootR, contentTop, tabTop);
        } else {
            for (Cover c : postCovers) c.hide();
            wallCover.hide();
            if (!onHome) { prevHeaders = new ArrayList<>(); aboveIsSuggested = false; }
        }

        // 5. Explore grid (but never the search bar or search results).
        boolean exploreCovered = false;
        if (search != null && search.selected && !viewer && !backVisible && Prefs.on(this, Prefs.HIDE_EXPLORE)) {
            exploreCovered = coverExplore(root, rootR, tabTop);
        }
        if (!exploreCovered) exploreCover.hide();

        // 6. Debug dump button.
        if (Prefs.on(this, Prefs.DEBUG_BUTTON)) {
            int size = dp(48);
            int y = rootR.top + rootR.height() * 2 / 5;
            debugButton.show(new Rect(rootR.right - size - dp(4), y, rootR.right - dp(4), y + size));
        } else {
            debugButton.hide();
        }

        state = "home=" + onHome + " reelsTab=" + onReelsTab + " viewer=" + viewer
                + " exploreSel=" + (search != null && search.selected) + " back=" + backVisible
                + " tabTop=" + tabTop + " exploreCovered=" + exploreCovered;
    }

    // ------------------------------------------------------------------ reels

    private void redirectFromReels(Tab feed) {
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 900) return;
        lastRedirect = now;
        boolean clicked = feed != null && clickNodeOrAncestor(feed.node);
        if (!clicked) performGlobalAction(GLOBAL_ACTION_BACK);
        Prefs.bump(this, Prefs.STAT_REELS_BLOCKED);
        toast("Reels are fenced off");
    }

    private boolean hasFullscreenViewer(AccessibilityNodeInfo root, Rect rootR) {
        for (String id : VIEWER_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(P + id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.height() >= rootR.height() * 0.6 && b.width() >= rootR.width() * 0.8) return true;
            }
        }
        return false;
    }

    /** Detects swiping from the reel you opened onto the next one, and backs out. */
    private void handleScroll(AccessibilityEvent e) {
        if (!viewerActive || Prefs.isPaused(this) || !Prefs.on(this, Prefs.SINGLE_REEL_ONLY)) return;
        AccessibilityNodeInfo src = e.getSource();
        if (src == null || !isReelPager(src)) return;
        long now = SystemClock.uptimeMillis();
        int idx = e.getFromIndex();
        int dy = Build.VERSION.SDK_INT >= 28 ? e.getScrollDeltaY() : 0;
        if (now - viewerSince < VIEWER_GRACE_MS) {
            // The player settles into place when it opens; that isn't you swiping.
            if (idx >= 0) viewerIndex = idx;
            return;
        }
        boolean moved = Math.abs(dy) > dp(16) || (viewerIndex >= 0 && idx >= 0 && idx != viewerIndex);
        if (viewerIndex < 0 && idx >= 0) viewerIndex = idx;
        if (!moved || now - lastSwipeExit < 1500) return;
        lastSwipeExit = now;
        viewerActive = false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        Prefs.bump(this, Prefs.STAT_SWIPES_BLOCKED);
        toast("Just the reel you opened — taking you back");
    }

    private boolean isReelPager(AccessibilityNodeInfo src) {
        AccessibilityNodeInfo n = src;
        for (int depth = 0; depth < 3 && n != null; depth++) {
            String id = n.getViewIdResourceName();
            if (id != null) for (String hint : PAGER_ID_HINTS) if (id.contains(hint)) return true;
            n = n.getParent();
        }
        // Id-less pager: a vertical scroller that fills the screen inside the reel player.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        Rect rr = new Rect(), sb = new Rect();
        root.getBoundsInScreen(rr);
        src.getBoundsInScreen(sb);
        return sb.height() >= rr.height() * 0.6 && sb.width() >= rr.width() * 0.8;
    }

    // ------------------------------------------------------------------ home feed

    private static final class Header {
        final String name; final int top; final boolean suggested;
        Header(String name, int top, boolean suggested) { this.name = name; this.top = top; this.suggested = suggested; }
    }

    private void coverHomeFeed(AccessibilityNodeInfo root, Rect rootR, int contentTop, int contentBottom) {
        boolean hideAds = Prefs.on(this, Prefs.HIDE_ADS);
        List<Header> headers = new ArrayList<>();
        List<AccessibilityNodeInfo> hs = root.findAccessibilityNodeInfosByViewId(P + "row_feed_profile_header");
        if (hs != null) {
            for (AccessibilityNodeInfo h : hs) {
                if (!h.isVisibleToUser()) continue;
                Rect b = new Rect();
                h.getBoundsInScreen(b);
                if (b.bottom <= contentTop || b.top >= contentBottom) continue;
                headers.add(new Header(headerName(h), b.top, isSuggestedHeader(h, hideAds)));
            }
        }
        Collections.sort(headers, new Comparator<Header>() {
            @Override public int compare(Header a, Header b) { return a.top - b.top; }
        });

        // Which post owns the strip above the first visible header? If a header that was above the
        // first one has just scrolled off the top, that post's status carries over.
        int firstTop = headers.isEmpty() ? contentBottom : headers.get(0).top;
        Header lowestGone = null;
        for (Header old : prevHeaders) {
            boolean stillHere = false;
            for (Header h : headers) if (h.name.equals(old.name) && Math.abs(h.top - old.top) < rootR.height() / 2) { stillHere = true; break; }
            if (!stillHere && old.top < firstTop && (lowestGone == null || old.top > lowestGone.top)) lowestGone = old;
        }
        if (lowestGone != null) aboveIsSuggested = lowestGone.suggested;
        prevHeaders = headers;

        // Collect intervals [start, end) to cover.
        List<int[]> spans = new ArrayList<>();
        if (aboveIsSuggested && firstTop > contentTop) spans.add(new int[]{contentTop, firstTop});
        for (int i = 0; i < headers.size(); i++) {
            Header h = headers.get(i);
            if (!h.suggested) continue;
            spans.add(new int[]{h.top, nextOkHeaderTop(headers, h.top, contentBottom)});
        }
        for (Rect u : visibleRects(root, SUGGESTION_UNIT_IDS)) {
            int start = u.top - dp(12);
            spans.add(new int[]{start, Math.max(u.bottom, nextOkHeaderTop(headers, u.top, contentBottom))});
        }
        for (AccessibilityNodeInfo n : textNodes(root, "Suggested")) {
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t == null || t.length() > 40 || !t.toString().trim().toLowerCase().startsWith("suggested")) continue;
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            spans.add(new int[]{b.top - dp(12), nextOkHeaderTop(headers, b.top, contentBottom)});
        }

        // The "caught up" wall: everything below is suggested. Never let it start so high that
        // you can't scroll back up.
        int wallTop = Integer.MAX_VALUE;
        if (Prefs.on(this, Prefs.WALL_AT_CAUGHT_UP)) {
            for (Rect r : visibleRects(root, WALL_IDS)) wallTop = Math.min(wallTop, r.top);
            for (AccessibilityNodeInfo n : textNodes(root, "caught up")) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                wallTop = Math.min(wallTop, b.top - dp(24));
            }
        }
        if (wallTop != Integer.MAX_VALUE && wallTop < contentBottom) {
            int minTop = contentTop + (contentBottom - contentTop) / 4;
            int top = Math.max(wallTop, minTop);
            wallCover.show(new Rect(rootR.left, top, rootR.right, contentBottom));
            wallTop = top;
        } else {
            wallCover.hide();
            wallTop = contentBottom;
        }

        // Clamp, merge and draw.
        List<int[]> clamped = new ArrayList<>();
        for (int[] s : spans) {
            int a = Math.max(s[0], contentTop), b = Math.min(s[1], wallTop);
            if (b - a >= dp(24)) clamped.add(new int[]{a, b});
        }
        Collections.sort(clamped, new Comparator<int[]>() {
            @Override public int compare(int[] x, int[] y) { return x[0] - y[0]; }
        });
        List<int[]> merged = new ArrayList<>();
        for (int[] s : clamped) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + dp(4)) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else merged.add(new int[]{s[0], s[1]});
        }
        for (int i = 0; i < postCovers.length; i++) {
            if (i < merged.size()) postCovers[i].show(new Rect(rootR.left, merged.get(i)[0], rootR.right, merged.get(i)[1]));
            else postCovers[i].hide();
        }
    }

    /** Scrolls Instagram's vertical feed list by one page (never the sideways tab pager). */
    private void scrollFeed(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        Rect rr = new Rect();
        root.getBoundsInScreen(rr);
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size() && i < 600; i++) {
            AccessibilityNodeInfo n = queue.get(i);
            if (n == null) continue;
            if (n.isScrollable() && n.isVisibleToUser() && !isHorizontal(n)) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                long area = (long) b.width() * b.height();
                if (b.height() >= rr.height() * 0.4 && b.width() >= rr.width() * 0.8 && area < bestArea) {
                    best = n;
                    bestArea = area;
                }
            }
            for (int c = 0; c < n.getChildCount(); c++) queue.add(n.getChild(c));
        }
        if (best != null) {
            best.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        schedule(120);
    }

    private boolean isHorizontal(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id != null && (id.contains("view_pager") || id.contains("nav_view") || id.contains("tray") || id.contains("carousel"))) return true;
        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        if (ci != null && ci.getRowCount() == 1 && ci.getColumnCount() > 1) return true;
        CharSequence cls = n.getClassName();
        return cls != null && (cls.toString().contains("HorizontalScrollView") || cls.toString().endsWith("ViewPager"));
    }

    private int nextOkHeaderTop(List<Header> headers, int after, int fallback) {
        for (Header h : headers) if (!h.suggested && h.top > after + dp(8)) return h.top;
        return fallback;
    }

    private String headerName(AccessibilityNodeInfo h) {
        List<AccessibilityNodeInfo> n = h.findAccessibilityNodeInfosByViewId(P + "row_feed_photo_profile_name");
        if (n != null) for (AccessibilityNodeInfo x : n) if (x.getText() != null) return x.getText().toString();
        if (h.getContentDescription() != null) return h.getContentDescription().toString();
        return "?";
    }

    private boolean isSuggestedHeader(AccessibilityNodeInfo h, boolean hideAds) {
        for (String id : FOLLOW_BUTTON_IDS) {
            List<AccessibilityNodeInfo> n = h.findAccessibilityNodeInfosByViewId(P + id);
            if (n != null) for (AccessibilityNodeInfo x : n) if (x.isVisibleToUser()) return true;
        }
        for (AccessibilityNodeInfo x : textNodes(h, "Follow")) {
            CharSequence t = x.getText() != null ? x.getText() : x.getContentDescription();
            if (t != null && t.toString().trim().equalsIgnoreCase("Follow")) return true;
        }
        for (AccessibilityNodeInfo x : textNodes(h, "Suggested")) {
            if (x.isVisibleToUser()) return true;
        }
        if (hideAds) {
            for (AccessibilityNodeInfo x : textNodes(h, "Sponsored")) if (x.isVisibleToUser()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ explore

    private boolean coverExplore(AccessibilityNodeInfo root, Rect rootR, int tabTop) {
        // A profile, post or typeahead opened from search is never covered.
        if (firstVisible(root, new String[]{"profile_header_container", "profile_tabs_container"}) != null) return false;
        AccessibilityNodeInfo editor = null;
        for (String id : SEARCH_EDITOR_IDS) {
            List<AccessibilityNodeInfo> n = root.findAccessibilityNodeInfosByViewId(P + id);
            if (n != null) for (AccessibilityNodeInfo x : n) if (x.isVisibleToUser()) { editor = x; break; }
            if (editor != null) break;
        }
        int top;
        if (editor != null) {
            if (editor.isFocused()) return false;
            CharSequence t = editor.getText();
            boolean showingHint = Build.VERSION.SDK_INT >= 26 && editor.isShowingHintText();
            if (t != null && t.length() > 0 && !showingHint) return false;
            Rect b = new Rect();
            editor.getBoundsInScreen(b);
            top = b.bottom + dp(6);
        } else {
            Rect bar = firstVisible(root, new String[]{"explore_action_bar", "action_bar_container", "action_bar"});
            top = bar != null ? bar.bottom : rootR.top + rootR.height() / 6;
        }
        if (tabTop - top < dp(80)) return false;
        exploreCover.show(new Rect(rootR.left, top, rootR.right, tabTop));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static final class Tab {
        final AccessibilityNodeInfo node; final Rect bounds; final boolean selected;
        Tab(AccessibilityNodeInfo node, Rect bounds, boolean selected) { this.node = node; this.bounds = bounds; this.selected = selected; }
    }

    private Tab tab(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(P + id);
        if (nodes == null) return null;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.isEmpty()) continue;
            return new Tab(n, b, isSelectedDeep(n, 0));
        }
        return null;
    }

    private boolean isSelectedDeep(AccessibilityNodeInfo n, int depth) {
        if (n == null) return false;
        if (n.isSelected()) return true;
        if (depth >= 2) return false;
        for (int i = 0; i < n.getChildCount(); i++) if (isSelectedDeep(n.getChild(i), depth + 1)) return true;
        return false;
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo n) {
        for (int i = 0; i < 4 && n != null; i++) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            n = n.getParent();
        }
        return false;
    }

    private Rect firstVisible(AccessibilityNodeInfo root, String[] ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(P + id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (!b.isEmpty()) return b;
            }
        }
        return null;
    }

    private List<Rect> visibleRects(AccessibilityNodeInfo root, String[] ids) {
        List<Rect> out = new ArrayList<>();
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(P + id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (!b.isEmpty()) out.add(b);
            }
        }
        return out;
    }

    private List<AccessibilityNodeInfo> textNodes(AccessibilityNodeInfo root, String needle) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(needle);
        if (nodes == null) return out;
        for (AccessibilityNodeInfo n : nodes) if (n.isVisibleToUser()) out.add(n);
        return out;
    }

    private void hideAll() {
        if (tabCover == null) return;
        tabCover.hide();
        wallCover.hide();
        exploreCover.hide();
        debugButton.hide();
        for (Cover c : postCovers) c.hide();
    }

    private void toast(String msg) {
        if (toastView == null) return;
        ((TextView) toastView.view).setText(msg);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Rect r = new Rect();
        if (root != null) root.getBoundsInScreen(r);
        if (r.isEmpty()) r.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        int w = Math.min(r.width() - dp(48), dp(320));
        int top = r.top + r.height() / 8;
        toastView.showWrap(r.centerX() - w / 2, top, w);
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 1800);
    }

    private void doDump() {
        String text = Dumper.dump(getWindows(), getRootInActiveWindow(), state);
        String where = Dumper.save(this, text);
        toast(where != null ? "Saved " + where : "Couldn't save the dump");
    }

    private TextView makeLabel(String text, int bg, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(bg);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** One overlay window placed in absolute screen coordinates. */
    private final class Cover {
        final View view;
        final boolean touchable;
        private final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        private boolean shown;
        private final Rect current = new Rect();

        Cover(View view, boolean touchable) {
            this.view = view;
            this.touchable = touchable;
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | (touchable ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        void show(Rect r) {
            if (r.width() <= 0 || r.height() <= 0) { hide(); return; }
            if (shown && current.equals(r)) return;
            lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height();
            apply();
            current.set(r);
        }

        void showWrap(int x, int y, int width) {
            lp.x = x; lp.y = y; lp.width = width; lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            current.setEmpty();
            apply();
        }

        private void apply() {
            try {
                if (shown) wm.updateViewLayout(view, lp);
                else { wm.addView(view, lp); shown = true; }
            } catch (Exception ignored) {
                shown = view.getParent() != null;
            }
        }

        void hide() {
            if (!shown) return;
            try { wm.removeView(view); } catch (Exception ignored) {}
            shown = false;
            current.setEmpty();
        }
    }
}
