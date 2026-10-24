"""Offline behavioral checks for the exact release publisher; never contacts GitHub."""
from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().with_name('publish-verified-release.py')
spec = importlib.util.spec_from_file_location('offline_verified_release', SCRIPT)
publisher = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.dont_write_bytecode = True
spec.loader.exec_module(publisher)
SOURCE = 'a' * 40
OLD_HEAD = 'b' * 40
NEW_HEAD = 'c' * 40
PUBLIC_URL = f'https://github.com/{publisher.REPOSITORY}/releases/tag/{publisher.TAG}'


class DownloadTransportTests(unittest.TestCase):
    def assert_download_transport(self, path, api_accept, cdn):
        """Exercise real request/header construction without opening a socket."""
        api_url = 'https://api.github.com' + path
        payload = b'\x00synthetic binary download\xff'
        requests = []
        test = self

        class Response(io.BytesIO):
            status = 200
            headers = {'Content-Length': str(len(payload))}

        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                test.assertEqual(90, timeout)
                test.assertEqual('GET', request.get_method())
                test.assertIsNone(request.data)
                if len(requests) == 1:
                    test.assertEqual(api_url, request.full_url)
                    test.assertEqual('Bearer synthetic-workflow-token', request.get_header('Authorization'))
                    test.assertEqual(publisher.API_VERSION, request.get_header('X-github-api-version'))
                    # The actual Actions endpoint refused the former octet-stream
                    # header with HTTP 415 before returning its signed redirect.
                    if request.get_header('Accept') != api_accept:
                        raise publisher.urllib.error.HTTPError(api_url, 415, 'Synthetic unsupported media type', {}, None)
                    raise publisher.urllib.error.HTTPError(api_url, 302, 'Synthetic redirect', {'Location': cdn}, None)
                test.assertEqual(2, len(requests), 'No extra network or retry request is allowed')
                test.assertEqual(cdn, request.full_url)
                test.assertEqual('application/octet-stream', request.get_header('Accept'))
                test.assertFalse(request.has_header('Authorization'), 'The workflow token must never reach the CDN')
                return Response(payload)

        github = publisher.GitHub('synthetic-workflow-token')
        github._opener = Opener()
        with tempfile.TemporaryDirectory(prefix='release-download-test-') as directory:
            destination = Path(directory) / 'binary'
            github.download(path, destination)
            self.assertEqual(payload, destination.read_bytes())
        self.assertEqual([api_url, cdn], [request.full_url for request in requests])

    def test_actions_artifact_uses_json_api_accept_then_unauthenticated_binary_cdn(self):
        self.assert_download_transport(
            publisher.PREFIX + '/actions/artifacts/123/zip', 'application/vnd.github+json',
            'https://fixture.blob.core.windows.net/artifacts/synthetic.zip?fixture=only')

    def test_release_asset_keeps_binary_api_accept_then_unauthenticated_binary_cdn(self):
        self.assert_download_transport(
            publisher.PREFIX + '/releases/assets/456', 'application/octet-stream',
            'https://release-assets.githubusercontent.com/synthetic-fixture.apk?fixture=only')


