package com.lixingchi.pixellauncherfolder;

import android.view.View;
import android.widget.LinearLayout;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Uses the launcher's own plugin-row adapter for height, clipping and scrolling. */
public final class LauncherHook implements IXposedHookLoadPackage {
    private static final String HEADER = "com.android.launcher3.allapps.FloatingHeaderView";
    private static final String PLUGIN = "com.android.systemui.plugins.AllAppsRow";
    private static final String MARKER = Folders.PACKAGE + ".row";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!Folders.LAUNCHER.equals(pkg.packageName) || !Folders.LAUNCHER.equals(pkg.processName)) return;
        try {
            Class<?> header = Class.forName(HEADER, false, pkg.classLoader);
            Class<?> plugin = Class.forName(PLUGIN, false, pkg.classLoader);
            // Resolve the entire contract before installing hooks. Unknown builds stay stock.
            Method connect = header.getMethod("onPluginConnected", plugin);
            Method disconnect = header.getMethod("onPluginDisconnected", plugin);
            plugin.getMethod("setup", android.view.ViewGroup.class);
            plugin.getMethod("getExpectedHeight");
            header.getDeclaredMethod("onFinishInflate");
            header.getDeclaredMethod("setActiveRV", int.class);
            XposedHelpers.findAndHookMethod(header, "onFinishInflate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    LinearLayout host = (LinearLayout) p.thisObject;
                    if (host.findViewWithTag(MARKER) != null) return;
                    FolderRow row = new FolderRow(host.getContext(), true);
                    row.setTag(MARKER);
                    Object adapter = Proxy.newProxyInstance(pkg.classLoader, new Class<?>[]{plugin}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setup": return row;
                            case "getExpectedHeight": return row.expectedHeight();
                            case "setOnHeightUpdatedListener":
                            case "onCreate":
                            case "onDestroy": return null;
                            case "getVersion": return 1;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "toString": return "PixelLauncherFoldersRow";
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
                    try {
                        connect.invoke(host, adapter);
                        XposedBridge.log("PixelLauncherFolders: header row attached");
                    } catch (Throwable error) {
                        try { disconnect.invoke(host, adapter); } catch (Throwable ignored) { }
                        if (row.getParent() == host) host.removeView(row);
                        XposedBridge.log("PixelLauncherFolders: skipped incompatible header");
                        XposedBridge.log(error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(header, "setActiveRV", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View row = ((View) p.thisObject).findViewWithTag(MARKER);
                    if (row instanceof FolderRow) ((FolderRow) row).setPersonalPage(((Integer) p.args[0]) == 0);
                }
            });
            XposedBridge.log("PixelLauncherFolders: hook installed");
        } catch (Throwable error) {
            XposedBridge.log("PixelLauncherFolders: unsupported launcher; no row installed");
            XposedBridge.log(error);
        }
    }
}
