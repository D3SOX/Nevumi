#!/usr/bin/env python3
"""Build the Linux JNI bridges and stage their shared dependencies."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent

def build(output: Path, build_type: str):
    directory = ROOT / "shared/build/native/linux"
    subprocess.run(["cmake", "-S", str(ROOT / "native"), "-B", str(directory), "-G", "Ninja",
                    f"-DCMAKE_BUILD_TYPE={build_type}"], check=True)
    subprocess.run(["cmake", "--build", str(directory), "--parallel", str(min(os.cpu_count() or 1, 4))], check=True)
    output.mkdir(parents=True, exist_ok=True)
    libraries = [directory / "libnevumi_visualizer.so"] + list(directory.glob("_deps/projectm-build/src/libprojectM/libprojectM-4.so*"))
    if len(libraries) < 2:
        raise RuntimeError("The visualizer's shared libraries were not built")
    for library in libraries:
        shutil.copyfile(library, output / library.name)
    licenses = output / "licenses"
    licenses.mkdir(exist_ok=True)
    for component in ["projectm", "projectm_eval"]:
        source = directory / "_deps" / f"{component}-src"
        for document in source.rglob("*"):
            if document.is_file() and document.name.lower().startswith(("license", "copying")):
                target = licenses / component / document.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(document, target)
    subprocess.run([sys.executable, str(ROOT / "scripts/build-tailscale.py"), "--output", str(output)], check=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-type", choices=["Release", "RelWithDebInfo"], default=os.environ.get("NEVUMI_NATIVE_BUILD_TYPE", "Release"))
    args = parser.parse_args()
    build(args.output.resolve(), args.build_type)
