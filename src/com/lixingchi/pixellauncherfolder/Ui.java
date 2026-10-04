package com.lixingchi.pixellauncherfolder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.WindowInsetsController;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small Material You-style primitives without a runtime library dependency. */
final class Ui {
    private static int systemColor(Context c, String name, int fallback) {
        int id = c.getResources().getIdentifier(name, "color", "android");
        if (id == 0) return fallback;
        try { return c.getResources().getColor(id, c.getTheme()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    static int dp(Context c, float v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }
    static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    static int background(Context c) {
        return dark(c) ? systemColor(c, "system_neutral1_900", Color.rgb(20, 20, 24))
                : systemColor(c, "system_neutral1_10", Color.rgb(250, 248, 252));
    }
    static int cardColor(Context c) {
        return dark(c) ? systemColor(c, "system_neutral1_800", Color.rgb(31, 31, 36))
                : systemColor(c, "system_neutral1_50", Color.WHITE);
    }
    static int text(Context c) {
        return dark(c) ? systemColor(c, "system_neutral1_100", Color.rgb(232, 225, 229))
                : systemColor(c, "system_neutral1_900", Color.rgb(29, 27, 32));
    }
    static int secondaryText(Context c) {
        return dark(c) ? systemColor(c, "system_neutral2_200", Color.rgb(202, 196, 208))
                : systemColor(c, "system_neutral2_700", Color.rgb(73, 69, 79));
    }
    static int outline(Context c) {
        return dark(c) ? systemColor(c, "system_neutral2_500", Color.rgb(147, 143, 153))
                : systemColor(c, "system_neutral2_500", Color.rgb(121, 116, 126));
    }
    static int primary(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_200", Color.rgb(207, 188, 255))
                : systemColor(c, "system_accent1_600", Color.rgb(103, 80, 164));
    }
    static int onPrimary(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_900", Color.rgb(54, 30, 94))
                : systemColor(c, "system_accent1_0", Color.WHITE);
    }
    static int surface(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_800", Color.rgb(65, 47, 95))
                : systemColor(c, "system_accent1_100", Color.rgb(235, 222, 255));
    }
    // Match Pixel's dynamic blue selection family rather than the legacy
    // black accent supplied by Theme.Material.NoActionBar.
    static int pickerActive(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_200", Color.rgb(190, 198, 255))
                : systemColor(c, "system_accent1_600", Color.rgb(70, 88, 150));
    }
    static ColorStateList enabledColor(Context c, int color) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{secondaryText(c), color});
    }
    static int pickerElsewhere(Context c) {
        return dark(c) ? Color.rgb(145, 149, 160) : Color.rgb(125, 128, 138);
    }
    static GradientDrawable rounded(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(surface(c)); d.setCornerRadius(dp(c, 18)); return d;
    }
    static GradientDrawable cardBackground(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(cardColor(c)); d.setCornerRadius(dp(c, 24));
        d.setStroke(dp(c, 1), outline(c)); return d;
    }
    static TextView text(Context c, String value, int sp) {
        TextView view = new TextView(c); view.setText(value); view.setTextSize(sp); view.setTextColor(text(c));
        view.setFontFeatureSettings("kern"); return view;
    }
    static TextView headline(Context c, String value) {
        TextView view = text(c, value, 30); view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-0.015f); return view;
    }
    static TextView secondary(Context c, String value, int sp) {
        TextView view = text(c, value, sp); view.setTextColor(secondaryText(c)); return view;
    }
    static LinearLayout column(Context c, int padding) {
        LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padding); view.setPadding(p, p, p, p); return view;
    }
    static LinearLayout card(Context c) {
        LinearLayout view = column(c, 16); view.setBackground(cardBackground(c)); view.setElevation(dp(c, 1)); return view;
    }
    static View spacer(Context c, int height) {
        View view = new View(c); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height))); return view;
    }
    static Button button(Context c, String title, Runnable action) {
        Button b = new Button(c); b.setText(title); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(enabledColor(c, primary(c)));
        b.setMinHeight(dp(c, 48)); b.setMinWidth(dp(c, 48)); b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(dp(c, 24)); background.setStroke(dp(c, 1), outline(c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((primary(c) & 0x00ffffff) | 0x20000000), background, null));
        b.setStateListAnimator(null); b.setOnClickListener(v -> action.run()); return b;
    }
    static Button primaryButton(Context c, String title, Runnable action) {
        Button b = button(c, title, action); b.setTextColor(enabledColor(c, onPrimary(c)));
        GradientDrawable background = new GradientDrawable();
        background.setColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{surface(c), primary(c)}));
        background.setCornerRadius(dp(c, 24));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((onPrimary(c) & 0x00ffffff) | 0x20000000), background, null));
        return b;
    }
    static void applySystemBars(Window window, Context context) {
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background(context)));
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark(context) ? 0 : mask, mask);
        }
    }
}
