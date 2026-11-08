"""Offline behavioral checks for the exact release publisher; never contacts GitHub."""
from __future__ import annotations

from contextlib import ExitStack, redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
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


class UploadPayloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='upload-payload-offline-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'root'
        self.work = Path(self.temporary.name) / 'work'
        self.root.mkdir(); self.work.mkdir()
        self.upload = self.root / 'release-upload'
        self.upload.mkdir()
        self.payload = b'synthetic APK payload, never executed\x00\xff'
        (self.root / publisher.APK_PATH).write_bytes(self.payload)
        (self.root / publisher.NOTES_PATH).write_text('Synthetic release notes.', encoding='utf-8')
        self.value = {
            'tag': publisher.TAG, 'source_sha': SOURCE,
            'apk_sha256': hashlib.sha256(self.payload).hexdigest(),
            'signer_sha256': publisher.EXPECTED_SIGNER_SHA256,
            'apk_file': publisher.APK_PATH, 'notes_file': publisher.NOTES_PATH,
            'title': publisher.TITLE,
        }
        self.write_metadata()

    def write_metadata(self):
        (self.root / publisher.METADATA_PATH).write_text(
            json.dumps(self.value, ensure_ascii=False), encoding='utf-8')

    def make_chunks(self, pieces=None):
        apk = self.root / publisher.APK_PATH
        apk.unlink(missing_ok=True)
        self.parts = self.root / publisher.APK_PARTS_DIRECTORY
        self.parts.mkdir(exist_ok=True)
        for part in self.parts.iterdir():
            part.unlink()
        if pieces is None:
            pieces = [b'A' * publisher.APK_CHUNK_BYTES, b'final synthetic chunk\x00\xff']
        self.payload = b''.join(pieces)
        self.value['apk_sha256'] = hashlib.sha256(self.payload).hexdigest()
        self.value['apk_chunks'] = [hashlib.sha256(piece).hexdigest() for piece in pieces]
        for index, piece in enumerate(pieces):
            (self.parts / f'{index:04d}.part').write_bytes(piece)
        self.write_metadata()

    def assert_no_reassembled_apk(self):
        self.assertFalse((self.work / Path(publisher.APK_PATH).name).exists())

    def git_verification(self, paths=None, parents=None, status='', existing_source_payload=''):
        paths = publisher.upload_payload_paths(self.value) if paths is None else paths
        changed = [publisher.METADATA_PATH, publisher.NOTES_PATH, *paths]
        commands = {
            ('git', 'rev-parse', 'HEAD'): NEW_HEAD + '\n',
            ('git', 'show', '-s', '--format=%P', NEW_HEAD):
                (SOURCE if parents is None else parents) + '\n',
            ('git', 'diff', '--name-only', SOURCE, NEW_HEAD, '--'): '\n'.join(changed) + '\n',
            ('git', 'status', '--porcelain', '--untracked-files=all'): status,
            ('git', 'ls-tree', SOURCE, 'release-upload'): existing_source_payload,
        }
        with patch.object(publisher, 'run', side_effect=lambda args: commands[tuple(args)]):
            publisher.verify_upload_commit(self.root, self.value, NEW_HEAD)

    def test_whole_apk_schema_hash_and_three_file_commit_remain_compatible(self):
        self.assertEqual(self.value, publisher.metadata(self.root))
        self.assertEqual([publisher.APK_PATH], publisher.upload_payload_paths(self.value))
        self.assertEqual(self.root / publisher.APK_PATH, publisher.resolve_apk(self.root, self.value, self.work))
        self.git_verification()
        (self.root / publisher.APK_PATH).write_bytes(b'changed')
        with self.assertRaisesRegex(publisher.Refusal, 'digest or size'):
            publisher.metadata(self.root)

    def test_chunks_reassemble_in_declared_order_outside_checkout(self):
        self.make_chunks()
        value = publisher.metadata(self.root)
        expected = [publisher.APK_PARTS_DIRECTORY + '/0000.part', publisher.APK_PARTS_DIRECTORY + '/0001.part']
        self.assertEqual(expected, publisher.upload_payload_paths(value))
        self.git_verification()
        resolved = publisher.resolve_apk(self.root, value, self.work)
        self.assertEqual(self.work / Path(publisher.APK_PATH).name, resolved)
        self.assertEqual(self.payload, resolved.read_bytes())
        self.assertEqual(self.value['apk_sha256'], publisher.sha256(resolved))
        self.assertEqual(0o600, resolved.stat().st_mode & 0o777)
        self.assertFalse((self.root / publisher.APK_PATH).exists())

    def test_final_chunk_may_be_full_or_single_byte(self):
        for final_size in (1, publisher.APK_CHUNK_BYTES):
            with self.subTest(final_size=final_size):
                self.make_chunks([b'A' * publisher.APK_CHUNK_BYTES, b'Z' * final_size])
                value = publisher.metadata(self.root)
                resolved = publisher.resolve_apk(self.root, value, self.work)
                self.assertEqual(self.payload, resolved.read_bytes())
                resolved.unlink()

    def test_missing_part_is_refused(self):
        self.make_chunks()
        (self.parts / '0001.part').unlink()
        with self.assertRaisesRegex(publisher.Refusal, 'exactly the declared parts'):
            publisher.metadata(self.root)

    def test_extra_part_or_directory_is_refused(self):
        for directory in (False, True):
            with self.subTest(directory=directory):
                self.make_chunks()
                extra = self.parts / '0002.part'
                extra.mkdir() if directory else extra.write_bytes(b'extra')
                with self.assertRaisesRegex(publisher.Refusal, 'exactly the declared parts'):
                    publisher.metadata(self.root)
                extra.rmdir() if directory else extra.unlink()

    def test_corrupt_part_is_refused_and_partial_output_is_removed(self):
        self.make_chunks()
        value = publisher.metadata(self.root)
        (self.parts / '0001.part').write_bytes(b'changed chunk')
        with self.assertRaisesRegex(publisher.Refusal, 'chunk digest mismatch'):
            publisher.resolve_apk(self.root, value, self.work)
        self.assert_no_reassembled_apk()

    def test_reordered_chunk_hashes_are_refused(self):
        self.make_chunks()
        self.value['apk_chunks'].reverse()
        self.write_metadata()
        value = publisher.metadata(self.root)
        with self.assertRaisesRegex(publisher.Refusal, 'chunk digest mismatch'):
            publisher.resolve_apk(self.root, value, self.work)
        self.assert_no_reassembled_apk()

    def test_global_apk_hash_is_required_in_addition_to_chunk_hashes(self):
        self.make_chunks()
        self.value['apk_sha256'] = '0' * 64
        self.write_metadata()
        value = publisher.metadata(self.root)
        with self.assertRaisesRegex(publisher.Refusal, 'Reassembled APK digest mismatch'):
            publisher.resolve_apk(self.root, value, self.work)
        self.assert_no_reassembled_apk()

    def test_part_leaf_symlink_is_refused_even_inside_checkout(self):
        self.make_chunks()
        target = self.upload / 'retained.part'
        target.write_bytes((self.parts / '0001.part').read_bytes())
        (self.parts / '0001.part').unlink()
        (self.parts / '0001.part').symlink_to(target)
        with self.assertRaisesRegex(publisher.Refusal, 'symlink'):
            publisher.metadata(self.root)

    def test_part_directory_symlink_is_refused(self):
        self.make_chunks()
        target = self.work / 'relocated-parts'
        self.parts.rename(target)
        self.parts.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(publisher.Refusal, 'directory is missing or invalid'):
            publisher.metadata(self.root)

    def test_metadata_or_notes_symlink_is_refused(self):
        for relative in (publisher.METADATA_PATH, publisher.NOTES_PATH):
            with self.subTest(relative=relative):
                original = self.root / relative
                target = self.work / original.name
                original.rename(target)
                original.symlink_to(target)
                with self.assertRaisesRegex(publisher.Refusal, 'symlink'):
                    publisher.metadata(self.root)
                original.unlink()
                target.rename(original)

    def test_whole_apk_and_parts_are_mutually_exclusive(self):
        parts = self.root / publisher.APK_PARTS_DIRECTORY
        parts.mkdir()
        with self.assertRaisesRegex(publisher.Refusal, 'Whole APK mode'):
            publisher.metadata(self.root)
        self.make_chunks()
        (self.root / publisher.APK_PATH).write_bytes(self.payload)
        with self.assertRaisesRegex(publisher.Refusal, 'Chunked APK mode'):
            publisher.metadata(self.root)

    def test_chunk_schema_rejects_invalid_type_count_or_digest(self):
        digest = 'a' * 64
        invalid = (None, digest, {}, [], [digest] * 513, [1], ['A' * 64], ['a' * 63], [digest, 'https://example.invalid'])
        for chunks in invalid:
            with self.subTest(chunks_type=type(chunks).__name__, count=len(chunks) if isinstance(chunks, list) else None):
                self.value['apk_chunks'] = chunks
                self.write_metadata()
                with self.assertRaisesRegex(publisher.Refusal, 'chunk digest list'):
                    publisher.metadata(self.root)
        self.assertEqual([digest] * 512, publisher.chunk_digests({'apk_chunks': [digest] * 512}))

    def test_fixed_metadata_schema_rejects_unknown_fields_and_paths(self):
        for field, value in (('command', 'never executed'), ('apk_file', '../other.apk'),
                             ('notes_file', 'https://example.invalid'), ('source_sha', True)):
            with self.subTest(field=field):
                original = deepcopy(self.value)
                self.value[field] = value
                self.write_metadata()
                with self.assertRaises(publisher.Refusal):
                    publisher.metadata(self.root)
                self.value = original

    def test_metadata_size_limit_is_64_kib_in_both_modes(self):
        for chunks in (False, True):
            with self.subTest(chunks=chunks):
                if chunks:
                    self.make_chunks()
                encoded = json.dumps(self.value, ensure_ascii=False).encode('utf-8')
                path = self.root / publisher.METADATA_PATH
                path.write_bytes(encoded + b' ' * (publisher.MAX_METADATA_BYTES - len(encoded)))
                self.assertEqual(self.value, publisher.metadata(self.root))
                with path.open('ab') as stream:
                    stream.write(b' ')
                with self.assertRaisesRegex(publisher.Refusal, 'metadata is missing or invalid'):
                    publisher.metadata(self.root)

    def test_zero_oversized_or_short_nonfinal_chunks_are_refused(self):
        for sizes in ((0,), (publisher.APK_CHUNK_BYTES + 1,), (5, 1), (publisher.APK_CHUNK_BYTES, 0)):
            with self.subTest(sizes=sizes):
                self.make_chunks([b'A' * size for size in sizes])
                with self.assertRaisesRegex(publisher.Refusal, 'chunk size'):
                    publisher.metadata(self.root)

    def test_total_size_limit_is_enforced_during_metadata_and_reassembly(self):
        self.make_chunks()
        with patch.object(publisher, 'MAX_BINARY_BYTES', publisher.APK_CHUNK_BYTES):
            with self.assertRaisesRegex(publisher.Refusal, 'verification limit'):
                publisher.metadata(self.root)
            with self.assertRaisesRegex(publisher.Refusal, 'verification limit'):
                publisher.resolve_apk(self.root, self.value, self.work)
        self.assert_no_reassembled_apk()

    def test_reassembly_inside_checkout_or_over_existing_output_is_refused(self):
        self.make_chunks()
        value = publisher.metadata(self.root)
        with self.assertRaisesRegex(publisher.Refusal, 'outside the checkout'):
            publisher.resolve_apk(self.root, value, self.root)
        output = self.work / Path(publisher.APK_PATH).name
        output.write_bytes(b'previous verified output')
        with self.assertRaises(FileExistsError):
            publisher.resolve_apk(self.root, value, self.work)
        self.assertEqual(b'previous verified output', output.read_bytes())

    def test_upload_commit_requires_exact_part_set_and_sole_source_parent(self):
        self.make_chunks()
        expected = publisher.upload_payload_paths(self.value)
        for paths in (expected[:-1], [*expected, publisher.APK_PATH], [*expected, 'unrelated.txt']):
            with self.subTest(paths=paths):
                with self.assertRaisesRegex(publisher.Refusal, 'exact signed APK payload'):
                    self.git_verification(paths=paths)
        for parents in (OLD_HEAD, SOURCE + ' ' + OLD_HEAD):
            with self.subTest(parents=parents):
                with self.assertRaisesRegex(publisher.Refusal, 'sole parent'):
                    self.git_verification(parents=parents)
        with self.assertRaisesRegex(publisher.Refusal, 'uncommitted changes'):
            self.git_verification(status='?? extra.txt')
        with self.assertRaisesRegex(publisher.Refusal, 'must not contain release upload payloads'):
            self.git_verification(existing_source_payload='040000 tree abc\trelease-upload')

    def invoke_main(self, github_factory, verify_signature, compare_artifact, publish=None):
        environment = {
            'GITHUB_REPOSITORY': publisher.REPOSITORY, 'GITHUB_EVENT_NAME': 'push',
            'GITHUB_REF': 'refs/heads/' + publisher.BRANCH, 'GITHUB_SHA': NEW_HEAD,
            'GITHUB_TOKEN': 'synthetic-token-never-sent', 'GITHUB_STEP_SUMMARY': '',
        }
        with ExitStack() as stack:
            stack.enter_context(patch.dict(publisher.os.environ, environment))
            stack.enter_context(patch.object(publisher, '__file__', str(self.root / 'tools/publish-verified-release.py')))
            stack.enter_context(patch.object(publisher.Path, 'cwd', return_value=self.root))
            stack.enter_context(patch.object(publisher, 'verify_upload_commit'))
            stack.enter_context(patch.object(publisher, 'GitHub', side_effect=github_factory))
            stack.enter_context(patch.object(publisher, 'verify_signed_apk', side_effect=verify_signature))
            stack.enter_context(patch.object(publisher, 'compare_ci_artifact', side_effect=compare_artifact))
            stack.enter_context(patch.object(publisher, 'archive_merged_branches', return_value=[]))
            if publish is not None:
                stack.enter_context(patch.object(publisher, 'publish', side_effect=publish))
            stack.enter_context(redirect_stdout(io.StringIO()))
            publisher.main()

    def test_main_refuses_corrupt_chunk_before_github_or_signature_work(self):
        self.make_chunks()
        (self.parts / '0000.part').write_bytes(b'Z' * publisher.APK_CHUNK_BYTES)
        with self.assertRaisesRegex(publisher.Refusal, 'chunk digest mismatch'):
            self.invoke_main(
                lambda *_: self.fail('GitHub was created before chunk verification'),
                lambda *_: self.fail('Signature work was reached for corrupt chunks'),
                lambda *_: self.fail('CI comparison was reached for corrupt chunks'))

    def test_main_refuses_signature_failure_before_github_or_publication(self):
        self.make_chunks()
        def refused_signature(apk, value):
            self.assertEqual(self.payload, apk.read_bytes())
            self.assertFalse(apk.resolve().is_relative_to(self.root))
            raise publisher.Refusal('Synthetic signature refusal')
        with self.assertRaisesRegex(publisher.Refusal, 'Synthetic signature refusal'):
            self.invoke_main(
                lambda *_: self.fail('GitHub was created before signature verification'),
                refused_signature, lambda *_: self.fail('CI comparison preceded signature verification'))

    def test_main_refuses_ci_payload_failure_before_any_publication_mutation(self):
        self.make_chunks()
        github = PublicationGitHub()
        def refused_comparison(*_):
            self.assertEqual([], github.mutations)
            raise publisher.Refusal('Synthetic CI payload refusal')
        with self.assertRaisesRegex(publisher.Refusal, 'Synthetic CI payload refusal'):
            self.invoke_main(lambda *_: github, lambda *_: None, refused_comparison)
        self.assertEqual([], github.mutations)

    def test_main_verifies_same_reassembled_file_then_uploads_only_apk_and_sums(self):
        self.make_chunks()
        github = PublicationGitHub()
        events = []
        original_api = github.api
        original_publish = publisher.publish
        def cleanup_aware_api(path, method='GET', data=None, expected=(200,), absent=False):
            if method == 'DELETE' and path == publisher.PREFIX + '/git/refs/heads/' + publisher.BRANCH:
                github.mutations.append((method, path, None))
                return None
            return original_api(path, method, data, expected, absent)
        github.api = cleanup_aware_api
        def signature(apk, value):
            self.assertEqual(self.value, value)
            self.assertEqual(self.payload, apk.read_bytes())
            events.append(('signature', apk))
        def comparison(client, ci_run, source, apk, work):
            self.assertIs(github, client)
            self.assertEqual(SOURCE, source)
            self.assertEqual([], github.mutations)
            self.assertEqual(events[0][1], apk)
            events.append(('payload', apk))
        def checked_publication(root, client, value, work, ci_run, apk):
            self.assertEqual(['signature', 'payload'], [event[0] for event in events])
            self.assertEqual(events[0][1], apk)
            self.assertFalse(apk.resolve().is_relative_to(self.root))
            events.append(('publication', apk))
            return original_publish(root, client, value, work, ci_run, apk)
        self.invoke_main(lambda *_: github, signature, comparison, checked_publication)
        self.assertEqual(['signature', 'payload', 'publication'], [event[0] for event in events])
        self.assertFalse(events[0][1].exists(), 'Temporary reassembled APK must be removed after publication')
        uploaded = [call[1] for call in github.calls if call[0] == 'UPLOAD']
        self.assertEqual([Path(publisher.APK_PATH).name, 'SHA256SUMS'], uploaded)
        self.assertEqual(self.payload, github.assets[30])
        self.assertFalse((self.root / publisher.APK_PATH).exists())


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
            assert data['prerelease'] is False, 'The verified stable release must not be a prerelease'
            self.draft = {**deepcopy(data), 'id': 12, 'assets': []}
            return deepcopy(self.draft)
        if suffix.startswith('/actions/workflows/release-build.yml/runs?'):
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
            assert data == {'draft': False, 'prerelease': False, 'make_latest': 'true'}
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
        self.assertFalse(self.github.draft['prerelease'])
        self.assertEqual({'draft': False, 'prerelease': False, 'make_latest': 'true'},
                         self.github.mutations[-1][2], 'The verified stable release must become latest')

    def test_optional_verified_apk_path_is_used_without_whole_apk_in_checkout(self):
        original = self.root / publisher.APK_PATH
        verified = self.work / original.name
        original.rename(verified)
        with redirect_stdout(io.StringIO()):
            result = publisher.publish(self.root, self.github, self.value, self.work, self.github.ci_run, verified)
        self.assertEqual(PUBLIC_URL, result)
        self.assertEqual(verified.read_bytes(), self.github.assets[30])
        self.assertFalse(original.exists())

    def test_optional_apk_with_changed_bytes_or_name_causes_no_mutation(self):
        for wrong_name in (False, True):
            with self.subTest(wrong_name=wrong_name):
                verified = self.work / ('wrong.apk' if wrong_name else Path(publisher.APK_PATH).name)
                verified.write_bytes((self.root / publisher.APK_PATH).read_bytes() if wrong_name else b'changed')
                with self.assertRaisesRegex(publisher.Refusal, 'publication APK digest, name or size'):
                    publisher.publish(self.root, self.github, self.value, self.work, self.github.ci_run, verified)
                self.assertEqual([], self.github.calls)
                self.assertEqual([], self.github.mutations)

    def test_changed_asset_bytes_leave_draft_private(self):
        self.github.corrupt_asset = True
        with self.assertRaisesRegex(publisher.Refusal, 'hash differs'):
            self.publish()
        self.assert_never_public()

    def test_creation_as_prerelease_is_refused_before_asset_upload(self):
        original_api = self.github.api
        def wrong_creation(path, method='GET', data=None, expected=(200,), absent=False):
            result = original_api(path, method, data, expected, absent)
            if path == publisher.PREFIX + '/releases' and method == 'POST':
                self.github.draft['prerelease'] = True
                result['prerelease'] = True
            return result
        self.github.api = wrong_creation
        with self.assertRaisesRegex(publisher.Refusal, 'creation was not confirmed'):
            self.publish()
        self.assertEqual(['POST'], [method for method, _, _ in self.github.mutations])
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
