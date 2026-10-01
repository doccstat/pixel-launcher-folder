# Pixel Launcher Folders

`com.lixingchi.pixellauncherfolder` is a Vector/LSPosed module and settings
app for Pixel Launcher. It adds a horizontally scrollable folder row above the
personal app drawer and keeps folder data in the package's private preferences.
The launcher database and existing APKs are never written.

Install the APK, then enable only `com.google.android.apps.nexuslauncher` in
Vector and restart Pixel Launcher through the normal UI. The package has no
service, receiver, boot action, polling loop, or Vector-setting mutation. The
provider accepts reads from Pixel Launcher and the package itself; writes are
rejected. Folder entries are checked against personal-profile launcher
activities before they are launched.

Build from Windows, where the Android SDK/JDK is installed:

```powershell
python build.py
```

`build.py` uses API 37 and build-tools 36.0.0, performs a sequential direct SDK
build without a Gradle daemon, and writes `dist/PixelLauncherFolders.apk`.
The optional `python build.py --test` build adds an instrumentation runner. It
passed 21 checks on the Pixel 11 Pro Fold, including validation, persistence,
provider read-only behavior, editor rendering, and preview rendering.

For the connected devices used during validation, `67021FDDJ00280` is the Pixel
11 Pro Fold. The Galaxy Watch entries (`10.0.0.211:36891` and the `adb-RFA...`
serial) must not be used for installation.
