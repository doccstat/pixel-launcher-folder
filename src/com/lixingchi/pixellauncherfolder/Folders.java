package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Bounded, versioned data, owned exclusively by our APK. No launcher database access. */
final class Folders {
    static final String PACKAGE = "com.lixingchi.pixellauncherfolder";
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static final Uri URI = Uri.parse("content://" + PACKAGE + ".config/folders");
    static final int MAX_FOLDERS = 24, MAX_APPS = 100, MAX_BYTES = 262144;

    static final class Folder {
        final String id;
        String name;
        final List<String> apps;
        Folder(String id, String name, List<String> apps) {
            this.id = id; this.name = name; this.apps = new ArrayList<>(apps);
        }
        static Folder create(String name, List<String> apps) {
            return new Folder(UUID.randomUUID().toString(), name, apps);
        }
    }

    static List<Folder> parse(String raw) throws JSONException {
        if (raw == null || raw.length() > MAX_BYTES) throw new JSONException("Invalid configuration size");
        JSONObject root = new JSONObject(raw);
        if (root.getInt("version") != 1) throw new JSONException("Unsupported folder format");
        JSONArray array = root.getJSONArray("folders");
        if (array.length() > MAX_FOLDERS) throw new JSONException("Too many folders");
        List<Folder> result = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String id = item.getString("id"), name = item.getString("name").trim();
            if (id.isEmpty() || id.length() > 64 || !ids.add(id)) throw new JSONException("Invalid folder ID");
            if (name.isEmpty() || name.length() > 48) throw new JSONException("Use a folder name of 1–48 characters");
            JSONArray entries = item.getJSONArray("apps");
            if (entries.length() > MAX_APPS) throw new JSONException("Use at most 100 apps per folder");
            List<String> apps = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            for (int j = 0; j < entries.length(); j++) {
                String key = entries.getString(j);
                ComponentName component = ComponentName.unflattenFromString(key);
                if (component == null || component.getPackageName().isEmpty()
                        || component.getClassName().isEmpty() || key.length() > 512)
                    throw new JSONException("Invalid app component");
                key = component.flattenToString();
                if (seen.add(key)) apps.add(key);
            }
            result.add(new Folder(id, name, apps));
        }
        return result;
    }

    static String encode(List<Folder> folders) throws JSONException {
        JSONArray array = new JSONArray();
        for (Folder folder : folders) {
            array.put(new JSONObject().put("id", folder.id).put("name", folder.name.trim())
                    .put("apps", new JSONArray(folder.apps)));
        }
        String raw = new JSONObject().put("version", 1).put("folders", array).toString();
        parse(raw); // Reject invalid changes before touching durable state.
        return raw;
    }

    static String read(Context context) {
        return context.getSharedPreferences("folders", Context.MODE_PRIVATE)
                .getString("json", "{\"version\":1,\"folders\":[]}");
    }

    static void save(Context context, List<Folder> folders) throws JSONException {
        String raw = encode(folders);
        if (!context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString("json", raw).commit())
            throw new IllegalStateException("Could not save folders");
        context.getContentResolver().notifyChange(URI, null);
    }
}