class PublicationGitHub:
    """In-memory GitHub protocol. Every unexpected request fails the test."""
    def __init__(self):
        self.calls = []
        self.mutations = []
        self.main_reads = 0
        self.move_main_on_read = None
        self.existing_tag = False
        self.existing_release = False
        self.draft_collision = False
        self.corrupt_asset = False
        self.change_ci = False
        self.change_draft_assets = False
        self.draft = None
        self.assets = {}
        self.ci_run = {'id': 111, 'run_attempt': 1, 'head_sha': SOURCE,
                       'head_branch': 'main', 'event': 'push', 'status': 'completed',
                       'conclusion': 'success', 'head_repository': {'full_name': publisher.REPOSITORY}}

    def api(self, path, method='GET', data=None, expected=(200,), absent=False):
        self.calls.append((method, path, deepcopy(data)))
        if method != 'GET':
            self.mutations.append((method, path, deepcopy(data)))
        suffix = path.removeprefix(publisher.PREFIX)
        if suffix == '/git/ref/heads/main':
            self.main_reads += 1
            moved = self.move_main_on_read is not None and self.main_reads >= self.move_main_on_read
            return {'object': {'sha': NEW_HEAD if moved else SOURCE, 'type': 'commit'}}
        if suffix == '/git/ref/tags/' + publisher.TAG:
            if self.existing_tag or (self.draft is not None and not self.draft['draft']):
                return {'object': {'sha': SOURCE, 'type': 'commit'}}
            assert absent, 'Uncreated tag requested without absent=True'
            return None
        if suffix == '/releases/tags/' + publisher.TAG:
            assert absent
            return {'tag_name': publisher.TAG} if self.existing_release else None
        if suffix.startswith('/releases?'):
            return [{'id': 99, 'tag_name': publisher.TAG, 'draft': True}] if self.draft_collision else []
        if suffix == '/releases' and method == 'POST':
            assert data['draft'] is True
            self.draft = {**deepcopy(data), 'id': 12, 'assets': []}
            return deepcopy(self.draft)
        if suffix.startswith('/actions/workflows/android.yml/runs?'):
            run = deepcopy(self.ci_run)
            if self.change_ci:
                run['id'] += 1
            return {'workflow_runs': [run]}
        if suffix == '/releases/12' and method == 'GET':
            result = deepcopy(self.draft)
            if self.change_draft_assets:
                result['assets'].append({'id': 99})
            return result
        if suffix == '/releases/12' and method == 'PATCH':
            assert data == {'draft': False, 'make_latest': 'true'}
            self.draft.update(draft=False, prerelease=False, html_url=PUBLIC_URL)
            return deepcopy(self.draft)
        raise AssertionError(f'Unexpected mocked GitHub operation: {method} {suffix}')

    def upload(self, release_id, file, content_type):
        assert release_id == 12 and self.draft['draft'] is True
        identity = 30 + len(self.assets)
        payload = file.read_bytes()
        self.calls.append(('UPLOAD', file.name, len(payload)))
        self.mutations.append(('UPLOAD', file.name, len(payload)))
        self.assets[identity] = payload
        asset = {'id': identity, 'state': 'uploaded', 'name': file.name, 'size': len(payload)}
        self.draft['assets'].append(deepcopy(asset))
        return asset

    def download(self, path, destination, maximum=publisher.MAX_BINARY_BYTES):
        identity = int(path.rsplit('/', 1)[-1])
        self.calls.append(('DOWNLOAD', identity, None))
        payload = self.assets[identity]
        destination.write_bytes(payload + b'changed-after-upload' if self.corrupt_asset else payload)


class ArchiveGitHub:
    def __init__(self):
        self.calls = []
        self.mutations = []
        self.branches = {
            'main': SOURCE, 'backup/retained': OLD_HEAD, 'unknown/private-work': NEW_HEAD,
            publisher.ARCHIVABLE_BRANCHES[0]: OLD_HEAD,
        }
        self.branch_reads = {}
        self.tags = {}
        self.advance_branch = False
        self.wrong_archive = False
        self.unmerged = False
        self.main_moved = False

    def api(self, path, method='GET', data=None, expected=(200,), absent=False):
        self.calls.append((method, path, deepcopy(data)))
        if method != 'GET':
            self.mutations.append((method, path, deepcopy(data)))
        suffix = path.removeprefix(publisher.PREFIX)
        if suffix == '/git/ref/heads/main':
            return {'object': {'sha': NEW_HEAD if self.main_moved else SOURCE, 'type': 'commit'}}
        if suffix.startswith('/git/ref/heads/'):
            branch = suffix.removeprefix('/git/ref/heads/')
            if branch not in self.branches:
                assert absent
                return None
            self.branch_reads[branch] = self.branch_reads.get(branch, 0) + 1
            if self.advance_branch and self.branch_reads[branch] > 1:
                self.branches[branch] = NEW_HEAD
            return {'ref': 'refs/heads/' + branch,
                    'object': {'type': 'commit', 'sha': self.branches[branch]}}
        if suffix.startswith('/compare/'):
            return {'status': 'diverged' if self.unmerged else 'ahead',
                    'merge_base_commit': {'sha': NEW_HEAD if self.unmerged else OLD_HEAD}}
        if suffix.startswith('/git/ref/tags/'):
            name = 'refs/' + suffix.removeprefix('/git/ref/')
            if name not in self.tags:
                assert absent
                return None
            value = deepcopy(self.tags[name])
            if self.wrong_archive:
                value['object']['sha'] = NEW_HEAD
            return value
        if suffix == '/git/refs' and method == 'POST':
            ref = data['ref']
            assert ref.startswith('refs/tags/archive/')
            assert ref not in self.tags
            self.tags[ref] = {'ref': ref, 'object': {'type': 'commit', 'sha': data['sha']}}
            return deepcopy(self.tags[ref])
        if suffix.startswith('/git/refs/heads/') and method == 'DELETE':
            branch = suffix.removeprefix('/git/refs/heads/')
            assert branch in publisher.ARCHIVABLE_BRANCHES
            archive = 'refs/tags/archive/20261024/' + branch
            assert archive in self.tags, 'Branch removed before archive creation'
            assert self.tags[archive]['object']['sha'] == self.branches[branch], 'Exact branch head was not retained'
            del self.branches[branch]
            return None
        raise AssertionError(f'Unexpected archive operation: {method} {suffix}')


class PublishBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='publisher-offline-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'root'
        self.work = Path(self.temporary.name) / 'work'
        self.root.mkdir(); self.work.mkdir()
        apk = self.root / publisher.APK_PATH
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b'synthetic-already-verified-apk-fixture')
        (self.root / publisher.NOTES_PATH).write_text('Synthetic verified release notes.', encoding='utf-8')
        self.value = {'source_sha': SOURCE, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
        self.github = PublicationGitHub()

    def publish(self):
        with redirect_stdout(io.StringIO()):
            return publisher.publish(self.root, self.github, self.value, self.work, self.github.ci_run)

    def assert_never_public(self):
        self.assertFalse(any(method == 'PATCH' and data.get('draft') is False
                             for method, _, data in self.github.mutations if isinstance(data, dict)))
        self.assertTrue(self.github.draft is None or self.github.draft['draft'])

    def test_success_creates_draft_uploads_download_verifies_then_publishes(self):
        self.assertEqual(PUBLIC_URL, self.publish())
        events = [call[0] for call in self.github.calls]
        post = next(i for i, call in enumerate(self.github.calls) if call[0] == 'POST')
        public = next(i for i, call in enumerate(self.github.calls) if call[0] == 'PATCH')
        downloads = [i for i, call in enumerate(self.github.calls) if call[0] == 'DOWNLOAD']
        uploads = [i for i, call in enumerate(self.github.calls) if call[0] == 'UPLOAD']
        self.assertEqual(2, len(uploads)); self.assertEqual(2, len(downloads))
        self.assertTrue(post < min(uploads) < min(downloads) < public)
        self.assertTrue(max(downloads) < public)
        self.assertEqual(['POST', 'UPLOAD', 'UPLOAD', 'PATCH'], [item[0] for item in self.github.mutations])
        self.assertFalse(self.github.draft['draft'])

    def test_changed_asset_bytes_leave_draft_private(self):
        self.github.corrupt_asset = True
        with self.assertRaisesRegex(publisher.Refusal, 'hash differs'):
            self.publish()
        self.assert_never_public()

    def test_main_moved_before_work_causes_no_mutation(self):
        self.github.move_main_on_read = 1
        with self.assertRaisesRegex(publisher.Refusal, 'main no longer equals'):
            self.publish()
        self.assertEqual([], self.github.mutations)

    def test_main_moved_after_upload_leaves_draft_private(self):
        self.github.move_main_on_read = 2
        with self.assertRaisesRegex(publisher.Refusal, 'main no longer equals'):
            self.publish()
        self.assert_never_public()

    def test_existing_tag_causes_no_mutation(self):
        self.github.existing_tag = True
        with self.assertRaisesRegex(publisher.Refusal, 'tag already exists'):
            self.publish()
        self.assertEqual([], self.github.mutations)

    def test_existing_public_release_causes_no_mutation(self):
        self.github.existing_release = True
        with self.assertRaisesRegex(publisher.Refusal, 'release already exists'):
            self.publish()
        self.assertEqual([], self.github.mutations)

    def test_existing_draft_without_tag_causes_no_mutation(self):
        self.github.draft_collision = True
        with self.assertRaisesRegex(publisher.Refusal, 'draft or published release'):
            self.publish()
        self.assertEqual([], self.github.mutations)

    def test_ci_run_changed_after_upload_leaves_draft_private(self):
        self.github.change_ci = True
        with self.assertRaisesRegex(publisher.Refusal, 'validation changed'):
            self.publish()
        self.assert_never_public()

    def test_draft_assets_changed_after_verification_leave_draft_private(self):
        scenarios = {
            'extra-asset': lambda draft: draft['assets'].append({'id': 99}),
            'wrong-title': lambda draft: draft.update(name='Other release'),
            'wrong-notes': lambda draft: draft.update(body='Notes changed after verification'),
            'wrong-tag': lambda draft: draft.update(tag_name='v9.9.9'),
            'wrong-release-id': lambda draft: draft.update(id=99),
            'wrong-prerelease': lambda draft: draft.update(prerelease=True),
            'asset-name': lambda draft: draft['assets'][0].update(name='different.apk'),
            'asset-size': lambda draft: draft['assets'][0].update(size=999),
            'asset-state': lambda draft: draft['assets'][0].update(state='starter'),
            'asset-digest': lambda draft: draft['assets'][0].update(digest='sha256:' + '0' * 64),
            'duplicate-asset-id': lambda draft: draft['assets'][1].update(id=draft['assets'][0]['id']),
        }
        for scenario, mutate in scenarios.items():
            with self.subTest(scenario=scenario):
                self.github = PublicationGitHub()
                original_api = self.github.api
                def changing_api(path, method='GET', data=None, expected=(200,), absent=False):
                    result = original_api(path, method, data, expected, absent)
                    if path == publisher.PREFIX + '/releases/12' and method == 'GET':
                        mutate(result)
                    return result
                self.github.api = changing_api
                with self.assertRaises(publisher.Refusal):
                    self.publish()
                self.assert_never_public()


    def test_changed_draft_target_is_refused_before_publication(self):
        original_api = self.github.api
        def changing_api(path, method='GET', data=None, expected=(200,), absent=False):
            if path == publisher.PREFIX + '/releases/12' and method == 'GET':
                self.github.draft['target_commitish'] = NEW_HEAD
            if path == publisher.PREFIX + '/git/ref/tags/' + publisher.TAG and self.github.draft is not None and not self.github.draft['draft']:
                return {'object': {'sha': self.github.draft['target_commitish'], 'type': 'commit'}}
            return original_api(path, method, data, expected, absent)
        self.github.api = changing_api
        with self.assertRaises(publisher.Refusal):
            self.publish()
        self.assert_never_public()

    def test_wrong_tag_created_during_upload_is_refused_before_publication(self):
        original_api = self.github.api
        def changing_api(path, method='GET', data=None, expected=(200,), absent=False):
            if path == publisher.PREFIX + '/git/ref/tags/' + publisher.TAG and self.github.draft is not None:
                return {'object': {'sha': NEW_HEAD, 'type': 'commit'}}
            return original_api(path, method, data, expected, absent)
        self.github.api = changing_api
        with self.assertRaises(publisher.Refusal):
            self.publish()
        self.assert_never_public()


class ArchiveBehaviorTests(unittest.TestCase):
    def archive(self, github):
        with redirect_stdout(io.StringIO()):
            return publisher.archive_merged_branches(github, SOURCE)

    def test_only_allowlisted_merged_branch_deleted_after_exact_archive_readback(self):
        github = ArchiveGitHub()
        branch = publisher.ARCHIVABLE_BRANCHES[0]
        results = self.archive(github)
        self.assertEqual('archived-and-deleted', next(item for item in results if item['branch'] == branch)['result'])
        self.assertNotIn(branch, github.branches)
        self.assertEqual({'main', 'backup/retained', 'unknown/private-work'}, set(github.branches))
        self.assertEqual(OLD_HEAD, github.tags['refs/tags/archive/20261024/' + branch]['object']['sha'])
        deletion = next(i for i, call in enumerate(github.calls) if call[0] == 'DELETE')
        readback = publisher.PREFIX + '/git/ref/tags/archive/20261024/' + branch
        self.assertTrue(any(i < deletion and call[:2] == ('GET', readback)
                            for i, call in enumerate(github.calls)))
        self.assertEqual([publisher.PREFIX + '/git/refs/heads/' + branch],
                         [path for method, path, _ in github.mutations if method == 'DELETE'])

    def test_branch_head_advanced_after_archive_is_preserved(self):
        github = ArchiveGitHub(); github.advance_branch = True
        branch = publisher.ARCHIVABLE_BRANCHES[0]
        results = self.archive(github)
        self.assertEqual(NEW_HEAD, github.branches[branch])
        self.assertFalse(any(method == 'DELETE' for method, _, _ in github.mutations))
        self.assertIn('advanced', next(item for item in results if item['branch'] == branch)['reason'])

    def test_wrong_archive_readback_never_deletes_branch(self):
        github = ArchiveGitHub(); github.wrong_archive = True
        self.archive(github)
        self.assertFalse(any(method == 'DELETE' for method, _, _ in github.mutations))
        self.assertIn(publisher.ARCHIVABLE_BRANCHES[0], github.branches)

    def test_diverged_branch_is_preserved_without_archive_or_delete(self):
        github = ArchiveGitHub(); github.unmerged = True
        self.archive(github)
        self.assertEqual([], github.mutations)

    def test_moved_main_prevents_all_cleanup_mutations(self):
        github = ArchiveGitHub(); github.main_moved = True
        self.archive(github)
        self.assertEqual([], github.mutations)

    def test_forbidden_allowlist_entries_are_refused_without_even_reading_their_ref(self):
        github = ArchiveGitHub()
        with patch.object(publisher, 'ARCHIVABLE_BRANCHES', ('main', 'backup/retained', publisher.BRANCH)):
            outcomes = self.archive(github)
        self.assertEqual([], github.calls)
        self.assertTrue(all(item['result'] == 'kept' for item in outcomes))


if __name__ == '__main__':
    unittest.main(verbosity=2)
