#!/usr/bin/env python3
"""Prove a main merge was tested before building it; never rerun any test suite."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import sys
import urllib.parse
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_provenance', ROOT / 'tools/publish-verified-release.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
ARTIFACT = 'SousTitresVoix-release-validation'
EVIDENCE = ROOT / 'build/verification/pr-validation'


def commit_identity(github, sha):
    p.require(isinstance(sha, str) and p.HEX40.fullmatch(sha), 'Invalid commit identity.')
    value = github.api(p.PREFIX + '/git/commits/' + sha)
    p.require(value.get('sha') == sha, 'Commit identity mismatch.')
    tree = value.get('tree', {}).get('sha')
    parents = [item.get('sha') for item in value.get('parents', [])]
    p.require(isinstance(tree, str) and p.HEX40.fullmatch(tree) and len(parents) == 2 and
              all(isinstance(item, str) and p.HEX40.fullmatch(item) for item in parents) and
              parents[0] != parents[1], 'Release requires a direct two-parent PR merge.')
    return tree, parents


def select_run(github, source):
    """A matching old success cannot excuse a newer failure or a changed base."""
    p.assert_main(github, source)
    tree, parents = commit_identity(github, source)
    head = parents[1]
    pulls = github.api(p.PREFIX + '/commits/' + head + '/pulls?per_page=100')
    p.require(isinstance(pulls, list) and len(pulls) < 100, 'PR lookup is incomplete.')
    candidates = [item for item in pulls if item.get('state') == 'closed' and item.get('merged_at') and
                  item.get('base', {}).get('ref') == 'main' and
                  item.get('base', {}).get('repo', {}).get('full_name') == p.REPOSITORY and
                  item.get('head', {}).get('sha') == head and
                  item.get('head', {}).get('repo', {}).get('full_name') == p.REPOSITORY]
    p.require(len(candidates) == 1 and type(candidates[0].get('number')) is int,
              'No unique merged PR matches this main merge.')
    number = candidates[0]['number']
    details = github.api(p.PREFIX + f'/pulls/{number}')
    p.require(details.get('merged') is True and details.get('state') == 'closed' and
              details.get('head', {}).get('sha') == head and details.get('base', {}).get('ref') == 'main' and
              details.get('base', {}).get('repo', {}).get('full_name') == p.REPOSITORY and
              details.get('head', {}).get('repo', {}).get('full_name') == p.REPOSITORY,
              'PR details do not confirm the merged change.')
    query = urllib.parse.urlencode({'event': 'pull_request', 'head_sha': head, 'per_page': 100})
    listing = github.api(p.PREFIX + '/actions/workflows/android.yml/runs?' + query)
    p.require(listing.get('total_count', 0) < 100, 'Validation run lookup is incomplete.')
    runs = [item for item in listing.get('workflow_runs', []) if
            item.get('head_sha') == head and item.get('event') == 'pull_request' and
            item.get('path') == '.github/workflows/android.yml' and
            item.get('head_repository', {}).get('full_name') == p.REPOSITORY]
    p.require(bool(runs), 'No pre-merge APK validation exists for this PR head.')
    run = max(runs, key=lambda item: (int(item['id']), int(item.get('run_attempt', 1))))
    p.require(run.get('status') == 'completed' and run.get('conclusion') == 'success',
              'Latest pre-merge APK validation did not succeed.')
    return {'source_sha': source, 'source_tree': tree, 'source_parents': parents,
            'pr_number': number, 'validation_run': run['id'],
            'validation_attempt': run.get('run_attempt', 1), 'head_sha': head}


def read_artifact(bundle, destination, proof):
    """Read only the bounded named files; no ZIP entry is extracted as a path."""
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        p.require(len(entries) <= 100 and len({x.filename for x in entries}) == len(entries),
                  'Ambiguous validation artifact.')
        def entry(name, maximum):
            matches = [x for x in entries if PurePosixPath(x.filename).name == name]
            p.require(len(matches) == 1 and 0 < matches[0].file_size <= maximum and
                      not matches[0].is_dir() and not matches[0].flag_bits & 1,
                      'Missing, oversized or ambiguous validation evidence: ' + name)
            return matches[0]
        def text(name):
            return archive.read(entry(name, 256)).decode('ascii').strip()
        tested_source = text('source-commit.txt')
        p.require(p.HEX40.fullmatch(tested_source) and text('source-tree.txt') == proof['source_tree'] and
                  text('source-parents.txt').split() == proof['source_parents'],
                  'Main differs from the exact merge tree or parents tested before fusion.')
        item = entry('app-release.apk', p.MAX_BINARY_BYTES)
        with archive.open(item) as incoming, destination.open('wb') as outgoing:
            total = 0
            while block := incoming.read(1024 * 1024):
                total += len(block)
                p.require(total <= p.MAX_BINARY_BYTES, 'APK exceeds validation limit.')
                outgoing.write(block)
    proof['tested_merge_sha'] = tested_source
    proof['tested_apk_sha256'] = p.sha256(destination)


def prepare(github, source):
    proof = select_run(github, source)
    result = github.api(p.PREFIX + f"/actions/runs/{proof['validation_run']}/artifacts?per_page=100")
    artifacts = [item for item in result.get('artifacts', []) if
                 item.get('name') == ARTIFACT and not item.get('expired')]
    p.require(len(artifacts) == 1 and type(artifacts[0].get('id')) is int,
              'Validated test APK artifact is missing or ambiguous.')
    artifact = artifacts[0]
    p.require(artifact.get('workflow_run', {}).get('head_sha') in (None, proof['head_sha']),
              'Validation artifact belongs to another PR head.')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    bundle = EVIDENCE / 'validated-pr.zip'
    github.download(p.PREFIX + f"/actions/artifacts/{artifact['id']}/zip", bundle)
    read_artifact(bundle, EVIDENCE / 'app-release.apk', proof)
    bundle.unlink()
    p.assert_main(github, source)
    (EVIDENCE / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    print(f"PR #{proof['pr_number']}, run {proof['validation_run']}: same tree and merge parents; no tests rerun.")


def compare(apk):
    proof = json.loads((EVIDENCE / 'proof.json').read_text(encoding='utf-8'))
    tested = EVIDENCE / 'app-release.apk'
    p.require(proof['source_sha'] == os.environ.get('GITHUB_SHA') and
              p.sha256(tested) == proof['tested_apk_sha256'], 'Validation evidence changed.')
    p.require(p.apk_payload(tested) == p.apk_payload(apk),
              'Rebuilt main APK differs from the pre-merge tested APK; publication blocked.')
    proof['rebuilt_apk_sha256'] = p.sha256(apk)
    proof['payload_identical'] = True
    (EVIDENCE / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with Path(summary).open('a', encoding='utf-8') as stream:
            stream.write(f"PR #{proof['pr_number']} tested in [run {proof['validation_run']}]"
                         f"(https://github.com/{p.REPOSITORY}/actions/runs/{proof['validation_run']}).\n\n"
                         'Post-merge APK rebuilt without test suites. All application entries match the tested APK. '
                         'This artifact is unsigned; durable signing is required before installation/publication.\n')
    print('Rebuilt APK payload matches the tested PR APK; signature is the only excluded metadata.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'compare'))
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    p.require(os.environ.get('GITHUB_REPOSITORY') == p.REPOSITORY and
              os.environ.get('GITHUB_EVENT_NAME') == 'push' and os.environ.get('GITHUB_REF') == 'refs/heads/main',
              'Post-merge preparation is restricted to main pushes.')
    if args.operation == 'prepare':
        prepare(p.GitHub(os.environ.get('GITHUB_TOKEN', '')), os.environ.get('GITHUB_SHA', ''))
    else:
        p.require(args.apk is not None and args.apk.is_file(), 'Missing rebuilt release APK.')
        compare(args.apk)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Release preparation stopped: ' + (str(error) if isinstance(error, p.Refusal)
              else 'Invalid or unavailable validation evidence.'), file=sys.stderr)
        raise SystemExit(1) from None
