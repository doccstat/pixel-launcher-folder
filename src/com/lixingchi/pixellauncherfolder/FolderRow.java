package com.lixingchi.pixellauncherfolder;

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
import android.widget.PopupWindow;
import java.text.Collator;
import java.util.ArrayList;
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
    private PopupWindow folderPopup;
    private LauncherDrag homeDrag;
    private Context drawerContext;
    private float downX, downY;
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

    void bindDrawerContext(Context context) { drawerContext = context; }

    void bindProfile(String kind, long profileSerial) {
        if (!profile.equals(kind) || serial != profileSerial) {
            profile = kind; serial = profileSerial; generation++; content.removeAllViews(); apps.clear();
            dismissFolderPopup();
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
        dismissFolderPopup();
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

    static List<String> sortedApps(Folders.Folder folder, Map<String, AppEntry> apps) {
        List<String> result = new ArrayList<>(folder.apps);
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> {
            AppEntry left = apps.get(a), right = apps.get(b);
            String leftLabel = left == null ? new AppEntry(a).label : left.label;
            String rightLabel = right == null ? new AppEntry(b).label : right.label;
            int c = collator.compare(leftLabel, rightLabel);
            return c == 0 ? a.compareTo(b) : c;
        });
        return result;
    }

    private void render(List<Folders.Folder> folders) {
        content.removeAllViews();
        if (single) {
            Folders.Folder selected = folderIndex >= 0 && folderIndex < folders.size() ? folders.get(folderIndex) : null;
            folders = selected == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(selected);
        }
        for (Folders.Folder folder : folders) {
            LinearLayout card = Ui.column(getContext(), single ? 0 : 6); card.setGravity(Gravity.CENTER);
            // Adaptive icons in BubbleTextView are normalized inside the icon
            // slot. Match their visible size rather than filling the whole
            // measured slot with the folder circle.
            int normalization = single && stockIconSize > 0 ? Math.round(stockIconSize * 0.92f) : 0;
            int previewSize = normalization > 0 ? normalization : Ui.dp(getContext(), single ? 60 : 64);
            int miniSize = Math.min(Ui.dp(getContext(), single ? 20 : 22),
                    (previewSize - 2 * Ui.dp(getContext(), single ? 7 : 8)) / 2);
            int previewPadding = Ui.dp(getContext(), single ? 7 : 8);
            GridLayout preview = new GridLayout(getContext()); preview.setColumnCount(2); preview.setRowCount(2);
            preview.setPadding(previewPadding, previewPadding, previewPadding, previewPadding);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(Ui.surface(getContext()));
            preview.setBackground(circle); preview.setClipToOutline(true);
            if (single && normalization > 0) preview.setTranslationY(-(stockIconSize - previewSize) / 2f);
            List<String> appKeys = sortedApps(folder, apps);
            for (int i = 0; i < 4; i++) {
                ImageView icon = new ImageView(getContext());
                if (i < appKeys.size()) {
                    try { icon.setImageDrawable(entry(appKeys.get(i)).icon(getContext())); } catch (RuntimeException ignored) { }
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
            if (single) {
                float gap = stockDrawablePadding > 0 ? Math.max(0, stockDrawablePadding - Ui.dp(getContext(), 2)) : 0;
                if (normalization > 0) gap += (stockIconSize - previewSize) / 2f;
                label.setTranslationY(gap);
            }
            card.setFocusable(true); card.setContentDescription(folder.name + ", " + folder.apps.size() + " apps");
            card.setOnClickListener(v -> open(folder, v)); card.setOnLongClickListener(v -> { settings(); return true; });
            content.addView(card, new LinearLayout.LayoutParams(single ? -1 : Ui.dp(getContext(), 96), -1));
        }
        if (!single && folders.isEmpty())
            content.addView(Ui.button(getContext(), "+ Create folders", this::settings),
                    new LinearLayout.LayoutParams(-2, Ui.dp(getContext(), 52)));
    }

    private void dismissFolderPopup() {
        LauncherDrag drag = homeDrag; homeDrag = null;
        if (drag != null) drag.cancel();
        PopupWindow popup = folderPopup; folderPopup = null;
        if (popup != null) popup.dismiss();
    }

    private boolean forwardHomeDrag(android.view.MotionEvent event) {
        if (homeDrag == null) return false;
        int action = event.getActionMasked();
        try {
            if (action == android.view.MotionEvent.ACTION_CANCEL) homeDrag.cancel();
            else homeDrag.move(event);
        } catch (RuntimeException error) {
            android.util.Log.e("PixelLauncherFolders", "Home drag gesture failed", error);
            dismissFolderPopup(); return true;
        }
        if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            homeDrag = null; // UP already completed the drop; do not cancel its animation.
            dismissFolderPopup();
        }
        return true;
    }

    private void open(Folders.Folder folder, View anchor) {
        dismissFolderPopup();
        Context context = getContext();
        android.graphics.Rect bounds = new android.graphics.Rect(); anchor.getWindowVisibleDisplayFrame(bounds);
        int availableWidth = bounds.width() > 0 ? bounds.width() : getResources().getDisplayMetrics().widthPixels;
        int availableHeight = bounds.height() > 0 ? bounds.height() : getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Ui.dp(context, 520), availableWidth - Ui.dp(context, 32));
        float scale = Math.max(1f, getResources().getConfiguration().fontScale);
        int columns = Math.max(2, Math.min(4, (width - Ui.dp(context, 32)) / Ui.dp(context, 96 * scale)));
        LinearLayout panel = Ui.column(context, 16); panel.setBackground(Ui.cardBackground(context));
        TextView title = Ui.text(context, folder.name, 24); title.setTypeface(Typeface.DEFAULT_BOLD); title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); panel.addView(title); panel.addView(Ui.spacer(context, 6));
        panel.addView(Ui.secondary(context, (folder.profile.equals("work") ? "Work" : "Personal") + " · " + folder.apps.size()
                + (folder.apps.size() == 1 ? " app" : " apps"), 14)); panel.addView(Ui.spacer(context, 16));
        ScrollView scroll = new ScrollView(context); GridLayout grid = new GridLayout(context); grid.setColumnCount(columns); scroll.addView(grid);
        List<String> keys = sortedApps(folder, apps);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i); AppEntry app = entry(key);
            LinearLayout item = Ui.column(context, 8); item.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            item.setMinimumHeight(Ui.dp(context, 104 * scale)); item.setBackground(Ui.ripple(context, android.graphics.Color.TRANSPARENT, 16));
            ImageView icon = new ImageView(context); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try { icon.setImageDrawable(app.icon(context)); } catch (RuntimeException ignored) { }
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48))); item.addView(Ui.spacer(context, 8));
            TextView label = Ui.text(context, app.info == null ? "Unavailable" : app.label, 13); label.setGravity(Gravity.CENTER); label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END); item.addView(label);
            item.setFocusable(true); item.setContentDescription(app.label + ", " + (folder.profile.equals("work") ? "Work" : "Personal"));
            item.setOnClickListener(v -> {
                try { AppEntry.launch(context, key, folder.profile, folder.serial); dismissFolderPopup(); }
                catch (RuntimeException error) { Toast.makeText(context, "This app is unavailable", Toast.LENGTH_SHORT).show(); }
            });
            item.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY();
                }
                return false;
            });
            item.setOnLongClickListener(v -> {
                try {
                    Context home = AppEntry.homeContext(drawerContext != null ? drawerContext : context);
                    if (home != null) {
                        homeDrag = LauncherDrag.start(home, icon, key, folder.profile, folder.serial, downX, downY);
                        if (homeDrag != null) {
                            // Keep the source window until finger-up. Dismissing a PopupWindow
                            // mid-gesture loses MOVE/UP; make it invisible and forward the
                            // remaining gesture to the native controller instead.
                            panel.setAlpha(0f);
                            item.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                    } else if (AppEntry.startDrag(context, icon, key, folder.profile, folder.serial)) {
                        dismissFolderPopup(); return true;
                    }
                    Toast.makeText(context, "App dragging is unavailable here", Toast.LENGTH_SHORT).show();
                } catch (RuntimeException error) {
                    android.util.Log.e("PixelLauncherFolders", "Could not start app drag", error);
                    Toast.makeText(context, "App dragging is unavailable on this launcher build", Toast.LENGTH_SHORT).show();
                }
                return true; // A failed drag must never turn into an ordinary launch.
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(i / columns), GridLayout.spec(i % columns, 1, GridLayout.FILL, 1f));
            params.width = 0; params.height = -2; grid.addView(item, params);
        }
        int desired = Ui.dp(context, Math.max(1, (keys.size() + columns - 1) / columns) * 112 * scale);
        int height = Math.min(desired, Math.max(Ui.dp(context, 64), Math.min(Ui.dp(context, 420), availableHeight - Ui.dp(context, 190 * scale))));
        if (keys.isEmpty()) panel.addView(Ui.secondary(context, "No apps yet. Choose apps in folder settings.", 16));
        else panel.addView(scroll, new LinearLayout.LayoutParams(-1, height));
        panel.addView(Ui.spacer(context, 12));
        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.END);
        actions.addView(Ui.textButton(context, "Edit folders", () -> { dismissFolderPopup(); settings(); }));
        actions.addView(Ui.textButton(context, "Close", this::dismissFolderPopup)); panel.addView(actions);
        PopupWindow popup = new PopupWindow(panel, width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOutsideTouchable(true); popup.setClippingEnabled(true); popup.setElevation(Ui.dp(context, 8));
        popup.setTouchInterceptor((v, event) -> forwardHomeDrag(event));
        popup.setOnDismissListener(() -> { if (folderPopup == popup) dismissFolderPopup(); });
        // A floating taskbar drawer has a window context, not an Activity.
        // Attach to the existing drawer token instead of creating a dialog.
        try { folderPopup = popup; popup.showAtLocation(anchor, Gravity.CENTER, 0, 0); }
        catch (RuntimeException error) {
            dismissFolderPopup(); android.util.Log.e("PixelLauncherFolders", "Could not attach folder popup", error);
            Toast.makeText(context, "Could not open this folder", Toast.LENGTH_SHORT).show();
        }
    }
}
