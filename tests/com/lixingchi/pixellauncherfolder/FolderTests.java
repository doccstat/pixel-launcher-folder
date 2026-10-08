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
    private String mode;
    public interface FakeCloseable { void close(); }
    public interface FakeDraggable {
        int getViewType();
        void getSourceVisualDragBounds(android.graphics.Rect rect);
        void getWorkspaceVisualDragBounds(android.graphics.Rect rect);
        FakeCloseable prepareDrawDragView();
    }
    private static class FakeItem {
        private final android.os.UserHandle user;
        FakeItem(android.os.UserHandle user) { this.user = user; }
    }
    private static final class FakeApp extends FakeItem {
        private final android.content.ComponentName componentName;
        FakeApp(android.content.ComponentName component, android.os.UserHandle user) {
            super(user); componentName = component;
        }
    }
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments == null ? null : arguments.getString("mode");
        start();
    }
    private void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason); checks++;
    }
    private void reject(String json) throws Exception {
        try { Folders.parse(json); throw new AssertionError("Accepted malformed folders"); }
        catch (org.json.JSONException expected) { checks++; }
    }
    private long ownerSerial(Context context) { return Profiles.personalSerial(context); }

    private View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    @Override public void onStart() {
        Context context = getTargetContext();
        if ("inspect".equals(mode) || "repair".equals(mode)) {
            Bundle diagnostic = new Bundle();
            try {
                diagnostic.putString("before", Folders.read(context));
                diagnostic.putString("backup_tree", String.valueOf(Folders.backupTree(context)));
                if ("repair".equals(mode)) Folders.repairBackupArtifacts(context);
                diagnostic.putString("after", Folders.read(context));
                try (Cursor cursor = context.getContentResolver().query(Folders.URI, null, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new AssertionError("Provider unavailable");
                    diagnostic.putString("provider", cursor.getString(0));
                }
                finish(Activity.RESULT_OK, diagnostic);
            } catch (Throwable error) {
                diagnostic.putString("stream", android.util.Log.getStackTraceString(error));
                finish(Activity.RESULT_CANCELED, diagnostic);
            }
            return;
        }
        android.content.SharedPreferences realPreferences = context.getSharedPreferences("folders", Context.MODE_PRIVATE);
        java.util.Map<String, ?> realBefore = new java.util.HashMap<>(realPreferences.getAll());
        Folders.testing = true;
        android.content.SharedPreferences preferences = Folders.preferences(context);
        preferences.edit().clear().commit();
        boolean existed = preferences.contains("json"); String original = Folders.read(context);
        boolean backupExisted = preferences.contains("backup_tree");
        String originalBackup = preferences.getString("backup_tree", null);
        preferences.edit().remove("backup_tree").commit();
        Activity activity = null;
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            check(GridSizing.appSpan(12, 4) == 3, "Outer drawer app occupies three internal spans");
            check(GridSizing.appSpan(12, 6) == 2, "Unfolded drawer app occupies two internal spans");
            check(GridSizing.appSpan(4, 4) == 1, "Unmultiplied app grid remains one span");
            check(Folders.parse("{\"version\":1,\"folders\":[]}").isEmpty(), "Empty config");
            check(Folders.preferences(context) != realPreferences, "Instrumentation uses separate durable state");
            android.content.ClipData clip = AppEntry.activityClip("Test", null, android.os.Process.myUserHandle());
            check(clip.getDescription().hasMimeType("application/vnd.android.activity"), "Shell activity drag MIME type");
            check(clip.getItemAt(0).getIntent().hasExtra("android.intent.extra.PENDING_INTENT"),
                    "Drag payload uses the WM Shell pending-intent key");
            check(android.os.Process.myUserHandle().equals(clip.getItemAt(0).getIntent().getParcelableExtra(android.content.Intent.EXTRA_USER)),
                    "Drag payload preserves exact user");
            check((AppEntry.activityDragFlags() & android.view.View.DRAG_FLAG_GLOBAL) != 0
                    && (AppEntry.activityDragFlags() & android.view.View.DRAG_FLAG_OPAQUE) != 0
                    && (AppEntry.activityDragFlags() & (1 << 11)) != 0,
                    "Taskbar-compatible drag flags are present");
            android.content.ComponentName dragComponent = new android.content.ComponentName("example", "example.Main");
            android.os.UserHandle personal = android.os.Process.myUserHandle();
            android.os.UserHandle work = android.os.UserHandle.getUserHandleForUid(1000000);
            FakeApp personalApp = new FakeApp(dragComponent, personal), workApp = new FakeApp(dragComponent, work);
            Object[] store = {personalApp, workApp};
            check(LauncherDrag.findApp(store, dragComponent, personal) == personalApp, "Home drag selects exact Personal app");
            check(LauncherDrag.findApp(store, dragComponent, work) == workApp, "Home drag selects exact Work app");
            check(LauncherDrag.findApp(new Object[]{personalApp}, dragComponent, work) == null,
                    "Missing Work app never falls back to Personal");
            check(LauncherDrag.findApp(store, new android.content.ComponentName("other", "other.Main"), personal) == null,
                    "Missing drag component is rejected");
            check(AppEntry.homeContext(context) == null, "Settings never gains native launcher drag access");
            android.graphics.Rect originalBounds = new android.graphics.Rect(0, 0, 48, 48);
            FakeDraggable source = (FakeDraggable) LauncherDrag.draggable(FakeDraggable.class, originalBounds);
            check(source.getViewType() == 0, "Native controller gets a non-null draggable icon source");
            originalBounds.setEmpty();
            android.graphics.Rect sourceBounds = new android.graphics.Rect();
            source.getSourceVisualDragBounds(sourceBounds);
            check(sourceBounds.equals(new android.graphics.Rect(0, 0, 48, 48)), "Draggable captures source icon bounds");
            source.getWorkspaceVisualDragBounds(sourceBounds);
            check(sourceBounds.width() == 48, "Workspace drag bounds match the icon");
            check(source.prepareDrawDragView() != null, "Drag draw preparation supplies a closeable");
            source.prepareDrawDragView().close();
            check(source.equals(source) && !source.equals(null) && source.hashCode() == System.identityHashCode(source),
                    "Draggable proxy retains identity semantics");
            View dragLayer = new View(context) {
                @Override public void getLocationOnScreen(int[] position) { position[0] = 25; position[1] = 50; }
            };
            android.view.MotionEvent motion = android.view.MotionEvent.obtain(10, 20,
                    android.view.MotionEvent.ACTION_UP, 300, 400, 0);
            motion.offsetLocation(-200, -300); // Popup-local (100,100), raw screen (300,400).
            android.view.MotionEvent translated = LauncherDrag.inLayer(motion, dragLayer);
            try {
                check(translated.getX() == 275 && translated.getY() == 350, "Popup gesture maps into DragLayer coordinates");
                check(translated.getActionMasked() == android.view.MotionEvent.ACTION_UP, "Finger-up is forwarded for dropping");
                check(translated.getDownTime() == motion.getDownTime(), "Drag gesture timing is preserved");
                check(motion.getX() == 100 && motion.getY() == 100, "Forwarding does not mutate popup input");
            } finally { translated.recycle(); motion.recycle(); }
            reject("{\"version\":2,\"folders\":[]}"); reject("{}"); reject("not-json");
            List<Folders.Folder> list = new ArrayList<>();
            list.add(Folders.Folder.create(" Test ", Arrays.asList(Folders.PACKAGE + "/.MainActivity", Folders.PACKAGE + "/.MainActivity")));
            List<Folders.Folder> normalized = Folders.parse(Folders.encode(list));
            check(normalized.get(0).name.equals("Test"), "Names trimmed");
            check(normalized.get(0).apps.size() == 1, "Duplicate components removed");
            check(normalized.get(0).apps.get(0).equals(Folders.PACKAGE + "/" + Folders.PACKAGE + ".MainActivity"), "Components normalized");
            Folders.Folder artifact = Folders.Folder.create(".pixel-launcher-folders.json", new ArrayList<>());
            Folders.Folder numberedArtifact = Folders.Folder.create(".pixel-launcher-folders.json (4)", new ArrayList<>());
            Folders.Folder real = Folders.Folder.create("Finance", Arrays.asList(normalized.get(0).apps.get(0)));
            List<Folders.Folder> repaired = Folders.withoutBackupArtifacts(Arrays.asList(artifact, numberedArtifact, real));
            check(repaired.size() == 1 && repaired.get(0).id.equals(real.id), "Repair removes empty metadata folders");
            check(Folders.withoutBackupArtifacts(normalized).size() == 1, "Ordinary folders are not removed");
            Folders.Folder userTest = Folders.Folder.create("Test", Arrays.asList("com.example/com.example.Main"));
            check(Folders.withoutBackupArtifacts(Arrays.asList(artifact, userTest)).size() == 1, "User Test folder is preserved");
            Folders.Folder nonempty = Folders.Folder.create(artifact.name, real.apps);
            check(Folders.withoutBackupArtifacts(Arrays.asList(numberedArtifact, nonempty)).size() == 1, "Nonempty similarly named folder is preserved");
            check(Folders.withoutBackupArtifacts(repaired).get(0).id.equals(real.id), "Repair is idempotent");
            Folders.Folder other = Folders.Folder.create("Other", Arrays.asList(normalized.get(0).apps.get(0)));
            List<Folders.Folder> memberships = Arrays.asList(normalized.get(0), other);
            check(Folders.assignedElsewhere(memberships, normalized.get(0), "personal", ownerSerial(context),
                    ownerSerial(context), normalized.get(0).apps.get(0)), "Other-folder membership is visible");
            check(Folders.assignedElsewhere(memberships, other, "personal", ownerSerial(context),
                    ownerSerial(context), normalized.get(0).apps.get(0)), "Membership in a different folder remains visible");
            check(!Folders.assignedElsewhere(Arrays.asList(other), other, "personal", ownerSerial(context),
                    ownerSerial(context), normalized.get(0).apps.get(0)), "Current-folder membership is not marked elsewhere");
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
            String persisted = context.getSharedPreferences("folders-instrumentation", Context.MODE_PRIVATE).getString("json", null);
            check(persisted != null && persisted.contains(normalized.get(0).id), "Preferences persistence");
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
            check(Folders.keepInDrawer("{\"version\":1,\"folders\":[]}"), "Legacy defaults keep apps");
            check(normalized.get(0).profile.equals("personal") && normalized.get(0).serial == -1, "Legacy folders remain Personal");
            Folders.setKeepInDrawer(context, false);
            check(!Folders.keepInDrawer(Folders.read(context)), "Folder-only option persisted");
            Folders.save(context, normalized);
            check(!Folders.keepInDrawer(Folders.read(context)), "Folder edits preserve display option");
            Folders.setKeepInDrawer(context, true);
            check(Folders.keepInDrawer(Folders.read(context)), "Keep-apps option restored");
            long owner = Profiles.personalSerial(context);
            String component = normalized.get(0).apps.get(0);
            List<Folders.Folder> isolated = Arrays.asList(Folders.Folder.create("Work", "work", 100, Arrays.asList(component)));
            check(Folders.shouldHide(false, isolated, "work", 100, owner, component), "Folder-only hides same profile copy");
            check(!Folders.shouldHide(true, isolated, "work", 100, owner, component), "Keep mode preserves app list copy");
            check(!Folders.shouldHide(false, isolated, "personal", owner, owner, component), "Work membership never hides Personal copy");
            check(!Folders.shouldHide(false, isolated, "work", 101, owner, component), "Removed/recreated profile serial never matches");
            check(!Folders.shouldHide(false, isolated, "work", 100, owner, "other/.Activity"), "Unassigned apps preserved");
            check(Folders.parse(Folders.encode(isolated)).get(0).serial == 100, "Work serial round trip");
            Folders.Folder order = Folders.Folder.create("Order", Arrays.asList(
                    "z.pkg/.Z", "a.pkg/.A", "m.pkg/.M"));
            java.util.Map<String, AppEntry> labels = new java.util.HashMap<>();
            labels.put("z.pkg/.Z", new AppEntry("z.pkg/.Z"));
            labels.put("a.pkg/.A", new AppEntry("a.pkg/.A"));
            labels.put("m.pkg/.M", new AppEntry("m.pkg/.M"));
            check(FolderRow.sortedApps(order, labels).get(0).equals("a.pkg/.A"), "Folder apps sort by label");
            android.widget.CheckedTextView indicator = new android.widget.CheckedTextView(context);
            MainActivity.membershipIndicator(context, indicator, true, false);
            check(indicator.isChecked() && indicator.getCheckMarkTintList().getDefaultColor() == Ui.pickerActive(context),
                    "Current-folder apps use the active blue checkmark");
            MainActivity.membershipIndicator(context, indicator, false, true);
            check(indicator.isChecked() && indicator.getCheckMarkTintList().getDefaultColor() == Ui.pickerElsewhere(context),
                    "Other-folder apps use the inactive gray checkmark");
            MainActivity.membershipIndicator(context, indicator, false, false);
            check(!indicator.isChecked() && indicator.getCheckMarkTintList().getDefaultColor() == Ui.pickerElsewhere(context),
                    "Unassigned apps have no active checkmark");
            reject("{\"version\":1,\"folders\":[{\"id\":\"a\",\"name\":\"A\",\"profile\":\"private\",\"serial\":11,\"apps\":[]}]}");
            reject("{\"version\":1,\"keepInDrawer\":\"false\",\"folders\":[]}");
            List<Profiles> profiles = Profiles.available(context);
            check(profiles.stream().anyMatch(p -> p.kind.equals("personal")), "Personal profile discovered");
            for (Profiles profile : profiles) {
                check(profile.kind.equals("personal") || profile.kind.equals("work"), "Only Personal/managed Work exposed");
                if (profile.usable(context)) for (AppEntry app : AppEntry.list(context, profile.kind, profile.serial))
                    if (!app.info.getUser().equals(profile.user)) throw new AssertionError("Cross-profile picker leak");
            }
            checks++;
            result.putString("profiles", "Accessible profiles: " + profiles);
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity screen = activity;
            waitForIdleSync();
            runOnMainSync(() -> {
                check(find(screen.getWindow().getDecorView(), "Your folders") != null, "Editor opened");
                check(find(screen.getWindow().getDecorView(), "Test") != null, "Saved folder rendered");
                View settings = find(screen.getWindow().getDecorView(), "Settings"); check(settings != null && settings.performClick(), "Settings navigation");
            });
            waitForIdleSync();
            runOnMainSync(() -> check(find(screen.getWindow().getDecorView(), "Keep folder apps in All apps") != null, "Display option rendered"));
            // Exercise the actual row independently of Vector. Draw only our view,
            // including when the lockscreen covers the Activity.
            final FolderRow[] preview = new FolderRow[1];
            runOnMainSync(() -> {
                preview[0] = new FolderRow(screen, "personal", Profiles.personalSerial(context), false);
                screen.setContentView(preview[0]);
            });
            waitForIdleSync();
            FolderRow.IO.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            waitForIdleSync();
            runOnMainSync(() -> {
                check(find(preview[0], "Test") != null, "Personal folder preview loaded");
                android.widget.LinearLayout content = (android.widget.LinearLayout) preview[0].getChildAt(0);
                android.view.ViewGroup card = (android.view.ViewGroup) content.getChildAt(0);
                View icons = card.getChildAt(0);
                check(((android.graphics.drawable.GradientDrawable) icons.getBackground()).getShape()
                        == android.graphics.drawable.GradientDrawable.OVAL, "Circular folder background");
                check(((android.view.ViewGroup) icons).getChildCount() == 4, "Four preview positions");
                int width = Ui.dp(context, 400), height = preview[0].expectedHeight();
                preview[0].measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                preview[0].layout(0, 0, width, height);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(Ui.dark(context) ? 0xff17191e : 0xfffafaff);
                preview[0].draw(canvas);
                try (java.io.FileOutputStream output = context.openFileOutput("test-preview.png", Context.MODE_PRIVATE)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                bitmap.recycle();
            });
            runOnMainSync(() -> {
                preview[0] = new FolderRow(screen, "personal", Profiles.personalSerial(context), false, true);
                preview[0].bindFolderIndex(0);
                screen.setContentView(preview[0]);
            });
            waitForIdleSync();
            FolderRow.IO.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            waitForIdleSync();
            runOnMainSync(() -> {
                TextView label = (TextView) find(preview[0], "Test");
                check(label != null, "Single drawer cell loaded");
                label.setText("Finance");
                ViewGroup card = (ViewGroup) label.getParent();
                View icons = card.getChildAt(0);
                // Reproduce a multiplied grid on both display column counts.
                // The former one-span bug yields 85 px and clips the circle.
                for (int columns : new int[]{4, 6}) {
                    int availableWidth = columns == 4 ? 1020 : 1968;
                    int width = availableWidth * GridSizing.appSpan(12, columns) / 12;
                    int height = preview[0].expectedHeight();
                    preview[0].measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    preview[0].layout(0, 0, width, height);
                    check(icons.getWidth() == icons.getHeight(), "Drawer preview stays round at " + columns + " columns");
                    check(icons.getLeft() >= 0 && icons.getRight() <= card.getWidth(), "Complete circle inside drawer cell");
                    check(label.getLayout().getLineCount() == 1 && label.getLayout().getEllipsisCount(0) == 0,
                            "Finance label fits without clipping or wrapping");
                    check(label.getBottom() <= card.getHeight(), "Complete label inside drawer cell");
                    check(width * columns == availableWidth, "Folder and following apps fill one row");
                }
            });
            // The floating taskbar drawer supplies a themed window context,
            // not an Activity. A popup must obtain its token from the attached
            // anchor rather than attempting to show an Activity-only dialog.
            runOnMainSync(() -> {
                android.view.ContextThemeWrapper windowContext = new android.view.ContextThemeWrapper(
                        screen.getApplicationContext(), android.R.style.Theme_Material_Light_NoActionBar);
                preview[0] = new FolderRow(windowContext, "personal", Profiles.personalSerial(context), false, true);
                preview[0].bindFolderIndex(0); screen.setContentView(preview[0]);
            });
            waitForIdleSync(); FolderRow.IO.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS); waitForIdleSync();
            runOnMainSync(() -> {
                try {
                    TextView label = (TextView) find(preview[0], "Test");
                    check(label != null, "Folder rendered with non-Activity context");
                    View card = (View) label.getParent();
                    check(card.performClick(), "Folder tap handled");
                    java.lang.reflect.Field popupField = FolderRow.class.getDeclaredField("folderPopup"); popupField.setAccessible(true);
                    android.widget.PopupWindow popup = (android.widget.PopupWindow) popupField.get(preview[0]);
                    check(popup != null && popup.isShowing(), "Folder popup attaches without Activity context");
                    check(find(popup.getContentView(), "Test") != null, "Popup shows folder title");
                    check(find(popup.getContentView(), "Close").performClick() && !popup.isShowing(), "Close dismisses popup");
                    card.performClick(); popup = (android.widget.PopupWindow) popupField.get(preview[0]);
                    screen.setContentView(new android.widget.FrameLayout(screen));
                    check(!popup.isShowing(), "Detaching drawer dismisses popup");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            check(realBefore.equals(realPreferences.getAll()), "User preferences untouched by tests");
            result.putString("stream", "PASS: " + checks + " checks; editor and preview opened; user preferences untouched.\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
        } finally {
            android.content.SharedPreferences.Editor edit = preferences.edit();
            if (existed) edit.putString("json", original); else edit.remove("json");
            if (backupExisted) edit.putString("backup_tree", originalBackup); else edit.remove("backup_tree");
            if (!edit.commit()) { code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL: preference restoration failed"); }
            if (activity != null) { Activity close = activity; runOnMainSync(close::finish); }
        }
        finish(code, result);
    }
}
