"""Offline failure-path tests for the exact Python embedded in release.yml.

All GitHub CLI calls are intercepted. No tag, release or other API mutation occurs.
Run with: python scripts/test-release-publication.py
"""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/release.yml'
BODY = WORKFLOW.read_text(encoding='utf-8').split("          python3 - <<'PY'\n", 1)[1].split('          PY\n', 1)[0]
SOURCE = '\n'.join(line[10:] for line in BODY.splitlines())


class PublicationTests(unittest.TestCase):
    def exercise(self, fault=None, stable=False):
        tag = 'v1.2.3' if stable else 'v0.9.0-beta.1'
        semantic = tag[1:]
        windows = '1.2.3' if stable else '0.8.30001'
        prerelease = 'false' if stable else 'true'
        exe = f'GTA-Casino-Fingerprint-Solver-{tag}-windows-x64.exe'
        sha = '1' * 40
        env = dict(RELEASE_TAG=tag, SEMANTIC_VERSION=semantic, WINDOWS_VERSION=windows,
                   PRERELEASE=prerelease, RELEASE_SHA=sha, TRIGGER_SHA=sha,
                   TRIGGER_REF='refs/tags/' + tag, GH_REPO='owner/repo',
                   GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
        self.calls = []
        self.failure = None
        with tempfile.TemporaryDirectory() as tmp:
            bundle = Path(tmp) / 'bundle'
            bundle.mkdir()
            (bundle / exe).write_bytes(b'validated installer bytes')
            fields = {'Release tag': tag, 'Semantic version': semantic, 'Windows package version': windows,
                      'Prerelease': prerelease, 'Git SHA': sha, 'GitHub repository': 'owner/repo',
                      'GitHub run ID': '123', 'GitHub run attempt': '1', 'Installer public filename': exe,
                      'Installer SHA-256': hashlib.sha256((bundle / exe).read_bytes()).hexdigest(),
                      'Installer bytes': str((bundle / exe).stat().st_size), 'Code signing': 'NOT SIGNED',
                      'Temurin version': '21.0.6+7-LTS', 'Selected Java os.arch': 'amd64',
                      'Startup': 'DISARMED / zero input', 'Maven test count': '915'}
            if fault == 'provenance':
                fields['Git SHA'] = '2' * 40
            (bundle / 'release-provenance.txt').write_text(
                ''.join(f'{k}: {v}\n' for k, v in fields.items()), encoding='utf-8')
            (bundle / 'SHA256SUMS.txt').write_text(''.join(
                hashlib.sha256((bundle / name).read_bytes()).hexdigest() + '  ' + name + '\n'
                for name in (exe, 'release-provenance.txt')), encoding='utf-8')
            if fault == 'checksum':
                (bundle / exe).write_bytes(b'corrupted')
            if fault == 'extra_asset':
                (bundle / 'unexpected.msi').write_bytes(b'bad')
            if fault == 'duplicate_checksum':
                sums = bundle / 'SHA256SUMS.txt'
                sums.write_text(sums.read_text().splitlines()[0] + '\n' + sums.read_text().splitlines()[0] + '\n')
            if fault == 'invalid_tag':
                env['RELEASE_TAG'] = 'vfoo'
                env['TRIGGER_REF'] = 'refs/tags/vfoo'
            if fault == 'mapping':
                env['WINDOWS_VERSION'] = '0.9.0'

            def fake_gh(args, **kwargs):
                self.calls.append(args)
                command = args[1:]
                if command[0] == 'api':
                    path = command[-1]
                    if '/git/ref/tags/' in path:
                        if fault == 'missing_tag':
                            raise subprocess.CalledProcessError(1, args)
                        obj = {'type': 'commit', 'sha': '2' * 40 if fault == 'moved_tag' else sha}
                        if fault == 'annotated_tag':
                            obj = {'type': 'tag', 'sha': '3' * 40}
                        return json.dumps({'object': obj})
                    if '/git/tags/' in path:
                        return json.dumps({'object': {'type': 'commit', 'sha': sha}})
                    if '/compare/' in path:
                        return json.dumps({'status': 'diverged' if fault == 'side_branch' else 'ahead'})
                    if '/releases?' in path:
                        if fault == 'release_api_failure':
                            raise subprocess.CalledProcessError(1, args)
                        return json.dumps([[{'tag_name': tag}] if fault == 'existing_release' else []])
                if command[:2] == ['release', 'view']:
                    assets = [{'name': p.name, 'size': p.stat().st_size} for p in bundle.iterdir()]
                    if fault == 'asset_mismatch':
                        assets.pop()
                    return json.dumps({'isDraft': True, 'isPrerelease': not stable, 'tagName': tag, 'assets': assets})
                if command[:2] == ['release', 'download']:
                    destination = Path(command[command.index('--dir') + 1])
                    for p in bundle.iterdir():
                        (destination / p.name).write_bytes(p.read_bytes())
                    if fault == 'uploaded_corruption':
                        (destination / exe).write_bytes(b'corrupted remote bytes')
                    return ''
                if command[:2] == ['release', 'upload'] and fault == 'upload_failure':
                    raise subprocess.CalledProcessError(1, args)
                if command[:2] in (['release', 'create'], ['release', 'upload'], ['release', 'edit']):
                    return ''
                raise AssertionError(f'Unexpected mocked CLI call: {args}')

            previous = Path.cwd()
            try:
                os.chdir(tmp)
                with patch.dict(os.environ, env), patch('subprocess.check_output', fake_gh), contextlib.redirect_stdout(io.StringIO()):
                    try:
                        exec(compile(SOURCE, str(WORKFLOW), 'exec'), {})
                    except (AssertionError, subprocess.CalledProcessError) as exc:
                        self.failure = exc
            finally:
                os.chdir(previous)

    def mutations(self):
        return [c for c in self.calls if c[1:3] in
                (['release', 'create'], ['release', 'upload'], ['release', 'edit'])]

    def test_prerelease_draft_first_success(self):
        self.exercise()
        self.assertIsNone(self.failure)
        mutations = self.mutations()
        self.assertEqual([c[2] for c in mutations], ['create', 'upload', 'edit'])
        self.assertIn('--verify-tag', mutations[0])
        self.assertIn('--draft', mutations[0])
        self.assertIn('--generate-notes', mutations[0])
        self.assertIn('--latest=false', mutations[0])
        self.assertIn('--verify-tag', mutations[-1])
        self.assertIn('--prerelease=true', mutations[-1])
        self.assertIn('--latest=false', mutations[-1])
        download_index = next(i for i, c in enumerate(self.calls) if c[1:3] == ['release', 'download'])
        edit_index = next(i for i, c in enumerate(self.calls) if c[1:3] == ['release', 'edit'])
        self.assertLess(download_index, edit_index)

    def test_stable_success(self):
        self.exercise(stable=True)
        self.assertIsNone(self.failure)
        self.assertIn('--prerelease=false', self.mutations()[-1])
        self.assertIn('--latest=true', self.mutations()[-1])

    def test_annotated_tag_success(self):
        self.exercise('annotated_tag')
        self.assertIsNone(self.failure)

    def test_failures_before_draft_have_no_mutations(self):
        for fault in ('checksum', 'provenance', 'extra_asset', 'duplicate_checksum', 'invalid_tag',
                      'mapping', 'missing_tag', 'moved_tag', 'side_branch', 'existing_release', 'release_api_failure'):
            with self.subTest(fault=fault):
                self.exercise(fault)
                self.assertIsNotNone(self.failure)
                self.assertEqual(self.mutations(), [])

    def test_failures_after_draft_never_publish(self):
        for fault in ('upload_failure', 'asset_mismatch', 'uploaded_corruption'):
            with self.subTest(fault=fault):
                self.exercise(fault)
                self.assertIsNotNone(self.failure)
                self.assertTrue(any(c[2] == 'create' for c in self.mutations()))
                self.assertFalse(any(c[2] == 'edit' for c in self.mutations()))

    def test_workflow_event_and_permission_boundary(self):
        text = WORKFLOW.read_text(encoding='utf-8')
        publish = text.split('\n  publish:\n', 1)[1]
        self.assertIn("if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/') && needs.build.outputs.real-tag == 'true'", publish)
        self.assertEqual(text.count('contents: write'), 1)
        self.assertNotIn('checkout@', publish)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn("java-version: '21.0.6+7.0.LTS'", text)
        self.assertIn("'java.runtime.version' = '21.0.6+7-LTS'", text)
        self.assertNotIn('pull_request_target', text)
        self.assertNotIn('git tag', text)
        self.assertIsNotNone(re.search(r'workflow_dispatch:\n    inputs:\n      release-tag:', text))


if __name__ == '__main__':
    unittest.main()
