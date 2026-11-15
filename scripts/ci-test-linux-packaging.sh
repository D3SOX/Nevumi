#!/usr/bin/env bash
# Runs scripts/test-linux-packaging.py in the Arch container from scripts/compose.yaml, as the runner's user.
set -euo pipefail
# makepkg and repo-add need base-devel and GnuPG. The installed package needs the rest to start on a test display.
pacman -Syu --noconfirm --needed base-devel gnupg python xorg-server-xvfb xorg-xwininfo dbus glib2 mpv ffmpeg \
    libsecret libpulse sdl2 fontconfig libxrender libxtst ttf-dejavu
# The desktop media controls connect through D-Bus, which needs a machine ID the image does not have.
dbus-uuidgen --ensure=/etc/machine-id
useradd --uid "${NEVUMI_BUILD_UID:?Pass the runner user ID}" --create-home builder
runuser -u builder -- python scripts/test-linux-packaging.py
