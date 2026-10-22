#!/usr/bin/env python3
"""Validate actual JUnit XML; source inventory is an additional completeness gate."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", required=True, type=Path)
    parser.add_argument("--expected-count", required=True, type=int)
    parser.add_argument("--sources", type=Path)
    parser.add_argument("--not-before-ns", type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    problems = []
    expected = []
    if args.sources is not None:
        for path in sorted(args.sources.rglob("*")):
            if path.suffix not in (".kt", ".java"):
                continue
            text = path.read_text(encoding="utf-8")
            annotations = re.findall(r"@Test\b", text)
            if not annotations:
                continue
            names = re.findall(r"@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)
            package = re.search(r"^package\s+([\w.]+)", text, re.M)
            if (len(names) != len(annotations) or package is None
                    or re.search(r"\bclass\s+" + re.escape(path.stem) + r"\b", text) is None):
                problems.append(f"Unsupported test declaration; extend inventory parser explicitly: {path}")
                continue
            expected.extend(f"{package.group(1)}.{path.stem}.{name}" for name in names)
        if len(expected) != args.expected_count or len(set(expected)) != len(expected):
            problems.append(f"Source inventory expected {args.expected_count} unique tests, found {len(expected)}")

    reports = sorted(args.reports.rglob("TEST-*.xml"))
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    identities = []
    for path in reports:
        if args.not_before_ns is not None and path.stat().st_mtime_ns < args.not_before_ns:
            problems.append(f"Stale XML report: {path}")
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            problems.append(f"Invalid XML report {path}: {error}")
            continue
        for suite in root.iter("testsuite"):
            for key in ("failures", "errors", "skipped", "disabled"):
                if int(suite.get(key, "0")):
                    problems.append(f"Suite reports {key}={suite.get(key)}: {path}")
        for case in root.iter("testcase"):
            counts["tests"] += 1
            identity = f"{case.get('classname', '')}.{case.get('name', '')}"
            identities.append(identity)
            for outcome in case:
                key = {"failure": "failures", "error": "errors", "skipped": "skipped"}.get(outcome.tag)
                if key:
                    counts[key] += 1
                    print(f"{outcome.tag.upper()} {identity}")
                    print(outcome.get("message", ""))
                    print(outcome.text or "")
    duplicates = sorted(name for name, count in Counter(identities).items() if count > 1)
    missing = sorted(set(expected) - set(identities)) if args.sources else []
    unexpected = sorted(set(identities) - set(expected)) if args.sources else []
    if counts["tests"] != args.expected_count:
        problems.append(f"Expected {args.expected_count} executed tests, found {counts['tests']}")
    if duplicates or missing or unexpected:
        problems.append("Reported test identities differ from the complete inventory")
    if any(counts[key] for key in ("failures", "errors", "skipped")):
        problems.append("At least one test failed, errored or was skipped")
    summary = {
        "reports": len(reports), **counts, "expectedTests": args.expected_count,
        "sourceTests": len(expected) if args.sources else None,
        "duplicates": duplicates, "missing": missing, "unexpected": unexpected,
        "testIds": sorted(identities), "problems": problems,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key != "testIds"}, indent=2))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
