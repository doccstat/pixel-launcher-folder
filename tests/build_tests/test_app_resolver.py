import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
PACKAGE = pathlib.Path("com/lixingchi/pixellauncherfolder")


class AppResolverTests(unittest.TestCase):
    def test_actual_java_resolver(self):
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([
                "javac", "-d", output,
                str(ROOT / "src" / PACKAGE / "AppResolver.java"),
                str(ROOT / "tests" / PACKAGE / "AppResolverTests.java"),
            ], check=True)
            subprocess.run([
                "java", "-cp", output,
                "com.lixingchi.pixellauncherfolder.AppResolverTests",
            ], check=True)


if __name__ == "__main__":
    unittest.main()
