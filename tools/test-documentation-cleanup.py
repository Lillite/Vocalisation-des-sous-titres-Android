#!/usr/bin/env python3
"""Offline checks of the documentation workflow's cleanup preconditions.

The real inline workflow is executed with a strict in-memory GitHub client.
The publisher's ancestry/archive algorithm has its own behavioral test suite;
these tests verify that this workflow calls it only after all required gates.
"""
from contextlib import redirect_stdout
from copy import deepcopy
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/releases.yml'
specification = importlib.util.spec_from_file_location('documentation_cleanup_publisher', ROOT / 'tools/publish-verified-release.py')
publisher = importlib.util.module_from_spec(specification)
specification.loader.exec_module(publisher)
BRANCH = 'work/documentation-0.4.0-20261024'
SOURCE = 'a' * 40
HEAD = 'b' * 40
ADVANCED_HEAD = 'c' * 40
FIRST_PARENT = 'd' * 40
PULL_NUMBER = 12


def workflow_code():
    lines = WORKFLOW.read_text(encoding='utf-8').splitlines()
    marker = '      - name: Archiver la seule branche documentaire apres fusion et validation Android'
    if lines.count(marker) != 1:
        raise AssertionError('Expected exactly one documentation cleanup step')
    begin = lines.index("          python3 - <<'PY'", lines.index(marker)) + 1
    end = lines.index('          PY', begin)
    source = []
    for line in lines[begin:end]:
        if line and not line.startswith(' ' * 10):
            raise AssertionError('Unexpected workflow Python indentation')
        source.append(line[10:] if line else '')
    return compile('\n'.join(source) + '\n', str(WORKFLOW), 'exec')


class GitHubFixture:
    """Every unexpected endpoint or attempted direct mutation fails a test."""
    def __init__(self):
        self.calls = []
        self.absent = False
        self.advance = False
        self.branch_reads = 0
        self.draft = False
        self.head_sha = HEAD
        self.pulls = [{
            'number': PULL_NUMBER, 'state': 'closed', 'merged_at': '2026-10-24T01:00:00Z',
            'base': {'ref': 'main', 'repo': {'full_name': publisher.REPOSITORY}},
            'head': {'ref': BRANCH, 'sha': HEAD, 'repo': {'full_name': publisher.REPOSITORY}},
        }]
        # The actual 2026-03-10 response has no PR merge-commit field.
        # Keep list/details independent so a stale or divergent response fails.
        self.pull_details = dict(deepcopy(self.pulls[0]), merged=True)
        self.merge_commit = {'sha': SOURCE, 'parents': [{'sha': FIRST_PARENT}, {'sha': HEAD}]}

    def api(self, path, method='GET', data=None, expected=(200,), absent=False):
        self.calls.append((method, path))
        if method != 'GET':
            raise AssertionError('The orchestration must delegate all mutations to the checked archive helper')
        suffix = path.removeprefix(publisher.PREFIX)
        if suffix == '/git/ref/heads/' + BRANCH:
            self.branch_reads += 1
            if self.absent:
                if not absent:
                    raise AssertionError('Missing branch queried without absent=True')
                return None
            sha = ADVANCED_HEAD if self.advance and self.branch_reads > 1 else self.head_sha
            return {'ref': 'refs/heads/' + BRANCH, 'object': {'type': 'commit', 'sha': sha}}
        if suffix.startswith('/pulls?'):
            query = parse_qs(suffix.partition('?')[2])
            if query != {'state': ['closed'], 'base': ['main'], 'head': ['Lillite:' + BRANCH], 'per_page': ['100']}:
                raise AssertionError('Pull requests must be restricted to the exact repository branch and base')
            return deepcopy(self.pulls)
        if suffix == '/pulls/' + str(PULL_NUMBER):
            return deepcopy(self.pull_details)
        if suffix == '/git/commits/' + SOURCE:
            return deepcopy(self.merge_commit)
        if suffix == '/releases/tags/v0.4.0':
            return {'tag_name': 'v0.4.0', 'draft': self.draft}
        raise AssertionError('Unexpected mocked endpoint: ' + suffix)


