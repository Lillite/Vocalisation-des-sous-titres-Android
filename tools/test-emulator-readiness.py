#!/usr/bin/env python3
"""Behavioral checks of the read-only emulator boot gate, without Android stubs.

The helper itself executes against controlled ADB transport results and a virtual
clock. Window samples use Android 36's actual dumpsys shape; they are not an app,
launcher implementation, emulator, or replacement for instrumented device tests.
"""
from copy import deepcopy
import importlib.util
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
specification = importlib.util.spec_from_file_location(
    "emulator_readiness", ROOT / "tools/wait-emulator-ready.py")
readiness = importlib.util.module_from_spec(specification)
specification.loader.exec_module(readiness)

DEVICE = "emulator-5554"
PACKAGE = "com.android.launcher3"
ACTIVITY = PACKAGE + "/com.android.launcher3.uioverrides.QuickstepLauncher"
BOOT = ("getprop", "sys.boot_completed")
ANIMATION = ("getprop", "init.svc.bootanim")
FRAMEWORK = ("pm", "path", "android")
WINDOWS = ("dumpsys", "window")
READ_ONLY_QUERIES = {BOOT, ANIMATION, FRAMEWORK, WINDOWS}


def window_dump(token="3b7f841", *, focused_package=PACKAGE,
                window_token=None, drawn=True, pending=False, focused_app=PACKAGE):
    """A minimal real-format sample; completed frames omit mDrawState normally."""
    window_token = token if window_token is None else window_token
    visible = "true" if drawn else "false"
    draw_state = "      mDrawState=DRAW_PENDING\n" if pending else ""
    return (
        "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
        f"  mCurrentFocus=Window{{{token} u0 {focused_package}/com.android.launcher3.uioverrides.QuickstepLauncher}}\n"
        f"  mFocusedApp=ActivityRecord{{34048570 u0 {focused_app}/.uioverrides.QuickstepLauncher t7}}\n"
        "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
        f"  Window #0 Window{{{window_token} u0 {ACTIVITY}}}:\n"
        f"    mHasSurface={visible} isReadyForDisplay()={visible} mWindowRemovalAllowed=false\n"
        f"    WindowStateAnimator{{8bf1177 {ACTIVITY}}}:\n"
        f"      Surface: shown={visible}\n"
        + draw_state
        + f"    isOnScreen={visible}\n"
        f"    isVisible={visible}\n"
        "    keepClearAreas: restricted=[], unrestricted=[]\n"
        "  Window #1 Window{9705509 u0 StatusBar}:\n"
        "    mHasSurface=true isReadyForDisplay()=true\n"
        "    WindowStateAnimator{9b6e1e4 StatusBar}:\n"
        "      Surface: shown=true\n"
        "    isOnScreen=true\n"
        "    isVisible=true\n"
    )


def frame(**changes):
    value = {
        "boot": "1\n", "animation": "stopped\n",
        "framework": "package:/system/framework/framework-res.apk\n",
        "windows": window_dump(),
    }
    value.update(changes)
    return value


def system_window_dump(*, pending=False):
    draw_state = "    mDrawState=DRAW_PENDING\n" if pending else ""
    return (
        "  mCurrentFocus=Window{65a9b2 u0 NotificationShade}\n"
        "  mFocusedApp=null\n"
        "  Window #0 Window{65a9b2 u0 NotificationShade}:\n"
        "    mHasSurface=true isReadyForDisplay()=true\n"
        "    WindowStateAnimator{7fa93 NotificationShade}:\n"
        "      Surface: shown=true\n"
        + draw_state
        + "    isOnScreen=true\n"
        "    isVisible=true\n"
    )


class Clock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        if not math.isfinite(seconds) or seconds <= 0:
            raise AssertionError("The gate must sleep for a positive bounded interval")
        self.sleeps.append(seconds)
        self.now += seconds


