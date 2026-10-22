#!/usr/bin/env python3
"""Reconcile the existing releases listed in the manifest; dry-run by default."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPOSITORY = "Lillite/Vocalisation-des-sous-titres-Android"
ROOT = Path(__file__).resolve().parents[1]
API = f"https://api.github.com/repos/{REPOSITORY}"
SHA = re.compile(r"[0-9a-f]{40}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


class SyncError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SyncError(message)


class ApiRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SyncError("API redirect refused; repository scope must remain unchanged.")


class DownloadRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urlsplit(newurl)
        require(target.scheme == "https" and target.hostname in {
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        }, "Asset download redirected outside the permitted GitHub hosts.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = build_opener(ApiRedirects())

    def request(self, method, path, payload=None, optional=False):
        require(path.startswith("/") and not path.startswith("//"), "Invalid API path.")
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "SousTitresVoix-release-sync",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(API + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as error:
            if optional and error.code == 404:
                return None
            # Do not print response bodies, credentials or signed download URLs.
            raise SyncError(f"GitHub {method} {path}: HTTP {error.code}.") from None
        except (URLError, TimeoutError, json.JSONDecodeError):
            raise SyncError(f"GitHub {method} {path}: network or JSON response error.") from None

    def ref(self, tag):
        result = self.request("GET", "/git/ref/tags/" + quote(tag, safe=""), optional=True)
        if result is None:
            return None
        require(result.get("ref") == "refs/tags/" + tag, f"Unexpected ref for {tag}.")
        original = result["object"]
        obj = original
        # Accept an existing annotated canonical tag only if it resolves correctly.
        for _ in range(8):
            require(SHA.fullmatch(obj.get("sha", "")), f"Invalid tag object for {tag}.")
            if obj.get("type") == "commit":
                return {"sha": original["sha"], "type": original["type"], "commit": obj["sha"]}
            require(obj.get("type") == "tag", f"Tag {tag} does not resolve to a commit.")
            obj = self.request("GET", "/git/tags/" + obj["sha"])["object"]
        raise SyncError(f"Tag nesting is too deep for {tag}.")


def load_manifest(path):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    require(manifest.get("repository") == REPOSITORY, "Manifest repository is outside the permitted scope.")
    entries = manifest.get("releases")
    require(isinstance(entries, list) and entries, "The release manifest is empty.")
    for entry in entries:
        require(isinstance(entry, dict), "Invalid release entry.")
        for key in ("id",):
            require(type(entry.get(key)) is int and entry[key] > 0, f"Invalid {key}.")
        for key in ("source_commit", "old_tag_sha"):
            require(SHA.fullmatch(entry.get(key, "")), f"Invalid {key} for release {entry['id']}.")
        for key in ("tag", "old_tag"):
            require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", entry.get(key, "")), f"Invalid {key}.")
        require(re.fullmatch(r"v\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?", entry["tag"]), "Canonical tags must use a version prefixed with v.")
        require(entry["tag"] != entry["old_tag"], "Old and canonical tags must differ.")
        require(isinstance(entry.get("title"), str) and entry["title"].strip(), "Missing release title.")
        require(type(entry.get("prerelease")) is bool and type(entry.get("latest")) is bool, "Release flags must be booleans.")
        require(not (entry["prerelease"] and entry["latest"]), "A prerelease cannot be latest.")
        notes = entry.get("notes")
        require(isinstance(notes, str), "Missing release notes path.")
        notes_path = (ROOT / notes).resolve()
        require(notes_path.is_relative_to(ROOT / "docs/releases") and notes_path.suffix == ".md", "Notes must be Markdown files under docs/releases.")
        entry["body"] = notes_path.read_text(encoding="utf-8").strip()
        require(0 < len(entry["body"]) <= 65536, "Release notes are empty or too long.")
        asset = entry.get("asset", {})
        for key in ("id", "size", "version_code"):
            require(type(asset.get(key)) is int and asset[key] > 0, f"Invalid asset {key}.")
        require(isinstance(asset.get("name"), str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", asset["name"]), "Invalid APK filename.")
        require(SHA256.fullmatch(asset.get("sha256", "")), "Invalid asset SHA-256.")
        for key in ("version_name", "application_id"):
            require(isinstance(asset.get(key), str) and asset[key], f"Missing asset {key}.")
    for key in ("id", "tag", "old_tag"):
        require(len({entry[key] for entry in entries}) == len(entries), f"Duplicate release {key}.")
    require(len({entry["asset"]["id"] for entry in entries}) == len(entries), "Duplicate asset id.")
    require(not ({entry["tag"] for entry in entries} & {entry["old_tag"] for entry in entries}), "Canonical and old tag sets overlap.")
    require(sum(entry["latest"] for entry in entries) == 1, "Exactly one release must be latest.")
    return sorted(entries, key=lambda entry: entry["latest"])


def asset_snapshot(release):
    keys = ("id", "name", "size", "digest", "label", "content_type", "state")
    return sorted(({key: asset.get(key) for key in keys} for asset in release["assets"]), key=lambda asset: asset["id"])


def snapshot(release):
    keys = ("id", "tag_name", "target_commitish", "name", "body", "prerelease", "draft", "html_url")
    result = {key: release.get(key) for key in keys}
    result["assets"] = asset_snapshot(release)
    return result


def release_field_matches(key, actual, expected):
    # GitHub's editor can serialize Markdown with CRLF instead of LF. Only this
    # representation difference is ignored; snapshots and reports stay verbatim.
    if key == "body" and isinstance(actual, str) and isinstance(expected, str):
        return actual.replace("\r\n", "\n") == expected.replace("\r\n", "\n")
    return actual == expected


def verify_download(asset, entry, tag):
    expected = entry["asset"]
    url = urlsplit(asset.get("browser_download_url", ""))
    expected_path = f"/{REPOSITORY}/releases/download/{tag}/{expected['name']}"
    require(url.scheme == "https" and url.netloc == "github.com" and unquote(url.path) == expected_path and not url.query and not url.fragment, "Unexpected public APK download URL.")
    request = Request(asset["browser_download_url"], headers={"User-Agent": "SousTitresVoix-release-sync"})
    digest = hashlib.sha256()
    size = 0
    try:
        with build_opener(DownloadRedirects()).open(request, timeout=60) as response:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                require(size <= expected["size"], f"Downloaded APK is too large: {expected['name']}.")
                digest.update(chunk)
    except (HTTPError, URLError, TimeoutError):
        raise SyncError(f"Cannot download the public APK {expected['name']} for verification.") from None
    require(size == expected["size"] and digest.hexdigest() == expected["sha256"], f"Downloaded APK hash or size mismatch: {expected['name']}.")


def verify_release(client, entry, canonical=False, original_assets=None):
    release = client.request("GET", f"/releases/{entry['id']}")
    require(release.get("id") == entry["id"] and release.get("draft") is False, f"Release {entry['id']} is missing or unexpectedly a draft.")
    require(not release.get("immutable", False), f"Release {entry['id']} is immutable.")
    tags = {entry["tag"]} if canonical else {entry["old_tag"], entry["tag"]}
    require(release.get("tag_name") in tags, f"Unexpected current tag for release {entry['id']}.")
    matches = [asset for asset in release["assets"] if asset.get("id") == entry["asset"]["id"]]
    require(len(matches) == 1, f"Expected APK asset is missing from release {entry['id']}.")
    asset, expected = matches[0], entry["asset"]
    require(all(asset.get(key) == expected[key] for key in ("id", "name", "size")), f"APK metadata mismatch for release {entry['id']}.")
    require(asset.get("state") == "uploaded" and asset.get("digest") == "sha256:" + expected["sha256"], f"APK digest mismatch for release {entry['id']}.")
    if original_assets is not None:
        require(asset_snapshot(release) == original_assets, f"Assets changed for release {entry['id']}.")
    verify_download(asset, entry, release["tag_name"])
    if canonical:
        for key, value in desired_release(entry).items():
            if key != "make_latest":
                require(release_field_matches(key, release.get(key), value), f"Release {entry['id']} did not retain requested {key}.")
    return release


def verify_source(client, entry):
    result = client.request("GET", "/contents/app/build.gradle.kts?ref=" + entry["source_commit"])
    require(result.get("encoding") == "base64", "Unexpected build file encoding.")
    text = base64.b64decode(result["content"], validate=False).decode("utf-8")
    expected = entry["asset"]
    expressions = {
        "version_name": r'^\s*versionName\s*=\s*"([^"\n]+)"\s*$',
        "version_code": r'^\s*versionCode\s*=\s*(\d+)\s*$',
        "application_id": r'^\s*applicationId\s*=\s*"([^"\n]+)"\s*$',
    }
    for key, expression in expressions.items():
        require(re.findall(expression, text, re.M) == [str(expected[key])], f"Source {key} disagrees with the manifest for {entry['tag']}.")


def verify_refs(client, entry):
    canonical = client.ref(entry["tag"])
    old = client.ref(entry["old_tag"])
    require(canonical is None or canonical["commit"] == entry["source_commit"], f"Canonical tag {entry['tag']} already targets another commit.")
    require(old is None or old["sha"] == entry["old_tag_sha"], f"Old tag {entry['old_tag']} changed; refusing to delete it.")
    return canonical, old


def desired_release(entry):
    return {
        "tag_name": entry["tag"], "target_commitish": entry["source_commit"],
        "name": entry["title"], "body": entry["body"],
        "prerelease": entry["prerelease"], "draft": False,
        "make_latest": "true" if entry["latest"] else "false",
    }


def synchronize(client, entries, apply, report):
    latest = client.request("GET", "/releases/latest", optional=True)
    latest_id = latest.get("id") if latest else None
    require(latest_id is None or latest_id in {entry["id"] for entry in entries},
            "A release outside this manifest is now latest; update the manifest before changing the recommended version.")
    report["before_latest_id"] = latest_id
    report["before"], report["plan"], report["actions"] = [], [], []
    originals = {}
    # No write occurs until every release, asset, source and both refs pass.
    for entry in entries:
        release = verify_release(client, entry)
        verify_source(client, entry)
        canonical, old = verify_refs(client, entry)
        originals[entry["id"]] = asset_snapshot(release)
        report["before"].append(snapshot(release))
        desired = desired_release(entry)
        update = any(not release_field_matches(key, release.get(key), value) for key, value in desired.items() if key != "make_latest")
        update = update or (entry["latest"] and latest_id != entry["id"])
        report["plan"].append({"id": entry["id"], "tag": entry["tag"], "create_tag": canonical is None, "update_release": update, "delete_old_tag": old is not None})
    report["preflight"] = "passed"
    if not apply:
        report["after"] = report["before"]
        report["after_latest_id"] = latest_id
        report["status"] = "dry-run"
        return

    # Repeat the checks immediately before each mutation to detect concurrent edits.
    for entry, plan, before in zip(entries, report["plan"], report["before"]):
        current = verify_release(client, entry, original_assets=originals[entry["id"]])
        require(snapshot(current) == before, f"Release {entry['id']} changed after preflight.")
        canonical, _ = verify_refs(client, entry)
        if canonical is None:
            client.request("POST", "/git/refs", {"ref": "refs/tags/" + entry["tag"], "sha": entry["source_commit"]})
            report["actions"].append({"action": "create_tag", "tag": entry["tag"], "commit": entry["source_commit"]})
        if plan["update_release"]:
            client.request("PATCH", f"/releases/{entry['id']}", desired_release(entry))
            report["actions"].append({"action": "update_release", "id": entry["id"], "tag": entry["tag"]})

    def verify_all():
        after = []
        for entry in entries:
            release = verify_release(client, entry, canonical=True, original_assets=originals[entry["id"]])
            canonical, _ = verify_refs(client, entry)
            require(canonical is not None and canonical["commit"] == entry["source_commit"], f"Missing correct canonical tag {entry['tag']}.")
            after.append(snapshot(release))
        latest = client.request("GET", "/releases/latest")
        require(latest.get("id") == next(entry["id"] for entry in entries if entry["latest"]), "GitHub latest release is incorrect.")
        report["after"] = after
        report["after_latest_id"] = latest["id"]

    verify_all()
    # Only obsolete refs are deleted; existing release and asset IDs are retained.
    for entry in entries:
        _, old = verify_refs(client, entry)
        if old is not None:
            require(client.request("GET", "/releases/tags/" + quote(entry["old_tag"], safe=""), optional=True) is None,
                    f"Old tag {entry['old_tag']} is still attached to a release.")
            client.request("DELETE", "/git/refs/tags/" + quote(entry["old_tag"], safe=""))
            report["actions"].append({"action": "delete_old_tag", "tag": entry["old_tag"], "sha": entry["old_tag_sha"]})
    verify_all()
    for entry in entries:
        require(client.ref(entry["old_tag"]) is None, f"Old tag {entry['old_tag']} is still present.")
    report["status"] = "applied"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / ".github/release-history.json")
    parser.add_argument("--report", type=Path, default=ROOT / "build/verification/releases-report.json")
    parser.add_argument("--apply", action="store_true", help="Apply the verified manifest from this repository's main branch in GitHub Actions.")
    args = parser.parse_args()
    report = {"repository": REPOSITORY, "mode": "apply" if args.apply else "dry-run", "status": "failed"}
    try:
        token = os.environ.get("GITHUB_TOKEN", "")
        repository = os.environ.get("GITHUB_REPOSITORY")
        require(repository in (None, REPOSITORY), "Workflow repository is outside the permitted scope.")
        if args.apply:
            require(repository == REPOSITORY and os.environ.get("GITHUB_REF") == "refs/heads/main", "Apply is permitted only on this repository's main branch.")
            require(bool(token), "GITHUB_TOKEN is required for apply.")
        entries = load_manifest(args.manifest)
        synchronize(GitHub(token), entries, args.apply, report)
        print(json.dumps({"status": report["status"], "plan": report["plan"], "actions": report["actions"]}, ensure_ascii=False, indent=2))
        return 0
    except (SyncError, OSError, ValueError, KeyError, TypeError) as error:
        # Validation errors contain no credentials; network errors are sanitized above.
        message = str(error)
        if os.environ.get("GITHUB_TOKEN"):
            message = message.replace(os.environ["GITHUB_TOKEN"], "[redacted]")
        report["error"] = message
        print("ERROR: " + message, file=sys.stderr)
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
