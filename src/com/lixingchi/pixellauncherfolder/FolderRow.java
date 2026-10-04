package com.lixingchi.pixellauncherfolder;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** No service/polling: one shared, idle-expiring worker and an attachment-scoped observer. */
final class FolderRow extends FrameLayout {
    static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> { Thread t = new Thread(task, "pixel-folders-read"); t.setDaemon(true); return t; });
    private final LinearLayout content;
    private final boolean live;
    private final boolean single;
    private final int topInset;
    private final int stockIconSize;
    private final float stockTextSize;
    private final int stockDrawablePadding;
    private final Typeface stockTypeface;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AppEntry> apps = new HashMap<>();
    private boolean attached, registered, loading, reload;
    private String profile;
    private long serial;
    private int generation;
    private int folderIndex = -1;
    private AlertDialog folderDialog;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean self) { refresh(); }
    };

    FolderRow(Context context, String profile, long serial, boolean live) {
        this(context, profile, serial, live, false);
    }

    FolderRow(Context context, String profile, long serial, boolean live, boolean single) {
        this(context, profile, serial, live, single, 0, 0f, 0, null);
    }

    FolderRow(Context context, String profile, long serial, boolean live, boolean single,
            int stockIconSize, float stockTextSize, int stockDrawablePadding) {
        this(context, profile, serial, live, single, stockIconSize, stockTextSize, stockDrawablePadding, null);
    }

    FolderRow(Context context, String profile, long serial, boolean live, boolean single,
            int stockIconSize, float stockTextSize, int stockDrawablePadding, Typeface stockTypeface) {
        super(context); this.live = live; this.single = single; this.profile = profile; this.serial = serial;
        this.stockIconSize = stockIconSize; this.stockTextSize = stockTextSize;
        this.stockDrawablePadding = stockDrawablePadding; this.stockTypeface = stockTypeface;
        // The folder is now measured with the launcher app-cell span. It needs
        // no display-specific vertical compensation and must share the stock
        // icon baseline on both screens.
        topInset = 0;
        setClipChildren(false); setClipToPadding(false);
        setLayoutParams(new LinearLayout.LayoutParams(-1, expectedHeight()));
        content = new LinearLayout(context); content.setGravity(single
                ? (topInset == 0 ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL | Gravity.TOP)
                : Gravity.CENTER_VERTICAL);
        content.setPadding(single ? 0 : Ui.dp(context, 12), topInset == 0 ? Ui.dp(context, 4) : topInset,
                single ? 0 : Ui.dp(context, 12), Ui.dp(context, 4));
        // A direct child is important for grid-cell measurement. The settings
        // preview has a bounded number of folders and does not need scrolling.
        addView(content, new FrameLayout.LayoutParams(single ? -1 : -2, -1));
    }

    // Pixel Launcher uses roughly 52–66 dp app icons on this device. Keep the
    // folder target at the upper end so it does not look smaller than a stock
    // app cell when the user's icon-size setting is large.
    int expectedHeight() {
        return Ui.dp(getContext(), 92 + 20 * Math.max(1, getResources().getConfiguration().fontScale)) + topInset;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Let GridLayoutManager constrain the width to one app cell. Forcing
        // the width to the incoming parent spec makes the first cell overflow
        // and clip at the left edge on the outer display.
        super.onMeasure(widthSpec,
                MeasureSpec.makeMeasureSpec(expectedHeight(), MeasureSpec.EXACTLY));
    }

    void bindFolderIndex(int index) { folderIndex = index; refresh(); }

    void bindProfile(String kind, long profileSerial) {
        if (!profile.equals(kind) || serial != profileSerial) {
            profile = kind; serial = profileSerial; generation++; content.removeAllViews(); apps.clear();
            if (folderDialog != null) { folderDialog.dismiss(); folderDialog = null; }
        }
        refresh();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        if (live) {
            try { getContext().getContentResolver().registerContentObserver(Folders.URI, false, observer); registered = true; }
            catch (RuntimeException ignored) { }
        }
        refresh();
    }
    @Override protected void onDetachedFromWindow() {
        attached = false; generation++;
        if (registered) { getContext().getContentResolver().unregisterContentObserver(observer); registered = false; }
        if (folderDialog != null) { folderDialog.dismiss(); folderDialog = null; }
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && attached) refresh();
    }

    private void refresh() {
        if (!attached) return;
        if (loading) { reload = true; return; }
        loading = true;
        final String readProfile = profile; final long readSerial = serial; final int readGeneration = generation;
        IO.execute(() -> {
            try {
                String raw;
                if (live) {
                    try (Cursor cursor = getContext().getContentResolver().query(Folders.URI, new String[]{"json"}, null, null, null)) {
                        if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("No folder configuration");
                        raw = cursor.getString(0);
                    }
                } else raw = Folders.read(getContext());
                List<Folders.Folder> allFolders = Folders.parse(raw);
                List<Folders.Folder> folders = new java.util.ArrayList<>();
                long profileSerial = readSerial < 0 ? Profiles.personalSerial(getContext()) : readSerial;
                for (Folders.Folder folder : allFolders)
                    if (Folders.matches(folder, readProfile, profileSerial, Profiles.personalSerial(getContext()))) folders.add(folder);
                List<AppEntry> entries = AppEntry.list(getContext(), readProfile, readSerial);
                main.post(() -> {
                    if (attached && readGeneration == generation) {
                        apps.clear(); for (AppEntry e : entries) apps.put(e.key, e);
                        render(folders);
                    }
                    finishLoad();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (attached && readGeneration == generation) {
                        content.removeAllViews();
                        content.addView(Ui.button(getContext(), "Profile unavailable · Edit folders", this::settings));
                    }
                    finishLoad();
                });
            }
        });
    }
    private void finishLoad() { loading = false; if (reload) { reload = false; refresh(); } }
    private void settings() {
        try { getContext().startActivity(new Intent().setClassName(Folders.PACKAGE, Folders.PACKAGE + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException error) { Toast.makeText(getContext(), "Folder settings are unavailable", Toast.LENGTH_SHORT).show(); }
    }
    private AppEntry entry(String key) { AppEntry e = apps.get(key); return e == null ? new AppEntry(key) : e; }

    private void render(List<Folders.Folder> folders) {
        content.removeAllViews();
        if (single) {
            Folders.Folder selected = folderIndex >= 0 && folderIndex < folders.size() ? folders.get(folderIndex) : null;
            folders = selected == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(selected);
        }
        for (Folders.Folder folder : folders) {
            LinearLayout card = Ui.column(getContext(), single ? 0 : 6); card.setGravity(Gravity.CENTER);
            int previewSize = single && stockIconSize > 0 ? stockIconSize : Ui.dp(getContext(), single ? 60 : 64);
            int miniSize = Math.min(Ui.dp(getContext(), single ? 20 : 22),
                    (previewSize - 2 * Ui.dp(getContext(), single ? 7 : 8)) / 2);
            int previewPadding = Ui.dp(getContext(), single ? 7 : 8);
            GridLayout preview = new GridLayout(getContext()); preview.setColumnCount(2); preview.setRowCount(2);
            preview.setPadding(previewPadding, previewPadding, previewPadding, previewPadding);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(Ui.surface(getContext()));
            preview.setBackground(circle); preview.setClipToOutline(true);
            for (int i = 0; i < 4; i++) {
                ImageView icon = new ImageView(getContext());
                if (i < folder.apps.size()) {
                    try { icon.setImageDrawable(entry(folder.apps.get(i)).icon(getContext())); } catch (RuntimeException ignored) { }
                }
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout slot = new FrameLayout(getContext());
                slot.addView(icon, new FrameLayout.LayoutParams(miniSize, miniSize, Gravity.CENTER));
                GridLayout.LayoutParams slotParams = new GridLayout.LayoutParams(
                        GridLayout.spec(i / 2, 1, GridLayout.FILL, 1f),
                        GridLayout.spec(i % 2, 1, GridLayout.FILL, 1f));
                slotParams.width = 0; slotParams.height = 0;
                preview.addView(slot, slotParams);
            }
            card.addView(preview, new LinearLayout.LayoutParams(previewSize, previewSize));
            TextView label = Ui.text(getContext(), folder.name, 14); label.setMaxLines(1);
            if (single && stockTextSize > 0) label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, stockTextSize);
            if (single && stockTypeface != null) label.setTypeface(stockTypeface);
            label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END); label.setGravity(Gravity.CENTER); card.addView(label);
            // BubbleTextView's own drawable-to-label gap preserves the stock
            // baseline without a display-specific magic offset.
            if (single && stockDrawablePadding > 0)
                label.setTranslationY(Math.max(0, stockDrawablePadding - Ui.dp(getContext(), 2)));
            card.setFocusable(true); card.setContentDescription(folder.name + ", " + folder.apps.size() + " apps");
            card.setOnClickListener(v -> open(folder)); card.setOnLongClickListener(v -> { settings(); return true; });
            content.addView(card, new LinearLayout.LayoutParams(single ? -1 : Ui.dp(getContext(), 96), -1));
        }
        if (!single && folders.isEmpty())
            content.addView(Ui.button(getContext(), "+ Create folders", this::settings),
                    new LinearLayout.LayoutParams(-2, Ui.dp(getContext(), 52)));
    }

    private void open(Folders.Folder folder) {
        if (folderDialog != null) folderDialog.dismiss();
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout list = Ui.column(getContext(), 12); scroll.addView(list);
        for (String key : folder.apps) {
            AppEntry e = entry(key);
            android.widget.Button item = Ui.button(getContext(), e.label, () -> {
                try { AppEntry.launch(getContext(), key, folder.profile, folder.serial); if (folderDialog != null) folderDialog.dismiss(); }
                catch (RuntimeException error) { Toast.makeText(getContext(), "This app is unavailable", Toast.LENGTH_SHORT).show(); }
            });
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            try {
                android.graphics.drawable.Drawable icon = e.icon(getContext());
                icon.setBounds(0, 0, Ui.dp(getContext(), 36), Ui.dp(getContext(), 36));
                item.setCompoundDrawablesRelative(icon, null, null, null); item.setCompoundDrawablePadding(Ui.dp(getContext(), 12));
            } catch (RuntimeException ignored) { }
            list.addView(item);
        }
        if (folder.apps.isEmpty()) list.addView(Ui.text(getContext(), "This folder is empty. Add apps in folder settings.", 16));
        folderDialog = new AlertDialog.Builder(getContext()).setTitle(folder.name).setView(scroll)
                .setNeutralButton("Edit folders", (d, w) -> settings()).setPositiveButton("Close", null).create();
        folderDialog.show();
        if (folderDialog.getWindow() != null) folderDialog.getWindow().setLayout(-1,
                Math.min(Ui.dp(getContext(), 520), getResources().getDisplayMetrics().heightPixels * 3 / 4));
    }
}
