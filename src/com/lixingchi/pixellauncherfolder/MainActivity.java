package com.lixingchi.pixellauncherfolder;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.CheckBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private List<Folders.Folder> folders;
    private LinearLayout body;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { folders = Folders.parse(Folders.read(this)); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Could not read folders")
                .setMessage("Existing data has been kept. " + e.getMessage())
                .setPositiveButton("Close", (d, w) -> finish()).setCancelable(false).show();
            return;
        }
        LinearLayout page = Ui.column(this, 0);
        page.setBackgroundColor(Ui.dark(this) ? 0xff17191e : 0xfffafaff);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        ScrollView scroll = new ScrollView(this); body = Ui.column(this, 20);
        scroll.addView(body); page.addView(scroll); setContentView(page); page.requestApplyInsets();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(Ui.text(this, "Drawer folders", 28));
        body.addView(Ui.text(this, "Circular folders appear at the top of each profile’s app list, below the tabs. Search always finds your apps.", 16));
        body.addView(Ui.text(this, "One-time setup: enable this module in Vector for Pixel Launcher only, then restart the launcher yourself. This app does not change Vector settings or restart other apps.", 14));
        CheckBox keep = new CheckBox(this); keep.setText("Keep apps in the main app list too"); keep.setTextColor(Ui.text(this));
        try { keep.setChecked(Folders.keepInDrawer(Folders.read(this))); } catch (Exception e) { message(e.getMessage()); }
        keep.setOnCheckedChangeListener((button, checked) -> { try { Folders.setKeepInDrawer(this, checked); } catch (Exception e) { message(e.getMessage()); render(); } });
        body.addView(keep);
        for (Profiles profile : Profiles.available(this)) {
            body.addView(Ui.button(this, "Preview " + (profile.kind.equals("work") ? "Work" : "Personal") + " folders", () -> {
                FolderRow row = new FolderRow(this, profile.kind, profile.serial, false);
                new AlertDialog.Builder(this).setTitle("Folder preview").setView(row).setPositiveButton("Close", null).show();
            }));
        }
        body.addView(Ui.button(this, "New folder", () -> {
            if (folders.size() >= Folders.MAX_FOLDERS) { message("Use at most 24 folders"); return; }
            edit(null);
        }));
        if (folders.isEmpty()) body.addView(Ui.text(this, "No folders yet. Create one and choose its apps.", 16));
        for (int i = 0; i < folders.size(); i++) {
            final int index = i;
            Folders.Folder folder = folders.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            View edit = Ui.button(this, folder.name + " · " + (folder.profile.equals("work") ? "Work" : "Personal") + " · " + folder.apps.size(), () -> edit(folder));
            line.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
            android.widget.Button up = Ui.button(this, "↑", () -> move(index, -1));
            up.setContentDescription("Move " + folder.name + " up"); up.setEnabled(i > 0);
            line.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
            android.widget.Button down = Ui.button(this, "↓", () -> move(index, 1));
            down.setContentDescription("Move " + folder.name + " down"); down.setEnabled(i < folders.size() - 1);
            line.addView(down, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
            body.addView(line);
        }
    }

    private boolean save(List<Folders.Folder> next) {
        try { Folders.save(this, next); folders = next; render(); return true; }
        catch (Exception e) { message(e.getMessage()); return false; }
    }
    private void move(int index, int offset) {
        List<Folders.Folder> next = new ArrayList<>(folders);
        Collections.swap(next, index, index + offset); save(next);
    }
    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private void edit(Folders.Folder original) {
        LinearLayout form = Ui.column(this, 20);
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Folder name");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        name.setText(original == null ? "" : original.name); form.addView(name);
        List<Profiles> availableProfiles = Profiles.available(this);
        if (availableProfiles.isEmpty()) { message("No accessible profiles"); return; }
        long originalSerial = original == null ? -2 : original.serial < 0 ? Profiles.personalSerial(this) : original.serial;
        int initialIndex = 0;
        if (original != null) {
            boolean found = false;
            for (int i = 0; i < availableProfiles.size(); i++) if (availableProfiles.get(i).serial == originalSerial
                    && availableProfiles.get(i).kind.equals(original.profile)) { initialIndex = i; found = true; }
            if (!found) { availableProfiles.add(new Profiles(null, originalSerial, original.profile)); initialIndex = availableProfiles.size() - 1; }
        }
        android.widget.Spinner profilePicker = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<Profiles> profileAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, availableProfiles);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profilePicker.setAdapter(profileAdapter); profilePicker.setSelection(initialIndex);
        // Changing an existing folder's profile would silently retarget its components.
        profilePicker.setEnabled(original == null); form.addView(profilePicker);
        Set<String> selected = new LinkedHashSet<>();
        if (original != null) selected.addAll(original.apps);
        android.widget.Button choose = new android.widget.Button(this);
        choose.setAllCaps(false); choose.setText("Choose apps · " + selected.size());
        choose.setOnClickListener(v -> pickApps(selected, (Profiles) profilePicker.getSelectedItem(), () -> choose.setText("Choose apps · " + selected.size())));
        profilePicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private int previous = profilePicker.getSelectedItemPosition();
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position != previous) { selected.clear(); choose.setText("Choose apps · 0"); previous = position; }
            }
        });
        form.addView(choose);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "New folder" : "Edit folder")
                .setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
                .setNeutralButton(original == null ? null : "Delete", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) { name.setError("Enter a folder name"); return; }
                List<Folders.Folder> next = new ArrayList<>(folders);
                Profiles profile = (Profiles) profilePicker.getSelectedItem();
                Folders.Folder updated = original == null ? Folders.Folder.create(title, profile.kind, profile.serial, new ArrayList<>(selected))
                        : new Folders.Folder(original.id, title, original.profile, original.serial, new ArrayList<>(selected));
                if (original == null) next.add(updated); else next.set(folders.indexOf(original), updated);
                if (save(next)) dialog.dismiss();
            });
            if (original != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle("Delete “" + original.name + "”?")
                    .setMessage("Only this folder is removed. Its apps stay installed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (a, b) -> {
                        List<Folders.Folder> next = new ArrayList<>(folders); next.remove(original);
                        if (save(next)) dialog.dismiss();
                    }).show());
        });
        dialog.show();
    }

    private void pickApps(Set<String> selected, Profiles profile, Runnable done) {
        final List<AppEntry> all;
        try { all = AppEntry.list(this, profile.kind, profile.serial); } catch (RuntimeException e) { message(e.getMessage()); return; }
        HashSet<String> available = new HashSet<>();
        for (AppEntry e : all) available.add(e.key);
        for (String missing : selected) if (!available.contains(missing)) all.add(new AppEntry(missing));
        Set<String> draft = new LinkedHashSet<>(selected);
        List<AppEntry> visible = new ArrayList<>(all);
        LinearLayout panel = Ui.column(this, 16);
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("Search apps"); panel.addView(search);
        ListView list = new ListView(this);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int pos) { return visible.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View recycled, ViewGroup parent) {
                CheckedTextView text = recycled instanceof CheckedTextView ? (CheckedTextView) recycled
                        : (CheckedTextView) getLayoutInflater().inflate(android.R.layout.simple_list_item_multiple_choice, parent, false);
                AppEntry entry = visible.get(pos);
                text.setText(entry.label + "\n" + entry.key); text.setTextSize(14);
                text.setChecked(draft.contains(entry.key)); return text;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            String key = visible.get(pos).key;
            if (!draft.remove(key)) {
                if (draft.size() >= Folders.MAX_APPS) { message("Use at most 100 apps per folder"); return; }
                draft.add(key);
            }
            adapter.notifyDataSetChanged();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.ROOT); visible.clear();
                for (AppEntry e : all) if ((e.label + " " + e.key).toLowerCase(Locale.ROOT).contains(query)) visible.add(e);
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) { }
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 340)));
        new AlertDialog.Builder(this).setTitle(profile.kind.equals("work") ? "Work apps" : "Personal apps").setView(panel).setNegativeButton("Cancel", null)
            .setPositiveButton("Done", (d, w) -> { selected.clear(); selected.addAll(draft); done.run(); }).show();
    }
}
