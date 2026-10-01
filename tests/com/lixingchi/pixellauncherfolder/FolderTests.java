package com.lixingchi.pixellauncherfolder;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicitly invoked test variant only. Restores exact original app preferences in finally. */
public final class FolderTests extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason); checks++;
    }
    private void reject(String json) throws Exception {
        try { Folders.parse(json); throw new AssertionError("Accepted malformed folders"); }
        catch (org.json.JSONException expected) { checks++; }
    }
    private View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    @Override public void onStart() {
        Context context = getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("folders", Context.MODE_PRIVATE);
        boolean existed = preferences.contains("json"); String original = Folders.read(context);
        Activity activity = null;
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            check(Folders.parse("{\"version\":1,\"folders\":[]}").isEmpty(), "Empty config");
            reject("{\"version\":2,\"folders\":[]}"); reject("{}"); reject("not-json");
            List<Folders.Folder> list = new ArrayList<>();
            list.add(Folders.Folder.create(" Test ", Arrays.asList(Folders.PACKAGE + "/.MainActivity", Folders.PACKAGE + "/.MainActivity")));
            List<Folders.Folder> normalized = Folders.parse(Folders.encode(list));
            check(normalized.get(0).name.equals("Test"), "Names trimmed");
            check(normalized.get(0).apps.size() == 1, "Duplicate components removed");
            check(normalized.get(0).apps.get(0).equals(Folders.PACKAGE + "/" + Folders.PACKAGE + ".MainActivity"), "Components normalized");
            String duplicate = "{\"version\":1,\"folders\":[{\"id\":\"same\",\"name\":\"A\",\"apps\":[]},{\"id\":\"same\",\"name\":\"B\",\"apps\":[]}]";
            reject(duplicate);
            reject("{\"version\":1,\"folders\":[{\"id\":\"a\",\"name\":\"   \",\"apps\":[]}]}");
            reject("{\"version\":1,\"folders\":[{\"id\":\"a\",\"name\":\"Apps\",\"apps\":[\"invalid\"]}]}");
            List<Folders.Folder> tooMany = new ArrayList<>();
            for (int i = 0; i < 25; i++) tooMany.add(Folders.Folder.create("Folder", new ArrayList<>()));
            try { Folders.encode(tooMany); throw new AssertionError("Folder bound"); } catch (org.json.JSONException expected) { checks++; }
            try { Folders.encode(Arrays.asList(Folders.Folder.create("Apps", java.util.Collections.nCopies(101, Folders.PACKAGE + "/.MainActivity")))); throw new AssertionError("App bound"); }
            catch (org.json.JSONException expected) { checks++; }
            Folders.save(context, normalized);
            check(Folders.parse(Folders.read(context)).get(0).id.equals(normalized.get(0).id), "Persistence round trip");
            try (Cursor cursor = context.getContentResolver().query(Folders.URI, null, null, null, null)) {
                check(cursor != null && cursor.moveToFirst(), "Own provider readable");
                check(Folders.parse(cursor.getString(0)).get(0).name.equals("Test"), "Provider returns saved config");
            }
            try { context.getContentResolver().delete(Folders.URI, null, null); throw new AssertionError("Provider writable"); }
            catch (SecurityException expected) { checks++; }
            check(AppEntry.list(context).stream().anyMatch(e -> e.key.equals(normalized.get(0).apps.get(0))), "Own launcher activity visible");
            try { AppEntry.launch(context, Folders.PACKAGE + "/.NoSuchActivity"); throw new AssertionError("Arbitrary activity launched"); }
            catch (RuntimeException expected) { checks++; }
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity screen = activity;
            waitForIdleSync();
            runOnMainSync(() -> {
                check(find(screen.getWindow().getDecorView(), "Drawer folders") != null, "Editor opened");
                check(find(screen.getWindow().getDecorView(), "Test · 1") != null, "Saved folder rendered");
                View preview = find(screen.getWindow().getDecorView(), "Preview folder row");
                check(preview != null, "Preview button"); preview.performClick();
            });
            waitForIdleSync();
            // Read only our foreground view tree; no input to any other package.
            android.os.SystemClock.sleep(750);
            android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap != null) {
                try (java.io.FileOutputStream output = context.openFileOutput("test-preview.png", Context.MODE_PRIVATE)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                }
                bitmap.recycle();
            }
            result.putString("stream", "PASS: " + checks + " checks; editor and preview opened; original preferences restored.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
        } finally {
            android.content.SharedPreferences.Editor edit = preferences.edit();
            if (existed) edit.putString("json", original); else edit.remove("json");
            if (!edit.commit()) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: preference restoration failed"); }
            context.getContentResolver().notifyChange(Folders.URI, null);
            if (activity != null) { Activity close = activity; runOnMainSync(close::finish); }
        }
        finish(code, result);
    }
}
