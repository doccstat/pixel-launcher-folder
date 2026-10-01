package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.os.Process;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

final class AppEntry {
    final String key, label;
    final LauncherActivityInfo info;
    AppEntry(LauncherActivityInfo info) {
        this.info = info;
        key = info.getComponentName().flattenToString();
        label = info.getLabel().toString();
    }
    AppEntry(String missing) { key = missing; label = "Unavailable · " + missing; info = null; }
    Drawable icon(Context context) {
        return info == null ? context.getPackageManager().getDefaultActivityIcon()
                : info.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi);
    }
    static List<AppEntry> list(Context context) {
        List<AppEntry> entries = new ArrayList<>();
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo info : launcherApps.getActivityList(null, Process.myUserHandle()))
            entries.add(new AppEntry(info));
        Collator collator = Collator.getInstance();
        entries.sort((a, b) -> { int c = collator.compare(a.label, b.label); return c == 0 ? a.key.compareTo(b.key) : c; });
        return entries;
    }
    static void launch(Context context, String key) {
        LauncherApps apps = context.getSystemService(LauncherApps.class);
        ComponentName component = ComponentName.unflattenFromString(key);
        if (component == null || !apps.isActivityEnabled(component, Process.myUserHandle()))
            throw new IllegalStateException("This app is unavailable");
        // Verify this is still a launcher activity, not a stale/arbitrary exported component.
        for (LauncherActivityInfo info : apps.getActivityList(component.getPackageName(), Process.myUserHandle())) {
            if (component.equals(info.getComponentName())) {
                apps.startMainActivity(component, Process.myUserHandle(), null, null);
                return;
            }
        }
        throw new IllegalStateException("This app is no longer in the app drawer");
    }
}
