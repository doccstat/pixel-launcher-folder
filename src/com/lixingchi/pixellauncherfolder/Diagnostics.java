package com.lixingchi.pixellauncherfolder;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User-exported snapshot: no folder names, app assignments, storage URIs or device identifiers. */
final class Diagnostics {
    private static final List<String> events = new ArrayList<>();

    static synchronized void record(String operation, Throwable error) {
        if (events.size() == 16) events.remove(0);
        events.add(operation + ": " + (error == null ? "ok" : error.getClass().getSimpleName()));
    }

    static JSONObject collect(Context context) throws Exception {
        JSONObject result = new JSONObject().put("format", 1)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL).put("locale", context.getResources().getConfiguration().getLocales().toLanguageTags());
        for (String pkg : new String[]{Folders.PACKAGE, Folders.LAUNCHER}) {
            try {
                android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
                result.put(pkg, new JSONObject().put("version", info.versionName).put("code", info.getLongVersionCode()));
            } catch (Exception error) { result.put(pkg, error.getClass().getSimpleName()); }
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream apk = new java.io.FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = apk.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        result.put("apkSha256", hash.toString());
        String raw = Folders.read(context);
        boolean follow = Folders.followAppChanges(raw);
        result.put("settings", new JSONObject().put("keepInDrawer", Folders.keepInDrawer(raw))
                .put("followAppChanges", follow).put("hideUnavailable", Folders.hideUnavailable(raw))
                .put("backupConnected", Folders.backupTree(context) != null));
        Map<String, List<Folders.Folder>> groups = new LinkedHashMap<>();
        for (Folders.Folder folder : Folders.parse(raw))
            groups.computeIfAbsent(folder.profile + ":" + folder.serial, key -> new ArrayList<>()).add(folder);
        JSONArray profiles = new JSONArray();
        for (List<Folders.Folder> folders : groups.values()) {
            Folders.Folder first = folders.get(0);
            JSONObject profile = new JSONObject().put("kind", first.profile).put("folders", folders.size());
            int assigned = 0;
            for (Folders.Folder folder : folders) assigned += folder.apps.size();
            profile.put("assignments", assigned);
            try {
                Map<String, AppEntry> apps = AppEntry.index(AppEntry.list(context, first.profile, first.serial));
                int missing = 0;
                for (Folders.Folder folder : folders) for (String key : folder.apps)
                    if (AppEntry.resolve(key, apps, follow) == null) missing++;
                profile.put("availableEntries", apps.size()).put("unavailableAssignments", missing).put("inventory", "ok");
            } catch (Exception error) { profile.put("inventory", error.getClass().getSimpleName()); }
            profiles.put(profile);
        }
        result.put("profiles", profiles);
        synchronized (Diagnostics.class) { result.put("recentOperations", new JSONArray(events)); }
        return result;
    }
}
