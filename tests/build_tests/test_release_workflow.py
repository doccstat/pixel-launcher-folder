"""Exercise the actual workflow shell against a fake GitHub CLI (no network)."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/distribute-release.yml"
RELEASE_WORKFLOW = ROOT / ".github/workflows/release.yml"


def step_script(prefix, workflow=WORKFLOW):
    # Avoid a PyYAML dependency in the Android signing runner's build guards.
    block = next(b for b in workflow.read_text().split("      - name: ")[1:]
                 if b.startswith(prefix))
    lines = block.split("        run: |\n", 1)[1].splitlines()
    script = []
    for line in lines:
        if line and not line.startswith("          "):
            break
        script.append(line[10:] if line else "")
    return "\n".join(script) + "\n"


def release(code=3, name=None, draft=False, author="github-actions[bot]", prerelease=False):
    name = name or f"0.{code}.0"
    return dict(id=code, tag_name=f"{code}-{name}", name=name, body="release notes",
                draft=draft, prerelease=prerelease, author=dict(login=author))


FAKE_GH = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
data = json.loads(pathlib.Path(os.environ['FAKE_DATA']).read_text())
with open(os.environ['FAKE_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if data.get('fail_command') == args[:2]:
    sys.exit(1)
if args[:2] == ['api', '--paginate']:
    if data.get('api_failure'):
        sys.exit(1)
    print(json.dumps(data.get('pages', [[]])))
elif args[0] == 'api':
    endpoint = args[1]
    if endpoint.endswith('/commits/main'):
        print(data.get('head', os.environ['GITHUB_SHA']))
    elif endpoint.endswith('/releases/latest'):
        if not data.get('latest'):
            sys.exit(1)
        print(json.dumps(data['latest']))
    elif '--method' in args:
        print('{}')
    elif '/releases/' in endpoint:
        print(json.dumps(data['current'][endpoint.rsplit('/', 1)[1]]))
    else:
        sys.exit('Unexpected API call: ' + repr(args))
elif args[:2] not in (['release', 'edit'], ['release', 'create'], ['release', 'upload'],
                     ['workflow', 'run']):
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
                        FAKE_LOG=str(self.log), GITHUB_SHA="a" * 40,
                        GITHUB_REPOSITORY="doccstat/pixel-launcher-folder",
                        LISTING_REPOSITORY="Xposed-Modules-Repo/com.lixingchi.pixellauncherfolder")
        self.manifest = (ROOT / "AndroidManifest.xml").read_bytes()
        (self.directory / "AndroidManifest.xml").write_bytes(self.manifest)
        attrs = ET.fromstring(self.manifest).attrib
        android = "{http://schemas.android.com/apk/res/android}"
        self.source = release(int(attrs[android + "versionCode"]), attrs[android + "versionName"])
        self.tag = self.source['tag_name']
        for directory in ("dist", "verified-release"):
            assets = self.directory / directory
            assets.mkdir()
            for name in ("PixelLauncherFolders.apk", "PixelLauncherFolders.apk.sha256"):
                (assets / name).write_text("new build")
        for name, value in (("release-tag", self.tag), ("release-title", self.source['name']),
                            ("release-notes.md", "notes")):
            (self.directory / name).write_text(value + "\n")

    def run_step(self, prefix, data, workflow=WORKFLOW):
        self.data.write_text(json.dumps(data))
        self.log.write_text("")
        return subprocess.run(["bash", "-c", step_script(prefix, workflow)], env=self.env,
                              cwd=self.directory, capture_output=True, text=True)

    def calls(self, *prefix):
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [call for call in calls if call[:len(prefix)] == list(prefix)]

    def test_source_creates_manifest_version_without_mutating_it(self):
        result = self.run_step("Replace", {}, RELEASE_WORKFLOW)
        self.assertEqual(result.returncode, 0, result.stderr)
        create, = self.calls('release', 'create')
        self.assertEqual(create[2], self.tag)
        self.assertIn('--latest', create)
        self.assertNotIn('--draft', create)
        self.assertNotIn('--prerelease', create)
        self.assertEqual(create[create.index('--target') + 1], self.env['GITHUB_SHA'])
        self.assertEqual((self.directory / 'AndroidManifest.xml').read_bytes(), self.manifest)
        self.assertEqual(len(self.calls('workflow', 'run')), 1)

    def test_source_same_version_replaces_assets_and_updates_tag(self):
        result = self.run_step("Replace", dict(pages=[[self.source]]), RELEASE_WORKFLOW)
        self.assertEqual(result.returncode, 0, result.stderr)
        upload, = self.calls('release', 'upload')
        self.assertEqual(upload[2], self.tag)
        self.assertIn('--clobber', upload)
        edit, = self.calls('release', 'edit')
        self.assertEqual(edit[2], self.tag)
        self.assertIn('--draft=false', edit)
        self.assertIn('--prerelease=false', edit)
        self.assertIn('--latest', edit)
        patch, = [c for c in self.calls('api') if 'PATCH' in c]
        self.assertTrue(patch[1].endswith('/git/refs/tags/' + self.tag))
        self.assertIn('sha=' + self.env['GITHUB_SHA'], patch)
        self.assertFalse(self.calls('release', 'create'))
        self.assertEqual((self.directory / 'AndroidManifest.xml').read_bytes(), self.manifest)
        self.assertEqual(len(self.calls('workflow', 'run')), 1)

    def test_superseded_source_run_cannot_overwrite_newer_assets(self):
        result = self.run_step("Replace", dict(head="b" * 40), RELEASE_WORKFLOW)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.calls()), 1)

    def test_source_api_error_does_not_create_release(self):
        for failure in (dict(api_failure=True), dict(fail_command=[
                'api', 'repos/doccstat/pixel-launcher-folder/commits/main'])):
            with self.subTest(failure=failure):
                result = self.run_step("Replace", failure, RELEASE_WORKFLOW)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.calls('release'))
                self.assertFalse(self.calls('workflow'))

    def test_source_upload_failure_does_not_dispatch_or_delete_drafts(self):
        result = self.run_step("Replace", dict(pages=[[self.source]],
            fail_command=['release', 'upload']), RELEASE_WORKFLOW)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls('workflow'))
        self.assertFalse(any('DELETE' in c for c in self.calls()))

    def test_cleanup_only_removes_legacy_bot_drafts_after_publication(self):
        old = release(300012, '0.3.0-build.12', draft=True)
        other = release(300013, '0.3.0-build.13', draft=True, author='someone')
        published = release(300011, '0.3.0-build.11')
        manual = release(4, draft=True)
        result = self.run_step("Replace", dict(pages=[[old, published], [other, manual]],
            current={str(old['id']): old}), RELEASE_WORKFLOW)
        self.assertEqual(result.returncode, 0, result.stderr)
        delete, = [c for c in self.calls() if 'DELETE' in c]
        self.assertTrue(delete[1].endswith('/' + str(old['id'])))
        self.assertLess(self.calls().index(self.calls('release', 'create')[0]),
                        self.calls().index(delete))

    def test_cleanup_rechecks_draft_before_deleting(self):
        old = release(300012, '0.3.0-build.12', draft=True)
        for current in (dict(old, draft=False), dict(old, name='manually renamed')):
            with self.subTest(current=current):
                result = self.run_step("Replace", dict(pages=[[old]],
                    current={str(old['id']): current}), RELEASE_WORKFLOW)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse(any('DELETE' in c for c in self.calls()))

    def test_distribution_uses_github_latest_even_for_stale_events(self):
        self.env['REQUESTED_TAG'] = '2-0.2.0'
        result = self.run_step("Resolve", dict(latest=self.source,
            pages=[[release(300012, '0.3.0-build.12')]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / 'release-tag').read_text().strip(), self.tag)
        self.assertEqual(self.calls(), [['api',
            'repos/doccstat/pixel-launcher-folder/releases/latest']])

    def test_no_latest_release_fails_without_falling_back(self):
        result = self.run_step("Resolve", {})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls()), 1)

    def test_distribution_rejects_drafts_prereleases_and_invalid_tags(self):
        for changes in (dict(draft=True), dict(prerelease=True), dict(tag_name='invalid')):
            with self.subTest(changes=changes):
                result = self.run_step("Resolve", dict(latest=dict(self.source, **changes)))
                self.assertNotEqual(result.returncode, 0)

    def test_existing_listing_release_is_replaced_and_stabilized(self):
        result = self.run_step("Create or repair", dict(pages=[[dict(self.source, prerelease=True)]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        upload, = self.calls('release', 'upload')
        self.assertEqual(upload[2], self.tag)
        self.assertIn('--clobber', upload)
        self.assertTrue(all(str(self.directory / 'verified-release' / name) in upload
            for name in ('PixelLauncherFolders.apk', 'PixelLauncherFolders.apk.sha256')))
        edit, = self.calls('release', 'edit')
        self.assertIn('--draft=false', edit)
        self.assertIn('--prerelease=false', edit)
        self.assertIn('--latest', edit)
        self.assertFalse(self.calls('release', 'create'))

    def test_new_listing_release_is_stable_despite_legacy_version_codes(self):
        result = self.run_step("Create or repair", dict(pages=[[release(300009, '0.3.0-build.9')]]))
        self.assertEqual(result.returncode, 0, result.stderr)
        create, = self.calls('release', 'create')
        self.assertEqual(create[2], self.tag)
        self.assertIn('--latest', create)
        self.assertNotIn('--prerelease', create)
        self.assertNotIn('--draft', create)

    def test_listing_api_error_is_not_treated_as_missing_release(self):
        result = self.run_step("Create or repair", dict(api_failure=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls('release'))

    def test_workflows_have_no_candidate_versioning_or_manual_tag_input(self):
        workflow = RELEASE_WORKFLOW.read_text()
        for retired in ('github.run_number', 'CANDIDATE_NUMBER', 'refresh-draft.py', 'root.set'):
            self.assertNotIn(retired, workflow)
        self.assertIn('actions: write', workflow)
        self.assertIn('gh workflow run distribute-release.yml', workflow)
        self.assertNotIn('inputs:', WORKFLOW.read_text())
        for path in (WORKFLOW, RELEASE_WORKFLOW):
            self.assertIn('group: signed-release\n  cancel-in-progress: false', path.read_text())

    def test_all_workflow_shell_blocks_parse(self):
        for workflow, prefixes in ((WORKFLOW, ('Resolve', 'Download', 'Create or repair')),
                                   (RELEASE_WORKFLOW, ('Build with', 'Replace'))):
            for prefix in prefixes:
                with self.subTest(workflow=workflow.name, prefix=prefix):
                    result = subprocess.run(['bash', '-n'], input=step_script(prefix, workflow),
                                            text=True, capture_output=True)
                    self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