class DocumentationCleanupTests(unittest.TestCase):
    def setUp(self):
        self.client = GitHubFixture()
        self.ci_calls, self.archive_calls = [], []
        self.ci_failure = False
        self.temporary = tempfile.TemporaryDirectory(prefix='documentation-cleanup-test-')
        self.addCleanup(self.temporary.cleanup)
        self.summary = Path(self.temporary.name) / 'summary.md'
        self.module = SimpleNamespace(
            REPOSITORY=publisher.REPOSITORY, PREFIX=publisher.PREFIX, HEX40=publisher.HEX40,
            require=publisher.require, ARCHIVABLE_BRANCHES=publisher.ARCHIVABLE_BRANCHES,
            GitHub=lambda token: self.client,
            successful_main_run=self.successful_main_run,
            archive_merged_branches=self.archive_merged_branches,
        )

    def successful_main_run(self, github, source):
        self.assertIs(github, self.client)
        self.assertEqual(45 * 60, self.module.CI_WAIT_SECONDS)
        self.ci_calls.append(source)
        if self.ci_failure:
            raise publisher.Refusal('Synthetic failed or changed main validation')
        return {'id': 123, 'head_sha': source, 'conclusion': 'success'}

    def archive_merged_branches(self, github, source):
        self.assertIs(github, self.client)
        self.archive_calls.append((source, self.module.ARCHIVABLE_BRANCHES))
        return [{'branch': BRANCH, 'result': 'archived-and-deleted',
                 'reason': 'refs/tags/archive/20261024/' + BRANCH + ' → ' + HEAD}]

    def run_workflow(self, **environment_changes):
        environment = {
            'GITHUB_REPOSITORY': publisher.REPOSITORY, 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_SHA': SOURCE, 'GITHUB_TOKEN': 'synthetic-test-token',
            'GITHUB_STEP_SUMMARY': str(self.summary),
        }
        environment.update(environment_changes)
        loader = SimpleNamespace(exec_module=lambda module: None)
        with patch.dict(os.environ, environment, clear=True), \
                patch('importlib.util.spec_from_file_location', return_value=SimpleNamespace(loader=loader)), \
                patch('importlib.util.module_from_spec', return_value=self.module), \
                patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('Network disabled in cleanup tests')), \
                redirect_stdout(io.StringIO()):
            exec(workflow_code(), {'__name__': '__documentation_cleanup_test__'})

    def assert_not_archived(self):
        self.assertEqual([], self.archive_calls)
        self.assertFalse(self.summary.exists())

    def test_absent_branch_is_noop_before_ci_wait(self):
        self.client.absent = True
        with self.assertRaises(SystemExit) as result:
            self.run_workflow()
        self.assertEqual(0, result.exception.code)
        self.assertEqual([], self.ci_calls)
        self.assertEqual(1, len(self.client.calls))
        self.assert_not_archived()

    def test_only_one_exact_merged_pull_request_is_accepted(self):
        original = deepcopy(self.client.pulls)
        for corruption in ('unmerged', 'state', 'base-ref', 'base-repository', 'head-ref', 'head-sha',
                           'head-repository', 'number-missing', 'number-bool', 'number-zero', 'number-text',
                           'duplicate', 'incomplete-page', 'not-a-list'):
            with self.subTest(corruption=corruption):
                self.client.pulls = deepcopy(original)
                pull = self.client.pulls[0]
                if corruption == 'unmerged': pull['merged_at'] = None
                elif corruption == 'state': pull['state'] = 'open'
                elif corruption == 'base-ref': pull['base']['ref'] = 'synthetic-other'
                elif corruption == 'base-repository': pull['base']['repo']['full_name'] = 'synthetic/other'
                elif corruption == 'head-ref': pull['head']['ref'] = 'work/synthetic-other'
                elif corruption == 'head-sha': pull['head']['sha'] = ADVANCED_HEAD
                elif corruption == 'head-repository': pull['head']['repo']['full_name'] = 'synthetic/other'
                elif corruption == 'number-missing': del pull['number']
                elif corruption == 'number-bool': pull['number'] = True
                elif corruption == 'number-zero': pull['number'] = 0
                elif corruption == 'number-text': pull['number'] = str(PULL_NUMBER)
                elif corruption == 'duplicate': self.client.pulls *= 2
                elif corruption == 'incomplete-page': self.client.pulls *= 100
                else: self.client.pulls = {'unexpected': 'object'}
                with self.assertRaises(publisher.Refusal):
                    self.run_workflow()
                self.assertEqual([], self.ci_calls)
                self.assert_not_archived()

    def test_pull_details_independently_confirm_exact_closed_merge(self):
        original = deepcopy(self.client.pull_details)
        for corruption in ('unmerged', 'merged-missing', 'merged-text', 'closed-without-merged-at',
                           'state', 'number', 'number-bool', 'base-ref', 'base-repository',
                           'head-ref', 'head-sha', 'head-repository', 'head-missing', 'not-an-object'):
            with self.subTest(corruption=corruption):
                self.client.pull_details = deepcopy(original)
                pull = self.client.pull_details
                if corruption == 'unmerged': pull['merged'] = False
                elif corruption == 'merged-missing': del pull['merged']
                elif corruption == 'merged-text': pull['merged'] = 'true'
                elif corruption == 'closed-without-merged-at': pull['merged_at'] = None
                elif corruption == 'state': pull['state'] = 'open'
                elif corruption == 'number': pull['number'] = PULL_NUMBER + 1
                elif corruption == 'number-bool': pull['number'] = True
                elif corruption == 'base-ref': pull['base']['ref'] = 'synthetic-other'
                elif corruption == 'base-repository': pull['base']['repo']['full_name'] = 'synthetic/other'
                elif corruption == 'head-ref': pull['head']['ref'] = 'work/synthetic-other'
                elif corruption == 'head-sha': pull['head']['sha'] = ADVANCED_HEAD
                elif corruption == 'head-repository': pull['head']['repo']['full_name'] = 'synthetic/other'
                elif corruption == 'head-missing': pull['head'] = None
                else: self.client.pull_details = []
                with self.assertRaises(publisher.Refusal):
                    self.run_workflow()
                self.assertEqual([], self.ci_calls)
                self.assert_not_archived()

    def test_only_exact_two_parent_git_merge_proves_main_integration(self):
        original = deepcopy(self.client.merge_commit)
        for corruption in ('source', 'source-missing', 'one-parent', 'three-parents', 'parent-order',
                           'second-parent', 'same-parents', 'invalid-first-parent', 'missing-first-sha',
                           'non-string-first-sha', 'parents-object', 'parent-not-object', 'not-an-object'):
            with self.subTest(corruption=corruption):
                self.client.merge_commit = deepcopy(original)
                commit = self.client.merge_commit
                if corruption == 'source': commit['sha'] = ADVANCED_HEAD
                elif corruption == 'source-missing': del commit['sha']
                elif corruption == 'one-parent': commit['parents'] = [{'sha': HEAD}]
                elif corruption == 'three-parents': commit['parents'].append({'sha': ADVANCED_HEAD})
                elif corruption == 'parent-order': commit['parents'].reverse()
                elif corruption == 'second-parent': commit['parents'][1]['sha'] = ADVANCED_HEAD
                elif corruption == 'same-parents': commit['parents'][0]['sha'] = HEAD
                elif corruption == 'invalid-first-parent': commit['parents'][0]['sha'] = 'invalid'
                elif corruption == 'missing-first-sha': del commit['parents'][0]['sha']
                elif corruption == 'non-string-first-sha': commit['parents'][0]['sha'] = 123
                elif corruption == 'parents-object': commit['parents'] = {'sha': HEAD}
                elif corruption == 'parent-not-object': commit['parents'][1] = HEAD
                else: self.client.merge_commit = []
                with self.assertRaises(publisher.Refusal):
                    self.run_workflow()
                self.assertEqual([], self.ci_calls)
                self.assert_not_archived()

    def test_2026_api_without_merge_commit_field_uses_details_and_git_before_ci(self):
        self.assertNotIn('merge_commit_sha', self.client.pulls[0])
        self.assertNotIn('merge_commit_sha', self.client.pull_details)
        self.run_workflow()
        paths = [path.removeprefix(publisher.PREFIX) for method, path in self.client.calls]
        self.assertEqual('/git/ref/heads/' + BRANCH, paths[0])
        self.assertTrue(paths[1].startswith('/pulls?'))
        self.assertEqual(['/pulls/' + str(PULL_NUMBER), '/git/commits/' + SOURCE,
                          '/releases/tags/v0.4.0', '/git/ref/heads/' + BRANCH], paths[2:])
        self.assertEqual([SOURCE], self.ci_calls)
        self.assertEqual([(SOURCE, (BRANCH,))], self.archive_calls)

    def test_draft_release_blocks_ci_wait_and_cleanup(self):
        self.client.draft = True
        with self.assertRaises(publisher.Refusal):
            self.run_workflow()
        self.assertEqual([], self.ci_calls)
        self.assert_not_archived()

    def test_failed_or_changed_main_validation_prevents_archive(self):
        self.ci_failure = True
        with self.assertRaises(publisher.Refusal):
            self.run_workflow()
        self.assertEqual([SOURCE], self.ci_calls)
        self.assert_not_archived()

    def test_branch_advanced_during_ci_wait_is_preserved(self):
        self.client.advance = True
        with self.assertRaises(publisher.Refusal):
            self.run_workflow()
        self.assertEqual([SOURCE], self.ci_calls)
        self.assertEqual(2, self.client.branch_reads)
        self.assert_not_archived()

    def test_success_limits_archive_to_exact_branch_after_ci_and_writes_summary(self):
        self.run_workflow()
        self.assertEqual([SOURCE], self.ci_calls)
        self.assertEqual([(SOURCE, (BRANCH,))], self.archive_calls)
        self.assertEqual(2, self.client.branch_reads)
        summary = self.summary.read_text(encoding='utf-8')
        self.assertIn(BRANCH, summary)
        self.assertIn('archived-and-deleted', summary)
        self.assertIn(HEAD, summary)
        self.assertNotIn('synthetic-test-token', summary)

    def test_wrong_repository_ref_or_source_cannot_archive(self):
        for changes in ({'GITHUB_REPOSITORY': 'synthetic/other'},
                        {'GITHUB_REF': 'refs/heads/work/other'},
                        {'GITHUB_SHA': 'invalid-source'}):
            with self.subTest(changes=changes):
                with self.assertRaises(publisher.Refusal):
                    self.run_workflow(**changes)
                self.assertEqual([], self.ci_calls)
                self.assert_not_archived()
        self.client.head_sha = 'invalid-head'
        with self.assertRaises(publisher.Refusal):
            self.run_workflow()
        self.assertEqual([], self.ci_calls)
        self.assert_not_archived()


if __name__ == '__main__':
    unittest.main(verbosity=2)
