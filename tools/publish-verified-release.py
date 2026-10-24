#!/usr/bin/env python3
"""Publish only the signed 0.4.0 translation edition already validated on main.

The temporary branch contains three data files, never a signing key. This script
does not compile or sign an APK, delete a release/tag, replace an asset, execute a
payload command, or accept a caller-controlled API host. An interrupted draft is
left visible for review and is never silently replaced by a later invocation.

GitHub API references:
https://docs.github.com/en/rest/releases/releases
https://docs.github.com/en/rest/releases/assets
https://docs.github.com/en/rest/actions/artifacts
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import time
from typing import Any
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


REPOSITORY = "Lillite/Vocalisation-des-sous-titres-Android"
BRANCH = "release-upload/v0.4.0"
TAG = "v0.4.0"
TITLE = "Sous-titres en voix 0.4.0 — édition Traduction"
APPLICATION_ID = "fr.soustitresvoix.player.translation"
VERSION_NAME = "0.4.0"
VERSION_CODE = "6"
EXPECTED_SIGNER_SHA256 = "304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d"
APK_PATH = "release-upload/SousTitresVoix-0.4.0-traduction.apk"
NOTES_PATH = "release-upload/notes.md"
METADATA_PATH = "release-upload/release.json"
VALIDATION_ARTIFACT = "SousTitresVoix-release-validation"
API_VERSION = "2026-03-10"
MAX_BINARY_BYTES = 512 * 1024 * 1024
MAX_JSON_BYTES = 8 * 1024 * 1024
CI_WAIT_SECONDS = 25 * 60
PREFIX = f"/repos/{REPOSITORY}"
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))\Z", re.IGNORECASE)
ARCHIVABLE_BRANCHES = (
    "cinema-0.2", "cinema-0.3", "cinema-0.3.1", "cinema-voix-v02", "consolidation-main-0.3.2",
    "fix/release-notes-line-endings", "validation-cinema-0.3",
    "work/etape-1-modularisation-20261022", "work/etape-2-capacites-20261022",
    "work/etape-3-commandes-image-20261023", "work/etape-4-interface-image-20261023",
    "work/etape-5-commandes-audio-20261023", "work/etape-6-audio-expert-20261023",
    "work/traduction-francaise-20261024",
)


class Refusal(RuntimeError):
    """A publication invariant failed; no secret/API body belongs in its text."""


def require(condition: Any, message: str) -> None:
    if not condition:
        raise Refusal(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field refused.")
        result[key] = value
    return result


def parse_json(data: bytes) -> Any:
    require(len(data) <= MAX_JSON_BYTES, "JSON response exceeds the verification limit.")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError) as error:
        raise Refusal("Invalid UTF-8 JSON data.") from error


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def run(arguments: list[str], timeout: int = 120) -> str:
    try:
        result = subprocess.run(arguments, check=False, capture_output=True, text=True,
                                encoding="utf-8", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Refusal("A required local verification tool could not complete.") from error
    require(result.returncode == 0, f"Verification failed: {Path(arguments[0]).name}.")
    return result.stdout


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: Any, response: Any, code: int, message: str,
                         headers: Any, new_url: str) -> None:
        return None


class GitHub:
    def __init__(self, token: str) -> None:
        require(bool(token), "The workflow token is missing.")
        self._token = token
        self._opener = urllib.request.build_opener(NoRedirect())

    def _request(self, url: str, method: str = "GET", body: bytes | None = None,
                 content_type: str = "application/json", accept: str = "application/vnd.github+json",
                 authenticated: bool = True) -> Any:
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == "https" and parsed.username is None and parsed.password is None,
                "Non-HTTPS or credential-bearing URL refused.")
        if authenticated:
            require(parsed.hostname in {"api.github.com", "uploads.github.com"} and parsed.port in (None, 443),
                    "Workflow token destination refused.")
        headers = {"Accept": accept, "User-Agent": "SousTitresVoix-verified-release",
                   "X-GitHub-Api-Version": API_VERSION}
        if authenticated:
            headers["Authorization"] = f"Bearer {self._token}"
        if body is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        return self._opener.open(request, timeout=90)

    def api(self, path: str, method: str = "GET", data: Any = None,
            expected: tuple[int, ...] = (200,), absent: bool = False) -> Any:
        require(path.startswith(PREFIX + "/"), "API repository scope refused.")
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        try:
            with self._request("https://api.github.com" + path, method, body) as response:
                require(response.status in expected, "Unexpected GitHub API status.")
                payload = response.read(MAX_JSON_BYTES + 1)
                return parse_json(payload) if payload else None
        except urllib.error.HTTPError as error:
            if absent and error.code == 404:
                return None
            # An error body can contain user data; never print it or the token.
            raise Refusal(f"GitHub refused {method} with HTTP {error.code}; no automatic retry.") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Refusal("GitHub request interrupted; no automatic mutation retry.") from None

    def download(self, path: str, destination: Path, maximum: int = MAX_BINARY_BYTES) -> None:
        require(path.startswith(PREFIX + "/"), "Binary download repository scope refused.")
        # Actions negotiates its ZIP redirect as JSON; release assets negotiate
        # binary content. The signed CDN request always remains unauthenticated.
        api_accept = ("application/vnd.github+json"
                      if re.fullmatch(re.escape(PREFIX) + r"/actions/artifacts/[0-9]+/zip", path)
                      else "application/octet-stream")
        url = "https://api.github.com" + path
        authenticated = True
        for _ in range(5):
            try:
                response = self._request(url, accept=api_accept if authenticated else "application/octet-stream",
                                         authenticated=authenticated)
            except urllib.error.HTTPError as error:
                if error.code not in (301, 302, 303, 307, 308):
                    raise Refusal(f"GitHub binary download failed with HTTP {error.code}.") from None
                location = error.headers.get("Location", "")
                parsed = urllib.parse.urlsplit(location)
                host = parsed.hostname or ""
                require(parsed.scheme == "https" and parsed.username is None and parsed.password is None and
                        parsed.port in (None, 443) and
                        (host.endswith(".githubusercontent.com") or host.endswith(".blob.core.windows.net")),
                        "Binary redirect destination refused.")
                url, authenticated = location, False
                continue
            except (urllib.error.URLError, TimeoutError, OSError):
                raise Refusal("Binary download interrupted.") from None
            with response, destination.open("wb") as output:
                require(response.status == 200, "Unexpected binary download status.")
                length = response.headers.get("Content-Length")
                require(length is None or (length.isdigit() and int(length) <= maximum),
                        "Binary download exceeds the verification limit.")
                size = 0
                while block := response.read(1024 * 1024):
                    size += len(block)
                    require(size <= maximum, "Binary download exceeds the verification limit.")
                    output.write(block)
                require(size > 0, "An empty binary download was refused.")
            return
        raise Refusal("Too many binary redirects.")

    def upload(self, release_id: int, file: Path, content_type: str) -> dict[str, Any]:
        require(type(release_id) is int and release_id > 0, "Invalid draft release identity.")
        require(file.is_file() and 0 < file.stat().st_size <= MAX_BINARY_BYTES, "Invalid release asset size.")
        url = (f"https://uploads.github.com{PREFIX}/releases/{release_id}/assets?" +
               urllib.parse.urlencode({"name": file.name}))
        try:
            with self._request(url, "POST", file.read_bytes(), content_type) as response:
                require(response.status == 201, "Asset upload was not confirmed.")
                asset = parse_json(response.read(MAX_JSON_BYTES + 1))
        except urllib.error.HTTPError as error:
            raise Refusal(f"Asset upload failed with HTTP {error.code}; draft retained for review.") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise Refusal("Asset upload interrupted; draft retained, no automatic retry.") from None
        require(isinstance(asset, dict) and asset.get("state") == "uploaded" and
                asset.get("name") == file.name and asset.get("size") == file.stat().st_size and
                type(asset.get("id")) is int, "Uploaded asset identity or size mismatch.")
        digest = asset.get("digest")
        require(digest is None or digest == "sha256:" + sha256(file), "GitHub asset digest mismatch.")
        return asset


def metadata(root: Path) -> dict[str, str]:
    path = root / METADATA_PATH
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 8192,
            "Release metadata is missing or invalid.")
    value = parse_json(path.read_bytes())
    fields = {"tag", "source_sha", "apk_sha256", "signer_sha256", "apk_file", "notes_file", "title"}
    require(isinstance(value, dict) and set(value) == fields and all(isinstance(v, str) for v in value.values()),
            "Release metadata schema refused.")
    require(value["tag"] == TAG and value["title"] == TITLE and value["apk_file"] == APK_PATH and
            value["notes_file"] == NOTES_PATH, "Release version, title or payload path refused.")
    require(HEX40.fullmatch(value["source_sha"]) and HEX64.fullmatch(value["apk_sha256"]) and
            HEX64.fullmatch(value["signer_sha256"]), "Malformed release digest.")
    require(value["signer_sha256"] == EXPECTED_SIGNER_SHA256,
            "The supplied signing identity differs from this edition's durable signer.")
    for relative in (METADATA_PATH, APK_PATH, NOTES_PATH):
        file = root / relative
        require(file.is_file() and not file.is_symlink() and file.resolve().is_relative_to(root),
                "A release payload must be a regular repository file.")
        require(not (root / PurePosixPath(relative).parent).is_symlink(), "Payload directory symlink refused.")
    apk = root / APK_PATH
    require(0 < apk.stat().st_size <= MAX_BINARY_BYTES and sha256(apk) == value["apk_sha256"],
            "Signed APK digest or size mismatch.")
    notes = root / NOTES_PATH
    require(0 < notes.stat().st_size <= 64 * 1024, "Release notes size refused.")
    try:
        notes.read_text(encoding="utf-8")
    except UnicodeError as error:
        raise Refusal("Release notes must be UTF-8.") from error
    return value


def verify_upload_commit(root: Path, value: dict[str, str], upload_sha: str) -> None:
    require(HEX40.fullmatch(upload_sha), "Invalid workflow commit.")
    actual = run(["git", "rev-parse", "HEAD"]).strip()
    require(actual == upload_sha, "Checkout does not match the triggering upload commit.")
    parents = run(["git", "show", "-s", "--format=%P", upload_sha]).strip().split()
    require(parents == [value["source_sha"]], "Upload commit must have the validated source as its sole parent.")
    changed = set(run(["git", "diff", "--name-only", value["source_sha"], upload_sha, "--"]).splitlines())
    require(changed == {METADATA_PATH, APK_PATH, NOTES_PATH},
            "Upload branch must add only release metadata, signed APK and notes.")
    status = run(["git", "status", "--porcelain", "--untracked-files=all"]).strip()
    require(not status, "Checkout contains uncommitted changes.")
    require(run(["git", "ls-tree", value["source_sha"], "release-upload"]).strip() == "",
            "The clean source commit must not contain release upload payloads.")


def verify_signed_apk(apk: Path, value: dict[str, str]) -> None:
    sdk = Path(os.environ.get("ANDROID_HOME", ""))
    tools = sdk / "build-tools/36.0.0"
    apkanalyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    require(tools.is_dir() and apkanalyzer.is_file(), "Android SDK 36 verification tools are missing.")
    manifest = ET.fromstring(run([str(apkanalyzer), "manifest", "print", str(apk)]))
    ns = "{http://schemas.android.com/apk/res/android}"
    require(manifest.get("package") == APPLICATION_ID and manifest.get(ns + "versionName") == VERSION_NAME and
            manifest.get(ns + "versionCode") == VERSION_CODE, "APK application identity or version mismatch.")
    application = manifest.find("application")
    require(application is not None and application.get(ns + "debuggable", "false") == "false" and
            application.get(ns + "allowBackup") == "false" and application.get(ns + "usesCleartextTraffic") == "false",
            "APK debug, backup or cleartext-network protection mismatch.")
    permissions = {item.get(ns + "name") for item in manifest if item.tag.startswith("uses-permission")}
    require("android.permission.INTERNET" in permissions and permissions <= {
        "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.WAKE_LOCK",
        APPLICATION_ID + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"},
        "APK permissions exceed the translation edition allow-list.")
    require(not any("Fixture" in item.get(ns + "name", "") for item in application),
            "A test fixture leaked into the production APK.")
    output = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", output, re.MULTILINE)
    require(len(digests) == 1 and digests[0].lower() == value["signer_sha256"], "APK signature identity mismatch.")
    require(not re.search(r"^Signer #\d+ certificate DN:.*CN=Android Debug", output, re.MULTILINE),
            "An Android debug signing key cannot publish this release.")
    run([str(tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(apk)])


def assert_main(github: GitHub, source_sha: str) -> None:
    reference = github.api(PREFIX + "/git/ref/heads/main")
    require(reference.get("object", {}).get("sha") == source_sha,
            "main no longer equals the intended source; publication refused.")


def successful_main_run(github: GitHub, source_sha: str) -> dict[str, Any]:
    deadline = time.monotonic() + CI_WAIT_SECONDS
    while True:
        assert_main(github, source_sha)
        query = urllib.parse.urlencode({"event": "push", "head_sha": source_sha, "per_page": 100})
        result = github.api(PREFIX + "/actions/workflows/android.yml/runs?" + query)
        candidates = [run for run in result.get("workflow_runs", []) if
                      run.get("head_sha") == source_sha and run.get("head_branch") == "main" and
                      run.get("event") == "push" and
                      run.get("head_repository", {}).get("full_name") == REPOSITORY]
        if candidates:
            latest = max(candidates, key=lambda run: (int(run["id"]), int(run.get("run_attempt", 1))))
            if latest.get("status") == "completed":
                require(latest.get("conclusion") == "success", "Latest Android push validation of main did not succeed.")
                return latest
        require(time.monotonic() < deadline, "Android push validation did not finish within 25 minutes.")
        print("Waiting for the exact main Android push validation…", flush=True)
        time.sleep(15)


def apk_payload(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    total = 0
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) <= 100_000, "APK archive contains too many entries.")
        all_names: set[str] = set()
        for entry in entries:
            name = PurePosixPath(entry.filename)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in entry.filename and
                    entry.filename not in all_names and not (entry.flag_bits & 1),
                    "Ambiguous or encrypted APK archive entry refused.")
            all_names.add(entry.filename)
            if entry.is_dir() or SIGNATURE_ENTRY.fullmatch(entry.filename):
                continue
            total += entry.file_size
            require(0 <= entry.file_size <= MAX_BINARY_BYTES and total <= MAX_BINARY_BYTES,
                    "Uncompressed APK size exceeds the verification limit.")
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                while block := stream.read(1024 * 1024):
                    digest.update(block)
            result[entry.filename] = digest.hexdigest()
    require("AndroidManifest.xml" in result and "classes.dex" in result, "Invalid APK payload.")
    return result


def compare_ci_artifact(github: GitHub, run: dict[str, Any], source_sha: str, apk: Path, work: Path) -> None:
    result = github.api(PREFIX + f"/actions/runs/{int(run['id'])}/artifacts?per_page=100")
    artifacts = [item for item in result.get("artifacts", []) if item.get("name") == VALIDATION_ARTIFACT and not item.get("expired")]
    require(len(artifacts) == 1, "The exact validated release artifact is missing or ambiguous.")
    artifact = artifacts[0]
    source = artifact.get("workflow_run", {}).get("head_sha")
    require(source is None or source == source_sha, "Validation artifact source mismatch.")
    bundle = work / "ci-validation.zip"
    github.download(PREFIX + f"/actions/artifacts/{int(artifact['id'])}/zip", bundle)
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        require(len(entries) <= 100, "Unexpected release artifact contents.")
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), "Duplicate release artifact entry refused.")
        apps = [entry for entry in entries if PurePosixPath(entry.filename).name == "app-release.apk"]
        commits = [entry for entry in entries if PurePosixPath(entry.filename).name == "source-commit.txt"]
        require(len(apps) == 1 and len(commits) == 1 and 0 < apps[0].file_size <= MAX_BINARY_BYTES and
                0 < commits[0].file_size <= 128, "Validated app/source artifact layout mismatch.")
        require(archive.read(commits[0]).decode("ascii").strip() == source_sha,
                "The artifact was not built from the exact source commit.")
        verified = work / "ci-app-release.apk"
        with archive.open(apps[0]) as source_stream, verified.open("wb") as output:
            size = 0
            while block := source_stream.read(1024 * 1024):
                size += len(block)
                require(size <= MAX_BINARY_BYTES, "Validated APK exceeds the verification limit.")
                output.write(block)
    require(apk_payload(verified) == apk_payload(apk),
            "Signed APK differs from the exact CI-tested APK outside its signing metadata.")
    print("Verified all non-signature APK entries against the exact successful main artifact.", flush=True)


def no_existing_release_or_tag(github: GitHub) -> None:
    require(github.api(PREFIX + "/git/ref/tags/" + TAG, absent=True) is None,
            "The release tag already exists; it will never be replaced.")
    require(github.api(PREFIX + "/releases/tags/" + TAG, absent=True) is None,
            "The release already exists; it will never be replaced.")
    # Drafts with a not-yet-created tag are not guaranteed to be found by the
    # tag endpoint. Read every visible draft page before attempting a new one.
    for page in range(1, 101):
        releases = github.api(PREFIX + f"/releases?per_page=100&page={page}")
        require(isinstance(releases, list), "Invalid release listing.")
        require(not any(item.get("tag_name") == TAG for item in releases),
                "A draft or published release with this tag already exists.")
        if len(releases) < 100:
            return
    raise Refusal("Release listing pagination limit reached.")


def verify_tag(github: GitHub, source_sha: str, reference: dict[str, Any] | None = None) -> None:
    target = (reference if reference is not None else
              github.api(PREFIX + "/git/ref/tags/" + TAG)).get("object", {})
    for _ in range(5):
        if target.get("type") == "commit":
            require(target.get("sha") == source_sha, "Published tag does not identify the validated source.")
            return
        require(target.get("type") == "tag" and HEX40.fullmatch(str(target.get("sha", ""))),
                "Unexpected published tag object.")
        target = github.api(PREFIX + "/git/tags/" + target["sha"]).get("object", {})
    raise Refusal("Published tag indirection limit exceeded.")


def publish(root: Path, github: GitHub, value: dict[str, str], work: Path, ci_run: dict[str, Any]) -> str:
    assert_main(github, value["source_sha"])
    no_existing_release_or_tag(github)
    apk = root / APK_PATH
    sums = work / "SHA256SUMS"
    sums.write_text(f"{value['apk_sha256']}  {apk.name}\n", encoding="ascii")
    notes = (root / NOTES_PATH).read_text(encoding="utf-8")
    release = github.api(PREFIX + "/releases", "POST", {
        "tag_name": TAG, "target_commitish": value["source_sha"], "name": TITLE,
        "body": notes, "draft": True, "prerelease": False,
    }, expected=(201,))
    require(release.get("draft") is True and release.get("tag_name") == TAG and
            type(release.get("id")) is int, "Draft release creation was not confirmed.")
    release_id = release["id"]
    print(f"Draft release created: {release_id}; no public publication before asset checks.", flush=True)
    assets = [github.upload(release_id, apk, "application/vnd.android.package-archive"),
              github.upload(release_id, sums, "text/plain; charset=us-ascii")]
    for asset, original in zip(assets, (apk, sums)):
        downloaded = work / ("downloaded-" + original.name)
        github.download(PREFIX + f"/releases/assets/{asset['id']}", downloaded)
        require(sha256(downloaded) == sha256(original), "Downloaded release asset hash differs from the verified local file.")
    # Recheck source and the very same CI run just before making the draft public.
    assert_main(github, value["source_sha"])
    latest = successful_main_run(github, value["source_sha"])
    require(latest["id"] == ci_run["id"] and latest.get("run_attempt", 1) == ci_run.get("run_attempt", 1),
            "Android validation changed during publication; draft retained for review.")
    visible = github.api(PREFIX + f"/releases/{release_id}")
    require(visible.get("id") == release_id and visible.get("draft") is True and
            visible.get("prerelease") is False and visible.get("tag_name") == TAG and
            visible.get("target_commitish") == value["source_sha"] and
            visible.get("name") == TITLE and visible.get("body") == notes,
            "Draft contents changed before publication.")
    visible_assets = visible.get("assets")
    require(isinstance(visible_assets, list) and len(visible_assets) == len(assets) and
            all(isinstance(item, dict) and type(item.get("id")) is int for item in visible_assets) and
            {item["id"] for item in visible_assets} == {item["id"] for item in assets},
            "Draft contents changed before publication.")
    by_id = {item["id"]: item for item in visible_assets}
    for uploaded, original in zip(assets, (apk, sums)):
        item = by_id[uploaded["id"]]
        require(item.get("state") == "uploaded" and item.get("name") == original.name and
                item.get("size") == original.stat().st_size and
                item.get("digest") in (None, "sha256:" + sha256(original)),
                "Draft asset identity, size or digest changed before publication.")
    # A draft need not have created its tag yet. If another writer created one
    # during uploads, verify its exact target before making anything public.
    appeared_tag = github.api(PREFIX + "/git/ref/tags/" + TAG, absent=True)
    if appeared_tag is not None:
        verify_tag(github, value["source_sha"], appeared_tag)
    assert_main(github, value["source_sha"])
    # GitHub offers no atomic compare-and-publish transaction for these reads.
    # Concurrent changes after the last checks remain possible; verify again
    # after publication and never claim these checks eliminate that final race.
    published = github.api(PREFIX + f"/releases/{release_id}", "PATCH", {"draft": False, "make_latest": "true"})
    require(published.get("draft") is False and published.get("prerelease") is False and
            published.get("tag_name") == TAG, "Public release state was not confirmed.")
    verify_tag(github, value["source_sha"])
    url = published.get("html_url", "")
    require(url == f"https://github.com/{REPOSITORY}/releases/tag/{TAG}", "Unexpected public release URL.")
    return url


def archive_merged_branches(github: GitHub, source_sha: str) -> list[dict[str, str]]:
    """After release only: retain each old head in a tag before deleting its ref.

    Unknown branches, main and backup/* are never eligible. Each deletion needs
    a proven ancestor relationship, an exact archive ref, a second unchanged
    branch read and an unchanged main. The API has no conditional ref-delete;
    an unexpected changed head is preserved instead of being force-updated.
    """
    results: list[dict[str, str]] = []
    for branch in ARCHIVABLE_BRANCHES:
        outcome = {"branch": branch, "result": "kept", "reason": ""}
        try:
            require(branch != "main" and not branch.startswith("backup/") and branch != BRANCH,
                    "Branch cleanup allow-list invariant failed.")
            assert_main(github, source_sha)
            reference = github.api(PREFIX + "/git/ref/heads/" + branch, absent=True)
            if reference is None:
                outcome.update(result="absent", reason="Branch already absent; nothing deleted.")
                results.append(outcome)
                continue
            head = reference.get("object", {})
            require(reference.get("ref") == "refs/heads/" + branch and head.get("type") == "commit" and
                    HEX40.fullmatch(str(head.get("sha", ""))), "Unexpected branch ref; preserved.")
            head_sha = head["sha"]
            comparison = github.api(PREFIX + f"/compare/{head_sha}...{source_sha}")
            require(comparison.get("status") in {"ahead", "identical"} and
                    comparison.get("merge_base_commit", {}).get("sha") == head_sha,
                    "Branch is not an ancestor of validated main; preserved.")
            archive = "refs/tags/archive/20261024/" + branch
            existing = github.api(PREFIX + "/git/ref/" + archive.removeprefix("refs/"), absent=True)
            if existing is None:
                existing = github.api(PREFIX + "/git/refs", "POST", {"ref": archive, "sha": head_sha}, expected=(201,))
            require(existing.get("ref") == archive and existing.get("object", {}).get("type") == "commit" and
                    existing.get("object", {}).get("sha") == head_sha,
                    "Archive tag does not preserve this exact branch head; branch preserved.")
            # Read the created archive as well, rather than relying only on POST.
            verified = github.api(PREFIX + "/git/ref/" + archive.removeprefix("refs/"))
            require(verified.get("ref") == archive and verified.get("object", {}).get("sha") == head_sha,
                    "Archive ref verification failed; branch preserved.")
            current = github.api(PREFIX + "/git/ref/heads/" + branch)
            require(current.get("object", {}).get("sha") == head_sha, "Branch advanced during cleanup; preserved.")
            assert_main(github, source_sha)
            github.api(PREFIX + "/git/refs/heads/" + branch, "DELETE", expected=(204,))
            require(github.api(PREFIX + "/git/ref/heads/" + branch, absent=True) is None,
                    "Branch deletion could not be confirmed.")
            outcome.update(result="archived-and-deleted", reason=archive + " → " + head_sha)
        except Refusal as error:
            outcome["reason"] = str(error)
            print(f"::warning::Branch {branch}: cleanup stopped; public release remains available.", flush=True)
        except Exception:
            outcome["reason"] = "Unexpected cleanup failure; inspect this branch before retrying."
            print(f"::warning::Branch {branch}: cleanup not confirmed; public release remains available.", flush=True)
        results.append(outcome)
    for outcome in results:
        print(f"Branch cleanup: {outcome['branch']}: {outcome['result']}: {outcome['reason']}", flush=True)
    return results


def main() -> None:
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and os.environ.get("GITHUB_EVENT_NAME") == "push" and
            os.environ.get("GITHUB_REF") == "refs/heads/" + BRANCH,
            "Publication is allowed only for this repository's exact temporary upload branch.")
    root = Path(__file__).resolve().parents[1]
    require(Path.cwd().resolve() == root, "Run publication from the repository root.")
    value = metadata(root)
    verify_upload_commit(root, value, os.environ.get("GITHUB_SHA", ""))
    verify_signed_apk(root / APK_PATH, value)
    github = GitHub(os.environ.get("GITHUB_TOKEN", ""))
    no_existing_release_or_tag(github)
    ci_run = successful_main_run(github, value["source_sha"])
    with tempfile.TemporaryDirectory(prefix="verified-translation-release-") as directory:
        work = Path(directory)
        compare_ci_artifact(github, ci_run, value["source_sha"], root / APK_PATH, work)
        url = publish(root, github, value, work, ci_run)
    print(f"Published verified release: {url}", flush=True)
    archived = archive_merged_branches(github, value["source_sha"])
    cleanup = "Temporary upload branch deleted after verified public publication."
    try:
        github.api(PREFIX + "/git/refs/heads/" + BRANCH, "DELETE", expected=(204,))
    except Refusal:
        cleanup = "Public release verified; temporary branch cleanup failed and remains to be done."
        print("::warning::" + cleanup, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write(f"[Published release]({url})\n\nSource: `{value['source_sha']}`\n\n"
                         f"APK SHA-256: `{value['apk_sha256']}`\n\nSigner SHA-256: `{value['signer_sha256']}`\n\n"
                         f"Android validation: [run {ci_run['id']}](https://github.com/{REPOSITORY}/actions/runs/{ci_run['id']})\n\n"
                         f"{cleanup}\n")
            stream.write("\n| Branch | Cleanup result | Archive or reason |\n|---|---|---|\n")
            for item in archived:
                stream.write(f"| `{item['branch']}` | {item['result']} | {item['reason']} |\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never log urllib request objects, headers, signed redirect URLs, API
        # response bodies, environment contents or exception chains.
        text = str(error) if isinstance(error, Refusal) else "Unexpected publication verification failure."
        print("Publication stopped; inspect any draft/public release before retrying: " + text, file=sys.stderr)
        raise SystemExit(1) from None
