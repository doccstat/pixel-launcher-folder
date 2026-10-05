package com.lixingchi.pixellauncherfolder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_BACKUP_TREE = 41;
    private List<Folders.Folder> folders;
    private LinearLayout body, page;
    private boolean settings;
    private Dialog editorDialog, pickerDialog;
    private final android.window.OnBackInvokedCallback settingsBack = () -> { settings = false; render(); };
    private final Map<String, AppEntry> icons = new HashMap<>();
    private int iconGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = state != null && state.getBoolean("settings");
        try { Folders.repairBackupArtifacts(this); } catch (Exception ignored) { }
        Folders.synchronizeFromBackup(this);
        try { folders = Folders.parse(Folders.read(this)); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Could not read folders")
                .setMessage("Existing data has been kept. " + e.getMessage())
                .setPositiveButton("Close", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        page = Ui.column(this, 0); page.setBackgroundColor(Ui.background(this));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(page); Ui.applySystemBars(getWindow(), this); page.requestApplyInsets();
        render(); loadIcons();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("settings", settings); super.onSaveInstanceState(state);
    }

    private void render() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(settingsBack);
        if (settings) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, settingsBack);
        page.removeAllViews();
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView brand = Ui.text(this, "Drawer folders", 20); brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button navigation = Ui.textButton(this, settings ? "Folders" : "Settings", () -> { settings = !settings; render(); });
        bar.addView(navigation); page.addView(bar);
        page.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(this);
        body = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                int capped = Math.min(View.MeasureSpec.getSize(width), Ui.dp(MainActivity.this, 760));
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.EXACTLY), height);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL); int margin = Ui.dp(this, 24);
        body.setPadding(margin, margin, margin, margin);
        center.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(center); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (settings) renderSettings(); else renderFolders();
    }

    private void renderFolders() {
        body.addView(Ui.headline(this, "Your folders")); body.addView(Ui.spacer(this, 8));
        body.addView(Ui.secondary(this, "Tap a folder to edit. Apps inside always appear A–Z.", 15));
        body.addView(Ui.spacer(this, 24));
        if (folders.isEmpty()) {
            LinearLayout empty = Ui.column(this, 24); empty.setBackground(Ui.rounded(this));
            empty.addView(Ui.text(this, "A place for every app", 22)); empty.addView(Ui.spacer(this, 8));
            empty.addView(Ui.secondary(this, "Group your everyday apps into a folder. Personal and Work stay separate.", 15));
            body.addView(empty);
        } else {
            LinkedHashSet<String> profileGroups = new LinkedHashSet<>();
            for (Folders.Folder f : folders) profileGroups.add(f.profile + ":" + f.serial);
            for (String group : profileGroups) {
                Folders.Folder representative = null;
                for (Folders.Folder f : folders) if (group.equals(f.profile + ":" + f.serial)) { representative = f; break; }
                body.addView(Ui.sectionTitle(this, profileName(representative.profile)));
                body.addView(Ui.spacer(this, 12));
                for (Folders.Folder folder : folders) if (group.equals(folder.profile + ":" + folder.serial)) {
                    LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
                    row.addView(folderPreview(folder), new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
                    LinearLayout copy = Ui.column(this, 0); copy.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
                    TextView name = Ui.text(this, folder.name, 18); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    copy.addView(name); copy.addView(Ui.spacer(this, 4)); copy.addView(Ui.secondary(this, appCount(folder.apps.size()), 14));
                    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                    row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 16));
                    row.setFocusable(true); row.setContentDescription("Edit " + folder.name + ", " + appCount(folder.apps.size())); row.setOnClickListener(v -> edit(folder));
                    Button more = Ui.textButton(this, "⋮", () -> folderMenu(folder)); more.setTextSize(24);
                    more.setContentDescription("Actions for " + folder.name); row.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
                    body.addView(row); body.addView(Ui.divider(this), new LinearLayout.LayoutParams(-1, 1));
                }
                body.addView(Ui.spacer(this, 24));
            }
        }
        if (Folders.backupTree(this) == null) {
            body.addView(Ui.spacer(this, 16));
            body.addView(Ui.secondary(this, "No backup folder selected. Keep a readable copy in Files.", 14));
            body.addView(Ui.textButton(this, "Set up backup", () -> { settings = true; render(); }));
        }
        LinearLayout footer = Ui.column(this, 16);
        footer.addView(Ui.primaryButton(this, "+  New folder", () -> {
            if (folders.size() >= Folders.MAX_FOLDERS) { message("Use at most 24 folders"); return; } edit(null);
        })); page.addView(footer);
    }

    private View folderPreview(Folders.Folder folder) {
        android.widget.GridLayout preview = new android.widget.GridLayout(this); preview.setColumnCount(2); preview.setRowCount(2);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(Ui.surface(this)); preview.setBackground(circle);
        int padding = Ui.dp(this, 8); preview.setPadding(padding, padding, padding, padding);
        Map<String, AppEntry> profileIcons = new HashMap<>();
        for (String key : folder.apps) { AppEntry app = icons.get(folder.profile + ":" + folder.serial + ":" + key); if (app != null) profileIcons.put(key, app); }
        List<String> sorted = FolderRow.sortedApps(folder, profileIcons);
        for (int i = 0; i < 4; i++) {
            ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (i < sorted.size()) { AppEntry app = profileIcons.get(sorted.get(i)); if (app != null) try { icon.setImageDrawable(app.icon(this)); } catch (RuntimeException ignored) { } }
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(i / 2, 1, android.widget.GridLayout.FILL, 1f),
                    android.widget.GridLayout.spec(i % 2, 1, android.widget.GridLayout.FILL, 1f));
            params.width = 0; params.height = 0; params.setMargins(1, 1, 1, 1); preview.addView(icon, params);
        }
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return preview;
    }

    private void loadIcons() {
        final int generation = ++iconGeneration;
        List<Folders.Folder> snapshot = new ArrayList<>(folders);
        FolderRow.IO.execute(() -> {
            Map<String, AppEntry> loaded = new HashMap<>(); Set<String> groups = new HashSet<>();
            for (Folders.Folder folder : snapshot) {
                String group = folder.profile + ":" + folder.serial;
                if (!groups.add(group)) continue;
                try { for (AppEntry entry : AppEntry.list(this, folder.profile, folder.serial)) loaded.put(group + ":" + entry.key, entry); }
                catch (RuntimeException ignored) { }
            }
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && generation == iconGeneration) {
                icons.clear(); icons.putAll(loaded); if (!settings) render();
            } });
        });
    }

    private void folderMenu(Folders.Folder folder) {
        new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{"Edit folder", "Move earlier", "Move later", "Delete folder"}, (d, which) -> {
            if (which == 0) edit(folder);
            if (which == 1 || which == 2) {
                int index = folders.indexOf(folder), direction = which == 1 ? -1 : 1, target = index + direction;
                while (target >= 0 && target < folders.size() && (folders.get(target).serial != folder.serial || !folders.get(target).profile.equals(folder.profile))) target += direction;
                if (target < 0 || target >= folders.size()) { message(which == 1 ? "Already first in this profile" : "Already last in this profile"); return; }
                List<Folders.Folder> next = new ArrayList<>(folders); Collections.swap(next, index, target); save(next);
            }
            if (which == 3) confirmDelete(folder, () -> { });
        }).show();
    }

    private void renderSettings() {
        body.addView(Ui.headline(this, "Settings")); body.addView(Ui.spacer(this, 24));
        body.addView(Ui.sectionTitle(this, "App drawer")); body.addView(Ui.spacer(this, 12));
        Switch keep = new Switch(this); keep.setText("Keep folder apps in All apps"); keep.setTextColor(Ui.text(this)); keep.setTextSize(16); keep.setMinHeight(Ui.dp(this, 56));
        try { keep.setChecked(Folders.keepInDrawer(Folders.read(this))); } catch (Exception e) { message(e.getMessage()); }
        keep.setOnCheckedChangeListener((button, checked) -> { try { Folders.setKeepInDrawer(this, checked); } catch (Exception e) { message(e.getMessage()); render(); } });
        body.addView(keep); body.addView(Ui.secondary(this, "When off, assigned apps appear only in their folders. Launcher search still finds them.", 14));
        body.addView(Ui.spacer(this, 28)); body.addView(Ui.sectionTitle(this, "Backup & restore")); body.addView(Ui.spacer(this, 12));
        Uri tree = Folders.backupTree(this);
        body.addView(Ui.text(this, tree == null ? "Keep a copy in Files" : "Backup folder connected", 20)); body.addView(Ui.spacer(this, 8));
        body.addView(Ui.secondary(this, tree == null ? "Choose a folder you can see. Your folders are saved as editable .txt files, with a manifest for profiles and order." : backupName(tree) + "\nChanges sync here automatically. Import replaces your folder assignments, never your installed apps.", 14));
        body.addView(Ui.spacer(this, 12)); body.addView(Ui.button(this, tree == null ? "Choose backup folder" : "Change backup folder", this::chooseBackupFolder));
        if (tree != null) {
            body.addView(Ui.spacer(this, 8)); LinearLayout actions = new LinearLayout(this);
            actions.addView(Ui.button(this, "Export folders", this::exportFolders), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = Ui.dp(this, 8);
            actions.addView(Ui.button(this, "Import folders", this::importFolders), params); body.addView(actions);
        }
        body.addView(Ui.spacer(this, 28)); body.addView(Ui.sectionTitle(this, "Launcher setup")); body.addView(Ui.spacer(this, 12));
        body.addView(Ui.secondary(this, "1. Enable this module in Vector or LSPosed.\n2. Select Pixel Launcher only.\n3. Restart the launcher through its normal UI.", 15));
        body.addView(Ui.spacer(this, 12)); body.addView(Ui.secondary(this, "This app never changes module-manager settings or restarts other apps. Private Space is excluded.", 13));
        body.addView(Ui.spacer(this, 28)); body.addView(Ui.sectionTitle(this, "Preview")); body.addView(Ui.spacer(this, 8));
        for (Profiles profile : Profiles.available(this)) body.addView(Ui.textButton(this, "Preview " + profileName(profile.kind) + " folders", () -> {
            android.widget.HorizontalScrollView viewport = new android.widget.HorizontalScrollView(this);
            viewport.addView(new FolderRow(this, profile.kind, profile.serial, false));
            new AlertDialog.Builder(this).setTitle(profileName(profile.kind) + " folders").setView(viewport).setPositiveButton("Close", null).show();
        }));
    }

    private static String profileName(String kind) { return kind.equals("work") ? "Work" : "Personal"; }
    private static String appCount(int count) { return count + (count == 1 ? " app" : " apps"); }
    private String backupName(Uri tree) {
        try { return android.provider.DocumentsContract.getTreeDocumentId(tree).replace("primary:", "Internal storage / "); }
        catch (RuntimeException ignored) { return "Selected folder in Files"; }
    }
    private boolean save(List<Folders.Folder> next) {
        try { Folders.save(this, next); folders = next; render(); loadIcons(); return true; }
        catch (Exception e) { message(e.getMessage()); return false; }
    }
    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private void chooseBackupFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK_BACKUP_TREE);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_BACKUP_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData(); int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(tree, flags); Folders.setBackupTree(this, tree); render(); message("Backup folder selected. Choose Import or Export."); }
        catch (RuntimeException error) { message("Could not keep access to that folder"); }
    }
    private void exportFolders() {
        try { Folders.exportTree(this, folders); message("Folders exported"); }
        catch (Exception error) { message("Export failed: " + error.getMessage()); }
    }
    private void importFolders() {
        new AlertDialog.Builder(this).setTitle("Replace folder assignments?")
                .setMessage("Import reads your selected backup folder. Current assignments are replaced; installed apps are never changed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Import", (d, w) -> {
                    try { Folders.ImportResult imported = Folders.importTree(this); Folders.save(this, imported.folders, imported.keep);
                        folders = imported.folders; render(); loadIcons(); message(imported.skipped == 0 ? "Folders imported" : "Folders imported; unavailable profiles were skipped"); }
                    catch (Exception error) { message("Import failed: " + error.getMessage()); }
                }).show();
    }

    private Dialog sheet(LinearLayout panel) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        panel.setBackground(Ui.cardBackground(this)); dialog.setContentView(panel);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        int width = Math.min(Ui.dp(this, 560), getWindow().getDecorView().getWidth() - Ui.dp(this, 24));
        dialog.getWindow().setLayout(Math.max(Ui.dp(this, 240), width), -2); return dialog;
    }

    private void edit(Folders.Folder original) {
        List<Profiles> available = Profiles.available(this);
        if (available.isEmpty()) { message("No accessible profiles"); return; }
        long serial = original == null ? -2 : original.serial < 0 ? Profiles.personalSerial(this) : original.serial;
        int initial = 0;
        if (original != null) {
            boolean found = false;
            for (int i = 0; i < available.size(); i++) if (available.get(i).serial == serial && available.get(i).kind.equals(original.profile)) { initial = i; found = true; }
            if (!found) { available.add(new Profiles(null, serial, original.profile)); initial = available.size() - 1; }
        }
        LinearLayout panel = Ui.column(this, 24); panel.addView(Ui.text(this, original == null ? "New folder" : "Edit folder", 24)); panel.addView(Ui.spacer(this, 24));
        panel.addView(Ui.sectionTitle(this, "Folder name")); panel.addView(Ui.spacer(this, 8));
        EditText name = Ui.input(this, "e.g. Everyday"); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        name.setText(original == null ? "" : original.name); panel.addView(name); panel.addView(Ui.spacer(this, 20));
        panel.addView(Ui.sectionTitle(this, "Profile")); panel.addView(Ui.spacer(this, 8));
        android.widget.Spinner picker = new android.widget.Spinner(this); picker.setMinimumHeight(Ui.dp(this, 48));
        List<String> profileLabels = new ArrayList<>(); for (Profiles profile : available) profileLabels.add(profileName(profile.kind) + " · profile " + profile.serial);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profileLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); picker.setAdapter(adapter); picker.setSelection(initial); picker.setEnabled(original == null); panel.addView(picker);
        if (original != null) panel.addView(Ui.secondary(this, "A saved folder stays in its original profile.", 13));
        panel.addView(Ui.spacer(this, 20));
        Set<String> selected = new LinkedHashSet<>(); if (original != null) selected.addAll(original.apps);
        Button choose = Ui.button(this, "Choose apps · " + selected.size(), () -> { });
        choose.setOnClickListener(v -> pickApps(selected, available.get(picker.getSelectedItemPosition()), original, () -> choose.setText("Choose apps · " + selected.size())));
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private int previous = picker.getSelectedItemPosition();
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { if (position != previous) { selected.clear(); choose.setText("Choose apps · 0"); previous = position; } }
        });
        panel.addView(choose); panel.addView(Ui.spacer(this, 24));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
        final Dialog[] holder = new Dialog[1];
        actions.addView(Ui.textButton(this, "Cancel", () -> holder[0].dismiss()));
        actions.addView(Ui.primaryButton(this, "Save folder", () -> {
            String title = name.getText().toString().trim(); if (title.isEmpty()) { name.setError("Enter a folder name"); name.requestFocus(); return; }
            Profiles profile = available.get(picker.getSelectedItemPosition());
            Folders.Folder updated = original == null ? Folders.Folder.create(title, profile.kind, profile.serial, new ArrayList<>(selected))
                    : new Folders.Folder(original.id, title, original.profile, original.serial, new ArrayList<>(selected));
            List<Folders.Folder> next = new ArrayList<>(folders); if (original == null) next.add(updated); else next.set(folders.indexOf(original), updated);
            if (save(next)) holder[0].dismiss();
        })); panel.addView(actions);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel); LinearLayout container = Ui.column(this, 0); container.addView(scroll);
        holder[0] = sheet(container); editorDialog = holder[0];
        editorDialog.setOnDismissListener(d -> editorDialog = null);
    }

    private void confirmDelete(Folders.Folder folder, Runnable done) {
        new AlertDialog.Builder(this).setTitle("Delete “" + folder.name + "”?").setMessage("Only the folder is removed. Its apps stay installed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete folder", (d, w) -> { List<Folders.Folder> next = new ArrayList<>(folders); next.remove(folder); if (save(next)) done.run(); }).show();
    }

    static void membershipIndicator(Context context, CheckedTextView text, boolean selected, boolean elsewhere) {
        text.setChecked(selected || elsewhere); text.setCheckMarkTintList(ColorStateList.valueOf(selected ? Ui.pickerActive(context) : Ui.pickerElsewhere(context)));
        text.setStateDescription(selected ? "Selected in this folder" : elsewhere ? "In another folder; tap to add here" : "Not selected");
    }

    private void pickApps(Set<String> selected, Profiles profile, Folders.Folder current, Runnable done) {
        final List<AppEntry> all;
        try { all = AppEntry.list(this, profile.kind, profile.serial); } catch (RuntimeException e) { message(e.getMessage()); return; }
        HashSet<String> available = new HashSet<>(); for (AppEntry app : all) available.add(app.key);
        for (String missing : selected) if (!available.contains(missing)) all.add(new AppEntry(missing));
        Set<String> draft = new LinkedHashSet<>(selected); List<AppEntry> visible = new ArrayList<>(all);
        LinearLayout panel = Ui.column(this, 20);
        panel.addView(Ui.text(this, profileName(profile.kind) + " apps", 24)); panel.addView(Ui.spacer(this, 8));
        TextView count = Ui.secondary(this, appCount(draft.size()) + " selected", 14); panel.addView(count); panel.addView(Ui.spacer(this, 16));
        EditText search = Ui.input(this, "Search apps"); panel.addView(search); panel.addView(Ui.spacer(this, 8));
        ListView list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.dividerColor(this))); list.setDividerHeight(1);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int pos) { return visible.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View recycled, ViewGroup parent) {
                AppEntry entry = visible.get(pos); boolean checked = draft.contains(entry.key);
                boolean elsewhere = Folders.assignedElsewhere(folders, current, profile.kind, profile.serial, Profiles.personalSerial(MainActivity.this), entry.key);
                LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(MainActivity.this);
                row.removeAllViews(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 12)); row.setMinimumHeight(Ui.dp(MainActivity.this, 64));
                ImageView icon = new ImageView(MainActivity.this); try { icon.setImageDrawable(entry.icon(MainActivity.this)); } catch (RuntimeException ignored) { }
                row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 40), Ui.dp(MainActivity.this, 40)));
                LinearLayout copy = Ui.column(MainActivity.this, 0); copy.setPadding(Ui.dp(MainActivity.this, 12), 0, Ui.dp(MainActivity.this, 8), 0);
                TextView label = Ui.text(MainActivity.this, entry.info == null ? "Unavailable app" : entry.label, 16); label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END); copy.addView(label);
                if (entry.info == null || elsewhere) { copy.addView(Ui.spacer(MainActivity.this, 4)); copy.addView(Ui.secondary(MainActivity.this, entry.info == null ? entry.key : checked ? "Also in another folder" : "In another folder", 12)); }
                row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                CheckedTextView check = new CheckedTextView(MainActivity.this); check.setCheckMarkDrawable(android.R.drawable.checkbox_on_background); membershipIndicator(MainActivity.this, check, checked, elsewhere);
                if (!checked && !elsewhere) check.setCheckMarkDrawable(android.R.drawable.checkbox_off_background);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(check, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 32), Ui.dp(MainActivity.this, 32)));
                row.setContentDescription(label.getText() + ", " + check.getStateDescription()); return row;
            }
        };
        list.setAdapter(adapter); TextView empty = Ui.secondary(this, "No matching apps", 15); empty.setGravity(Gravity.CENTER); empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        list.setEmptyView(empty);
        list.setOnItemClickListener((p, v, pos, id) -> {
            String key = visible.get(pos).key; if (!draft.remove(key)) { if (draft.size() >= Folders.MAX_APPS) { message("Use at most 100 apps per folder"); return; } draft.add(key); }
            count.setText(appCount(draft.size()) + " selected"); adapter.notifyDataSetChanged();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.ROOT).trim(); visible.clear(); for (AppEntry app : all) if ((app.label + " " + app.key).toLowerCase(Locale.ROOT).contains(query)) visible.add(app); adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) { }
        });
        FrameLayout results = new FrameLayout(this); results.addView(list, new FrameLayout.LayoutParams(-1, -1)); results.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        panel.addView(results, new LinearLayout.LayoutParams(-1, 0, 1)); panel.addView(Ui.spacer(this, 12));
        final Dialog[] holder = new Dialog[1]; LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
        actions.addView(Ui.textButton(this, "Cancel", () -> holder[0].dismiss())); actions.addView(Ui.primaryButton(this, "Done", () -> { selected.clear(); selected.addAll(draft); done.run(); holder[0].dismiss(); })); panel.addView(actions);
        holder[0] = sheet(panel); pickerDialog = holder[0];
        pickerDialog.setOnDismissListener(d -> pickerDialog = null);
        holder[0].getWindow().setLayout(Math.min(Ui.dp(this, 560), getWindow().getDecorView().getWidth() - Ui.dp(this, 24)), Math.max(Ui.dp(this, 280), getWindow().getDecorView().getHeight() - Ui.dp(this, 100)));
    }
}
