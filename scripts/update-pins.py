#!/usr/bin/env python3
"""Updates a version pin that Dependabot cannot track and prints a summary when it changed."""
import argparse
import hashlib
import json
import re
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CMAKE = ROOT / "native/CMakeLists.txt"
MPV = ROOT / "scripts/build_mpv.py"
APPIMAGE = ROOT / "scripts/package-appimage.py"
PRESETS = ROOT / "scripts/package-milkdrop-presets.py"
SDK_PACKAGES = ROOT / "scripts/android-sdk-packages"


def github(path: str, query: str) -> str:
    return subprocess.check_output(["gh", "api", path, "--jq", query], text=True).strip()


def sha256(url: str) -> str:
    with urllib.request.urlopen(url, timeout=120) as response:
        return hashlib.file_digest(response, "sha256").hexdigest()


def edit(path: Path, pattern: str, replacement: str, count: int = 1):
    text, found = re.subn(pattern, replacement, path.read_text(), flags=re.MULTILINE)
    if found != count:
        raise RuntimeError(f"Expected {count} match(es) of {pattern!r} in {path.relative_to(ROOT)}, found {found}")
    path.write_text(text)


def replace_everywhere(old: str, new: str, contexts: list[str]):
    """Replaces [old] in every tracked file where it follows one of [contexts], such as `ndk;`."""
    files = subprocess.check_output(["git", "grep", "-l", "-F", old], cwd=ROOT, text=True).split()
    changed = False
    for name in files:
        path = ROOT / name
        text = path.read_text()
        for context in contexts:
            text = text.replace(context + old, context + new)
        if text != path.read_text():
            path.write_text(text)
            changed = True
    if not changed:
        raise RuntimeError(f"Found no pinned use of {old}")


def version_key(version: str):
    return tuple(int(part) for part in version.split("."))


def projectm():
    current = re.search(r"set\(CMAKE_PROJECT_VERSION (\S+)\)", CMAKE.read_text())[1]
    tag = github("repos/projectM-visualizer/projectm/releases/latest", ".tag_name")
    version = tag.removeprefix("v")
    if version_key(version) <= version_key(current):
        return None
    commit = github(f"repos/projectM-visualizer/projectm/commits/{tag}", ".sha")
    # The release vendors the evaluator as a submodule, so its exact revision comes from the tag.
    evaluator = github(f"repos/projectM-visualizer/projectm/contents/vendor/projectm-eval?ref={tag}", ".sha")
    for repository, revision in [("projectm-eval", evaluator), ("projectm", commit)]:
        url = f"https://codeload.github.com/projectM-visualizer/{repository}/tar.gz/{revision}"
        edit(CMAKE, rf"(codeload\.github\.com/projectM-visualizer/{repository}/tar\.gz/)\w+(\n\s+URL_HASH SHA256=)\w+",
             rf"\g<1>{revision}\g<2>{sha256(url)}")
    major, minor, patch = version.split(".")
    for suffix, value in [("", version), ("_MAJOR", major), ("_MINOR", minor), ("_PATCH", patch)]:
        edit(CMAKE, rf"set\(CMAKE_PROJECT_VERSION{suffix} \S+\)", f"set(CMAKE_PROJECT_VERSION{suffix} {value})")
    replace_everywhere(current, version, ["projectM "])
    return f"projectM {current} to {version}"


def mpv():
    current = re.search(r'^VERSION = "(\S+)"', MPV.read_text(), re.MULTILINE)[1]
    tag = github("repos/mpv-player/mpv/releases/latest", ".tag_name")
    version = tag.removeprefix("v")
    if version_key(version) <= version_key(current):
        return None
    commit = github(f"repos/mpv-player/mpv/commits/{tag}", ".sha")
    url = f"https://codeload.github.com/mpv-player/mpv/tar.gz/{commit}"
    edit(MPV, r'^VERSION = "\S+"', f'VERSION = "{version}"')
    edit(MPV, r'^SOURCE_URL = "\S+"', f'SOURCE_URL = "{url}"')
    edit(MPV, r'^SOURCE_SHA256 = "\w+"', f'SOURCE_SHA256 = "{sha256(url)}"')
    return f"mpv {current} to {version}"


def appimagetool():
    text = APPIMAGE.read_text()
    current = re.search(r'"release", "download", "(\S+)", "--repo", "AppImage/appimagetool"', text)[1]
    releases = json.loads(github("repos/AppImage/appimagetool/releases", "[.[] | select(.prerelease | not)]"))
    # The project also publishes a moving `continuous` release, which cannot be pinned.
    numbered = [release for release in releases if re.fullmatch(r"\d+(\.\d+)+", release["tag_name"])]
    latest = max(numbered, key=lambda release: version_key(release["tag_name"]))
    version = latest["tag_name"]
    if version_key(version) <= version_key(current):
        return None
    asset = next(asset for asset in latest["assets"] if asset["name"] == "appimagetool-x86_64.AppImage")
    edit(APPIMAGE, r'("release", "download", )"\S+"', rf'\g<1>"{version}"')
    edit(APPIMAGE, r'^TOOL_SHA256 = "\w+"', f'TOOL_SHA256 = "{sha256(asset["browser_download_url"])}"')
    return f"appimagetool {current} to {version}"


def milkdrop_presets():
    updated = []
    for repository, current in re.findall(r'\("(presets-[\w-]+)", "(\w+)"', PRESETS.read_text()):
        revision = github(f"repos/projectM-visualizer/{repository}/commits/HEAD", ".sha")
        if revision == current:
            continue
        url = f"https://codeload.github.com/projectM-visualizer/{repository}/legacy.tar.gz/{revision}"
        edit(PRESETS, rf'(\("{repository}", )"\w+",(\s+)"\w+"', rf'\g<1>"{revision}",\g<2>"{sha256(url)}"')
        updated.append(repository)
    # The packager checks the preset count, so a changed pack fails CI until that number is updated too.
    return f"MilkDrop presets ({', '.join(updated)})" if updated else None


def android_packages() -> dict[str, list[str]]:
    listing = subprocess.check_output(["sdkmanager", "--list"], text=True)
    available = listing.split("Available Packages:", 1)[1]
    packages: dict[str, list[str]] = {}
    for name, version in re.findall(r"^\s*(build-tools|ndk|cmake);(\S+)\s*\|", available, re.MULTILINE):
        if re.fullmatch(r"\d+(\.\d+)+", version):
            packages.setdefault(name, []).append(version)
    return packages


def android(package: str):
    # Workflows read these versions from SDK_PACKAGES, because the workflow token cannot push changes to workflow files.
    current = re.search(rf"^{package};(\S+)$", SDK_PACKAGES.read_text(), re.MULTILINE)[1]
    contexts = {"build-tools": ["build-tools;", "build-tools/", "build-tools "], "ndk": ["ndk;", 'ndkVersion = "'],
                "cmake": ["cmake;", 'version = "']}[package]
    latest = max(android_packages()[package], key=version_key)
    if version_key(latest) <= version_key(current):
        return None
    replace_everywhere(current, latest, contexts)
    return f"Android {package} {current} to {latest}"


PINS = {
    "projectm": projectm,
    "mpv": mpv,
    "appimagetool": appimagetool,
    "milkdrop-presets": milkdrop_presets,
    "android-build-tools": lambda: android("build-tools"),
    "android-ndk": lambda: android("ndk"),
    "android-cmake": lambda: android("cmake"),
}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pin", choices=PINS)
    summary = PINS[parser.parse_args().pin]()
    if summary:
        print(summary)
