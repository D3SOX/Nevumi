#!/usr/bin/env python3
"""Run every Android test class and retain separate, verified results."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
# One unit per test plus one per class for Gradle/device setup. Keep shards small as the suite grows.
SHARD_BUDGET = 28
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classes", nargs="*")
parser.add_argument("--shard", default="1/1", help="Run one balanced shard, as INDEX/COUNT")
parser.add_argument("--matrix", action="store_true", help="Print the dynamic CI shard matrix without running tests")
parser.add_argument("--class-timeout", type=int, default=300, help="Maximum seconds for one class, including Gradle setup")
parser.add_argument("--resume", action="store_true",
                    help="Run the classes a stopped emulator left behind, keeping the results from before it stopped")
arguments = parser.parse_args()
if arguments.class_timeout <= 0:
    parser.error("--class-timeout must be positive")
selected = arguments.classes
shard = re.fullmatch(r"([1-9][0-9]*)/([1-9][0-9]*)", arguments.shard)
if shard is None or int(shard[1]) > int(shard[2]):
    raise SystemExit("Use a shard such as 2/4")
sources = ROOT / "androidApp/src/androidTest/kotlin/me/d3sox/nevumi"
classes = [path.stem for path in sorted(sources.glob("Android*Test.kt"))
           if "@ColdStartupCheck" not in path.read_text()]
if selected:
    if not set(selected) <= set(classes):
        raise SystemExit("Unknown test class")
    classes = selected
SUPPRESS = re.compile(r"@SdkSuppress\(minSdkVersion = (\d+)\)")


def expected_cases(name, api=float("inf")):
    """Find the test names that must report a result on this API, including runtime skips."""
    header, _, body = (sources / f"{name}.kt").read_text().partition("class " + name)
    if max(map(int, SUPPRESS.findall(header)), default=0) > api:
        return set()
    annotated = re.findall(r"((?:@\w+(?:\([^)]*\))?\s+)+)fun (\w+)\(", body)
    return {method for annotations, method in annotated if "@Test" in annotations
            and max(map(int, SUPPRESS.findall(annotations)), default=0) <= api}


weights = {name: 1 + len(expected_cases(name)) for name in classes}


def distribute(count):
    """Place the largest classes first, breaking ties consistently on every runner."""
    groups = [[] for _ in range(count)]
    loads = [0] * count
    for name in sorted(classes, key=lambda name: (-weights[name], name)):
        index = min(range(count), key=lambda index: loads[index])
        groups[index].append(name)
        loads[index] += weights[name]
    return groups, loads


if not classes:
    raise SystemExit("No Android test classes found")
if arguments.matrix:
    count = min(len(classes), (sum(weights.values()) + SHARD_BUDGET - 1) // SHARD_BUDGET)
    # A class cannot be split, but it can occupy a shard on its own.
    while count < len(classes) and any(
            load > SHARD_BUDGET and len(group) > 1 for group, load in zip(*distribute(count))):
        count += 1
    if count > 128:
        raise SystemExit("Android suite exceeds GitHub's 256-job matrix limit across two API levels")
    print(json.dumps([{"index": index, "total": count} for index in range(1, count + 1)]))
    raise SystemExit(0)

# Classes that had not passed when the emulator stopped, for a second emulator to run.
unfinished = ROOT / ".test-environment/android-unfinished-classes"
if arguments.resume:
    classes = unfinished.read_text().split()
    unfinished.unlink()
else:
    classes = distribute(int(shard[2]))[0][int(shard[1]) - 1]
serial = os.environ["ANDROID_SERIAL"]
if not re.fullmatch(r"emulator-[0-9]{4}", serial):
    raise SystemExit("Run this suite on an owned emulator")
reports = ROOT / "androidApp/build/outputs/androidTest-results/connected/debug"
output = ROOT / ".test-environment/android-class-results"
if arguments.resume:
    output /= "new-emulator"
shutil.rmtree(output, ignore_errors=True)
output.mkdir(parents=True)
api = int(subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
                         capture_output=True, text=True, check=True, timeout=30).stdout.strip())


failed = []
flaky = []
executed = 0


def run(name, attempt):
    """Run one class and keep its reports; return the executed cases, or None when it failed."""
    print(f"::group::{name} (attempt {attempt})", flush=True)
    shutil.rmtree(reports, ignore_errors=True)
    started = time.monotonic()
    timed_out = False
    with subprocess.Popen([str(ROOT / "gradlew"), ":androidApp:connectedDebugAndroidTest",
                             "-Dorg.gradle.jvmargs=-Xmx1g", "--max-workers=2",
                             "-Pkotlin.compiler.execution.strategy=in-process",
                             "-Pandroid.injected.device.serial=" + serial,
                             "-Pandroid.testInstrumentationRunnerArguments.class=me.d3sox.nevumi." + name,
                             "--console=plain"], cwd=ROOT, start_new_session=True) as process:
        try:
            process.wait(timeout=arguments.class_timeout)
        except subprocess.TimeoutExpired:
            # Stop the client and its children. Do not reuse a stuck Gradle/device session for retries.
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            timed_out = True
    diagnostics = output / (name if attempt == 1 else f"{name}-attempt{attempt}")
    if reports.exists():
        shutil.copytree(reports, diagnostics)
    print(f"{name}: {time.monotonic() - started:.1f}s", flush=True)
    print("::endgroup::", flush=True)
    if timed_out:
        print(f"::warning title=Hung Android test::{name} timed out after {arguments.class_timeout} seconds", flush=True)
        # Instrumentation reports can stop before the device hangs. Retain its final log as well.
        diagnostics.mkdir(parents=True, exist_ok=True)
        with (diagnostics / "device-logcat.txt").open("w") as log:
            try:
                subprocess.run(["adb", "-s", serial, "logcat", "-d", "-v", "threadtime"],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=10)
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError) as error:
                print(f"::warning::Could not capture device log for {name}: {error}", flush=True)
        if device_lost():
            return None
        # The killed run can leave its instrumentation on the device, so stop both packages before the next class
        # or the retry starts its own Gradle run.
        for package in ["me.d3sox.nevumi", "me.d3sox.nevumi.test"]:
            try:
                subprocess.run(["adb", "-s", serial, "shell", "am", "force-stop", package],
                               capture_output=True, check=True, timeout=30)
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError) as error:
                raise SystemExit(f"Android suite failed: {serial} is unresponsive after {name} timed out; "
                                 f"cannot stop {package}. Diagnostics: {diagnostics}. {error}")
        return None
    cases = [case for report in reports.glob("TEST-*.xml")
             for case in ET.parse(report).getroot().findall(".//testcase")]
    completed = [case for case in cases if case.find("skipped") is None]
    expected = expected_cases(name, api)
    # Runtime assumptions still produce a result. Only API-suppressed skips are optional.
    reported = [case.get("name") for case in cases
                if case.get("name") in expected or case.find("skipped") is None]
    complete = len(reported) == len(expected) and set(reported) == expected
    if not complete:
        print(f"{name}: expected {sorted(expected)}, received {reported}", flush=True)
    ok = (process.returncode == 0 and complete
          and all(case.get("classname") == "me.d3sox.nevumi." + name
                  and case.find("failure") is None and case.find("error") is None for case in cases))
    return completed if ok else None


def device_lost():
    """Wait out a soft restart, but report an emulator that stays gone for a minute."""
    try:
        subprocess.run(["adb", "-s", serial, "wait-for-device"], capture_output=True, check=True, timeout=60)
        return False
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
        return True


def stop_for_lost_device(remaining):
    """Hosted emulators sometimes freeze and exit mid-suite. Leave the unfinished classes for a new emulator, once."""
    message = f"{serial} stopped responding"
    if failed or arguments.resume:
        raise SystemExit(f"Android suite failed: {message}; failed: {failed}; not run: {remaining}")
    unfinished.write_text("\n".join(remaining) + "\n")
    print(f"::warning title=Android emulator stopped::{message}; {len(remaining)} classes will run on a new emulator",
          flush=True)
    raise SystemExit(f"Android suite stopped: {message}; unfinished classes: {remaining}")


retry = []
for index, name in enumerate(classes):
    cases = run(name, 1)
    if cases is None:
        if device_lost():
            stop_for_lost_device(retry + classes[index:])
        retry.append(name)
    else:
        executed += len(cases)
# Hosted emulators share two slow cores with Gradle and keep settling for minutes after boot, so retry failures
# once after the other classes have run; a second failure is a real failure.
for index, name in enumerate(retry):
    cases = run(name, 2)
    if cases is None:
        if device_lost():
            stop_for_lost_device(retry[index:])
        failed.append(name)
        continue
    flaky.append(name)
    print(f"::warning title=Flaky Android test::{name} failed once and passed on retry", flush=True)
    executed += len(cases)
if failed:
    raise SystemExit(f"Android suite failed: {failed}; passed on retry: {flaky}; {executed} cases across {len(classes)} classes")
print(f"Passed {executed} Android checks across {len(classes)} classes; passed on retry: {flaky or 'none'}")
