package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
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
