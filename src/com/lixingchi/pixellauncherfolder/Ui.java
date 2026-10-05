package com.lixingchi.pixellauncherfolder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native design system for the settings surface and launcher popup. */
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
        return dark(c) ? systemColor(c, "system_neutral1_900", Color.rgb(18, 19, 23))
                : systemColor(c, "system_neutral1_10", Color.rgb(248, 249, 252));
    }
    static int cardColor(Context c) {
        return dark(c) ? systemColor(c, "system_neutral1_800", Color.rgb(29, 31, 36))
                : systemColor(c, "system_neutral1_50", Color.WHITE);
    }
    static int text(Context c) {
        return dark(c) ? systemColor(c, "system_neutral1_100", Color.rgb(238, 238, 246))
                : systemColor(c, "system_neutral1_900", Color.rgb(27, 28, 32));
    }
    static int secondaryText(Context c) {
        return dark(c) ? systemColor(c, "system_neutral2_200", Color.rgb(198, 199, 209))
                : systemColor(c, "system_neutral2_700", Color.rgb(70, 72, 80));
    }
    static int outline(Context c) {
        return dark(c) ? systemColor(c, "system_neutral2_500", Color.rgb(127, 129, 140))
                : systemColor(c, "system_neutral2_500", Color.rgb(116, 118, 128));
    }
    static int dividerColor(Context c) {
        return dark(c) ? Color.argb(42, 220, 220, 230) : Color.argb(34, 40, 42, 48);
    }
    static int primary(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_200", Color.rgb(190, 198, 255))
                : systemColor(c, "system_accent1_600", Color.rgb(67, 82, 160));
    }
    static int onPrimary(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_900", Color.rgb(25, 36, 82))
                : systemColor(c, "system_accent1_0", Color.WHITE);
    }
    static int surface(Context c) {
        return dark(c) ? systemColor(c, "system_accent1_800", Color.rgb(44, 53, 98))
                : systemColor(c, "system_accent1_100", Color.rgb(224, 229, 255));
    }
    static int surfaceMuted(Context c) {
        return dark(c) ? Color.rgb(36, 38, 45) : Color.rgb(239, 241, 247);
    }
    static int pickerActive(Context c) { return primary(c); }
    static int pickerElsewhere(Context c) { return dark(c) ? Color.rgb(145, 148, 160) : Color.rgb(124, 127, 138); }

    static ColorStateList enabledColor(Context c, int color) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{secondaryText(c), color});
    }
    static GradientDrawable rounded(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(surface(c)); d.setCornerRadius(dp(c, 18)); return d;
    }
    static GradientDrawable cardBackground(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(cardColor(c)); d.setCornerRadius(dp(c, 26)); return d;
    }
    static GradientDrawable rowBackground(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(surfaceMuted(c)); d.setCornerRadius(dp(c, 20)); return d;
    }
    static TextView text(Context c, String value, int sp) {
        TextView view = new TextView(c); view.setText(value); view.setTextSize(sp); view.setTextColor(text(c));
        view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false); return view;
    }
    static TextView eyebrow(Context c, String value) {
        TextView view = text(c, value.toUpperCase(java.util.Locale.ROOT), 12);
        view.setTextColor(primary(c)); view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); view.setLetterSpacing(.12f); return view;
    }
    static TextView headline(Context c, String value) {
        TextView view = text(c, value, 32); view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-.025f); return view;
    }
    static TextView sectionTitle(Context c, String value) {
        TextView view = text(c, value.toUpperCase(java.util.Locale.ROOT), 12);
        view.setTextColor(secondaryText(c)); view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); view.setLetterSpacing(.1f); return view;
    }
    static TextView secondary(Context c, String value, int sp) {
        TextView view = text(c, value, sp); view.setTextColor(secondaryText(c)); view.setLineSpacing(0, 1.12f); return view;
    }
    static LinearLayout column(Context c, int padding) {
        LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padding); view.setPadding(p, p, p, p); return view;
    }
    static LinearLayout card(Context c) {
        LinearLayout view = column(c, 18); view.setBackground(cardBackground(c)); return view;
    }
    static LinearLayout row(Context c) {
        LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14)); view.setBackground(rowBackground(c)); return view;
    }
    static View divider(Context c) {
        View view = new View(c); view.setBackgroundColor(dividerColor(c)); return view;
    }
    static View spacer(Context c, int height) {
        View view = new View(c); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height))); return view;
    }
    static ImageView mark(Context c) {
        ImageView mark = new ImageView(c);
        mark.setImageDrawable(new FolderGlyph(primary(c))); mark.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12)); mark.setBackground(rounded(c));
        return mark;
    }
    private static final class FolderGlyph extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final int color;
        FolderGlyph(int color) { this.color = color; paint.setColor(color); }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); float w = b.width(), h = b.height();
            canvas.drawRoundRect(3, h * .27f, w - 3, h - 3, h * .12f, h * .12f, paint);
            canvas.drawRoundRect(4, h * .12f, w * .48f, h * .38f, h * .1f, h * .1f, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    static TextView badge(Context c, String value) {
        TextView badge = text(c, value, 12); badge.setTextColor(primary(c)); badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6)); badge.setBackground(rounded(c)); return badge;
    }
    static android.graphics.drawable.Drawable ripple(Context c, int fill, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(c, radius));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28, Color.red(primary(c)), Color.green(primary(c)), Color.blue(primary(c)))), shape, null);
    }
    static android.widget.EditText input(Context c, String hint) {
        android.widget.EditText input = new android.widget.EditText(c); input.setSingleLine(true); input.setHint(hint); input.setTextSize(16);
        input.setTextColor(text(c)); input.setHintTextColor(secondaryText(c)); input.setPadding(dp(c, 16), 0, dp(c, 16), 0); input.setMinHeight(dp(c, 52));
        GradientDrawable background = new GradientDrawable(); background.setColor(surfaceMuted(c)); background.setCornerRadius(dp(c, 16));
        background.setStroke(dp(c, 1), outline(c)); input.setBackground(background); return input;
    }
    static Button button(Context c, String title, Runnable action) {
        Button b = new Button(c); b.setText(title); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setTextColor(enabledColor(c, primary(c))); b.setMinHeight(dp(c, 48)); b.setMinWidth(dp(c, 48));
        b.setPadding(dp(c, 15), 0, dp(c, 15), 0); b.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.TRANSPARENT); background.setCornerRadius(dp(c, 18));
        background.setStroke(dp(c, 1), outline(c));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(30, Color.red(primary(c)), Color.green(primary(c)), Color.blue(primary(c)))), background, null));
        b.setOnClickListener(v -> action.run()); return b;
    }
    static Button primaryButton(Context c, String title, Runnable action) {
        Button b = button(c, title, action); b.setTextColor(enabledColor(c, onPrimary(c)));
        GradientDrawable background = new GradientDrawable(); background.setColor(primary(c)); background.setCornerRadius(dp(c, 18));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(38, 255, 255, 255)), background, null)); return b;
    }
    static Button textButton(Context c, String title, Runnable action) {
        Button b = button(c, title, action); b.setBackground(ripple(c, Color.TRANSPARENT, 18)); b.setTextColor(enabledColor(c, primary(c))); return b;
    }
    static void applySystemBars(Window window, Context context) {
        window.setBackgroundDrawable(new ColorDrawable(background(context)));
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark(context) ? 0 : mask, mask);
        }
    }
}
