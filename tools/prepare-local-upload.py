#!/usr/bin/env python3
"""Prepare immutable Git objects from a reviewed signed APK transport.

This one-off helper writes only Git objects and one strictly new fixed staging
ref. It never signs, executes metadata, creates an upload ref, tag or release.
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
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

REPOSITORY = 'Lillite/Vocalisation-des-sous-titres-Android'
PREPARE_BRANCH = 'release-prepare/v0.5.7-directcl'
STAGING_REF = 'refs/heads/release-blob/v0.5.7-directcl'
STAGING_API = '/git/ref/heads/release-blob/v0.5.7-directcl'
GIT_REMOTE = 'https://github.com/Lillite/Vocalisation-des-sous-titres-Android.git'
SOURCE = 'cdeec9e590c35ca69035c67c1afd3b0a6cde2157'
SOURCE_TREE = '2cb44887982f6c7cfef7e77fc225af2297ecb039'
SOURCE_PARENTS = ['1e982b907c6d322cb4066da743c9a8fefcabe7a2', '48b069c507cf2ea9221cf547693459d8c3c96ffc']
APK_BYTES = 53595121
APK_SHA256 = '0aba11bb5eace394c91cbdbf8ab77c54750add6d89d0b3834eb60ed0667fe82c'
APK_BLOB = '80ae45861cc1938c8520e3b5aa070a9a82bde932'
GZIP_BYTES = 24897237
GZIP_SHA256 = 'c2c4bbc00a4d0664ad7957d57ade8537e28789ec234ca6357079faa892089bf6'
PARTS = [('SousTitresVoix-0.5.7-directcl.apk.gz.part00', 4194304, '2c97cc085e595e832e735d2561ebe84e268d8b677dda4c98cfa2e07596ba9516'), ('SousTitresVoix-0.5.7-directcl.apk.gz.part01', 4194304, '684067ce7ae5fff1fe7e519b106ca7ecd2200f6c076dc409ef67b1d14117abb6'), ('SousTitresVoix-0.5.7-directcl.apk.gz.part02', 4194304, '288cd69e7bb21cac9e691f041979d13918ef361ef67d29c9b1ed6edacec0ae58'), ('SousTitresVoix-0.5.7-directcl.apk.gz.part03', 4194304, 'b9e341e78d1b0f01e8ade944e5654a94d71a54f68af2c46bf14f996a6df56569'), ('SousTitresVoix-0.5.7-directcl.apk.gz.part04', 4194304, '73a793389b7f1e59df1c69af241d0a69f499f1bd901e590e9005a9c2dbb64b4a'), ('SousTitresVoix-0.5.7-directcl.apk.gz.part05', 3925717, 'a46634c66ab6e9e8c69223a02029d19b9a3388097117e232a0d1a52ddacb19a3')]
NOTES_BYTES = 5311
NOTES_SHA256 = '32cefa0c381239590580fdf45fd235f7a4b115d7e111bf1977b9cbaa42d3db41'
METADATA_BYTES = 450
METADATA_SHA256 = 'f8ff1ab410c68cd886c6c7194746821344de3f9fb78f0d531e87d807e384e51a'
APK_NAME = 'SousTitresVoix-0.5.7-directcl.apk'
APK_PATH = 'release-upload/' + APK_NAME
NOTES_PATH = 'release-upload/notes.md'
METADATA_PATH = 'release-upload/release.json'
TRANSPORT_DIR = Path('release-transport')
EXPECTED_METADATA = {'tag': 'v0.5.7-directcl', 'source_sha': 'cdeec9e590c35ca69035c67c1afd3b0a6cde2157', 'apk_sha256': '0aba11bb5eace394c91cbdbf8ab77c54750add6d89d0b3834eb60ed0667fe82c', 'signer_sha256': '304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d', 'apk_file': 'release-upload/SousTitresVoix-0.5.7-directcl.apk', 'notes_file': 'release-upload/notes.md', 'title': 'Sous-titres en voix 0.5.7 — Accès OpenCL direct (préversion)'}
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


def safe_error_diagnostic(data, token):
    """Keep bounded message/resource/field/code, never error.value or raw data."""
    try:
        require(len(data) <= 8192, 'Oversized error diagnostic.')
        parsed = parse_json(data)
        require(isinstance(parsed, dict), 'Invalid error diagnostic.')
        result = {}
        message = parsed.get('message')
        if isinstance(message, str):
            if token:
                message = message.replace(token, '[redacted]')
            message = re.sub(r'[A-Za-z0-9+/=_-]{80,}', '[redacted-long-value]', message)
            message = re.sub(r'[\x00-\x1f\x7f]+', ' ', message)
            result['message'] = message[:500]
        errors = parsed.get('errors')
        if isinstance(errors, list):
            safe_errors = []
            for item in errors[:10]:
                if isinstance(item, dict):
                    safe_item = {key: value for key, value in item.items()
                                 if key in {'resource', 'field', 'code'} and isinstance(value, str) and
                                 re.fullmatch(r'[A-Za-z0-9_. -]{1,80}', value) and not (token and token in value)}
                    if safe_item:
                        safe_errors.append(safe_item)
            result['errors'] = safe_errors
        return json.dumps(result, ensure_ascii=False)
    except Exception:
        return 'No bounded parseable diagnostic.'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Refusal('API redirect refused.')


class GitHub:
    """Read-only API client; all Git writes use the reviewed fixed staging ref."""
    def __init__(self):
        token = os.environ.get('GITHUB_TOKEN', '')
        require(bool(token), 'Workflow token unavailable.')
        self._token = token
        self._opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, path, value=None, *, absent=False):
        allowed_read = path in {
            '/git/ref/heads/main', '/git/commits/' + SOURCE, '/git/trees/' + SOURCE_TREE,
            STAGING_API,
        } or bool(re.fullmatch(r'/git/commits/[0-9a-f]{40}', path))
        require(method == 'GET' and allowed_read and value is None,
                'Only read-only verification API operations are permitted.')
        require(not absent or path == STAGING_API, 'Optional absence is limited to the fixed staging ref.')
        request = urllib.request.Request(API + path, method='GET', headers={
            'Authorization': 'Bearer ' + self._token,
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2026-03-10',
            'User-Agent': 'SousTitresVoix-reviewed-git-upload-preparation',
        })
        try:
            with self._opener.open(request, timeout=90) as response:
                require(response.status == 200, 'Unexpected verification API status.')
                data = response.read(MAX_RESPONSE_BYTES + 1)
                require(len(data) <= MAX_RESPONSE_BYTES, 'Verification response exceeds limit.')
                return parse_json(data)
        except urllib.error.HTTPError as error:
            if absent and error.code == 404:
                return None
            diagnostic = safe_error_diagnostic(error.read(8193), self._token)
            raise Refusal('GitHub refused GET ' + path + ' with HTTP ' + str(error.code) +
                          '; ' + diagnostic + '; no retry.') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Refusal('GitHub verification interrupted; no automatic retry.') from None


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


def git_command(arguments, environment, *, input_bytes=None):
    result = subprocess.run(['git', *arguments], input=input_bytes, env=environment,
                            capture_output=True, timeout=240, check=False)
    # Git/curl may mention authentication in verbose failures. Never emit either stream.
    require(result.returncode == 0, 'Fixed-scope Git operation failed: ' + arguments[0] + '; output withheld.')
    return result.stdout.decode('utf-8').strip()


def git_environment(index):
    token = os.environ.get('GITHUB_TOKEN', '')
    require(bool(token), 'Workflow token unavailable.')
    environment = dict(os.environ)
    for name in list(environment):
        if name.startswith('GIT_TRACE') or name == 'GIT_CURL_VERBOSE':
            environment.pop(name, None)
    for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES',
                 'GIT_AUTHOR_DATE', 'GIT_COMMITTER_DATE'):
        environment.pop(name, None)
    # The credential exists only in this child environment, never command arguments/files/logs.
    authorization = base64.b64encode(('x-access-token:' + token).encode('utf-8')).decode('ascii')
    environment.update({
        'GIT_INDEX_FILE': str(index),
        'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_SYSTEM': '/dev/null', 'GIT_CONFIG_GLOBAL': '/dev/null',
        'GIT_CONFIG_COUNT': '1',
        'GIT_CONFIG_KEY_0': 'http.https://github.com/.extraheader',
        'GIT_CONFIG_VALUE_0': 'AUTHORIZATION: basic ' + authorization,
        'GIT_TERMINAL_PROMPT': '0', 'GCM_INTERACTIVE': 'Never',
        'GIT_TRACE': '0', 'GIT_TRACE_CURL': '0', 'GIT_TRACE_PACKET': '0',
        'GIT_AUTHOR_NAME': 'github-actions[bot]',
        'GIT_AUTHOR_EMAIL': '41898282+github-actions[bot]@users.noreply.github.com',
        'GIT_COMMITTER_NAME': 'github-actions[bot]',
        'GIT_COMMITTER_EMAIL': '41898282+github-actions[bot]@users.noreply.github.com',
    })
    return environment


def prepare():
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY and
            os.environ.get('GITHUB_EVENT_NAME') == 'push' and
            os.environ.get('GITHUB_REF') == 'refs/heads/' + PREPARE_BRANCH and
            HEX40.fullmatch(os.environ.get('GITHUB_SHA', '')),
            'Preparation is restricted to the reviewed repository and temporary branch.')
    require(STAGING_REF == 'refs/heads/release-blob/v0.5.7-directcl' and
            STAGING_API == '/git/ref/heads/release-blob/v0.5.7-directcl' and
            GIT_REMOTE == 'https://github.com/' + REPOSITORY + '.git',
            'Fixed staging scope changed.')
    notes_bytes = checked_bytes(TRANSPORT_DIR / 'notes.md', NOTES_BYTES, NOTES_SHA256)
    metadata_bytes = checked_bytes(TRANSPORT_DIR / 'release.json', METADATA_BYTES, METADATA_SHA256)
    notes_bytes.decode('utf-8'); metadata_bytes.decode('utf-8')
    require(parse_json(metadata_bytes) == EXPECTED_METADATA, 'Publication metadata differs from reviewed constants.')
    github = GitHub()
    assert_main(github)
    require(github.request('GET', STAGING_API, absent=True) is None,
            'The fixed staging ref already exists; refusing replacement.')
    source_tree = github.request('GET', '/git/trees/' + SOURCE_TREE)
    require(source_tree.get('sha') == SOURCE_TREE and source_tree.get('truncated') is False and
            isinstance(source_tree.get('tree'), list) and
            not any(item.get('path') == 'release-upload' for item in source_tree['tree']),
            'Reviewed source already contains an upload payload or has an incomplete tree.')
    with tempfile.TemporaryDirectory(prefix='reviewed-git-apk-transport-') as temporary:
        work = Path(temporary)
        gzip_path = work / (APK_NAME + '.gz')
        join_transport(TRANSPORT_DIR, gzip_path)
        apk = work / APK_NAME
        restore_apk(gzip_path, apk)
        print('Transport restored: exact reviewed signed APK verified.', flush=True)
        environment = git_environment(work / 'isolated-index')
        try:
            git_command(['fetch', '--quiet', '--no-tags', '--depth=2', GIT_REMOTE, SOURCE], environment)
            require(git_command(['show', '-s', '--format=%T', SOURCE], environment) == SOURCE_TREE and
                    git_command(['show', '-s', '--format=%P', SOURCE], environment).split() == SOURCE_PARENTS,
                    'Fetched source tree or merge parents mismatch.')
            require(not git_command(['ls-remote', '--heads', GIT_REMOTE, STAGING_REF], environment),
                    'Fixed staging ref appeared before preparation.')
            git_command(['read-tree', SOURCE_TREE], environment)
            apk_blob = git_command(['hash-object', '-w', str(apk)], environment)
            require(apk_blob == APK_BLOB, 'Local Git APK blob identity mismatch.')
            notes_blob = git_command(['hash-object', '-w', '--stdin'], environment, input_bytes=notes_bytes)
            metadata_blob = git_command(['hash-object', '-w', '--stdin'], environment, input_bytes=metadata_bytes)
            require(notes_blob == blob_sha(notes_bytes) and metadata_blob == blob_sha(metadata_bytes),
                    'Local Git public text blob mismatch.')
            for path, sha in ((APK_PATH, apk_blob), (NOTES_PATH, notes_blob), (METADATA_PATH, metadata_blob)):
                git_command(['update-index', '--add', '--cacheinfo', '100644,' + sha + ',' + path], environment)
            tree = git_command(['write-tree'], environment)
            require(HEX40.fullmatch(tree), 'Invalid prepared upload tree.')
            expected_changes = {'A\t' + path for path in (APK_PATH, NOTES_PATH, METADATA_PATH)}
            require(set(git_command(['diff-tree', '--no-commit-id', '--name-status', '-r',
                                     SOURCE_TREE, tree], environment).splitlines()) == expected_changes,
                    'Prepared tree changes more than the three allowed public files.')
            message = 'Préparer APK 0.5.7-directcl signé et vérifié avec parent source unique\n'.encode('utf-8')
            commit_sha = git_command(['commit-tree', tree, '-p', SOURCE], environment, input_bytes=message)
            require(HEX40.fullmatch(commit_sha) and
                    git_command(['show', '-s', '--format=%P', commit_sha], environment).split() == [SOURCE] and
                    git_command(['show', '-s', '--format=%T', commit_sha], environment) == tree,
                    'Prepared upload commit does not have the exact source as its sole parent.')
            assert_main(github)
            require(github.request('GET', STAGING_API, absent=True) is None,
                    'The fixed staging ref appeared; no push is allowed.')
            print('Pushing reviewed objects only to the new fixed release-blob staging ref.', flush=True)
            git_command(['push', '--porcelain', '--force-with-lease=' + STAGING_REF + ':',
                         GIT_REMOTE, commit_sha + ':' + STAGING_REF], environment)
        finally:
            environment.pop('GIT_CONFIG_VALUE_0', None)
        staged = github.request('GET', STAGING_API)
        require(staged.get('object', {}).get('sha') == commit_sha and
                staged.get('object', {}).get('type') == 'commit', 'Remote staging ref identity mismatch.')
        confirmed = github.request('GET', '/git/commits/' + commit_sha)
        require(confirmed.get('sha') == commit_sha and confirmed.get('tree', {}).get('sha') == tree and
                [parent.get('sha') for parent in confirmed.get('parents', [])] == [SOURCE],
                'Remote upload commit identity, tree or sole parent mismatch.')
        assert_main(github)
    report = {
        'status': 'staged-not-published', 'repository': REPOSITORY,
        'transport_commit': os.environ['GITHUB_SHA'], 'source_commit': SOURCE,
        'source_tree': SOURCE_TREE, 'source_parents': SOURCE_PARENTS,
        'apk_bytes': APK_BYTES, 'apk_sha256': APK_SHA256, 'apk_blob_sha1': APK_BLOB,
        'gzip_sha256': GZIP_SHA256, 'notes_sha256': NOTES_SHA256, 'metadata_sha256': METADATA_SHA256,
        'transport_parts': [{'name': name, 'bytes': size, 'sha256': checksum} for name, size, checksum in PARTS],
        'notes_blob_sha1': notes_blob, 'metadata_blob_sha1': metadata_blob,
        'upload_tree_sha': tree, 'upload_commit_sha': commit_sha, 'upload_commit_parents': [SOURCE],
        'public_payload_paths': [APK_PATH, NOTES_PATH, METADATA_PATH],
        'staging_ref': STAGING_REF, 'staging_ref_created': True,
        'upload_ref_created': False, 'published': False,
    }
    path = Path('build/release-upload-preparation.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('Prepared APK blob: ' + APK_BLOB)
    print('Staged upload commit: ' + commit_sha)
    print('Only the fixed release-blob ref was created. No upload ref, tag or release was created.')


if __name__ == '__main__':
    try:
        prepare()
    except Exception as error:
        print('Upload preparation refused: ' + (str(error) if isinstance(error, Refusal)
              else 'Invalid or unavailable preparation data.'), file=sys.stderr)
        raise SystemExit(1) from None
