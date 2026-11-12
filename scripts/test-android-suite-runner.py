#!/usr/bin/env python3
"""Exercise the Android suite driver with local substitutes for Gradle and adb."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SOURCES = Path("androidApp/src/androidTest/kotlin/me/d3sox/nevumi")


class AndroidSuiteRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="nevumi-suite-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy(ROOT / "scripts/test-android-suite.py", self.root / "scripts")
        (self.root / SOURCES).mkdir(parents=True)
        self.executable("adb", "print('36')\n")
        self.executable("gradlew", """
import os
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET
name = next(arg.split('=')[-1] for arg in sys.argv if 'RunnerArguments.class=' in arg)
with open('invocations', 'a') as log:
    log.write(name + '\\n')
reports = Path('androidApp/build/outputs/androidTest-results/connected/debug')
reports.mkdir(parents=True, exist_ok=True)
(reports / 'device.log').write_text('Retain diagnostics even on timeout')
if os.environ.get('STALL') == 'true':
    Path('gradle.pid').write_text(str(os.getpid()))
    time.sleep(60)
attempt = Path('invocations').read_text().splitlines().count(name)
retry = os.environ.get('RETRY') == 'true' and attempt == 1
suite = ET.Element('testsuite')
if not retry:
    source = Path('androidApp/src/androidTest/kotlin/me/d3sox/nevumi') / (name.split('.')[-1] + '.kt')
    partial = os.environ.get('PARTIAL') == 'true' or len(Path('invocations').read_text().splitlines()) > int(os.environ.get('PARTIAL_AFTER', 1 << 30))
    count = 1 if partial else source.read_text().count('@Test')
    for index in range(count):
        case = ET.SubElement(suite, 'testcase', classname=name, name=f'example{index}')
        if os.environ.get('SKIPPED') == 'true' or os.environ.get('SKIPPED_INDEX') == str(index):
            ET.SubElement(case, 'skipped')
ET.ElementTree(suite).write(reports / 'TEST-example.xml')
""")
        self.environment = dict(os.environ, ANDROID_SERIAL="emulator-5554",
                                PATH=str(self.root) + os.pathsep + os.environ["PATH"])

    def executable(self, name, source):
        path = self.root / name
        path.write_text(f"#!{sys.executable}\n" + source)
        path.chmod(0o755)

    def source(self, name, annotation="", tests=1):
        (self.root / SOURCES / f"{name}.kt").write_text(
            f"{annotation}\nclass {name} {{\n"
            + "\n".join(f"@Test\nfun example{index}() {{}}" for index in range(tests)) + "\n}\n")

    def run_suite(self, *arguments, **environment):
        return subprocess.run([sys.executable, "scripts/test-android-suite.py", *arguments],
                              cwd=self.root, env=dict(self.environment, **environment),
                              capture_output=True, text=True, timeout=10)

    def test_hung_class_fails_promptly_and_retains_diagnostics(self):
        self.source("AndroidExampleTest")
        result = self.run_suite("--class-timeout", "1", STALL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("timed out after 1 seconds", result.stdout + result.stderr)
        self.assertTrue((self.root / ".test-environment/android-class-results/AndroidExampleTest/device.log").is_file())
        pid = int((self.root / "gradle.pid").read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        # A hang is retried once in a new Gradle run, like any other failure, and moves to a new emulator when it repeats.
        self.assertEqual(2, len((self.root / "invocations").read_text().splitlines()))
        self.assertEqual(["AndroidExampleTest"],
                         (self.root / ".test-environment/android-unfinished-classes").read_text().split())

    def test_unresponsive_device_stops_the_shard_without_retrying_or_losing_diagnostics(self):
        self.source("AndroidExampleTest")
        self.executable("adb", "import sys\nprint('Device hang diagnostics' if 'logcat' in sys.argv else '36')\n")
        # Reproduce the hosted failure at the subprocess boundary, without waiting 30 seconds for adb.
        launcher = """
import runpy
import subprocess
import sys
from unittest.mock import patch
original_run = subprocess.run
def run(command, **kwargs):
    if command[0] == 'adb' and 'force-stop' in command:
        raise subprocess.TimeoutExpired(command, kwargs['timeout'])
    return original_run(command, **kwargs)
