#!/usr/bin/env python3
"""Build the embedded Tailscale JNI library for Linux or an Android ABI."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "native/tailscale"
LIBRARY = "libnevumi_tailscale.so"
# Log uploads and network flow logs stay out of the binary.
TAGS = "ts_omit_logtail,ts_omit_netlog"
ANDROID = {"arm64-v8a": ("arm64", "aarch64-linux-android"), "x86_64": ("amd64", "x86_64-linux-android")}
ANDROID_API = 31


def build(output: Path, abi: str | None = None, ndk: Path | None = None):
    environment = dict(os.environ, CGO_ENABLED="1")
    flags = "-s -w"
    if abi is None:
        java = Path(os.environ.get("JAVA_HOME") or Path(shutil.which("javac")).resolve().parent.parent)
        environment["CGO_CFLAGS"] = f"-I{java / 'include'} -I{java / 'include/linux'}"
    else:
        architecture, triple = ANDROID[abi]
        clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / f"{triple}{ANDROID_API}-clang"
        environment.update(GOOS="android", GOARCH=architecture, CC=str(clang))
        flags += " -extldflags=-Wl,-z,max-page-size=16384"
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["go", "build", "-trimpath", "-buildmode=c-shared", "-tags", TAGS, "-ldflags", flags,
                    "-o", str(output / LIBRARY), "."], cwd=SOURCE, env=environment, check=True)
    (output / "libnevumi_tailscale.h").unlink(missing_ok=True)


def stage_licenses(output: Path):
    listing = subprocess.run(["go", "list", "-deps", "-tags", TAGS, "-f",
                              "{{with .Module}}{{if not .Main}}{{.Path}}\t{{.Dir}}{{end}}{{end}}", "."],
                             cwd=SOURCE, capture_output=True, text=True, check=True).stdout
    modules = dict(line.split("\t") for line in listing.splitlines() if line)
    for path, directory in modules.items():
        directory = Path(directory)
        for document in directory.iterdir():
            if document.is_file() and document.suffix != ".go" and document.name.lower().startswith(("license", "copying", "notice")):
                target = output / "licenses/tailscale" / path / document.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(document, target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-abi", choices=sorted(ANDROID))
    parser.add_argument("--ndk", type=Path)
    args = parser.parse_args()
    if args.android_abi and not args.ndk:
        parser.error("--ndk is required for Android builds")
    build(args.output.resolve(), args.android_abi, args.ndk)
    if not args.android_abi:
        stage_licenses(args.output.resolve())
