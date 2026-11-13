#!/usr/bin/env bash
# The emulator action waits until every emulator process exits, so a shutdown that hangs holds its step until the
# timeout. Ask the emulator to quit, then kill what is left.
set -uo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Run this helper on an isolated GitHub Actions runner' >&2; exit 1; }
processes="${ANDROID_HOME:?}/emulator/(emulator|qemu/)"
adb -s "${ANDROID_SERIAL:-emulator-5554}" emu kill >/dev/null 2>&1
for _attempt in {1..60}; do
    pgrep -f "$processes" >/dev/null || exit 0
    sleep 1
done
echo 'The emulator did not exit within a minute, so it is killed' >&2
pkill -KILL -f "$processes"
exit 0
