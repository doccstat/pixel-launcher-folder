# Pixel Launcher Folders

`com.lixingchi.pixellauncherfolder` is a Vector/LSPosed module and settings
app for Pixel Launcher. It adds circular folder previews as consecutive normal
grid cells at the beginning of each app list, below the Personal/Work tabs, so
the next installed app follows immediately in the same first row. Folder data
belongs to this package; the launcher database and existing APKs are never
written.

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
background. The drawer has no Edit button; long-pressing a folder opens
settings.

The settings app also offers a user-selected Android Document Provider backup
folder. **Export folders** writes one simple `<folder>.txt` file per folder,
with one package identifier per line, plus `.pixel-launcher-folders.json` to
preserve folder IDs, ordering, Personal/Work profile serials, and the drawer
toggle. **Import folders** accepts that manifest format or a directory of plain
`.txt` files; without a manifest, each filename becomes a Personal folder and
each line may be either a package name or a flattened launcher component. The
import replaces folder assignments but never changes installed apps. Older
releases that accidentally imported `.json.txt` manifests are cleaned on the
next settings-app launch. Once a backup tree is selected, the private preferences are the fast launcher cache:
the settings app imports the selected backup on startup, and every save writes
the new state back to the selected tree. Use **Export folders** after selecting
a new or manually edited directory when you want the current private state to
become authoritative.

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
current build passed **67 checks** on the Pixel 11 Pro Fold, including Personal
and Work profile discovery, cross-profile picker isolation, profile-serial
matching, folder-only filtering, toggle persistence, circular preview drawing,
four-icon preview slots, and complete folder-cell sizing on the outer and unfolded
grid spans. The hook reads the stock `BubbleTextView` icon size, text size, and
drawable gap at runtime. The live outer-display screenshot
`Screenshot_20261001-182814.png` verified a complete circular Finance cell,
its full label, and Android Faker immediately following it in the first row
after the launcher was reloaded through Vector. Search and scrolling remain
covered by the launcher’s normal behavior rather than package instrumentation.

For the connected devices used during validation, `67021FDDJ00280` is the Pixel
11 Pro Fold. The Galaxy Watch entries (`10.0.0.211:37053` and the `adb-RFA...`
serial) must not be used for installation.