sys.argv = ['scripts/test-android-suite.py', '--class-timeout', '1']
with patch('subprocess.run', side_effect=run):
    runpy.run_path(sys.argv[0], run_name='__main__')
"""
        result = subprocess.run([sys.executable, "-c", launcher], cwd=self.root,
                                env=dict(self.environment, STALL="true"),
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("emulator-5554 is unresponsive", result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(1, len((self.root / "invocations").read_text().splitlines()))
        self.assertTrue((self.root / ".test-environment/android-class-results/AndroidExampleTest/device.log").is_file())
        log = self.root / ".test-environment/android-class-results/AndroidExampleTest/device-logcat.txt"
        self.assertEqual("Device hang diagnostics\n", log.read_text())

    def test_stopped_emulator_leaves_unfinished_classes_for_a_new_one(self):
        for name in ["AndroidFirstTest", "AndroidSecondTest", "AndroidThirdTest"]:
            self.source(name, tests=2)
        # The emulator stops during the second class and does not come back.
        self.executable("adb", """
import sys
from pathlib import Path
calls = Path('invocations').read_text().splitlines() if Path('invocations').exists() else []
sys.exit(1 if 'wait-for-device' in sys.argv and len(calls) >= 2 else print('36'))
""")
        result = self.run_suite(PARTIAL_AFTER="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("emulator-5554 stopped responding", result.stdout + result.stderr)
        unfinished = self.root / ".test-environment/android-unfinished-classes"
        self.assertEqual(["AndroidSecondTest", "AndroidThirdTest"], unfinished.read_text().split())
        output = self.root / ".test-environment/android-class-results"
        self.assertTrue((output / "AndroidFirstTest/TEST-example.xml").is_file())

        self.executable("adb", "print('36')\n")
        (self.root / "invocations").unlink()
        result = self.run_suite("--resume")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(["me.d3sox.nevumi.AndroidSecondTest", "me.d3sox.nevumi.AndroidThirdTest"],
                         (self.root / "invocations").read_text().splitlines())
        self.assertFalse(unfinished.exists())
        # The first emulator's results stay next to the new ones.
        self.assertTrue((output / "AndroidFirstTest/TEST-example.xml").is_file())
        self.assertTrue((output / "new-emulator/AndroidThirdTest/TEST-example.xml").is_file())

    def test_a_second_stopped_emulator_fails_the_shard(self):
        self.source("AndroidExampleTest", tests=2)
        (self.root / ".test-environment").mkdir()
        (self.root / ".test-environment/android-unfinished-classes").write_text("AndroidExampleTest\n")
        self.executable("adb", "import sys\nsys.exit(1 if 'wait-for-device' in sys.argv else print('36'))\n")
        result = self.run_suite("--resume", PARTIAL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Android suite failed on a second emulator: emulator-5554 stopped responding", result.stdout + result.stderr)
        self.assertFalse((self.root / ".test-environment/android-unfinished-classes").exists())

    def test_a_class_that_fails_twice_runs_once_more_on_a_new_emulator(self):
        self.source("AndroidPassingTest")
        self.source("AndroidFailingTest", tests=2)
        result = self.run_suite(PARTIAL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Android emulator replaced", result.stdout)
        unfinished = self.root / ".test-environment/android-unfinished-classes"
        self.assertEqual(["AndroidFailingTest"], unfinished.read_text().split())
        output = self.root / ".test-environment/android-class-results"
        # Both failed attempts keep the device's focus state for diagnosis.
        self.assertTrue((output / "AndroidFailingTest/device-focus.txt").is_file())
        self.assertTrue((output / "AndroidFailingTest-attempt2/device-focus.txt").is_file())
        self.assertFalse((output / "AndroidPassingTest/device-focus.txt").exists())

        (self.root / "invocations").unlink()
        result = self.run_suite("--resume")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(["me.d3sox.nevumi.AndroidFailingTest"], (self.root / "invocations").read_text().splitlines())

        # A class that also fails twice on the new emulator fails the shard.
        unfinished.write_text("AndroidFailingTest\n")
        result = self.run_suite("--resume", PARTIAL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Android suite failed on a second emulator: ['AndroidFailingTest'] failed twice",
                      result.stdout + result.stderr)
        self.assertFalse(unfinished.exists())

    def matrix(self):
        # Planning runs on a fresh runner with neither an Android SDK nor a device.
        self.environment.pop("ANDROID_SERIAL", None)
        result = self.run_suite("--matrix")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_dynamic_shards_cover_each_class_once_and_exclude_cold_start(self):
        shutil.copytree(ROOT / SOURCES, self.root / SOURCES, dirs_exist_ok=True)
        expected = {"me.d3sox.nevumi." + path.stem for path in (ROOT / SOURCES).glob("Android*Test.kt")
                    if "@ColdStartupCheck" not in path.read_text()}
        for name in expected:
            self.source(name.split(".")[-1])
        matrix = self.matrix()
        self.assertEqual(matrix, self.matrix(), "Planning must be deterministic")
        for shard in matrix:
            result = self.run_suite("--shard", f"{shard['index']}/{shard['total']}", ANDROID_SERIAL="emulator-5554")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        actual = (self.root / "invocations").read_text().splitlines()
        self.assertCountEqual(sorted(expected), actual)

    def test_new_classes_and_methods_increase_the_shard_count(self):
        self.source("AndroidExampleTest")
        initial = len(self.matrix())
        for index in range(20):
            self.source(f"AndroidExample{index}Test")
        more_classes = len(self.matrix())
        self.assertGreater(more_classes, initial)
        for index in range(20):
            self.source(f"AndroidExample{index}Test", tests=4)
        self.assertGreater(len(self.matrix()), more_classes)

    def test_large_classes_are_spread_between_shards(self):
        for index in range(3):
            self.source(f"AndroidLarge{index}Test", tests=20)
            self.source(f"AndroidSmall{index}Test")
        matrix = self.matrix()
        self.assertEqual(3, len(matrix))
        for shard in matrix:
            (self.root / "invocations").unlink(missing_ok=True)
            result = self.run_suite("--shard", f"{shard['index']}/{shard['total']}", ANDROID_SERIAL="emulator-5554")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            calls = (self.root / "invocations").read_text()
            self.assertEqual(1, calls.count("AndroidLarge"))
            self.assertEqual(1, calls.count("AndroidSmall"))

    def test_oversized_class_gets_its_own_shard(self):
        self.source("AndroidLargeTest", tests=50)
        self.source("AndroidSmallTest")
        self.assertEqual(2, len(self.matrix()))

    def test_empty_suite_cannot_produce_a_green_matrix(self):
        result = self.run_suite("--matrix")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No Android test classes found", result.stderr)

    def test_empty_report_is_retried_and_both_attempts_are_retained(self):
        self.source("AndroidExampleTest")
        result = self.run_suite(RETRY="true")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("failed once and passed on retry", result.stdout)
        output = self.root / ".test-environment/android-class-results"
        self.assertTrue((output / "AndroidExampleTest/TEST-example.xml").is_file())
        self.assertTrue((output / "AndroidExampleTest-attempt2/TEST-example.xml").is_file())

    def test_partial_report_is_not_accepted_as_a_passing_class(self):
        self.source("AndroidExampleTest", tests=2)
        result = self.run_suite(PARTIAL="true")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(2, len((self.root / "invocations").read_text().splitlines()))

    def test_api_suppressed_class_is_not_a_failure_or_an_executed_test(self):
        self.source("AndroidExampleTest", annotation="@SdkSuppress(minSdkVersion = 37)")
        result = self.run_suite(SKIPPED="true")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Passed 0 Android checks", result.stdout)

    def test_runtime_skip_counts_as_a_reported_result_but_not_an_executed_test(self):
        self.source("AndroidExampleTest", tests=2)
        result = self.run_suite(SKIPPED_INDEX="0")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Passed 1 Android checks", result.stdout)
        self.assertEqual(1, len((self.root / "invocations").read_text().splitlines()))

    def test_api_suppressed_method_cannot_mask_a_missing_result(self):
        self.source("AndroidExampleTest", tests=2)
        path = self.root / SOURCES / "AndroidExampleTest.kt"
        path.write_text(path.read_text().replace("@Test\nfun example0", "@SdkSuppress(minSdkVersion = 37)\n@Test\nfun example0"))
        result = self.run_suite(SKIPPED_INDEX="0")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Passed 1 Android checks", result.stdout)
        result = self.run_suite(SKIPPED_INDEX="0", PARTIAL="true")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
