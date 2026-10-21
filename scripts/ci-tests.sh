#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Run this helper on an isolated GitHub Actions runner' >&2; exit 1; }
mode="${1:?Choose desktop or android}"
[[ "$mode" == desktop || "$mode" == android ]]
mkdir -p .test-environment
services=()
pairing_group=''
cleanup() {
    if [[ -n "$pairing_group" ]]; then kill -- "-$pairing_group" 2>/dev/null || true; fi
    for process in "${services[@]}"; do kill "$process" 2>/dev/null || true; done
    wait 2>/dev/null || true
    python scripts/test-navidrome.py stop --sftp
    python scripts/test-navidrome.py stop
    python scripts/test-subsonic.py stop
}
trap cleanup EXIT
trap 'exit 130' INT TERM
python scripts/test-navidrome.py start
python scripts/test-subsonic.py start
python scripts/test-navidrome.py start --sftp
python scripts/test-sftp.py serve > .test-environment/sftp.log 2>&1 &
services+=("$!")
python scripts/test-listenbrainz.py > .test-environment/listenbrainz.log 2>&1 &
services+=("$!")
python scripts/test-sc-gate.py > .test-environment/sc-gate.log 2>&1 &
services+=("$!")
if [[ "$mode" == desktop ]]; then
    # Hosted runners have no desktop session or unlocked credential collection.
    keyring_environment="$(printf 'nevumi-ci-only' | gnome-keyring-daemon --unlock --components=secrets)"
    export GNOME_KEYRING_CONTROL
    GNOME_KEYRING_CONTROL="$(printf '%s\n' "$keyring_environment" | sed -n 's/^GNOME_KEYRING_CONTROL=//p')"
    gnome-keyring-daemon --start --components=secrets
    printf 'fixture-password' | secret-tool store --label='Nevumi CI check' application nevumi-ci
    [[ "$(secret-tool lookup application nevumi-ci)" == fixture-password ]]
    secret-tool clear application nevumi-ci
    if ! pactl info >/dev/null 2>&1; then
        pulseaudio --daemonize=no --exit-idle-time=-1 > .test-environment/pulseaudio.log 2>&1 &
        services+=("$!")
        for _attempt in {1..30}; do
            if pactl info >/dev/null 2>&1; then break; fi
            sleep 1
        done
    fi
    # PulseAudio removes its automatic dummy sink when a test creates a real sink.
    pactl load-module module-null-sink sink_name=nevumi_ci_audio >/dev/null
    pactl set-default-sink nevumi_ci_audio
    xvfb-run --auto-servernum --server-args='-screen 0 1920x1080x24 -nolisten tcp' ./gradlew --configure-on-demand :shared:desktopTest --console=plain
else
    export ANDROID_SERIAL=emulator-5554
    setsid ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx512m --max-workers=2 \
        -Pkotlin.compiler.execution.strategy=in-process :shared:pairedFixture --console=plain > .test-environment/pairing.log 2>&1 &
    pairing_group="$!"
    for _attempt in {1..600}; do
        if [[ -f .test-environment/paired-fixture/control-port ]]; then break; fi
        kill -0 "$pairing_group"
        sleep 1
    done
    [[ -f .test-environment/paired-fixture/control-port ]]
    for port in 14533 14534 14535 14536 14537 14538 "$(cat .test-environment/paired-fixture/port)" "$(cat .test-environment/paired-fixture/control-port)"; do
        adb -s "$ANDROID_SERIAL" reverse "tcp:$port" "tcp:$port"
    done
    adb -s "$ANDROID_SERIAL" push .test-environment/sftp-fixture/client /data/local/tmp/nevumi-sftp-client
    adb -s "$ANDROID_SERIAL" push .test-environment/sftp-fixture/android-fixture.properties /data/local/tmp/nevumi-sftp-fixture.properties
    adb -s "$ANDROID_SERIAL" push .test-environment/paired-fixture/control-port /data/local/tmp/nevumi-sync-control-port
    shard="${NEVUMI_ANDROID_SHARD:-1/1}"
    python3 scripts/test-android-suite.py --shard "$shard"
    # The cold-start check runs once per device, on the first shard.
    if [[ "${shard%%/*}" == 1 ]]; then
        NEVUMI_TEST_SERIAL="$ANDROID_SERIAL" python3 scripts/test-android-startup.py
    fi
fi
