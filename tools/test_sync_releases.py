#!/usr/bin/env python3
"""Offline tests for migration entries and already-canonical published releases."""
import base64
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("release_sync_under_test", Path(__file__).with_name("sync-releases.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def fixture_entry(identity=1, migration=False, latest=True):
    """All identifiers and hashes below are synthetic test fixtures."""
    entry = {
        "id": identity, "tag": f"v1.0.{identity}", "source_commit": f"{identity:040x}",
        "title": f"Synthetic release {identity}", "prerelease": False, "latest": latest,
        "notes": f"docs/releases/synthetic-{identity}.md",
        "asset": {"id": identity + 100, "size": 42, "version_code": identity,
                  "name": f"Synthetic-{identity}.apk", "sha256": f"{identity:064x}",
                  "version_name": f"1.0.{identity}", "application_id": "test.synthetic"},
    }
    if migration:
        entry.update(old_tag=f"Legacy{identity}", old_tag_sha=f"{identity + 1000:040x}")
    return entry


class Client:
    def __init__(self, entries):
        self.calls, self.mutations, self.ref_reads = [], [], []
        self.entries = {entry['id']: deepcopy(entry) for entry in entries}
        self.refs = {entry['tag']: {"sha": entry['source_commit'], "type": "commit", "commit": entry['source_commit']}
                     for entry in entries}
        self.old_tags = {entry['old_tag'] for entry in entries if 'old_tag' in entry}
        self.releases = {}
        for entry in entries:
            if 'old_tag' in entry:
                self.refs[entry['old_tag']] = {"sha": entry['old_tag_sha'], "type": "commit", "commit": entry['old_tag_sha']}
            release = {key: value for key, value in sync.desired_release(entry).items() if key != 'make_latest'}
            release.update(id=entry['id'], tag_name=entry.get('old_tag', entry['tag']), immutable=False,
                           html_url=f"https://github.com/{sync.REPOSITORY}/releases/tag/{entry.get('old_tag', entry['tag'])}")
            release['assets'] = [{**entry['asset'], "state": "uploaded", "digest": "sha256:" + entry['asset']['sha256'],
                                  "browser_download_url": "https://github.com/synthetic/fixture-only"}]
            self.releases[entry['id']] = release
        self.latest = next(entry['id'] for entry in entries if entry['latest'])

    def ref(self, tag):
        self.ref_reads.append(tag)
        return deepcopy(self.refs.get(tag))

    def request(self, method, path, payload=None, optional=False):
        self.calls.append((method, path, deepcopy(payload)))
        if method != 'GET':
            self.mutations.append((method, path, deepcopy(payload)))
        if method == 'GET' and path == '/releases/latest':
            return deepcopy(self.releases[self.latest])
        if method == 'GET' and path.startswith('/contents/app/build.gradle.kts?ref='):
            source = path.rsplit('=', 1)[1]
            entry = next(entry for entry in self.entries.values() if entry['source_commit'] == source)
            asset = entry['asset']
            text = f'versionName = "{asset["version_name"]}"\nversionCode = {asset["version_code"]}\napplicationId = "{asset["application_id"]}"\n'
            return {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}
        if method == 'GET' and path.startswith('/releases/tags/'):
            tag = path.removeprefix('/releases/tags/')
            found = next((release for release in self.releases.values() if release['tag_name'] == tag), None)
            return deepcopy(found)
        if path.startswith('/releases/'):
            identity = int(path.rsplit('/', 1)[1])
            if method == 'GET':
                return deepcopy(self.releases[identity])
            if method == 'PATCH':
                self.releases[identity].update({key: value for key, value in payload.items() if key != 'make_latest'})
                self.releases[identity]['html_url'] = f"https://github.com/{sync.REPOSITORY}/releases/tag/{payload['tag_name']}"
                if payload['make_latest'] == 'true':
                    self.latest = identity
                return deepcopy(self.releases[identity])
        if method == 'POST' and path == '/git/refs':
            tag = payload['ref'].removeprefix('refs/tags/')
            if tag in self.refs:
                raise AssertionError('Existing canonical tag must not be replaced')
            self.refs[tag] = {"sha": payload['sha'], "type": "commit", "commit": payload['sha']}
            return deepcopy(self.refs[tag])
        if method == 'DELETE' and path.startswith('/git/refs/tags/'):
            tag = path.removeprefix('/git/refs/tags/')
            if tag not in self.old_tags:
                raise AssertionError('Only a declared migration tag may be deleted')
            del self.refs[tag]
            return None
        raise AssertionError(f'Unexpected mocked GitHub request: {method} {path}')


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='release-sync-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'docs/releases').mkdir(parents=True)

    def load(self, entries):
        for entry in entries:
            (self.root / entry['notes']).write_text('Synthetic notes.\n', encoding='utf-8')
        file = self.root / 'manifest.json'
        file.write_text(json.dumps({'repository': sync.REPOSITORY, 'releases': entries}), encoding='utf-8')
        with patch.object(sync, 'ROOT', self.root):
            return sync.load_manifest(file)

    def test_current_historical_manifest_keeps_all_existing_migration_checks(self):
        entries = sync.load_manifest(sync.ROOT / '.github/release-history.json')
        self.assertGreaterEqual(len(entries), 4)
        migrations = [entry for entry in entries if 'old_tag' in entry]
        self.assertGreaterEqual(len(migrations), 4)
        self.assertTrue(all('old_tag_sha' in entry for entry in migrations))
        self.assertEqual(1, sum(entry['latest'] for entry in entries))

    def test_new_canonical_entries_need_no_invented_old_tag(self):
        entries = self.load([fixture_entry(1, migration=True, latest=False), fixture_entry(2), fixture_entry(3, latest=False)])
        self.assertEqual(3, len(entries))
        self.assertEqual(2, entries[-1]['id'])
        for entry in entries:
            if entry['id'] in (2, 3):
                self.assertNotIn('old_tag', entry)
                self.assertNotIn('old_tag_sha', entry)

    def test_partial_migration_fields_are_rejected(self):
        for missing in ('old_tag', 'old_tag_sha'):
            with self.subTest(missing=missing):
                entry = fixture_entry(migration=True)
                del entry[missing]
                with self.assertRaisesRegex(sync.SyncError, 'supplied together'):
                    self.load([entry])

    def test_legacy_tag_still_requires_distinct_name_valid_sha_and_uniqueness(self):
        bad = fixture_entry(migration=True)
        bad['old_tag'] = bad['tag']
        with self.assertRaisesRegex(sync.SyncError, 'must differ'):
            self.load([bad])
        bad = fixture_entry(migration=True)
        bad['old_tag_sha'] = 'not-a-sha'
        with self.assertRaisesRegex(sync.SyncError, 'Invalid old_tag_sha'):
            self.load([bad])
        first, second = fixture_entry(1, migration=True, latest=False), fixture_entry(2, migration=True)
        second['old_tag'] = first['old_tag']
        with self.assertRaisesRegex(sync.SyncError, 'Duplicate release old_tag'):
            self.load([first, second])

    def test_old_and_canonical_tag_sets_cannot_overlap_even_with_new_entries(self):
        first, second = fixture_entry(1, migration=True, latest=False), fixture_entry(2)
        first['old_tag'] = second['tag']
        with self.assertRaisesRegex(sync.SyncError, 'sets overlap'):
            self.load([first, second])

    def test_exactly_one_latest_and_unique_ids_tags_assets_still_required(self):
        for corruption in ('two-latest', 'no-latest', 'id', 'tag', 'asset'):
            with self.subTest(corruption=corruption):
                first, second = fixture_entry(1, latest=False), fixture_entry(2)
                if corruption == 'two-latest': first['latest'] = True
                if corruption == 'no-latest': second['latest'] = False
                if corruption == 'id': second['id'] = first['id']
                if corruption == 'tag': second['tag'] = first['tag']
                if corruption == 'asset': second['asset']['id'] = first['asset']['id']
                with self.assertRaises(sync.SyncError):
                    self.load([first, second])


class SynchronizeTests(unittest.TestCase):
    def entries(self):
        result = [fixture_entry(1, migration=True, latest=False), fixture_entry(2)]
        for entry in result:
            entry['body'] = 'Synthetic notes.'
        return result

    def run_sync(self, client, entries, apply=True):
        report = {}
        # Binary I/O is exercised in the publisher suite. This suite verifies
        # metadata/ref orchestration without making any network request.
        with patch.object(sync, 'verify_download') as download:
            sync.synchronize(client, entries, apply, report)
        self.assertTrue(download.called)
        return report

    def test_mixed_entries_apply_deletes_only_the_declared_legacy_ref(self):
        entries = self.entries(); client = Client(entries)
        report = self.run_sync(client, entries)
        self.assertEqual('applied', report['status'])
        deletes = [path for method, path, _ in client.mutations if method == 'DELETE']
        self.assertEqual(['/git/refs/tags/Legacy1'], deletes)
        self.assertIn(entries[0]['tag'], client.refs)
        self.assertIn(entries[1]['tag'], client.refs)
        self.assertEqual(2, report['after_latest_id'])
        self.assertFalse(report['plan'][1]['delete_old_tag'])
        self.assertNotIn(None, client.ref_reads)

    def test_already_canonical_only_manifest_is_read_only_when_already_correct(self):
        entries = [self.entries()[1]]; client = Client(entries)
        report = self.run_sync(client, entries)
        self.assertEqual('applied', report['status'])
        self.assertEqual([], client.mutations)
        self.assertEqual([entries[0]['tag']], list(client.refs))

    def test_dry_run_with_new_entry_performs_no_mutation(self):
        entries = self.entries(); client = Client(entries)
        report = self.run_sync(client, entries, apply=False)
        self.assertEqual('dry-run', report['status'])
        self.assertEqual([], client.mutations)
        self.assertIn('Legacy1', client.refs)

    def test_missing_new_canonical_ref_is_created_without_inventing_or_deleting_an_old_tag(self):
        entries = [self.entries()[1]]; client = Client(entries)
        del client.refs[entries[0]['tag']]
        report = self.run_sync(client, entries)
        self.assertEqual('applied', report['status'])
        self.assertEqual([('POST', '/git/refs', {'ref': 'refs/tags/' + entries[0]['tag'],
                                               'sha': entries[0]['source_commit']})], client.mutations)
        self.assertEqual([entries[0]['tag']], list(client.refs))

    def test_changed_old_ref_refuses_before_any_write(self):
        entries = self.entries(); client = Client(entries)
        client.refs['Legacy1']['sha'] = 'f' * 40
        with self.assertRaisesRegex(sync.SyncError, 'changed; refusing to delete'):
            self.run_sync(client, entries)
        self.assertEqual([], client.mutations)

    def test_wrong_canonical_target_refuses_before_any_write(self):
        entries = self.entries(); client = Client(entries)
        client.refs[entries[1]['tag']]['commit'] = 'f' * 40
        with self.assertRaisesRegex(sync.SyncError, 'targets another commit'):
            self.run_sync(client, entries)
        self.assertEqual([], client.mutations)

    def test_new_entry_checks_only_canonical_ref_and_never_deletes_it(self):
        entry = self.entries()[1]; client = Client([entry])
        canonical, old = sync.verify_refs(client, entry)
        self.assertEqual(entry['source_commit'], canonical['commit'])
        self.assertIsNone(old)
        self.assertEqual([entry['tag']], client.ref_reads)
        client.releases[entry['id']]['tag_name'] = 'LegacyUnexpected'
        with patch.object(sync, 'verify_download'):
            with self.assertRaisesRegex(sync.SyncError, 'Unexpected current tag'):
                sync.verify_release(client, entry)


if __name__ == '__main__':
    unittest.main(verbosity=2)
