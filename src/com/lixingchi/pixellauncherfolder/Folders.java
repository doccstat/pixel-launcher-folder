package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, versioned data, owned exclusively by our APK. No launcher database access. */
final class Folders {
    static final String PACKAGE = "com.lixingchi.pixellauncherfolder";
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static final Uri URI = Uri.parse("content://" + PACKAGE + ".config/folders");
    static final int MAX_FOLDERS = 24, MAX_APPS = 100, MAX_BYTES = 262144;

    static final class Folder {
        final String id;
        String name;
        final String profile;
        final long serial;
        final List<String> apps;
        Folder(String id, String name, String profile, long serial, List<String> apps) {
            this.id = id; this.name = name; this.profile = profile; this.serial = serial; this.apps = new ArrayList<>(apps);
        }
        static Folder create(String name, List<String> apps) {
            return new Folder(UUID.randomUUID().toString(), name, "personal", -1, apps);
        }
        static Folder create(String name, String profile, long serial, List<String> apps) {
            return new Folder(UUID.randomUUID().toString(), name, profile, serial, apps);
        }
    }

    static List<Folder> parse(String raw) throws JSONException {
        if (raw == null || raw.length() > MAX_BYTES) throw new JSONException("Invalid configuration size");
        JSONObject root = new JSONObject(raw);
        if (root.getInt("version") != 1) throw new JSONException("Unsupported folder format");
        keepInDrawer(raw);
        JSONArray array = root.getJSONArray("folders");
        if (array.length() > MAX_FOLDERS) throw new JSONException("Too many folders");
        List<Folder> result = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String id = item.getString("id"), name = item.getString("name").trim();
            String profile = item.optString("profile", "personal");
            long serial = item.optLong("serial", -1);
            if (serial < -1 || (profile.equals("work") && serial < 0)) throw new JSONException("Invalid profile serial");
            if (id.isEmpty() || id.length() > 64 || !ids.add(id)) throw new JSONException("Invalid folder ID");
            if (name.isEmpty() || name.length() > 48) throw new JSONException("Use a folder name of 1–48 characters");
            if (!profile.equals("personal") && !profile.equals("work")) throw new JSONException("Choose Personal or Work");
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
            result.add(new Folder(id, name, profile, serial, apps));
        }
        return result;
    }

    static String encode(List<Folder> folders) throws JSONException { return encode(folders, true); }

    static String encode(List<Folder> folders, boolean keep) throws JSONException {
        JSONArray array = new JSONArray();
        for (Folder folder : folders) {
            array.put(new JSONObject().put("id", folder.id).put("name", folder.name.trim()).put("profile", folder.profile).put("serial", folder.serial)
                    .put("apps", new JSONArray(folder.apps)));
        }
        String raw = new JSONObject().put("version", 1).put("keepInDrawer", keep).put("folders", array).toString();
        parse(raw); // Reject invalid changes before touching durable state.
        return raw;
    }

    private static String readInternal(Context context) {
        return context.getSharedPreferences("folders", Context.MODE_PRIVATE)
                .getString("json", "{\"version\":1,\"folders\":[]}");
    }

    static String read(Context context) { return readInternal(context); }

    // Earlier text/plain manifests were imported as empty folders. Remove only
    // empty entries with those reserved metadata names; keep ordinary folders.
    static List<Folder> withoutBackupArtifacts(List<Folder> folders) {
        boolean contaminated = false;
        for (Folder folder : folders)
            if (folder.apps.isEmpty() && manifestFolderName(folder.name)) contaminated = true;
        if (!contaminated) return new ArrayList<>(folders);
        List<Folder> cleaned = new ArrayList<>();
        for (Folder folder : folders) {
            boolean manifest = folder.apps.isEmpty() && manifestFolderName(folder.name);
            if (!manifest) cleaned.add(folder);
        }
        return cleaned;
    }

    private static boolean manifestFolderName(String name) {
        return name.equals(".pixel-launcher-folders.json")
                || name.matches("\\.pixel-launcher-folders\\.json \\([0-9]+\\)");
    }

    static void repairBackupArtifacts(Context context) throws JSONException {
        List<Folder> current = parse(readInternal(context));
        List<Folder> cleaned = withoutBackupArtifacts(current);
        if (cleaned.size() != current.size()) save(context, cleaned);
    }

    static void synchronizeFromBackup(Context context) {
        Uri tree = backupTree(context);
        if (tree == null) return;
        try {
            if (!children(context, tree).containsKey(".pixel-launcher-folders.json")) return;
            ImportResult external = importTree(context);
            String raw = encode(external.folders, external.keep);
            if (!raw.equals(readInternal(context))) {
                context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString("json", raw).commit();
                context.getContentResolver().notifyChange(URI, null);
            }
        } catch (Exception ignored) { }
    }

    static void save(Context context, List<Folder> folders) throws JSONException {
        save(context, folders, keepInDrawer(read(context)));
    }

    static void save(Context context, List<Folder> folders, boolean keep) throws JSONException {
        String raw = encode(folders, keep);
        if (!context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString("json", raw).commit())
            throw new IllegalStateException("Could not save folders");
        context.getContentResolver().notifyChange(URI, null);
        syncBackup(context);
    }

    static void syncBackup(Context context) {
        if (backupTree(context) == null) return;
        try { exportTree(context, parse(readInternal(context))); }
        catch (Exception ignored) { }
    }

    static Uri backupTree(Context context) {
        String value = context.getSharedPreferences("folders", Context.MODE_PRIVATE)
                .getString("backup_tree", null);
        return value == null ? null : Uri.parse(value);
    }

    static void setBackupTree(Context context, Uri tree) {
        context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit()
                .putString("backup_tree", tree.toString()).commit();
    }

    static final class ImportResult {
        final List<Folder> folders;
        final boolean keep;
        final int skipped;
        ImportResult(List<Folder> folders, boolean keep, int skipped) {
            this.folders = folders; this.keep = keep; this.skipped = skipped;
        }
    }

    private static Uri treeDocument(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private static Map<String, Uri> children(Context context, Uri tree) throws JSONException {
        Map<String, Uri> result = new LinkedHashMap<>();
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (android.database.Cursor cursor = context.getContentResolver().query(query,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1);
                result.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        }
        return result;
    }

    private static Uri writableFile(Context context, Uri tree, Map<String, Uri> current, String name) throws Exception {
        Uri existing = current.get(name);
        return existing != null ? existing : DocumentsContract.createDocument(context.getContentResolver(), treeDocument(tree),
                name.endsWith(".json") ? "application/json" : "text/plain", name);
    }

    private static boolean legacyManifest(String name) {
        return name.equals(".pixel-launcher-folders.json.txt")
                || (name.startsWith(".pixel-launcher-folders.json (") && name.endsWith(").txt"));
    }

    private static String safeName(String name, Set<String> used) {
        String value = name.replaceAll("[\\\\/:*?\\\"<>|]", "_").trim();
        if (value.isEmpty()) value = "folder";
        String base = value, suffix = ".txt"; int number = 2;
        while (used.contains(value.toLowerCase(java.util.Locale.ROOT) + suffix)) value = base + "_" + number++;
        used.add(value.toLowerCase(java.util.Locale.ROOT) + suffix);
        return value + suffix;
    }

    static void exportTree(Context context, List<Folder> folders) throws Exception {
        Uri tree = backupTree(context);
        if (tree == null) throw new IllegalStateException("Choose a backup folder first");
        Map<String, Uri> current = children(context, tree); Set<String> used = new HashSet<>();
        for (Map.Entry<String, Uri> entry : new ArrayList<>(current.entrySet())) {
            if (legacyManifest(entry.getKey())) {
                DocumentsContract.deleteDocument(context.getContentResolver(), entry.getValue());
                current.remove(entry.getKey());
            }
        }
        Set<String> desired = new HashSet<>(); JSONArray manifestFolders = new JSONArray();
        for (Folder folder : folders) {
            String file = safeName(folder.name, used); desired.add(file);
            StringBuilder text = new StringBuilder(); Set<String> packages = new HashSet<>();
            for (String key : folder.apps) {
                ComponentName component = ComponentName.unflattenFromString(key);
                if (component != null && packages.add(component.getPackageName())) text.append(component.getPackageName()).append('\n');
            }
            Uri target = writableFile(context, tree, current, file);
            try (java.io.OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new java.io.IOException("Could not open " + file);
                output.write(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            manifestFolders.put(new JSONObject().put("id", folder.id).put("name", folder.name)
                    .put("profile", folder.profile).put("serial", folder.serial).put("file", file));
        }
        JSONObject manifest = new JSONObject().put("version", 1).put("keepInDrawer", keepInDrawer(readInternal(context)))
                .put("folders", manifestFolders);
        String oldManifestText = current.containsKey(".pixel-launcher-folders.json")
                ? readFile(context, current.get(".pixel-launcher-folders.json")) : null;
        if (oldManifestText != null) {
            try {
                JSONArray oldFolders = new JSONObject(oldManifestText).optJSONArray("folders");
                if (oldFolders != null) for (int i = 0; i < oldFolders.length(); i++) {
                    String oldFile = oldFolders.getJSONObject(i).optString("file", "");
                    if (!oldFile.isEmpty() && !desired.contains(oldFile) && current.containsKey(oldFile))
                        DocumentsContract.deleteDocument(context.getContentResolver(), current.get(oldFile));
                }
            } catch (JSONException ignored) { }
        }
        Uri manifestUri = writableFile(context, tree, current, ".pixel-launcher-folders.json");
        try (java.io.OutputStream output = context.getContentResolver().openOutputStream(manifestUri, "wt")) {
            if (output == null) throw new java.io.IOException("Could not open manifest");
            output.write(manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String readFile(Context context, Uri uri) throws Exception {
        try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new java.io.IOException("Could not open backup file");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private static List<String> lines(String raw) {
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim(); if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
        }
        return result;
    }

    private static List<String> resolveApps(Context context, String profile, long serial, List<String> names) throws Exception {
        List<AppEntry> available = AppEntry.list(context, profile, serial);
        Map<String, String> components = new HashMap<>();
        for (AppEntry entry : available) components.putIfAbsent(entry.info.getComponentName().getPackageName(), entry.key);
        List<String> result = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
        for (String name : names) {
            String component = name;
            ComponentName parsed = ComponentName.unflattenFromString(name);
            if (parsed == null) component = components.get(name);
            if (component != null && seen.add(component)) result.add(component);
        }
        return result;
    }

    static ImportResult importTree(Context context) throws Exception {
        Uri tree = backupTree(context);
        if (tree == null) throw new IllegalStateException("Choose a backup folder first");
        Map<String, Uri> files = children(context, tree); Uri manifestUri = files.get(".pixel-launcher-folders.json");
        List<Folder> result = new ArrayList<>(); int skipped = 0; boolean keep = true;
        if (manifestUri != null) {
            JSONObject manifest = new JSONObject(readFile(context, manifestUri));
            if (manifest.getInt("version") != 1) throw new JSONException("Unsupported backup format");
            keep = manifest.optBoolean("keepInDrawer", true); JSONArray entries = manifest.getJSONArray("folders");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i); Uri file = files.get(item.getString("file"));
                if (file == null) continue;
                String profile = item.optString("profile", "personal"); long serial = item.optLong("serial", -1);
                try {
                    List<String> apps = resolveApps(context, profile, serial, lines(readFile(context, file)));
                    result.add(new Folder(item.getString("id"), item.getString("name"), profile, serial, apps));
                } catch (Exception unavailable) { skipped++; }
            }
        } else {
            for (Map.Entry<String, Uri> entry : files.entrySet()) {
                if (!entry.getKey().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")
                        || legacyManifest(entry.getKey())) continue;
                String name = entry.getKey().substring(0, entry.getKey().length() - 4);
                result.add(Folder.create(name, "personal", -1, resolveApps(context, "personal", -1,
                        lines(readFile(context, entry.getValue())))));
            }
        }
        result = withoutBackupArtifacts(result);
        encode(result, keep); // Validate bounds and component structure before returning.
        return new ImportResult(result, keep, skipped);
    }

    static boolean keepInDrawer(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        if (!root.has("keepInDrawer")) return true;
        Object value = root.get("keepInDrawer");
        if (!(value instanceof Boolean)) throw new JSONException("Invalid display option");
        return (Boolean) value;
    }

    static void setKeepInDrawer(Context context, boolean keep) throws JSONException {
        String raw = encode(parse(read(context)), keep);
        if (!context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString("json", raw).commit())
            throw new IllegalStateException("Could not save display option");
        context.getContentResolver().notifyChange(URI, null);
        syncBackup(context);
    }

    static boolean inFolders(List<Folder> folders, String component) {
        for (Folder folder : folders) if (folder.apps.contains(component)) return true;
        return false;
    }

    static boolean assignedElsewhere(List<Folder> folders, Folder current, String kind, long serial,
            long personalSerial, String component) {
        for (Folder folder : folders) {
            if (current != null && current.id.equals(folder.id)) continue;
            if (matches(folder, kind, serial, personalSerial) && folder.apps.contains(component)) return true;
        }
        return false;
    }

    static boolean shouldHide(boolean keep, List<Folder> folders, String kind, long serial,
            long personalSerial, String component) {
        if (keep) return false;
        for (Folder folder : folders)
            if (matches(folder, kind, serial, personalSerial) && folder.apps.contains(component)) return true;
        return false;
    }

    static boolean matches(Folder folder, String kind, long serial, long personalSerial) {
        return folder.profile.equals(kind) && (folder.serial < 0 ? personalSerial : folder.serial) == serial;
    }
}
