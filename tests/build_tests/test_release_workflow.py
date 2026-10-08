"""Exercise the actual workflow shell against a fake GitHub CLI (no network)."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/distribute-release.yml"


def step_script(prefix):
    # Avoid a PyYAML dependency in the Android signing runner's build guards.
    block = next(b for b in WORKFLOW.read_text().split("      - name: ")[1:]
                 if b.startswith(prefix))
    lines = block.split("        run: |\n", 1)[1].splitlines()
    script = []
    for line in lines:
        if line and not line.startswith("          "):
            break
        script.append(line[10:] if line else "")
    return "\n".join(script) + "\n"


def release(code, draft=False, author="github-actions[bot]", prerelease=False):
    name = f"0.3.0-build.{code}" if code > 3 else "0.3.0"
    return dict(id=code, tag_name=f"{code}-{name}", name=name, draft=draft,
                prerelease=prerelease, author=dict(login=author))


FAKE_GH = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
p = pathlib.Path(os.environ['FAKE_DATA'])
data = json.loads(p.read_text())
with open(os.environ['FAKE_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if data.get('api_failure') and args[0] == 'api':
    sys.exit(1)
if args[:2] == ['api', '--paginate']:
    print(json.dumps(data.get('pages', [[]])))
elif args[0] == 'api':
    if '--method' in args:
        print('{}')
    else:
        print(json.dumps(data.get('current', {})))
elif args[:2] == ['release', 'view']:
    if '--jq' in args:
        print('99')
    else:
        print(json.dumps(data.get('view', {})))
elif args[:2] == ['release', 'download']:
    name = args[args.index('--pattern') + 1]
    directory = pathlib.Path(args[args.index('--dir') + 1])
    (directory / name).write_text(data.get('binary', 'same'))
elif args[:2] not in (['release', 'edit'], ['release', 'create']):
    sys.exit('Unexpected gh command: ' + repr(args))
'''


@unittest.skipUnless(shutil.which("jq"), "workflow shell requires jq")
class ReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.data = self.directory / "data.json"
        self.log = self.directory / "log"
        cli = self.directory / "gh"
        cli.write_text(FAKE_GH)
        cli.chmod(0o700)
        self.env = dict(os.environ, PATH=f"{self.directory}:{os.environ['PATH']}",
                        RUNNER_TEMP=str(self.directory), FAKE_DATA=str(self.data),
                        FAKE_LOG=str(self.log), REQUESTED_TAG="",
                        GITHUB_REPOSITORY="doccstat/pixel-launcher-folder",
                        LISTING_REPOSITORY="Xposed-Modules-Repo/com.lixingchi.pixellauncherfolder")

    def run_step(self, prefix, data):
        self.data.write_text(json.dumps(data))
        return subprocess.run(["bash", "-c", step_script(prefix)], env=self.env,
                              capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def prepare_distribution(self, code=3, prerelease=False):
        self.tag = release(code)["tag_name"]
        for name, value in (("release-tag", self.tag), ("release-prerelease", str(prerelease).lower()),
                            ("release-title", "title"), ("release-notes.md", "notes")):
            (self.directory / name).write_text(value + "\n")
        verified = self.directory / "verified-release"
        verified.mkdir()
        for name in ("PixelLauncherFolders.apk", "PixelLauncherFolders.apk.sha256"):
            (verified / name).write_text("same")

    def test_manual_selects_newest_draft_not_old_publication(self):
        draft = release(300008, draft=True, prerelease=True)
        result = self.run_step("Resolve", dict(pages=[[release(3)], [draft]],
            view=dict(isDraft=True, isPrerelease=True, name=draft['name'], body="notes")))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "release-tag").read_text().strip(), draft['tag_name'])

    def test_manual_ignores_unrelated_drafts(self):
        other = release(900000, draft=True, author="someone")
        result = self.run_step("Resolve", dict(pages=[[release(3), other]],
            view=dict(isDraft=False, isPrerelease=False, name="0.3.0", body="notes")))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "release-tag").read_text().strip(), "3-0.3.0")

    def test_publication_event_uses_exact_tag(self):
        self.env['REQUESTED_TAG'] = "3-0.3.0"
        result = self.run_step("Resolve", dict(view=dict(isDraft=False, isPrerelease=False,
                                                        name="0.3.0", body="notes")))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c[0] == 'api' for c in self.calls()))

    def test_empty_source_fails_without_falling_back(self):
        result = self.run_step("Resolve", dict(pages=[[]]))
        self.assertNotEqual(result.returncode, 0)

    def test_manual_publishes_candidate_stable(self):
        self.prepare_distribution(300008)
        (self.directory / "release-draft").write_text("true\n")
        result = self.run_step("Publish the verified", {})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--draft=false', self.calls()[0])
        self.assertIn('--prerelease=false', self.calls()[0])

    def test_stable_listing_retry_repairs_status_without_asset_overwrite(self):
        self.prepare_distribution()
        result = self.run_step("Create or repair", dict(pages=[[release(3, prerelease=True)]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        patch = next(c for c in self.calls() if '--method' in c)
        self.assertIn('prerelease=false', patch)
        self.assertIn('make_latest=true', patch)
        self.assertNotIn('--clobber', str(self.calls()))
        self.assertFalse(any(c[:2] == ['release', 'upload'] for c in self.calls()))

    def test_changed_listing_apk_is_not_replaced(self):
        self.prepare_distribution()
        result = self.run_step("Create or repair", dict(pages=[[release(3)]], binary="different"))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any('--method' in c for c in self.calls()))

    def test_older_release_cannot_make_listing_latest(self):
        self.prepare_distribution()
        result = self.run_step("Create or repair", dict(pages=[[release(3), release(300009)]]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('refusing a rollback', result.stdout)
        self.assertEqual(len(self.calls()), 1)

    def test_listing_api_error_is_not_treated_as_missing_release(self):
        self.prepare_distribution()
        result = self.run_step("Create or repair", dict(api_failure=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[:2] == ['release', 'create'] for c in self.calls()))

    def test_new_listing_release_is_stable(self):
        self.prepare_distribution(300008)
        result = self.run_step("Create or repair", dict(pages=[[]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        create = next(c for c in self.calls() if c[:2] == ['release', 'create'])
        self.assertEqual(create[2], self.tag)
        self.assertNotIn('--prerelease', create)

    def test_explicit_source_prerelease_is_preserved(self):
        self.prepare_distribution(300008, prerelease=True)
        result = self.run_step("Create or repair", dict(pages=[[]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--prerelease', self.calls()[-1])

    def test_cleanup_preserves_newer_and_unrelated_drafts(self):
        self.prepare_distribution(300008)
        old = release(300007, draft=True)
        pages = [[old, release(300009, draft=True), release(300006, draft=True, author='someone'),
                  release(300005)]]
        result = self.run_step("Remove superseded", dict(pages=pages, current=old))
        self.assertEqual(result.returncode, 0, result.stderr)
        deletes = [c for c in self.calls() if 'DELETE' in c]
        self.assertEqual(len(deletes), 1)
        self.assertTrue(deletes[0][1].endswith('/300007'))

    def test_cleanup_rechecks_publication_before_deleting(self):
        self.prepare_distribution(300008)
        result = self.run_step("Remove superseded", dict(pages=[[release(300007, draft=True)]],
                                                        current=release(300007)))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any('DELETE' in c for c in self.calls()))

    def test_all_workflow_shell_blocks_parse(self):
        for prefix in ('Resolve', 'Download', 'Publish the verified', 'Create or repair', 'Remove superseded'):
            result = subprocess.run(['bash', '-n'], input=step_script(prefix), text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
