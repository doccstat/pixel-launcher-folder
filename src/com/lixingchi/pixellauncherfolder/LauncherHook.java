package com.lixingchi.pixellauncherfolder;

import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Adds a real adapter item BEFORE section/row calculation and DiffUtil notifications. */
public final class LauncherHook implements IXposedHookLoadPackage {
    static final int FOLDER_TYPE = 1 << 24; // Distinct from stock icon and Private Space types.
    private static final String ROOT = "com.android.launcher3.allapps.";
    private static final WeakHashMap<Object, WeakReference<Controller>> models = new WeakHashMap<>();
    private static Controller controller(Object model) {
        WeakReference<Controller> value = models.get(model); return value == null ? null : value.get();
    }
    private static java.lang.reflect.Constructor<?> createItem;
    private static boolean ready;

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static Method method(Class<?> type, String name, Class<?>... params) throws Exception {
        Method m = type.getDeclaredMethod(name, params); m.setAccessible(true); return m;
    }
    private static void log(Throwable error) { XposedBridge.log("PixelLauncherFolders: " + error); }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!Folders.LAUNCHER.equals(pkg.packageName) || !Folders.LAUNCHER.equals(pkg.processName)) return;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            ClassLoader cl = pkg.classLoader;
            Class<?> base = Class.forName(ROOT + "BaseAllAppsAdapter", false, cl);
            Class<?> model = Class.forName(ROOT + "AlphabeticalAppsList", false, cl);
            Class<?> holder = Class.forName(ROOT + "ActivityAllAppsContainerView$AdapterHolder", false, cl);
            Class<?> item = Class.forName(ROOT + "BaseAllAppsAdapter$AdapterItem", false, cl);
            createItem = item.getDeclaredConstructor(int.class); createItem.setAccessible(true);
            Method bind = null;
            for (Method m : base.getDeclaredMethods()) if (m.getName().equals("onBindViewHolder")
                    && m.getParameterCount() == 2 && m.getParameterTypes()[1] == int.class) bind = m;
            if (bind == null) throw new NoSuchMethodException("onBindViewHolder");
            Method addApps = method(model, "addAppsWithSections", int.class, List.class);
            Method setup = method(holder, "setup", View.class, Predicate.class);
            Method span = method(Class.forName(ROOT + "AllAppsGridAdapter$GridSpanSizer", false, cl), "getSpanSize", int.class);
            // Resolve fields before any hook becomes active. Unsupported builds fail closed.
            for (String name : new String[]{"mAdapterItems", "mApps", "mSearchResults", "mFastScrollerSections"}) field(model, name);
            field(base, "mApps"); field(item, "viewType"); field(bind.getParameterTypes()[0], "itemView");
            field(holder, "mAppsList"); field(holder, "mType");

            Method create = method(base, "onCreateViewHolder", ViewGroup.class, int.class);
            hooks.add(XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if ((Integer) p.args[1] != FOLDER_TYPE) return;
                    // The target APK erases ViewHolder's own constructor. Its
                    // stock blank-row branch constructs the concrete holder.
                    Object result = XposedBridge.invokeOriginalMethod(create, p.thisObject, new Object[]{p.args[0], 256});
                    ViewGroup parent = (ViewGroup) p.args[0];
                    FolderRow row = new FolderRow(parent.getContext(), "personal", -1, true);
                    row.setLayoutParams(new ViewGroup.LayoutParams(-1, row.expectedHeight()));
                    set(result, "itemView", row); p.setResult(result);
                }
            }));
            hooks.add(XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    View view = (View) get(p.args[0], "itemView");
                    if (!(view instanceof FolderRow)) return;
                    p.setResult(null); // Never hand our row to the stock icon binder.
                    Controller controller = controller(get(p.thisObject, "mApps"));
                    if (controller != null) ((FolderRow) view).bindProfile(controller.kind, controller.serial());
                }
            }));
            hooks.add(XposedBridge.hookMethod(span, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object adapter = get(p.thisObject, "this$0");
                    List<?> items = (List<?>) get(get(adapter, "mApps"), "mAdapterItems");
                    int position = (Integer) p.args[0];
                    if (position >= 0 && position < items.size() && (Integer) get(items.get(position), "viewType") == FOLDER_TYPE)
                        p.setResult(get(get(adapter, "mGridLayoutMgr"), "mSpanCount"));
                }
            }));
            hooks.add(XposedBridge.hookMethod(setup, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!ready) return;
                    try {
                        int type = (Integer) get(p.thisObject, "mType");
                        if (type != 0 && type != 1) return; // Search model is never registered.
                        Object list = get(p.thisObject, "mAppsList");
                        Controller old = controller(list); models.remove(list); if (old != null) old.dispose();
                        Controller controller = new Controller(list, (View) p.args[0], type == 0 ? "personal" : "work");
                        models.put(list, new WeakReference<>(controller)); controller.start();
                    } catch (Exception error) { log(error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(addApps, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!ready) return;
                    try {
                        Controller controller = controller(p.thisObject);
                        // Private Space calls use a different list. No mutations to mApps,
                        // AllAppsStore, search results or profile predicates.
                        if (controller == null || p.args[1] != get(p.thisObject, "mApps")
                                || !((List<?>) get(p.thisObject, "mSearchResults")).isEmpty()) return;
                        List<Folders.Folder> folders = controller.visibleFolders();
                        if (folders.isEmpty()) return;
                        List<?> original = (List<?>) p.args[1];
                        List<Object> filtered = new ArrayList<>();
                        long serial = controller.serial();
                        for (Object app : original) {
                            ComponentName component = (ComponentName) get(app, "componentName");
                            UserHandle user = (UserHandle) get(app, "user");
                            long appSerial = controller.context.getSystemService(android.os.UserManager.class).getSerialNumberForUser(user);
                            boolean hidden = appSerial == serial && component != null
                                    && Folders.shouldHide(controller.keep, folders, controller.kind, appSerial,
                                            Profiles.personalSerial(controller.context), component.flattenToString());
                            if (!hidden) filtered.add(app);
                        }
                        // Prepare all reflection before touching the live adapter collection.
                        Object folderItem = createItem.newInstance(FOLDER_TYPE);
                        List<?> sections = (List<?>) get(p.thisObject, "mFastScrollerSections");
                        List<Field> positions = new ArrayList<>();
                        for (Object section : sections) positions.add(field(section.getClass(), "position"));
                        @SuppressWarnings("unchecked") List<Object> items = (List<Object>) get(p.thisObject, "mAdapterItems");
                        items.add(0, folderItem);
                        for (int i = 0; i < sections.size(); i++) positions.get(i).setInt(sections.get(i), positions.get(i).getInt(sections.get(i)) + 1);
                        p.args[0] = (Integer) p.args[0] + 1;
                        p.args[1] = filtered;
                    } catch (Exception error) { log(error); }
                }
            }));
            ready = true;
            XposedBridge.log("PixelLauncherFolders: profile grid hooks installed");
        } catch (Throwable error) {
            ready = false;
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            log(error);
        }
    }

    private static final class Controller implements View.OnAttachStateChangeListener {
        final WeakReference<Object> model;
        final View recycler;
        final Context context;
        final String kind;
        final Handler main = new Handler(Looper.getMainLooper());
        List<Folders.Folder> folders = new ArrayList<>();
        boolean keep = true, registered, loading, again, disposed;
        final ContentObserver observer = new ContentObserver(main) { @Override public void onChange(boolean self) { reload(); } };
        Controller(Object model, View view, String kind) {
            this.model = new WeakReference<>(model); recycler = view; context = view.getContext(); this.kind = kind;
        }
        long serial() throws Exception {
            Object list = model.get(); if (list == null) return -2;
            if (kind.equals("personal")) return Profiles.personalSerial(context);
            Object manager = get(list, "mWorkProviderManager");
            UserHandle user = (UserHandle) manager.getClass().getMethod("getProfileUser").invoke(manager);
            return context.getSystemService(android.os.UserManager.class).getSerialNumberForUser(user);
        }
        List<Folders.Folder> visibleFolders() throws Exception {
            long serial = serial();
            Profiles profile = Profiles.resolve(context, kind, serial);
            if (!profile.usable(context)) return new ArrayList<>();
            List<Folders.Folder> result = new ArrayList<>();
            for (Folders.Folder folder : folders) if (Folders.matches(folder, kind, serial, Profiles.personalSerial(context))) result.add(folder);
            return result;
        }
        void start() { recycler.addOnAttachStateChangeListener(this); if (recycler.isAttachedToWindow()) onViewAttachedToWindow(recycler); }
        void dispose() { disposed = true; recycler.removeOnAttachStateChangeListener(this); onViewDetachedFromWindow(recycler); }
        public void onViewAttachedToWindow(View view) {
            try { context.getContentResolver().registerContentObserver(Folders.URI, false, observer); registered = true; }
            catch (RuntimeException error) { log(error); }
            reload();
        }
        public void onViewDetachedFromWindow(View view) {
            if (registered) { context.getContentResolver().unregisterContentObserver(observer); registered = false; }
        }
        void reload() {
            if (disposed || !recycler.isAttachedToWindow()) return;
            if (loading) { again = true; return; } loading = true;
            FolderRow.IO.execute(() -> {
                List<Folders.Folder> next = new ArrayList<>(); boolean option = true;
                try (Cursor cursor = context.getContentResolver().query(Folders.URI, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String json = cursor.getString(0); next = Folders.parse(json); option = Folders.keepInDrawer(json);
                    }
                } catch (Exception error) { log(error); }
                List<Folders.Folder> value = next; boolean keepValue = option;
                main.post(() -> {
                    loading = false;
                    if (disposed) return;
                    folders = value; keep = keepValue;
                    Object list = model.get();
                    if (list != null && recycler.isAttachedToWindow()) {
                        try { list.getClass().getMethod("updateAdapterItems").invoke(list); }
                        catch (Exception error) { log(error); }
                    }
                    if (again) { again = false; reload(); }
                });
            });
        }
    }
}
