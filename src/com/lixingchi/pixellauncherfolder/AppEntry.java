package com.lixingchi.pixellauncherfolder;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

final class AppEntry {
    final String key, label;
    final LauncherActivityInfo info;
    AppEntry(LauncherActivityInfo info) {
        this.info = info; key = info.getComponentName().flattenToString(); label = info.getLabel().toString();
    }
    AppEntry(String missing) { key = missing; label = "Unavailable · " + missing; info = null; }
    Drawable icon(Context context) {
        return info == null ? context.getPackageManager().getDefaultActivityIcon()
                : info.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi);
    }
    static List<AppEntry> list(Context context) { return list(context, "personal", -1); }
    static List<AppEntry> list(Context context, String kind, long serial) {
        Profiles profile = Profiles.resolve(context, kind, serial);
        if (!profile.usable(context)) throw new IllegalStateException("Unlock or resume this profile to choose apps");
        List<AppEntry> entries = new ArrayList<>();
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo info : launcher.getActivityList(null, profile.user)) entries.add(new AppEntry(info));
        Collator collator = Collator.getInstance();
        entries.sort((a, b) -> { int c = collator.compare(a.label, b.label); return c == 0 ? a.key.compareTo(b.key) : c; });
        return entries;
    }
    static void launch(Context context, String key) { launch(context, key, "personal", -1); }

    /** Starts the same global Android drag payload Pixel Launcher uses for app cells. */
    static boolean startDrag(Context context, View source, String key, String kind, long serial) {
        Profiles profile = Profiles.resolve(context, kind, serial);
        ComponentName component = ComponentName.unflattenFromString(key);
        if (component == null || !profile.usable(context)) return false;
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        // These are framework system APIs available to the scoped launcher,
        // not the SDK settings app. Never synthesize a Personal-user intent
        // for a Work app or fall back to a full-screen launch on drag failure.
        if (!Folders.LAUNCHER.equals(context.getPackageName())) return false;
        try {
            java.lang.reflect.Method method = LauncherApps.class.getDeclaredMethod("getMainActivityLaunchIntent",
                    ComponentName.class, android.os.Bundle.class, android.os.UserHandle.class);
            method.setAccessible(true);
            android.app.PendingIntent pending = (android.app.PendingIntent) method.invoke(launcher, component, null, profile.user);
            if (pending == null) return false;
            ClipData data = activityClip(component.getPackageName(), pending, profile.user);
            Drawable icon = launcher.getActivityList(component.getPackageName(), profile.user).stream()
                    .filter(info -> component.equals(info.getComponentName())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("This app is no longer in the app drawer"))
                    .getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(source) {
                final int size = Ui.dp(context, 56);
                @Override public void onProvideShadowMetrics(android.graphics.Point dimensions, android.graphics.Point touch) {
                    dimensions.set(size, size); touch.set(size / 2, size / 2);
                }
                @Override public void onDrawShadow(android.graphics.Canvas canvas) {
                    android.graphics.Rect old = new android.graphics.Rect(icon.getBounds());
                    icon.setBounds(0, 0, size, size); icon.draw(canvas); icon.setBounds(old);
                }
            };
            return source.startDragAndDrop(data, shadow, null, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_OPAQUE);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Launcher app dragging is unavailable on this build", error);
        }
    }
    static ClipData activityClip(String label, android.app.PendingIntent launch, android.os.UserHandle user) {
        // Android framework ClipDescription constants are @hide, but this is
        // the WM Shell activity drag contract (not plain text or an app link).
        android.content.Intent payload = new android.content.Intent();
        payload.putExtra("android.intent.extra.PENDING_INTENT", launch);
        payload.putExtra(android.content.Intent.EXTRA_USER, user);
        return new ClipData(new ClipDescription(label, new String[]{"application/vnd.android.activity"}),
                new ClipData.Item(payload));
    }

    static void launch(Context context, String key, String kind, long serial) {
        Profiles profile = Profiles.resolve(context, kind, serial);
        if (!profile.usable(context)) throw new IllegalStateException("This profile is paused or locked");
        LauncherApps apps = context.getSystemService(LauncherApps.class);
        ComponentName component = ComponentName.unflattenFromString(key);
        if (component == null || !apps.isActivityEnabled(component, profile.user)) throw new IllegalStateException("This app is unavailable");
        for (LauncherActivityInfo info : apps.getActivityList(component.getPackageName(), profile.user)) {
            if (component.equals(info.getComponentName())) {
                apps.startMainActivity(component, profile.user, null, null); return;
            }
        }
        throw new IllegalStateException("This app is no longer in the app drawer");
    }
}
