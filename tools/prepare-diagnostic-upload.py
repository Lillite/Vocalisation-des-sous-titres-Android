#!/usr/bin/env python3
"""Prepare immutable Git objects from a reviewed signed APK transport.

This one-off helper never creates/updates a ref, tag or release, never signs,
and never executes metadata. Its sole writes are Git blobs, a tree and a commit.
An OAuth tool must later create the upload branch after independent review.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.request

REPOSITORY = 'Lillite/Vocalisation-des-sous-titres-Android'
PREPARE_BRANCH = 'release-prepare/v0.5.3-diagnostic'
SOURCE = '3b68b118c3f7d21ef47a47e6eb5611ceb34f2055'
SOURCE_TREE = '36453b4e63b219c769f2979ed73242b06dc26cb6'
SOURCE_PARENTS = ['d5b18f7e138ff4f2869e273b32db6c40fd46a98e', 'faa0b84e157fbe408a39e1e8441c3cf2ef35e21e']
APK_BYTES = 53_565_559
APK_SHA256 = 'c622d3f49bced6f1afeb8abd35060841cc2f7636f75576e5e6c1bc5af18d80d1'
APK_BLOB = '700324aa2bf686b3733351410612e3ef6a7305ff'
GZIP_BYTES = 24_855_803
GZIP_SHA256 = 'ef023a780c09a988eafffe5a36e0905ab728b5724731764d9df7e604b6af3105'
PARTS = [
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part00', 4194304, 'eda25aca33cba2bcef9b4725930be68da9dce77645018bb2e1c6e50a6ba4b480'),
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part01', 4194304, '6152f49f6a02a8037a3e5a6c3bd542c933c4f2247cd81a61645f5b8baa4de327'),
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part02', 4194304, '7d066a2c1be43c58a3857f749f4ab1480be3822a252a8dc4b98ccd6cb8468123'),
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part03', 4194304, '50a761d738e90967d4c3b17bde032566bb7a56d115f447ae14dcfe5efe5f89a8'),
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part04', 4194304, '0b92744c2ef7d8c4a1576fc63f7e7a20a3f186f7ee20e48e4b9bcc2157fd9017'),
    ('SousTitresVoix-0.5.3-diagnostic.apk.gz.part05', 3884283, '64a2f0363dea353632b98c3da1cecdaba8472d1e16199b0940366ac0321b07bc'),
]
NOTES_BYTES = 5611
NOTES_SHA256 = 'c002047c4a04fc6ea93bcbbe78d7f92ed8ca7689e9c05c1d70cc0b4a6834f843'
METADATA_BYTES = 450
METADATA_SHA256 = 'f08390c26e71847088700a0359eebad0163374f8e8b56fbf7383a0358a6934d4'
APK_NAME = 'SousTitresVoix-0.5.3-diagnostic.apk'
APK_PATH = 'release-upload/' + APK_NAME
NOTES_PATH = 'release-upload/notes.md'
METADATA_PATH = 'release-upload/release.json'
TRANSPORT_DIR = Path('release-transport')
EXPECTED_METADATA = {
    'tag': 'v0.5.3-diagnostic', 'source_sha': SOURCE, 'apk_sha256': APK_SHA256,
    'signer_sha256': '304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d',
    'apk_file': APK_PATH, 'notes_file': NOTES_PATH,
    'title': 'Sous-titres en voix 0.5.3 — Diagnostic local (préversion)',
}
API = 'https://api.github.com/repos/' + REPOSITORY
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
MAX_RESPONSE_BYTES = 4 * 1024 * 1024


class Refusal(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON field refused.')
        result[key] = value
    return result


def parse_json(data):
    return json.loads(data.decode('utf-8'), object_pairs_hook=unique_object)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Refusal('API redirect refused.')


class GitHub:
    def __init__(self):
        token = os.environ.get('GITHUB_TOKEN', '')
        require(bool(token), 'Workflow token unavailable.')
        self._token = token
        self._opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, value=None):
        allowed_read = path in {
            '/git/ref/heads/main', '/git/commits/' + SOURCE, '/git/trees/' + SOURCE_TREE,
        } or bool(re.fullmatch(r'/git/commits/[0-9a-f]{40}', path))
        require((method == 'GET' and allowed_read) or
                (method == 'POST' and path in {'/git/blobs', '/git/trees', '/git/commits'}),
                'API operation is outside preparation scope.')
        body = None if value is None else json.dumps(value, ensure_ascii=False).encode('utf-8')
        request = urllib.request.Request(API + path, data=body, method=method, headers={
            'Authorization': 'Bearer ' + self._token,
            'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/json',
            'X-GitHub-Api-Version': '2026-03-10',
            'User-Agent': 'SousTitresVoix-reviewed-upload-preparation',
        })
        try:
            with self._opener.open(request, timeout=180) as response:
                require(response.status == (201 if method == 'POST' else 200), 'Unexpected API status.')
                data = response.read(MAX_RESPONSE_BYTES + 1)
                require(len(data) <= MAX_RESPONSE_BYTES, 'API response exceeds verification limit.')
                return parse_json(data)
        except urllib.error.HTTPError as error:
            # Never log a token, request body, URL or response body.
            raise Refusal('GitHub refused a preparation operation with HTTP ' + str(error.code) + '; no retry.') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Refusal('GitHub preparation interrupted; no automatic mutation retry.') from None


def assert_main(github):
    reference = github.request('GET', '/git/ref/heads/main')
    require(reference.get('object', {}).get('sha') == SOURCE and reference.get('object', {}).get('type') == 'commit',
            'Main no longer equals the reviewed source.')
    commit = github.request('GET', '/git/commits/' + SOURCE)
    require(commit.get('sha') == SOURCE and commit.get('tree', {}).get('sha') == SOURCE_TREE and
            [parent.get('sha') for parent in commit.get('parents', [])] == SOURCE_PARENTS,
            'Source tree or two-parent identity differs from the validated merge.')


def checked_bytes(path, size, expected_sha):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size == size,
            'Transport file is missing, ambiguous or incorrectly sized.')
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == expected_sha, 'Transport file digest mismatch.')
    return data


def blob_sha(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()


def join_transport(folder, destination):
    require(len(PARTS) == 6 and len({name for name, _, _ in PARTS}) == len(PARTS),
            'Ambiguous transport parts declaration.')
    total = 0
    digest = hashlib.sha256()
    with destination.open('xb') as output:
        for name, size, expected_sha in PARTS:
            require(0 < size <= 4 * 1024 * 1024, 'Transport part exceeds its limit.')
            data = checked_bytes(folder / name, size, expected_sha)
            total += len(data)
            require(total <= GZIP_BYTES, 'Joined transport exceeds its exact size.')
            digest.update(data); output.write(data)
    require(total == GZIP_BYTES and digest.hexdigest() == GZIP_SHA256,
            'Joined gzip transport differs from the reviewed bytes.')


def restore_apk(gzip_path, destination):
    require(gzip_path.is_file() and not gzip_path.is_symlink() and gzip_path.stat().st_size == GZIP_BYTES,
            'Gzip transport size mismatch.')
    digest = hashlib.sha256()
    with gzip_path.open('rb') as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    require(digest.hexdigest() == GZIP_SHA256, 'Gzip transport digest mismatch.')
    total = 0
    digest = hashlib.sha256()
    git_digest = hashlib.sha1(b'blob ' + str(APK_BYTES).encode('ascii') + b'\0')
    with gzip.open(gzip_path, 'rb') as source, destination.open('xb') as output:
        while block := source.read(min(1024 * 1024, APK_BYTES - total + 1)):
            total += len(block)
            require(total <= APK_BYTES, 'Gzip expands beyond the exact reviewed APK size.')
            digest.update(block); git_digest.update(block); output.write(block)
    require(total == APK_BYTES and digest.hexdigest() == APK_SHA256 and git_digest.hexdigest() == APK_BLOB,
            'Restored APK differs from the reviewed signed bytes.')


def prepare():
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY and
            os.environ.get('GITHUB_EVENT_NAME') == 'push' and
            os.environ.get('GITHUB_REF') == 'refs/heads/' + PREPARE_BRANCH and
            HEX40.fullmatch(os.environ.get('GITHUB_SHA', '')),
            'Preparation is restricted to the reviewed repository and temporary branch.')
    notes_bytes = checked_bytes(TRANSPORT_DIR / 'notes.md', NOTES_BYTES, NOTES_SHA256)
    metadata_bytes = checked_bytes(TRANSPORT_DIR / 'release.json', METADATA_BYTES, METADATA_SHA256)
    notes = notes_bytes.decode('utf-8')
    metadata = metadata_bytes.decode('utf-8')
    require(parse_json(metadata_bytes) == EXPECTED_METADATA, 'Publication metadata differs from reviewed constants.')
    github = GitHub()
    assert_main(github)
    source_tree = github.request('GET', '/git/trees/' + SOURCE_TREE)
    require(source_tree.get('sha') == SOURCE_TREE and source_tree.get('truncated') is False and
            isinstance(source_tree.get('tree'), list) and
            not any(item.get('path') == 'release-upload' for item in source_tree['tree']),
            'Reviewed source already contains an upload payload or has an incomplete tree.')
    with tempfile.TemporaryDirectory(prefix='reviewed-apk-transport-') as temporary:
        gzip_path = Path(temporary) / (APK_NAME + '.gz')
        join_transport(TRANSPORT_DIR, gzip_path)
        apk = Path(temporary) / APK_NAME
        restore_apk(gzip_path, apk)
        data = apk.read_bytes()
        require(len(data) == APK_BYTES and blob_sha(data) == APK_BLOB, 'APK changed before blob creation.')
        created_blob = github.request('POST', '/git/blobs', {
            'encoding': 'base64', 'content': base64.b64encode(data).decode('ascii'),
        })
        require(created_blob.get('sha') == APK_BLOB, 'GitHub APK blob identity mismatch.')
    assert_main(github)
    created_tree = github.request('POST', '/git/trees', {
        'base_tree': SOURCE_TREE,
        'tree': [
            {'path': APK_PATH, 'mode': '100644', 'type': 'blob', 'sha': APK_BLOB},
            {'path': NOTES_PATH, 'mode': '100644', 'type': 'blob', 'content': notes},
            {'path': METADATA_PATH, 'mode': '100644', 'type': 'blob', 'content': metadata},
        ],
    })
    tree = created_tree.get('sha', '')
    require(isinstance(tree, str) and HEX40.fullmatch(tree), 'Prepared upload tree was not confirmed.')
    assert_main(github)
    created_commit = github.request('POST', '/git/commits', {
        'message': 'Préparer APK 0.5.3-diagnostic signé et vérifié, sans créer de branche de publication',
        'tree': tree, 'parents': [SOURCE],
    })
    commit_sha = created_commit.get('sha', '')
    require(isinstance(commit_sha, str) and HEX40.fullmatch(commit_sha), 'Prepared upload commit was not confirmed.')
    confirmed = github.request('GET', '/git/commits/' + commit_sha)
    require(confirmed.get('sha') == commit_sha and confirmed.get('tree', {}).get('sha') == tree and
            [parent.get('sha') for parent in confirmed.get('parents', [])] == [SOURCE],
            'Prepared upload commit does not have the exact source as its sole parent.')
    assert_main(github)
    report = {
        'status': 'prepared-not-published', 'repository': REPOSITORY,
        'transport_commit': os.environ['GITHUB_SHA'], 'source_commit': SOURCE,
        'source_tree': SOURCE_TREE, 'source_parents': SOURCE_PARENTS,
        'apk_bytes': APK_BYTES, 'apk_sha256': APK_SHA256, 'apk_blob_sha1': APK_BLOB,
        'gzip_sha256': GZIP_SHA256, 'notes_sha256': NOTES_SHA256, 'metadata_sha256': METADATA_SHA256,
        'transport_parts': [{'name': name, 'bytes': size, 'sha256': checksum} for name, size, checksum in PARTS],
        'notes_blob_sha1': blob_sha(notes_bytes), 'metadata_blob_sha1': blob_sha(metadata_bytes),
        'upload_tree_sha': tree, 'upload_commit_sha': commit_sha, 'upload_commit_parents': [SOURCE],
        'public_payload_paths': [APK_PATH, NOTES_PATH, METADATA_PATH],
        'ref_created': False, 'published': False,
    }
    path = Path('build/release-upload-preparation.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('Prepared APK blob: ' + APK_BLOB)
    print('Prepared upload commit: ' + commit_sha)
    print('No ref, tag or release was created. OAuth review and branch creation remain separate.')


if __name__ == '__main__':
    try:
        prepare()
    except Exception as error:
        print('Upload preparation refused: ' + (str(error) if isinstance(error, Refusal)
              else 'Invalid or unavailable preparation data.'), file=sys.stderr)
        raise SystemExit(1) from None
