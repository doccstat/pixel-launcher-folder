package com.lixingchi.pixellauncherfolder;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherUserInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import java.util.ArrayList;
import java.util.List;

/** Identify only the current personal user and explicitly managed profiles. Never infer Work from 'not me'. */
final class Profiles {
    final UserHandle user;
    final long serial;
    final String kind;
    Profiles(UserHandle user, long serial, String kind) { this.user = user; this.serial = serial; this.kind = kind; }
    static List<Profiles> available(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        LauncherApps apps = context.getSystemService(LauncherApps.class);
        List<Profiles> result = new ArrayList<>();
        for (UserHandle user : apps.getProfiles()) {
            String kind;
            if (user.equals(Process.myUserHandle()) && !users.isManagedProfile()) kind = "personal";
            else {
                try {
                    LauncherUserInfo info = apps.getLauncherUserInfo(user);
                    if (info == null || !UserManager.USER_TYPE_PROFILE_MANAGED.equals(info.getUserType())) continue;
                    kind = "work";
                } catch (RuntimeException denied) { continue; }
            }
            long serial = users.getSerialNumberForUser(user);
            if (serial >= 0) result.add(new Profiles(user, serial, kind));
        }
        result.sort((a, b) -> { int c = a.kind.compareTo(b.kind); return c != 0 ? c : Long.compare(a.serial, b.serial); });
        return result;
    }
    static long personalSerial(Context context) {
        return context.getSystemService(UserManager.class).getSerialNumberForUser(Process.myUserHandle());
    }
    static Profiles resolve(Context context, String kind, long serial) {
        long wanted = serial == -1 && "personal".equals(kind) ? personalSerial(context) : serial;
        for (Profiles profile : available(context)) if (profile.kind.equals(kind) && profile.serial == wanted) return profile;
        throw new IllegalStateException("This profile is unavailable");
    }
    boolean usable(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return !users.isQuietModeEnabled(user) && users.isUserUnlocked(user);
    }
    @Override public String toString() { return (kind.equals("work") ? "Work" : "Personal") + " · profile " + serial; }
}
