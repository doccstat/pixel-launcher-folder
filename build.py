#!/usr/bin/env python3
"""Sequential SDK build, no Gradle daemon; never contacts a device."""
import os
import re
import hashlib
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def certificate_digest(value):
    digest = value.replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("SIGNING_CERT_SHA256 must be a 64-hex-digit certificate SHA-256 fingerprint")
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", action="store_true", help="Include our instrumentation runner in this APK")
    parser.add_argument("--release", action="store_true", help="Require an existing explicitly configured signing key")
    args = parser.parse_args()
    if args.release and args.test:
        parser.error("Release APKs must not contain instrumentation")
    if args.release:
        required = ("SIGNING_KEYSTORE", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_PASSWORD", "SIGNING_KEY_ALIAS")
        missing = [name for name in required if not os.environ.get(name)]
        if missing:
            parser.error("Release signing requires: " + ", ".join(missing))
        if not Path(os.environ["SIGNING_KEYSTORE"]).is_file():
            parser.error("Release keystore does not exist; refusing to generate a replacement")
        try:
            expected_signer = certificate_digest(os.environ.get("SIGNING_CERT_SHA256", ""))
        except ValueError as error:
            parser.error(str(error))
    windows = os.name == "nt"
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk" if windows else Path.home() / "Android/Sdk")))
    java = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/latest/jdk-26" if windows else "/usr/lib/jvm/default-java"))
    android_env = os.environ.get("ANDROID_JAR")
    android = Path(android_env) if android_env else None
    if android is None:
        candidates = [sdk / "platforms/android-37.0/android.jar", sdk / "platforms/android-37/android.jar"]
        candidates += sorted(sdk.glob("platforms/android-37*/android.jar"), reverse=True)
        android = next((path for path in candidates if path.is_file()), candidates[0])
    bt_env = os.environ.get("BUILD_TOOLS")
    bt = Path(bt_env) if bt_env else None
    if bt is None:
        candidates = [sdk / "build-tools/36.0.0"] + sorted(sdk.glob("build-tools/36.*"), reverse=True)
        bt = next((path for path in candidates if (path / ("aapt2.exe" if windows else "aapt2")).is_file()), candidates[0])
    build, dist = ROOT / "build", ROOT / "dist"
    # Keep the signing key outside disposable build output, preserving updates.
    key = Path(os.environ.get("SIGNING_KEYSTORE", str(Path.home() / ".android/pixel-launcher-folders.keystore")))
    store_password = os.environ.get("SIGNING_STORE_PASSWORD", "android")
    key_password = os.environ.get("SIGNING_KEY_PASSWORD", store_password)
    key_alias = os.environ.get("SIGNING_KEY_ALIAS", "androiddebugkey")
    env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java / "bin") + os.pathsep + os.environ["PATH"],
               SIGNING_STORE_PASSWORD=store_password, SIGNING_KEY_PASSWORD=key_password)

    def run(*args):
        subprocess.run([str(x) for x in args], check=True, env=env, cwd=ROOT)

    def jtool(name):
        return java / "bin" / (name + (".exe" if windows else ""))

    def tool(name):
        return bt / (name + ((".bat" if name in ("d8", "apksigner") else ".exe") if windows else ""))

    if build.exists():
        shutil.rmtree(build)
    for path in (build / "classes", build / "dex", dist):
        path.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src").rglob("*.java")) + sorted((ROOT / "stubs").rglob("*.java"))
    if args.test:
        sources += sorted((ROOT / "tests").rglob("*.java"))
    run(jtool("javac"), "-source", "8", "-target", "8", "-cp", android, "-d", build / "classes", *sources)
    run(tool("d8"), "--classpath", android, "--classpath", build / "classes", "--min-api", "30",
        "--output", build / "dex", *sorted((build / "classes/com/lixingchi").rglob("*.class")))
    run(tool("aapt2"), "compile", "--dir", ROOT / "res", "-o", build / "resources.zip")
    manifest = ROOT / "AndroidManifest.xml"
    if args.test:
        ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
        tree = ET.parse(manifest)
        prefix = "{http://schemas.android.com/apk/res/android}"
        ET.SubElement(tree.getroot(), "instrumentation", {
            prefix + "name": "com.lixingchi.pixellauncherfolder.FolderTests",
            prefix + "targetPackage": "com.lixingchi.pixellauncherfolder"})
        manifest = build / "AndroidManifest.xml"
        tree.write(manifest, encoding="utf-8", xml_declaration=True)
    run(tool("aapt2"), "link", "-o", build / "unsigned.apk", "-I", android,
        "--manifest", manifest, build / "resources.zip")
    with zipfile.ZipFile(build / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(build / "dex/classes.dex", "classes.dex")
        apk.write(ROOT / "assets/xposed_init", "assets/xposed_init")
    run(tool("zipalign"), "-f", "4", build / "unsigned.apk", build / "aligned.apk")
    if not key.exists():
        if args.release:
            raise RuntimeError("Release keystore disappeared; refusing to generate a replacement")
        key.parent.mkdir(parents=True, exist_ok=True)
        run(jtool("keytool"), "-genkeypair", "-keystore", key, "-storepass:env", "SIGNING_STORE_PASSWORD", "-keypass:env", "SIGNING_KEY_PASSWORD",
            "-alias", key_alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Pixel Launcher Folders Development,O=doccstat,C=US")
    output = dist / "PixelLauncherFolders.apk"
    run(tool("apksigner"), "sign", "--ks", key, "--ks-pass", "env:SIGNING_STORE_PASSWORD",
        "--key-pass", "env:SIGNING_KEY_PASSWORD", "--ks-key-alias", key_alias,
        "--out", output, build / "aligned.apk")
    verification = subprocess.run([str(tool("apksigner")), "verify", "--verbose", "--print-certs", str(output)],
                                  check=True, env=env, cwd=ROOT, capture_output=True, text=True)
    print(verification.stdout)
    if args.release:
        digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verification.stdout)
        if len(digests) != 1 or certificate_digest(digests[0]) != expected_signer:
            output.unlink()
            raise RuntimeError("APK signing certificate does not match SIGNING_CERT_SHA256")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (dist / (output.name + ".sha256")).write_text(digest + "  " + output.name + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
