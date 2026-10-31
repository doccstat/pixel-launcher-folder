package com.lixingchi.pixellauncherfolder;

import java.util.Set;

/** Resolves normalized components against one profile's current launcher entries. */
final class AppResolver {
    static String resolve(String saved, Set<String> available, boolean followChanges) {
        if (saved == null) return null;
        int slash = saved.indexOf('/');
        if (slash <= 0 || slash == saved.length() - 1) return null;
        if (available.contains(saved)) return saved;
        if (!followChanges) return null;
        String prefix = saved.substring(0, slash + 1), replacement = null;
        for (String candidate : available) {
            if (!candidate.startsWith(prefix)) continue;
            if (replacement != null) return null; // Multiple entry points require a user choice.
            replacement = candidate;
        }
        return replacement;
    }

    static boolean contains(Iterable<String> saved, String current, Set<String> available, boolean followChanges) {
        String prefix = current.substring(0, current.indexOf('/') + 1);
        for (String key : saved)
            if (current.equals(key) || (key.startsWith(prefix) && current.equals(resolve(key, available, followChanges)))) return true;
        return false;
    }
}
