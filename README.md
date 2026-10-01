# Pixel Launcher Folders

`com.lixingchi.pixellauncherfolder` is a Vector/LSPosed module and settings
app for Pixel Launcher. It adds a horizontally scrollable row of circular
folder previews as the first item inside each app list, below the Personal/Work
tabs. Folder data belongs to this package; the launcher database and existing
APKs are never written.

Folders are explicitly assigned to the Android Personal profile or a managed
Work profile. Private Space and unknown profile types are excluded. App
selection and launching are profile-bound by the Android user serial, so an app
installed in Personal and Work remains two separate entries.

Existing folders stay Personal. New folders choose an exact profile, stored by
its stable serial number. The profile of a saved folder is fixed; create a new
folder to choose another profile.

The settings app has a global **Keep apps in the main app list too** toggle. When
enabled, folder apps remain in the alphabetical grid and search. When disabled,
the folder apps are removed from that same profile's alphabetical grid while
remaining searchable and available from the folder. Apps in another profile are
never hidden by the setting. Each preview shows up to four app icons in a round
background.

Install the APK, then enable only `com.google.android.apps.nexuslauncher` in
Vector and restart Pixel Launcher through the normal UI. The package has no
service, receiver, boot action, polling loop, or Vector-setting mutation. The
provider accepts reads from Pixel Launcher and the package itself; writes are
rejected.

## Build and validation

Build from Windows, where the Android SDK/JDK is installed:

```powershell
python build.py
```

`build.py` uses API 37 and build-tools 36.0.0, performs a sequential direct SDK
build without a Gradle daemon, and writes `dist/PixelLauncherFolders.apk`.
The optional `python build.py --test` build adds an instrumentation runner. The
current build passed **41 checks** on the Pixel 11 Pro Fold, including Personal
and Work profile discovery, cross-profile picker isolation, profile-serial
matching, folder-only filtering, toggle persistence, circular preview drawing,
and four-icon preview slots. These tests run in our package; live Vector hook
installation, drawer placement/scrolling and search still need verification
after the user reloads Pixel Launcher. There is no claim of end-to-end launcher
validation.

For the connected devices used during validation, `67021FDDJ00280` is the Pixel
11 Pro Fold. The Galaxy Watch entries (`10.0.0.211:37053` and the `adb-RFA...`
serial) must not be used for installation.
