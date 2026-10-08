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
    private static final String ACTIVITY_MIME = "application/vnd.android.activity";
    // ClipDescription's activity-drag constants are @hide/@SystemApi and
    // therefore absent from some public SDK stubs. These are their framework
    // defined keys on Android 15+.
    private static final String EXTRA_PENDING_INTENT = "android.intent.extra.PENDING_INTENT";
    private static final String EXTRA_LOGGING_INSTANCE_ID = "android.intent.extra.LOGGING_INSTANCE_ID";
    // View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION is @hide on the
    // public SDK; Android 15+ defines it as 1 << 11, as used by TaskbarDragController.
    private static final int RETURN_ANIMATION_DRAG_FLAG = 1 << 11;
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

    /** Floating taskbar drawers use WM Shell; Home drawers need the workspace controller. */
    static Context homeContext(Context context) {
        if (!Folders.LAUNCHER.equals(context.getPackageName())) return null;
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> launcher = Class.forName("com.android.launcher3.Launcher", false, loader);
            Class<?> activityContext = Class.forName("com.android.launcher3.views.ActivityContext", false, loader);
            java.lang.reflect.Method lookup = activityContext.getDeclaredMethod("lookupContextNoThrow", Context.class);
            lookup.setAccessible(true);
            Object resolved = lookup.invoke(null, context);
            return launcher.isInstance(resolved) ? (Context) resolved : null;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported launcher", error);
        }
    }

    /** Starts a global activity drag ONLY in the floating taskbar drawer. */
    static boolean startDrag(Context context, View source, String key, String kind, long serial) {
        Profiles profile = Profiles.resolve(context, kind, serial);
        ComponentName component = ComponentName.unflattenFromString(key);
        if (component == null || !profile.usable(context)) return false;
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        // Home gestures are bridged by FolderRow. Never silently fall back to
        // a Shell-only drag after a failed native Home drag.
        if (homeContext(context) != null) return false;
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
            LauncherActivityInfo activity = launcher.getActivityList(component.getPackageName(), profile.user).stream()
                    .filter(info -> component.equals(info.getComponentName())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("This app is no longer in the app drawer"));
            ClipData data = activityClip(context, activity.getLabel().toString(), pending, profile.user);
            Drawable icon = activity.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi);
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
            return source.startDragAndDrop(data, shadow, null, activityDragFlags());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Launcher app dragging is unavailable on this build", error);
        }
    }
    static ClipData activityClip(String label, android.app.PendingIntent launch, android.os.UserHandle user) {
        return activityClip(null, label, launch, user);
    }

    /** The same activity payload used by Pixel's TaskbarDragController. */
    static ClipData activityClip(Context context, String label, android.app.PendingIntent launch,
            android.os.UserHandle user) {
        // Android framework ClipDescription constants are @hide, but this is
        // the WM Shell activity drag contract (not plain text or an app link).
        android.content.Intent payload = new android.content.Intent();
        payload.putExtra(EXTRA_PENDING_INTENT, launch);
        payload.putExtra(android.content.Intent.EXTRA_USER, user);
        if (context != null) addShellDragMetadata(context, payload);
        return new ClipData(new ClipDescription(label, new String[]{ACTIVITY_MIME}),
                new ClipData.Item(payload));
    }

    /** Matches TaskbarDragController's global drag flags without hidden-API reflection. */
    static int activityDragFlags() {
        return View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_OPAQUE | RETURN_ANIMATION_DRAG_FLAG;
    }

    /**
     * WM Shell does not require these extras to accept an activity drag, but
     * Pixel's taskbar supplies them. Add them opportunistically so drops from
     * this module behave like native taskbar drops across launcher revisions.
     */
    private static void addShellDragMetadata(Context context, android.content.Intent payload) {
        ClassLoader loader = context.getClassLoader();
        try {
            Class<?> logUtils = Class.forName("com.android.quickstep.util.LogUtils", false, loader);
            java.lang.reflect.Method method = logUtils.getDeclaredMethod("getShellShareableInstanceId");
            method.setAccessible(true);
            Object pair = method.invoke(null);
            Object internalId = pair.getClass().getField("first").get(pair);
            if (internalId instanceof android.os.Parcelable) {
                payload.putExtra(EXTRA_LOGGING_INSTANCE_ID, (android.os.Parcelable) internalId);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }

        try {
            if (!isTransientTaskbar(context, loader)) return;
            Object dragLayer = context.getClass().getMethod("getDragLayer").invoke(context);
            Object value = null;
            java.lang.reflect.Method lastRect = findMethod(dragLayer.getClass(), "getLastDrawnTransientRect");
            if (lastRect != null) value = lastRect.invoke(dragLayer);
            if (value == null) {
                Object controllers = readField(context, "mControllers");
                Object viewController = readField(controllers, "taskbarViewController");
                Object taskbarView = readField(viewController, "mTaskbarView");
                java.lang.reflect.Method bounds = findMethod(taskbarView.getClass(),
                        "getTransientTaskbarIconLayoutBoundsInParent");
                if (bounds != null) value = bounds.invoke(taskbarView);
            }
            android.graphics.RectF region;
            if (value instanceof android.graphics.RectF) region = new android.graphics.RectF((android.graphics.RectF) value);
            else if (value instanceof android.graphics.Rect) region = new android.graphics.RectF((android.graphics.Rect) value);
            else return;
            int[] location = new int[2]; ((View) dragLayer).getLocationOnScreen(location);
            region.offset(location[0], location[1]);
            // Android 17's Shell constant is the literal key below. Keep the
            // fallback literal so this also works when the shared Shell class
            // is hidden or moved between launcher revisions.
            payload.putExtra("DISALLOW_HIT_REGION", (android.os.Parcelable) region);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
    }

    private static boolean isTransientTaskbar(Context context, ClassLoader loader)
            throws ReflectiveOperationException {
        java.lang.reflect.Method local = findMethod(context.getClass(), "isTransientTaskbar$1");
        if (local != null && local.getParameterTypes().length == 0) {
            return Boolean.TRUE.equals(local.invoke(context));
        }
        for (String name : new String[]{"com.android.launcher3.util.DisplayController",
                "com.android.launcher3.display.DisplayController"}) {
            try {
                Class<?> display = Class.forName(name, false, loader);
                java.lang.reflect.Method method = findMethod(display, "isTransientTaskbar", Context.class);
                if (method != null) return Boolean.TRUE.equals(method.invoke(null, context));
            } catch (ClassNotFoundException ignored) { }
        }
        return false;
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                java.lang.reflect.Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true); return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static Object readField(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true); return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
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
