#!/usr/bin/env python3
"""Build the pinned audio backend for CI and Ubuntu-compatible AppImages."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
VERSION = "0.42.0"
SOURCE_URL = "https://codeload.github.com/mpv-player/mpv/tar.gz/b0841e8c4d156b8403dc1876c4cbd9011eb583f3"
SOURCE_SHA256 = "a4a79fa6ca728762ac407734b0f93dbcbba250c3bb3bf97b77a342042be802e4"


def source_archive():
    archive = ROOT / ".test-environment/native-sources" / f"mpv-{VERSION}-source.tar.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as source, archive.open("xb") as output:
            shutil.copyfileobj(source, output)
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != SOURCE_SHA256:
            raise RuntimeError("mpv source checksum mismatch")
    return archive


def build(prefix):
    directory = ROOT / "shared/build/native/mpv" / VERSION
    directory.mkdir(parents=True, exist_ok=True)
    source = directory / "source"
    if not source.exists():
        with tarfile.open(source_archive()) as archive:
            archive.extractall(directory / "unpacked", filter="data")
        extracted = next((directory / "unpacked").iterdir())
        extracted.rename(source)
        (directory / "unpacked").rmdir()
    output = directory / "build"
    setup = ["meson", "setup"]
    if (output / "build.ninja").exists():
        setup.append("--reconfigure")
    subprocess.run([*setup, str(output), str(source), "--prefix", str(prefix),
                    "--libdir=lib", "--buildtype=release", "-Dauto_features=disabled",
                    "-Dcplayer=false", "-Dlibmpv=true", "-Dgl=disabled",
                    "-Dpulse=enabled", "-Dalsa=enabled", "-Dbuild-date=false"], check=True)
    subprocess.run(["meson", "compile", "-C", str(output), "-j", str(min(os.cpu_count() or 1, 4))], check=True)
    subprocess.run(["meson", "install", "-C", str(output)], check=True)
    licenses = prefix / "share/licenses/mpv"
    licenses.mkdir(parents=True, exist_ok=True)
    for name in ["Copyright", "LICENSE.GPL", "LICENSE.LGPL"]:
        shutil.copyfile(source / name, licenses / name)
    print(f"Built mpv {VERSION} at {prefix}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path, required=True)
    build(parser.parse_args().prefix.resolve())
