package app.feedfence;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Settings screen: setup status, toggles, pause, and how-to. Built in code to stay tiny. */
public class MainActivity extends Activity {
    private TextView status;
    private Button enableBtn;
    private Button pauseBtn;
    private TextView stats;
    private LinearLayout setupCard;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(col);

        TextView title = text("Feed Fence", 26, true);
        col.addView(title);
        col.addView(text("Instagram for stories, messages and people you follow. Reels and suggestions stay behind the fence.", 15, false));

        status = text("", 16, true);
        status.setPadding(0, dp(18), 0, dp(6));
        col.addView(status);

        setupCard = new LinearLayout(this);
        setupCard.setOrientation(LinearLayout.VERTICAL);
        setupCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x22E1306C);
        cardBg.setCornerRadius(dp(14));
        setupCard.setBackground(cardBg);
        setupCard.addView(text("One-time setup", 16, true));
        setupCard.addView(text(
                "1. Tap \"Turn on Feed Fence\" and switch Feed Fence on.\n\n" +
                "2. If the switch is greyed out or says \"Restricted setting\": tap \"Open app info\" below, " +
                "tap the ⋮ menu in the top-right corner, choose \"Allow restricted settings\", confirm, then do step 1 again.\n\n" +
                "3. Open Instagram. That's it.", 14, false));
        Button appInfo = button("Open app info");
        appInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
        setupCard.addView(appInfo);
        col.addView(setupCard);

        enableBtn = button("Turn on Feed Fence");
        enableBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        col.addView(enableBtn);

        col.addView(section("Reels"));
        col.addView(toggle(Prefs.BLOCK_REELS_TAB, "Block the Reels tab", "Opening Reels sends you straight back to Home."));
        col.addView(toggle(Prefs.COVER_REELS_BUTTON, "Cover the Reels button", "Puts a block over the Reels tab so you can't tap it by accident."));
        col.addView(toggle(Prefs.SINGLE_REEL_ONLY, "Only the reel you opened", "Reels people send you (and reels on posts from people you follow) still play. Swiping on to the next reel takes you back."));

        col.addView(section("Home feed"));
        col.addView(toggle(Prefs.HIDE_SUGGESTED, "Hide suggested posts", "Covers posts from accounts you don't follow, and suggestion carousels."));
        col.addView(toggle(Prefs.HIDE_ADS, "Hide ads", "Covers sponsored posts too."));
        col.addView(toggle(Prefs.WALL_AT_CAUGHT_UP, "Wall after \"You're all caught up\"", "Blocks the endless suggested feed once you've seen everything from people you follow."));

        col.addView(section("Search"));
        col.addView(toggle(Prefs.HIDE_EXPLORE, "Hide the Explore grid", "The search bar still works for finding people."));

        col.addView(section("Take a break from the fence"));
        pauseBtn = button("Pause for 10 minutes");
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SharedPreferences p = Prefs.get(MainActivity.this);
                if (Prefs.isPaused(MainActivity.this)) p.edit().putLong(Prefs.PAUSED_UNTIL, 0L).apply();
                else p.edit().putLong(Prefs.PAUSED_UNTIL, System.currentTimeMillis() + 10 * 60 * 1000L).apply();
                refresh();
            }
        });
        col.addView(pauseBtn);

        col.addView(section("If something slips through"));
        col.addView(toggle(Prefs.DEBUG_BUTTON, "Show screen-dump button", "Adds a small \"dump\" button in Instagram. Tap it on a screen that isn't being blocked properly; it saves that screen's layout to Downloads/FeedFence so the detection can be fixed. Dumps include any text on screen, so avoid using it on private chats."));

        stats = text("", 13, false);
        stats.setPadding(0, dp(20), 0, 0);
        col.addView(stats);
        TextView privacy = text("Feed Fence has no internet permission. It only looks at Instagram's screen layout, on your phone, and never sends anything anywhere.", 12, false);
        privacy.setAlpha(0.7f);
        privacy.setPadding(0, dp(12), 0, 0);
        col.addView(privacy);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = isServiceEnabled();
        if (Prefs.isPaused(this)) {
            long mins = Math.max(1, (Prefs.get(this).getLong(Prefs.PAUSED_UNTIL, 0) - System.currentTimeMillis()) / 60000 + 1);
            status.setText("⏸  Paused for about " + mins + " more min");
            status.setTextColor(0xFFE0A000);
            pauseBtn.setText("Resume now");
        } else {
            status.setText(on ? "✅  Feed Fence is on" : "⚠️  Feed Fence is off — finish setup below");
            status.setTextColor(on ? 0xFF2E9E5B : 0xFFE1306C);
            pauseBtn.setText("Pause for 10 minutes");
        }
        setupCard.setVisibility(on ? View.GONE : View.VISIBLE);
        enableBtn.setText(on ? "Accessibility settings" : "Turn on Feed Fence");
        SharedPreferences p = Prefs.get(this);
        stats.setText("Reels tab blocked " + p.getInt(Prefs.STAT_REELS_BLOCKED, 0) + " times · reel swipes stopped " + p.getInt(Prefs.STAT_SWIPES_BLOCKED, 0) + " times");
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, FenceService.class);
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(enabled);
        while (s.hasNext()) {
            ComponentName c = ComponentName.unflattenFromString(s.next());
            if (me.equals(c)) return true;
        }
        return false;
    }

    private View toggle(final String key, String label, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(6));
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(Prefs.on(this, key));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).edit().putBoolean(key, checked).apply();
            }
        });
        row.addView(sw);
        TextView d = text(detail, 13, false);
        d.setAlpha(0.7f);
        d.setPadding(0, dp(2), dp(56), 0);
        row.addView(d);
        return row;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 13, true);
        t.setTextColor(0xFFE1306C);
        t.setPadding(0, dp(24), 0, dp(2));
        t.setLetterSpacing(0.08f);
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
