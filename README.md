# Pixel Launcher Folders

Pixel Launcher Folders is an Xposed/LSPosed module that adds customizable
folders to the Pixel Launcher app drawer.

## Features

- Create and arrange folders as normal app-drawer cells.
- Sort folder contents and four-icon previews alphabetically.
- Optionally keep folder apps out of the main alphabetical app list.
- Tap to launch an app; long-press to start the launcher drag flow.
- Back up and restore assignments through Android's user-selected Files storage.
- Preserve Android profile identity while excluding Private Space.

The module does not edit the launcher database, modify APKs, install apps, or
read Private Space. It requires no Internet permission.

## Requirements

- Android 15 or newer.
- Root access with LSPosed, Vector, or a compatible Xposed framework.
- Pixel Launcher package `com.google.android.apps.nexuslauncher`.

The maintained test target is Pixel 11 Pro Fold, Android 17, Pixel Launcher 17.
Other launcher versions are unverified. Split-screen and pop-up dragging remain
experimental; disable the module's launcher scope if an update causes problems.

## Installation

1. Install the APK from [Releases](https://github.com/doccstat/pixel-launcher-folder/releases).
2. Enable Pixel Launcher Folders in LSPosed or Vector.
3. Scope it only to `com.google.android.apps.nexuslauncher`.
4. Restart Pixel Launcher and open the Pixel Launcher Folders settings app.

## Backup and restore

Choose a folder through Android's document picker. Export writes editable text
files plus a JSON manifest containing folder order, IDs, profile identity, and
settings. Import accepts that manifest or plain `.txt` files. Import changes
only this module's folder configuration; it never installs, removes, disables,
or modifies applications.

## Screenshots

<table>
<tr>
<td width="50%"><img src="docs/screenshots/drawer-folders.png" alt="Pixel Launcher folder cells in the app drawer" width="100%" /></td>
<td width="50%"><img src="docs/screenshots/settings.png" alt="Pixel Launcher Folders settings" width="100%" /></td>
</tr>
<tr>
<td width="50%"><img src="docs/screenshots/homescreen.png" alt="Folder opened on the outer display" width="100%" /></td>
<td width="50%"><img src="docs/screenshots/unfolded-homescreen.png" alt="Folder opened on the unfolded display" width="100%" /></td>
</tr>
</table>

## Support

Report issues at the [source repository](https://github.com/doccstat/pixel-launcher-folder/issues)
with the device model, Android version, Pixel Launcher version, module version,
and reproduction steps. This project is not affiliated with Google, Pixel
Launcher, or LSPosed.
