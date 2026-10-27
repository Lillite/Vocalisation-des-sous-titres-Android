#!/usr/bin/env python3
"""Offline regression tests for the pre-merge proof and rebuilt APK comparison."""
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('pr_validation', Path(__file__).with_name('verify-pr-validation.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SOURCE, BASE, HEAD, TREE, TESTED = (c * 40 for c in 'abcde')
REPO = gate.p.REPOSITORY


class Fixture:
    def __init__(self):
        self.parents = [BASE, HEAD]
        self.current = SOURCE
        self.pull = {'number': 12, 'state': 'closed', 'merged_at': '2026-10-27T12:00:00Z', 'merged': True,
                     'base': {'ref': 'main', 'repo': {'full_name': REPO}},
                     'head': {'sha': HEAD, 'repo': {'full_name': REPO}}}
        self.runs = [{'id': 42, 'run_attempt': 1, 'head_sha': HEAD, 'event': 'pull_request',
                      'path': '.github/workflows/android.yml', 'head_repository': {'full_name': REPO},
                      'status': 'completed', 'conclusion': 'success'}]

    def api(self, path):
        suffix = path.removeprefix(gate.p.PREFIX)
        if suffix == '/git/ref/heads/main': return {'object': {'sha': self.current}}
        if suffix == '/git/commits/' + SOURCE:
            return {'sha': SOURCE, 'tree': {'sha': TREE}, 'parents': [{'sha': x} for x in self.parents]}
        if suffix == '/commits/' + HEAD + '/pulls?per_page=100': return [deepcopy(self.pull)]
        if suffix == '/pulls/12': return deepcopy(self.pull)
        if suffix.startswith('/actions/workflows/android.yml/runs?'):
            return {'total_count': len(self.runs), 'workflow_runs': deepcopy(self.runs)}
        raise AssertionError('Unexpected endpoint: ' + suffix)


class ValidationTests(unittest.TestCase):
    def test_exact_merged_pr_with_successful_apk_validation_is_accepted(self):
        proof = gate.select_run(Fixture(), SOURCE)
        self.assertEqual([BASE, HEAD], proof['source_parents'])
        self.assertEqual(TREE, proof['source_tree'])
        self.assertEqual(42, proof['validation_run'])

    def test_newer_failed_pending_or_cancelled_run_never_falls_back_to_old_success(self):
        for status, conclusion in [('completed', 'failure'), ('in_progress', None), ('completed', 'cancelled')]:
            with self.subTest(status=status, conclusion=conclusion):
                fixture = Fixture()
                fixture.runs.append(dict(fixture.runs[0], id=43, status=status, conclusion=conclusion))
                with self.assertRaises(gate.p.Refusal): gate.select_run(fixture, SOURCE)

    def test_wrong_head_workflow_event_or_repository_is_rejected(self):
        for change in [{'head_sha': BASE}, {'event': 'workflow_dispatch'},
                       {'path': '.github/workflows/other.yml'}, {'head_repository': {'full_name': 'other/repo'}}]:
            with self.subTest(change=change):
                fixture = Fixture()
                fixture.runs[0].update(change)
                with self.assertRaises(gate.p.Refusal): gate.select_run(fixture, SOURCE)

    def test_unmerged_pr_direct_push_and_moved_main_are_rejected(self):
        fixture = Fixture()
        fixture.pull['merged'] = False
        with self.assertRaises(gate.p.Refusal): gate.select_run(fixture, SOURCE)
        fixture = Fixture()
        fixture.parents = [HEAD]
        with self.assertRaises(gate.p.Refusal): gate.select_run(fixture, SOURCE)
        fixture = Fixture()
        fixture.current = HEAD
        with self.assertRaises(gate.p.Refusal): gate.select_run(fixture, SOURCE)

    def artifact(self, directory, *, tree=TREE, parents=None, duplicate=False):
        bundle = directory / 'bundle.zip'
        with zipfile.ZipFile(bundle, 'w') as z:
            z.writestr('proof/source-commit.txt', TESTED + '\n')
            z.writestr('proof/source-tree.txt', tree + '\n')
            z.writestr('proof/source-parents.txt', ' '.join(parents or [BASE, HEAD]) + '\n')
            z.writestr('apk/app-release.apk', b'synthetic APK bytes')
            if duplicate: z.writestr('other/app-release.apk', b'ambiguous APK')
        return bundle

    def test_artifact_proves_tree_and_both_merge_parents(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            proof = gate.select_run(Fixture(), SOURCE)
            bundle = self.artifact(directory)
            gate.read_artifact(bundle, directory / 'apk', proof)
            self.assertEqual(TESTED, proof['tested_merge_sha'])
            for changes in [{'tree': HEAD}, {'parents': [HEAD, BASE]}, {'duplicate': True}]:
                with self.subTest(changes=changes):
                    bundle = self.artifact(directory, **changes)
                    with self.assertRaises(gate.p.Refusal):
                        gate.read_artifact(bundle, directory / 'apk', proof)

    def write_apk(self, path, dex=b'code', signed=False):
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('AndroidManifest.xml', b'manifest')
            z.writestr('classes.dex', dex)
            z.writestr('resources.arsc', b'resources')
            if signed:
                z.writestr('META-INF/SIGNER.SF', b'signature only')
                z.writestr('META-INF/SIGNER.RSA', b'signer')

    def test_rebuilt_payload_may_change_signature_but_never_code(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            tested = directory / 'app-release.apk'
            rebuilt = directory / 'rebuilt.apk'
            self.write_apk(tested, signed=True)
            self.write_apk(rebuilt)
            proof = {'source_sha': SOURCE, 'tested_apk_sha256': gate.p.sha256(tested)}
            (directory / 'proof.json').write_text(json.dumps(proof))
            with patch.object(gate, 'EVIDENCE', directory), patch.dict(os.environ, {'GITHUB_SHA': SOURCE}, clear=True):
                gate.compare(rebuilt)
                self.assertTrue(json.loads((directory / 'proof.json').read_text())['payload_identical'])
                self.write_apk(rebuilt, dex=b'changed code')
                with self.assertRaises(gate.p.Refusal): gate.compare(rebuilt)


if __name__ == '__main__':
    unittest.main(verbosity=2)
