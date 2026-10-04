# Publishing Pixel Launcher Folders

## Repositories

Depot is the editable source of truth. The Josh workflow exports the package
tree to `git@github.com:doccstat/pixel-launcher-folder.git`. Do not edit that
projection directly; publish source changes through Depot and let the export
workflow advance it.

The LSPosed module listing is a different repository. Submit an issue titled
`[submission] com.lixingchi.pixellauncherfolder` in
[Xposed-Modules-Repo/submission](https://github.com/Xposed-Modules-Repo/submission).
The verified root DNS record is:

```text
lsposed-modules-repo-verification=doccstat
```

The bot should create `Xposed-Modules-Repo/com.lixingchi.pixellauncherfolder`.
That repository contains only `SUMMARY`, `README.md`, and releases—not this
source tree. Copy the public-facing README content into that repository and
attach the reviewed release APK there.

## Signing

APK update compatibility depends on retaining the same signing certificate.
Create one long-lived release keystore offline and store it only in the GitHub
`release` environment:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded keystore;
- `ANDROID_KEYSTORE_PASSWORD`: keystore password;
- `ANDROID_KEY_PASSWORD`: key password;
- `ANDROID_KEY_ALIAS`: key alias;
- `ANDROID_SIGNING_CERT_SHA256`: environment variable containing the lowercase
  64-hex-digit SHA-256 certificate fingerprint.

Use a protected environment with required reviewer approval. The workflow
checks out a specific action commit, decodes the keystore only in the runner's
temporary directory, refuses to create a release key, verifies the APK
certificate against the protected fingerprint, and emits a SHA-256 checksum.
Do not put passwords or the keystore in Depot secrets, issue comments, release
notes, or the source repository.

For a release candidate:

1. Merge the source change in Depot.
2. Wait for the Josh projection and GitHub build to pass.
3. Confirm the manifest's `versionCode` and `versionName` are intentional.
4. Dispatch the protected **Signed release candidate** workflow.
5. Download the draft APK and checksum; verify the certificate locally with
   `apksigner verify --print-certs`.
6. Test the exact APK on the target Pixel, including launcher reload, Personal
   and Work folders, folder-only mode, and a SAF export/import round trip.
7. Create/publish the source release only after review. Upload the same APK to
   the generated LSPosed repository with tag `2-0.2.0` for this version.

Never publish the instrumentation APK. Never change the signing key after a
public release; increment `versionCode` for every update.

## Release gates

The current evidence supports a public beta candidate, not a stable universal
release. Before the first listing, complete:

- a real user-created folder export;
- import into a clean state and verification of Personal and Work profile
  metadata;
- verification that import does not install/remove/modify applications;
- launcher reload after installation and after disabling the module;
- exact APK certificate and checksum verification;
- review of the generated LSPosed README, `SUMMARY`, release title, changelog,
  and support link.

The module is tightly coupled to Pixel Launcher internals. State supported
Android/launcher builds explicitly and treat each major launcher update as a
new compatibility review.
