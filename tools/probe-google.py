#!/usr/bin/env python3
"""One synthetic Google web request, without retries, redirects or fallbacks.

This is external availability evidence, not a subtitle-film or linguistic test.
Unavailable services are reported as unavailable and do not invalidate offline
regression tests. An HTTP 200 response violating the required protocol fails.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


SOURCE = "The blue circle is above the square.\nEXIT"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_response(raw):
    """Match the adapter's typed segments, Unicode and complete source echo."""
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError("response_too_large")
    root = json.loads(raw.decode("utf-8", errors="strict"))
    if not isinstance(root, list) or not root or not isinstance(root[0], list):
        raise ValueError("invalid_root")
    segments = root[0]
    if not 1 <= len(segments) <= 10_000:
        raise ValueError("invalid_segment_count")
    translated, echoed = [], []
    for segment in segments:
        if (not isinstance(segment, list) or len(segment) < 2 or
                not isinstance(segment[0], str) or not isinstance(segment[1], str)):
            raise ValueError("invalid_segment")
        text, original = segment[:2]
        for value in (text, original):
            value.encode("utf-8", errors="strict")
            if "\0" in value:
                raise ValueError("invalid_unicode")
        if original.strip() and not text.strip():
            raise ValueError("untranslated_segment")
        translated.append(text)
        echoed.append(original)
    text = "".join(translated)
    if " ".join("".join(echoed).split()) != " ".join(SOURCE.split()):
        raise ValueError("incomplete_source_echo")
    if not text.strip() or len(text.encode("utf-16-le")) // 2 > 24_000:
        raise ValueError("invalid_translation")
    return len(segments), len(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {
        "schema": 1,
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "purpose": "one_synthetic_request_no_retry_no_fallback",
        "requests_attempted": 1,
        "endpoint_host": "translate.googleapis.com",
        "source_language": "en",
        "target_language": "fr",
        "source_characters": len(SOURCE),
        "source_sha256": hashlib.sha256(SOURCE.encode()).hexdigest(),
        "available": False,
        "protocol_validated": False,
        "linguistic_quality_validated": False,
        "full_track_validated": False,
    }
    query = urllib.parse.urlencode({"client": "gtx", "sl": "en", "tl": "fr", "dt": "t", "q": SOURCE})
    request = urllib.request.Request(
        "https://translate.googleapis.com/translate_a/single?" + query,
        headers={"Accept": "application/json", "User-Agent": "SousTitresVoix/0.5.2"},
        method="GET",
    )
    started = time.monotonic()
    exit_code = 0
    try:
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=15) as response:
            report["http_status"] = response.status
            raw = response.read(MAX_RESPONSE_BYTES + 1)
        if report["http_status"] != 200:
            report["outcome"] = "unexpected_http_status"
        else:
            try:
                count, characters = validate_response(raw)
            except (ValueError, TypeError, UnicodeError, OverflowError):
                report["outcome"] = "invalid_http_200_protocol"
                exit_code = 1
            else:
                report.update(available=True, protocol_validated=True, outcome="protocol_validated",
                              response_segments=count, translated_characters=characters)
    except urllib.error.HTTPError as error:
        report["http_status"] = error.code
        report["outcome"] = (
            "rate_limited" if error.code in (429, 529) else
            "blocked_or_access_denied" if error.code in (401, 403) else
            "redirect_refused" if 300 <= error.code < 400 else "service_unavailable"
        )
        error.close()
    except (urllib.error.URLError, OSError, TimeoutError) as error:
        report["outcome"] = "network_unavailable"
        report["error_type"] = type(error).__name__
    report["elapsed_seconds"] = round(time.monotonic() - started, 3)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if report["available"]:
        print("Google probe: one synthetic response passed protocol validation; film translation and quality are NOT validated.")
    else:
        print(f"Google probe: unavailable ({report['outcome']}); real translation NOT validated. No retry or fallback performed.")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
