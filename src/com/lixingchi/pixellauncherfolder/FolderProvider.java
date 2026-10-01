package com.lixingchi.pixellauncherfolder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import java.util.Arrays;

/** Cross-process read access for the scoped launcher only; no exported write path. */
public final class FolderProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private void authorize(Uri uri) {
        if (!Folders.URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        int caller = Binder.getCallingUid();
        if (caller == Process.myUid()) return;
        if (android.os.UserHandle.getUserHandleForUid(caller).equals(Process.myUserHandle())) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(caller);
            if (packages != null && Arrays.asList(packages).contains(Folders.LAUNCHER)) return;
        }
        throw new SecurityException("Folder configuration is private to Pixel Launcher");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        authorize(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"});
        cursor.addRow(new Object[]{Folders.read(getContext())});
        cursor.setNotificationUri(getContext().getContentResolver(), Folders.URI);
        return cursor;
    }
    @Override public String getType(Uri uri) { authorize(uri); return "vnd.android.cursor.item/vnd." + Folders.PACKAGE + ".folders"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new SecurityException("Read only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new SecurityException("Read only"); }
}
