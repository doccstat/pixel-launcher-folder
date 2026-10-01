#!/usr/bin/env python3
"""Sequential SDK build, no Gradle daemon; never contacts a device."""
import os
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", action="store_true", help="Include our instrumentation runner in this APK")
    args = parser.parse_args()
    windows = os.name == "nt"
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk" if windows else Path.home() / "Android/Sdk")))
    java = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/latest/jdk-26" if windows else "/usr/lib/jvm/default-java"))
    android = Path(os.environ.get("ANDROID_JAR", str(sdk / "platforms/android-37.0/android.jar")))
    bt = Path(os.environ.get("BUILD_TOOLS", str(sdk / "build-tools/36.0.0")))
    build, dist = ROOT / "build", ROOT / "dist"
    # Keep the signing key outside disposable build output, preserving updates.
    key = Path(os.environ.get("SIGNING_KEYSTORE", str(Path.home() / ".android/pixel-launcher-folders.keystore")))
    env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java / "bin") + os.pathsep + os.environ["PATH"])

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
        key.parent.mkdir(parents=True, exist_ok=True)
        run(jtool("keytool"), "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Pixel Launcher Folders Development,O=Depot,C=US")
    output = dist / "PixelLauncherFolders.apk"
    run(tool("apksigner"), "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey", "--out", output, build / "aligned.apk")
    run(tool("apksigner"), "verify", "--verbose", output)
    print(output)


if __name__ == "__main__":
    main()
