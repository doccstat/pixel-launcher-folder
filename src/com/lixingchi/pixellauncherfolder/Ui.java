package com.lixingchi.pixellauncherfolder;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static int dp(Context c, float v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }
    static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    static int text(Context c) { return dark(c) ? Color.rgb(230, 233, 240) : Color.rgb(27, 29, 35); }
    static int surface(Context c) { return dark(c) ? Color.rgb(43, 48, 59) : Color.rgb(224, 233, 249); }
    static GradientDrawable rounded(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(surface(c)); d.setCornerRadius(dp(c, 18)); return d;
    }
    static TextView text(Context c, String text, int sp) {
        TextView view = new TextView(c); view.setText(text); view.setTextSize(sp); view.setTextColor(text(c)); return view;
    }
    static LinearLayout column(Context c, int padding) {
        LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padding); view.setPadding(p, p, p, p); return view;
    }
    static Button button(Context c, String title, Runnable action) {
        Button b = new Button(c); b.setText(title); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
}
