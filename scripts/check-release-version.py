#!/usr/bin/env python3
"""Require release tags to match the shared app version, and Android releases to raise versionCode."""
import argparse
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("tag")
parser.add_argument("--version-code", action="store_true", help="compare versionCode with the previous release tag")
arguments = parser.parse_args()
match = re.fullmatch(r"v([0-9]+\.[0-9]+\.[0-9]+)", arguments.tag)
if match is None:
    raise SystemExit("Use a release tag such as v0.1.0")
version = re.search(r"(?m)^appVersion=(.+)$", (root / "gradle.properties").read_text())
if version is None or version[1] != match[1]:
    raise SystemExit("The tag must match appVersion in gradle.properties")


def version_key(tag):
    return tuple(int(part) for part in tag.removeprefix("v").split("."))


def version_code(build_file):
    return int(re.search(r"(?m)^\s*versionCode = ([0-9]+)$", build_file)[1])


if arguments.version_code:
    tags = subprocess.check_output(["git", "tag", "--list", "v*"], cwd=root, text=True).split()
    older = [tag for tag in tags if re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag) and version_key(tag) < version_key(match[0])]
    if older:
        previous = max(older, key=version_key)
        current_code = version_code((root / "androidApp/build.gradle.kts").read_text())
        previous_code = version_code(subprocess.check_output(
            ["git", "show", f"{previous}:androidApp/build.gradle.kts"], cwd=root, text=True))
        if current_code <= previous_code:
            raise SystemExit(f"Raise versionCode above {previous_code} from {previous}")
        print(f"versionCode {current_code} follows {previous_code} from {previous}")
