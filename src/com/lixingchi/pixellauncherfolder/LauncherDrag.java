package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Bridges a PopupWindow's gesture into the Home drawer's native drag controller. */
final class LauncherDrag {
    private final Object controller;
    private final Object launcher;
    private final View layer;
    private final Field touchInProgress;
    private final Method touch, cancel;
    private boolean claimedTouch;

    private LauncherDrag(Object controller, Object launcher, View layer) throws ReflectiveOperationException {
        this.controller = controller; this.launcher = launcher; this.layer = layer;
        touchInProgress = declaredField(launcher, "mTouchInProgress");
        touch = controller.getClass().getMethod("onControllerTouchEvent", MotionEvent.class);
        cancel = controller.getClass().getMethod("cancelDrag");
    }

    private static Field declaredField(Object object, String name) throws NoSuchFieldException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private void claimTouch() throws IllegalAccessException {
        touchInProgress.setBoolean(launcher, true);
        claimedTouch = true;
    }

    private void releaseTouch() {
        if (!claimedTouch) return;
        try { touchInProgress.setBoolean(launcher, false); }
        catch (IllegalAccessException error) { android.util.Log.w("PixelLauncherFolders", "Could not release launcher touch state", error); }
        claimedTouch = false;
    }

    static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object findApp(Object[] apps, ComponentName component, UserHandle user)
            throws ReflectiveOperationException {
        for (Object app : apps) if (component.equals(field(app, "componentName"))
                && user.equals(field(app, "user"))) return app;
        return null;
    }

    /** Pixel's controller dereferences this even when the drag shadow is a Drawable. */
    static Object draggable(Class<?> type, Rect bounds) {
        Rect snapshot = new Rect(bounds);
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getViewType": return 0; // DRAGGABLE_ICON, like BubbleTextView.
                case "getSourceVisualDragBounds":
                case "getWorkspaceVisualDragBounds": ((Rect) args[0]).set(snapshot); return null;
                case "prepareDrawDragView":
                    Class<?> closeable = method.getReturnType();
                    return Proxy.newProxyInstance(closeable.getClassLoader(), new Class<?>[]{closeable},
                            (unused, close, arguments) -> {
                                if ("close".equals(close.getName())) return null;
                                throw new UnsupportedOperationException(close.toString());
                            });
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "PixelLauncherFolders icon drag source";
                default: throw new UnsupportedOperationException(method.toString());
            }
        });
    }

    static LauncherDrag start(Context home, ImageView icon, String key, String kind, long serial,
            float downX, float downY) {
        LauncherDrag session = null;
        boolean attempted = false;
        try {
            Profiles profile = Profiles.resolve(home, kind, serial);
            ComponentName component = ComponentName.unflattenFromString(key);
            if (component == null || !profile.usable(home)) return null;
            ClassLoader loader = home.getClassLoader();
            Class<?> launcherType = Class.forName("com.android.launcher3.Launcher", false, loader);
            Class<?> longClick = Class.forName("com.android.launcher3.touch.ItemLongClickListener", false, loader);
            if (!(Boolean) longClick.getMethod("canStartAllAppsItemDrag", launcherType).invoke(null, home)) return null;
            Object appsView = field(home, "mAppsView");
            // Read the unfiltered store, not adapter items: folder-only apps remain draggable.
            Object app = findApp((Object[]) field(field(appsView, "mAllAppsStore"), "mApps"), component, profile.user);
            if (app == null) return null;
            Class<?> appType = Class.forName("com.android.launcher3.model.data.AppInfo", false, loader);
            Object item = appType.getConstructor(appType).newInstance(app);
            Object controller = field(home, "mDragController");
            View layer = (View) home.getClass().getMethod("getDragLayer").invoke(home);
            session = new LauncherDrag(controller, home, layer);
            Class<?> optionsType = Class.forName("com.android.launcher3.dragndrop.DragOptions", false, loader);
            Class<?> sourceType = Class.forName("com.android.launcher3.DragSource", false, loader);
            Class<?> itemType = Class.forName("com.android.launcher3.model.data.ItemInfo", false, loader);
            Class<?> draggableType = Class.forName("com.android.launcher3.dragndrop.DraggableView", false, loader);
            Method start = controller.getClass().getMethod("startDrag", Drawable.class, View.class,
                    draggableType, int.class, int.class, sourceType, itemType, Rect.class,
                    float.class, float.class, optionsType);
            Method intercept = controller.getClass().getMethod("onControllerInterceptTouchEvent", MotionEvent.class);
            Drawable drawable = icon.getDrawable();
            int width = icon.getWidth(), height = icon.getHeight();
            if (drawable == null || width <= 0 || height <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Rect old = new Rect(drawable.getBounds());
            try { drawable.setBounds(0, 0, width, height); drawable.draw(new Canvas(bitmap)); }
            finally { drawable.setBounds(old); }
            int[] origin = new int[2], position = new int[2];
            layer.getLocationOnScreen(origin); icon.getLocationOnScreen(position);
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                    downX - origin[0], downY - origin[1], 0);
            try { intercept.invoke(controller, down); } finally { down.recycle(); }
            attempted = true;
            // LauncherDragController cancels synthetic starts unless the Launcher
            // believes a touch is in progress. The real down occurred in our
            // PopupWindow, so claim that state for this forwarded gesture.
            session.claimTouch();
            // Same native entry point used by Workspace.beginDragShared. A popup is
            // not a descendant of DragLayer, so supply screen-relative icon bounds
            // explicitly rather than asking DragPreviewProvider to walk its parents.
            Object draggable = draggable(draggableType, new Rect(0, 0, width, height));
            start.invoke(controller, new BitmapDrawable(home.getResources(), bitmap), null, draggable,
                    position[0] - origin[0], position[1] - origin[1], appsView, item,
                    new Rect(0, 0, width, height), 1f, 1f, optionsType.getDeclaredConstructor().newInstance());
            if (!(Boolean) controller.getClass().getMethod("isDragging").invoke(controller)) {
                session.cancel(); return null;
            }
            android.util.Log.i("PixelLauncherFolders", "Started native Home-screen drag");
            return session;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (attempted && session != null) session.cancel();
            throw new IllegalStateException("Home-screen app dragging is unavailable on this launcher build", error);
        }
    }

    static MotionEvent inLayer(MotionEvent event, View layer) {
        int[] origin = new int[2]; layer.getLocationOnScreen(origin);
        MotionEvent copy = MotionEvent.obtain(event);
        copy.offsetLocation(event.getRawX() - event.getX() - origin[0],
                event.getRawY() - event.getY() - origin[1]);
        return copy;
    }

    void move(MotionEvent event) {
        int action = event.getActionMasked();
        MotionEvent copy = inLayer(event, layer);
        try { touch.invoke(controller, copy); }
        catch (ReflectiveOperationException error) { cancel(); throw new IllegalStateException(error); }
        finally {
            copy.recycle();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releaseTouch();
        }
    }

    void cancel() {
        try { cancel.invoke(controller); }
        catch (ReflectiveOperationException error) { android.util.Log.e("PixelLauncherFolders", "Could not cancel drag", error); }
        finally { releaseTouch(); }
    }
}