class AdbFixture:
    """Reject every command except the four authorized read-only boot probes."""
    def __init__(self, clock, frames, command_seconds=0.0):
        self.clock = clock
        self.frames = deepcopy(frames)
        self.command_seconds = command_seconds
        self.index = -1
        self.calls = []

    def __call__(self, argv, timeout):
        if not isinstance(argv, list) or argv[:4] != ["adb", "-s", DEVICE, "shell"]:
            raise AssertionError("Unexpected ADB target or command: " + repr(argv))
        query = tuple(argv[4:])
        if query not in READ_ONLY_QUERIES:
            raise AssertionError("Mutating or unexpected ADB probe: " + repr(query))
        if not math.isfinite(timeout) or timeout <= 0 or timeout > 5.0:
            raise AssertionError("ADB command timeout must be finite and at most five seconds")
        self.calls.append((tuple(argv), timeout, self.clock()))
        if query == BOOT:
            self.index += 1
        state = self.frames[min(max(self.index, 0), len(self.frames) - 1)]
        if state.get("exception") == "timeout":
            self.clock.now += timeout
            raise subprocess.TimeoutExpired(argv, timeout)
        if state.get("exception") == "oserror":
            raise OSError("Synthetic unavailable ADB executable")
        self.clock.now += self.command_seconds
        key = {BOOT: "boot", ANIMATION: "animation", FRAMEWORK: "framework", WINDOWS: "windows"}[query]
        return subprocess.CompletedProcess(argv, state.get("returncodes", {}).get(key, 0),
                                           state[key], "synthetic transport diagnostic")


class EmulatorReadinessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="emulator-readiness-test-")
        self.addCleanup(self.temporary.cleanup)
        self.evidence = Path(self.temporary.name)

    def invoke(self, frames, *, timeout=12.0, command_seconds=0.0, **options):
        clock = Clock()
        adb = AdbFixture(clock, frames, command_seconds)
        self.clock, self.adb = clock, adb
        result = readiness.wait_for_ready(adb, clock, clock.sleep, device=DEVICE,
            evidence_dir=self.evidence, timeout=timeout, poll_interval=1.0,
            stable_observations=3, **options)
        return result

    def assert_bounded_failure(self, frames, *, timeout=4.5, command_seconds=0.0):
        with self.assertRaises(readiness.ReadinessError):
            self.invoke(frames, timeout=timeout, command_seconds=command_seconds)
        self.assertLessEqual(self.clock(), timeout + 1e-9)
        self.assertTrue(self.adb.calls)
        for _, command_timeout, started in self.adb.calls:
            self.assertLessEqual(command_timeout, min(5.0, timeout - started) + 1e-9)

    def test_each_boot_framework_and_animation_gate_is_required_independently(self):
        for state in (
            frame(boot="0\n"),
            frame(animation="running\n"),
            frame(framework="Error: Could not access the Package Manager\n"),
            frame(animation="running\n", framework="", windows="mCurrentFocus=null\n"),
        ):
            with self.subTest(state=state):
                self.assert_bounded_failure([state])

    def test_framework_animation_focus_and_draw_must_all_become_ready(self):
        result = self.invoke([
            frame(boot="0\n"),
            frame(animation="running\n"),
            frame(framework="Error: Could not access the Package Manager\n"),
            frame(windows="mCurrentFocus=null\n"),
            frame(windows=window_dump(drawn=False)),
            frame(), frame(), frame(),
        ])
        self.assertIsInstance(result, dict)
        self.assertEqual(7, self.adb.index)

    def test_ready_window_must_stay_stable_for_three_observations(self):
        self.invoke([frame()])
        self.assertEqual(2, self.adb.index)
        self.assertEqual([1.0, 1.0], self.clock.sleeps)

    def test_focus_token_change_restarts_the_stability_period(self):
        self.invoke([
            frame(windows=window_dump("aaa")), frame(windows=window_dump("aaa")),
            frame(windows=window_dump("bbb")), frame(windows=window_dump("bbb")),
            frame(windows=window_dump("bbb")),
        ])
        self.assertEqual(4, self.adb.index)

    def test_a_not_drawn_observation_restarts_stability(self):
        self.invoke([frame(), frame(), frame(windows=window_dump(drawn=False)), frame(), frame(), frame()])
        self.assertEqual(5, self.adb.index)

    def test_another_drawn_window_cannot_stand_in_for_the_focused_window(self):
        # The StatusBar stays fully drawn while each Launcher condition is absent.
        for marker in ("mHasSurface=true", "isReadyForDisplay()=true", "Surface: shown=true", "isOnScreen=true", "isVisible=true"):
            with self.subTest(marker=marker):
                sample = window_dump().replace(marker, marker.replace("true", "false"), 1)
                self.assert_bounded_failure([frame(windows=sample)])

    def test_a_different_window_with_the_same_package_does_not_prove_focused_drawing(self):
        self.assert_bounded_failure([frame(windows=window_dump(token="focused", window_token="other"))])

    def test_focused_app_and_window_must_agree_with_expected_launcher(self):
        self.assert_bounded_failure([frame(windows=window_dump(focused_app="com.android.settings"))])

    def test_a_focused_non_launcher_window_is_not_ready(self):
        self.assert_bounded_failure([frame(windows=window_dump(focused_package="com.android.settings"))])

    def test_explicit_draw_pending_cannot_be_certified_ready(self):
        self.assert_bounded_failure([frame(windows=window_dump(pending=True))])

    def test_real_completed_window_without_draw_state_and_empty_bootanim_is_accepted(self):
        sample = window_dump()
        self.assertNotIn("mDrawState", sample)
        self.invoke([frame(animation="\n", windows=sample)])
        self.assertEqual(2, self.adb.index)

    def test_anr_fails_immediately_even_when_boot_is_incomplete(self):
        with self.assertRaises(readiness.ReadinessError):
            self.invoke([frame(boot="0\n", windows="mCurrentFocus=Window{38affa1 u0 Application Not Responding: com.android.launcher3}\n")])
        self.assertEqual(0, self.adb.index)
        self.assertEqual([], self.clock.sleeps)

    def test_anr_prevents_success_after_previous_good_observations(self):
        with self.assertRaises(readiness.ReadinessError):
            self.invoke([frame(), frame(), frame(windows=window_dump() + "\nApplication Not Responding: com.android.systemui\n")])
        self.assertEqual(2, self.adb.index)

    def test_failed_adb_queries_are_not_readiness_and_have_a_finite_budget(self):
        self.assert_bounded_failure([frame(returncodes={"framework": 1})], command_seconds=0.05)

    def test_adb_timeout_cannot_outlive_the_remaining_global_budget(self):
        self.assert_bounded_failure([frame(exception="timeout")], timeout=3.25)
        self.assertEqual(3.25, self.clock())

    def test_missing_adb_executable_is_a_bounded_explicit_failure(self):
        self.assert_bounded_failure([frame(exception="oserror")])

    def test_physical_and_network_device_targets_are_refused_before_adb(self):
        for device in ("R58M42XYZ", "192.168.1.20:5555", "", "emulator-5554; input keyevent 82"):
            with self.subTest(device=device):
                calls = []
                with self.assertRaises(readiness.ReadinessError):
                    readiness.wait_for_ready(lambda *args: calls.append(args), Clock(), lambda _: None,
                        device=device, evidence_dir=self.evidence)
                self.assertEqual([], calls)

    def test_initial_system_window_scope_accepts_a_stable_drawn_keyguard_without_an_app(self):
        result = self.invoke([frame(windows=system_window_dump())], expected_package=None)
        self.assertIsInstance(result, dict)
        self.assertEqual(2, self.adb.index)

    def test_initial_system_window_scope_still_rejects_draw_pending_and_anr(self):
        for sample, immediate in (
            (system_window_dump(pending=True), False),
            (system_window_dump() + "Application Not Responding: com.android.systemui\n", True),
        ):
            with self.subTest(immediate=immediate):
                with self.assertRaises(readiness.ReadinessError):
                    self.invoke([frame(windows=sample)], expected_package=None, timeout=4.5)
                self.assertLessEqual(self.clock(), 4.5)
                if immediate:
                    self.assertEqual(0, self.adb.index)
                    self.assertEqual([], self.clock.sleeps)

    def test_success_uses_only_bounded_read_only_adb_commands_without_key_injection(self):
        self.invoke([frame()], command_seconds=0.1)
        self.assertEqual(12, len(self.adb.calls))
        self.assertEqual(READ_ONLY_QUERIES, {tuple(argv[4:]) for argv, _, _ in self.adb.calls})
        for argv, timeout, _ in self.adb.calls:
            self.assertNotIn("input", argv)
            self.assertNotIn("keyevent", argv)
            self.assertNotIn("82", argv)
            self.assertNotIn("settings", argv)
            self.assertLessEqual(timeout, 5.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
