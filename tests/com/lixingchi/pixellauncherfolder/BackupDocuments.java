package com.lixingchi.pixellauncherfolder;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only: exercises the real SAF import/export path in disposable app cache. */
public final class BackupDocuments extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        File root = new File(getContext().getCacheDir(), "backup-tests");
        root.mkdirs();
        if (id.equals("root")) return root;
        if (id.isEmpty() || id.equals(".") || id.equals("..") || id.contains("/") || id.contains("\\"))
            throw new FileNotFoundException(id);
        return new File(root, id);
    }
    private MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS} : projection);
    }
    private void add(MatrixCursor cursor, File file, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = id;
            if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = file.getName();
            if (column.equals(Document.COLUMN_MIME_TYPE)) value = file.isDirectory() ? Document.MIME_TYPE_DIR : "text/plain";
            if (column.equals(Document.COLUMN_FLAGS)) value = file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{Root.COLUMN_ROOT_ID} : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = documents(projection); add(result, file(id), id); return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor result = documents(projection);
        File[] children = file(id).listFiles();
        if (children != null) for (File child : children) add(result, child, child.getName());
        return result;
    }
    @Override public boolean isChildDocument(String parent, String child) { return parent.equals("root") && !child.equals("root"); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException(parent);
        try { file(name).createNewFile(); return name; }
        catch (java.io.IOException error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.equals("root") || !file(id).delete()) throw new FileNotFoundException(id);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
}
