#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Run this helper on an isolated GitHub Actions runner' >&2; exit 1; }
version="${GITHUB_REF_NAME:?Expected a release tag}"
version="${version#v}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
export GNUPGHOME
GNUPGHOME="$(mktemp -d)"
cleanup() { gpgconf --kill gpg-agent || true; rm -rf -- "$GNUPGHOME"; }
trap cleanup EXIT
trap 'exit 130' INT TERM
printf '%s' "${NEVUMI_ARCH_SIGNING_KEY:?Configure the dedicated Arch signing key}" | gpg --batch --import
python scripts/package-linux.py --image desktopApp/build/compose/binaries/main/app/nevumi \
    --output .test-environment/release --version "$version"
python scripts/package-repository.py --release .test-environment/release \
    --output .test-environment/pages --signing-key "${NEVUMI_ARCH_SIGNING_FINGERPRINT:?Configure the full signing fingerprint}"
cp .test-environment/pages/arch/x86_64/*.pkg.tar.zst.sig .test-environment/release/
