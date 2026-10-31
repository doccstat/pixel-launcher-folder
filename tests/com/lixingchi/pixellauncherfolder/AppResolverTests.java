package com.lixingchi.pixellauncherfolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** No Android runtime needed: run by tests/build_tests/test_app_resolver.py. */
public final class AppResolverTests {
    private static Set<String> entries(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }

    public static void main(String[] args) {
        String old = "com.example/com.example.OldIcon", updated = "com.example/com.example.NewIcon";
        String second = "com.example/com.example.OtherActivity";
        Set<String> available = entries(updated, "com.example.other/com.example.other.Main");
        check(updated.equals(AppResolver.resolve(old, available, true)));
        check(AppResolver.resolve(old, available, false) == null);
        check(old.equals(AppResolver.resolve(old, entries(old, updated), true)));
        check(old.equals(AppResolver.resolve(old, entries(old, updated), false)));
        check(AppResolver.resolve(old, entries(updated, second), true) == null);
        check(AppResolver.resolve(old, entries(second, updated), true) == null);
        check(AppResolver.resolve(old, Collections.emptySet(), true) == null);
        check(AppResolver.resolve(old, entries("com.example.other/com.example.other.Main"), true) == null);
        check(AppResolver.resolve(old, entries("com.other/com.example.OldIcon"), true) == null);
        check(AppResolver.resolve("invalid", available, true) == null);
        check(AppResolver.resolve("/Main", available, true) == null);
        check(AppResolver.resolve("com.example/", available, true) == null);
        check(AppResolver.resolve(null, available, true) == null);
        check(AppResolver.contains(Arrays.asList(old), updated, available, true));
        check(!AppResolver.contains(Arrays.asList(old), updated, available, false));
        check(AppResolver.contains(Arrays.asList(old), old, Collections.emptySet(), true));
        check(!AppResolver.contains(Arrays.asList(old), second, entries(updated, second), true));
        check(!AppResolver.contains(Collections.emptyList(), updated, available, true));
        check(available.equals(entries(updated, "com.example.other/com.example.other.Main")));
        // Each call sees only its supplied profile; a replacement in another profile cannot leak in.
        check(AppResolver.resolve(old, entries("work.only/work.only.Main"), true) == null);
        // An upgrade's temporary empty inventory must not poison the next snapshot.
        check(AppResolver.resolve(old, Collections.emptySet(), true) == null);
        check(updated.equals(AppResolver.resolve(old, available, true)));
        System.out.println("PASS: 22 app-resolution checks");
    }
}
