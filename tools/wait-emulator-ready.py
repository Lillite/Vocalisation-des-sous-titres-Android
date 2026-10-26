#!/usr/bin/env python3
"""Read-only, bounded UI readiness for a disposable Android CI emulator.

Boot completion alone does not mean a focused, drawn window exists. Never
inject a key to discover readiness: that key can itself start a no-focus ANR.
The runner/monotonic clock/sleeper are injectable for behavioral tests.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import time
from typing import Callable


class ReadinessError(RuntimeError):
    pass


def focused_window(windows: str, expected_package: str | None) -> tuple[str | None, bool]:
    focus = re.search(r'^\s*mCurrentFocus=Window\{([^\s}]+)\b[^\n]*\}', windows, re.M)
    if not focus:
        return None, False
    token = focus.group(1)
    lines = windows.splitlines()
    block: list[str] = []
    window_header = ""
    for index, line in enumerate(lines):
        if not re.match(r'^\s*Window #\d+ Window\{' + re.escape(token) + r'\b[^\n]*\}:\s*$', line):
            continue
        window_header = line
        indentation = len(line) - len(line.lstrip())
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indentation:
                break
            block.append(following)
        break
    detail = '\n'.join(block)
    # Android 36 omits mDrawState when the normal shown state is reached.
    # Match the actual focused window, never another ready SystemUI surface.
    drawn = all(re.search(pattern, detail) for pattern in (
        r'\bmHasSurface=true\b', r'\bisReadyForDisplay\(\)=true\b',
        r'\bSurface:\s*shown=true\b', r'\bisOnScreen=true\b', r'\bisVisible=true\b',
    )) and not re.search(r'\bmDrawState=(?:DRAW_PENDING|COMMIT_DRAW_PENDING|READY_TO_SHOW)\b', detail)
    if expected_package is not None:
        package = re.search(r'\bpackage=([^\s]+)', detail)
        focused_app = re.search(r'^\s*mFocusedApp=ActivityRecord\{[^\n}]*\s' +
                                re.escape(expected_package) + r'/[^\s}]+', windows, re.M)
        focus_matches = re.search(r'^\s*mCurrentFocus=Window\{' + re.escape(token) + r'\s+u\d+\s+' +
                                  re.escape(expected_package) + r'/', windows, re.M)
        header_matches = re.search(r'\bu\d+\s+' + re.escape(expected_package) + r'/', window_header)
        drawn = (drawn and focus_matches is not None and header_matches is not None and focused_app is not None and
                 (package is None or package.group(1) == expected_package))
    return token, bool(drawn)


def _text(value: str | bytes | None) -> str:
    if isinstance(value, bytes):
        return value.decode('utf-8', errors='replace')
    return value or ''


def wait_for_ready(run: Callable, clock: Callable[[], float], sleep: Callable[[float], None], *,
                   device: str = 'emulator-5554', evidence_dir: Path,
                   timeout: float = 120.0, poll_interval: float = 2.0,
                   stable_observations: int = 3,
                   expected_package: str | None = 'com.android.launcher3') -> dict:
    if not re.fullmatch(r'emulator-\d+', device):
        raise ReadinessError('Refusing readiness probes on a non-emulator target.')
    if not math.isfinite(timeout) or not math.isfinite(poll_interval) or timeout <= 0 or poll_interval <= 0 or stable_observations < 2:
        raise ReadinessError('Readiness requires positive bounded timings and at least two stable observations.')
    if expected_package is not None and not re.fullmatch(r'[A-Za-z0-9_.]+', expected_package):
        raise ReadinessError('Invalid expected window package.')
    evidence_dir = Path(evidence_dir)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    started = clock()
    deadline = started + timeout
    stable = 0
    previous_token = None
    attempt = 0
    last = {}
    commands = (
        ('boot', ['getprop', 'sys.boot_completed']),
        ('animation', ['getprop', 'init.svc.bootanim']),
        ('framework', ['pm', 'path', 'android']),
        ('windows', ['dumpsys', 'window']),
    )
    while clock() < deadline:
        attempt += 1
        results = {}
        for name, shell in commands:
            remaining = deadline - clock()
            if remaining <= 0:
                raise ReadinessError('Emulator readiness deadline reached during a probe.')
            argv = ['adb', '-s', device, 'shell', *shell]
            try:
                result = run(argv, min(5.0, remaining))
                results[name] = {'exit': result.returncode, 'stdout': _text(result.stdout), 'stderr': _text(result.stderr)}
            except subprocess.TimeoutExpired as error:
                results[name] = {'exit': 'timeout', 'stdout': _text(error.stdout), 'stderr': _text(error.stderr)}
            except OSError as error:
                results[name] = {'exit': 'os-error', 'stdout': '', 'stderr': str(error)}
        windows = results['windows']['stdout']
        (evidence_dir / f'windows-{attempt:03}.txt').write_text(windows, encoding='utf-8')
        anr = re.search(r'Application Not Responding:|\bANR in\b|Application at fault:\s*ActivityRecord', windows, re.I) is not None
        token, drawn = focused_window(windows, expected_package)
        boot = results['boot']['stdout'].strip()
        animation = results['animation']['stdout'].strip()
        framework = results['framework']['stdout'].strip()
        ready = (all(result['exit'] == 0 for result in results.values()) and boot == '1' and
                 animation in ('', 'stopped') and framework.startswith('package:') and drawn)
        stable = stable + 1 if ready and token == previous_token else (1 if ready else 0)
        previous_token = token if ready else None
        last = {'attempt': attempt, 'capturedUtc': datetime.now(timezone.utc).isoformat(),
                'elapsedSeconds': clock() - started, 'device': device, 'expectedPackage': expected_package,
                'bootCompleted': boot, 'bootAnimation': animation, 'frameworkReady': framework.startswith('package:'),
                'focusToken': token, 'focusedWindowDrawn': drawn, 'ready': ready,
                'stableObservations': stable, 'anr': anr,
                'commands': {name: {'argv': ['adb', '-s', device, 'shell', *shell], **results[name]}
                             for name, shell in commands}}
        with (evidence_dir / 'readiness.jsonl').open('a', encoding='utf-8') as output:
            output.write(json.dumps(last, ensure_ascii=False) + '\n')
        if anr:
            raise ReadinessError('Emulator has an ANR before instrumentation; captured evidence is retained.')
        if stable >= stable_observations:
            (evidence_dir / 'ready.json').write_text(json.dumps(last, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            return last
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(poll_interval, remaining))
    raise ReadinessError(f'Emulator UI did not become ready within {timeout:g}s; last sample: {last.get("attempt", 0)}.')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default='emulator-5554')
    parser.add_argument('--evidence-dir', type=Path, required=True)
    parser.add_argument('--timeout', type=float, default=120)
    parser.add_argument('--poll-interval', type=float, default=2)
    parser.add_argument('--stable-observations', type=int, default=3)
    target = parser.add_mutually_exclusive_group()
    target.add_argument('--expected-package', default='com.android.launcher3')
    target.add_argument('--any-focused-window', action='store_true')
    args = parser.parse_args()
    def run(argv, timeout):
        return subprocess.run(argv, timeout=timeout, text=True, capture_output=True, check=False)
    try:
        result = wait_for_ready(run, time.monotonic, time.sleep, device=args.device, evidence_dir=args.evidence_dir,
                                timeout=args.timeout, poll_interval=args.poll_interval,
                                stable_observations=args.stable_observations,
                                expected_package=None if args.any_focused_window else args.expected_package)
        print(f'Ready: focused drawn window {result["focusToken"]}, {result["stableObservations"]} stable observations.')
        return 0
    except ReadinessError as error:
        print(str(error), file=__import__('sys').stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
