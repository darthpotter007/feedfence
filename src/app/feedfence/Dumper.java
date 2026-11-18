package app.feedfence;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug helper: writes the structure of the Instagram screen currently showing to a text
 * file in Downloads/FeedFence. Share that file with whoever maintains the app so the screen
 * detection can be updated when Instagram changes its layout.
 */
final class Dumper {
    private static final int MAX_NODES = 6000;
    private static final String IG_PREFIX = "com.instagram.android:id/";

    private Dumper() {}

    static String dump(List<AccessibilityWindowInfo> windows, AccessibilityNodeInfo fallbackRoot,
                       String stateSummary) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("Feed Fence screen dump\n");
        sb.append("time: ").append(new Date()).append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("detector state: ").append(stateSummary).append("\n\n");
        int[] count = {0};
        boolean any = false;
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !FenceService.IG.equals(pkg.toString())) continue;
                any = true;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                sb.append("=== window id=").append(w.getId()).append(" type=").append(w.getType())
                        .append(" active=").append(w.isActive()).append(" bounds=").append(b.toShortString())
                        .append(" ===\n");
                walk(root, 0, sb, count);
            }
        }
        if (!any && fallbackRoot != null) {
            sb.append("=== active window ===\n");
            walk(fallbackRoot, 0, sb, count);
        }
        sb.append("\nnodes: ").append(count[0]).append('\n');
        return sb.toString();
    }

    private static void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] >= MAX_NODES) return;
        count[0]++;
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        int dot = cls.lastIndexOf('.');
        sb.append(dot >= 0 ? cls.substring(dot + 1) : cls);
        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" #").append(id.startsWith(IG_PREFIX) ? id.substring(IG_PREFIX.length()) : id);
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(" text=\"").append(clip(t)).append('"');
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(" desc=\"").append(clip(d)).append('"');
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(' ').append(r.toShortString());
        if (n.isSelected()) sb.append(" SEL");
        if (n.isClickable()) sb.append(" CLK");
        if (n.isScrollable()) sb.append(" SCR");
        if (n.isFocused()) sb.append(" FOC");
        if (!n.isVisibleToUser()) sb.append(" HIDDEN");
        sb.append('\n');
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) walk(n.getChild(i), depth + 1, sb, count);
    }

    private static String clip(CharSequence s) {
        String x = s.toString().replace('\n', ' ').replace('"', '\'');
        return x.length() > 80 ? x.substring(0, 80) + "…" : x;
    }

    /** Saves the dump and returns a human-readable location, or null on failure. */
    static String save(Context ctx, String content) {
        String name = "feedfence-dump-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FeedFence");
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) return null;
                try { os.write(bytes); } finally { os.close(); }
                return "Downloads/FeedFence/" + name;
            } else {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) return null;
                File f = new File(dir, name);
                FileOutputStream os = new FileOutputStream(f);
                try { os.write(bytes); } finally { os.close(); }
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
