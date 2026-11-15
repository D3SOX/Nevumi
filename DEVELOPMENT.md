# Developing Nevumi

## Build and test

Install JDK 21, Python 3.11 or newer for the scripts (3.12 or newer for the F-Droid tooling in `scripts/requirements.txt`), the Go version in `native/tailscale/go.mod`, the Android SDK packages in `scripts/android-sdk-packages`. Linux also needs CMake, Ninja, SDL2 and OpenGL development files, mpv and its development library, FFmpeg, libsecret and PulseAudio client tools. Running or packaging the desktop app also needs JDK 25, which distributions bundle as their runtime. Android builds don't.

Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to your Android SDK. Gradle detects installed toolchains or reads `JAVA_HOME_21_X64` and `JAVA_HOME_25_X64`.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleDevelopment
./gradlew :desktopApp:createDistributable
```

The first native build downloads checksum-verified projectM 4.1.8 and its evaluator. Subsequent builds reuse those sources. Native dependencies remain shared libraries. The Tailscale client in `native/tailscale` builds with Go modules pinned in its `go.sum`.

The acceptance suite uses disposable Navidrome, gonic, SFTP and scrobbling fixtures. CI runs desktop tests and Android instrumentation on API 31 and API 36 with 16 KB memory pages. Release checks also install the optimized APK and open MilkDrop. Development and release builds test the Arch package and its signed repository in an Arch container. The fixture helpers under `scripts/` can start the same services locally. Use an emulator for instrumentation tests, which clear the app's test data.

Pull requests run the app suites when app, build, fixture or app-packaging inputs change, including edits to the test workflow itself. Website-only PRs check JavaScript syntax and site packaging instead. CodeQL scans the languages affected by a change; scheduled and manual security scans cover every language. Release and manual acceptance runs always run both app suites.

```sh
./gradlew :shared:desktopTest
./gradlew :androidApp:connectedDebugAndroidTest
```

Desktop UI tests need a display, such as `xvfb-run`, and the audio-monitor test needs a PulseAudio-compatible service. `scripts/ci-tests.sh` starts and stops the complete fixture set on an isolated CI runner.

## Release setup

`development.yml` builds a development prerelease when app or build inputs change on `master`, or on a manual workflow run. Each development run uses its own `development-<run ID>` tag. Assets upload to a draft before publication, so development releases work with GitHub release immutability. Dependency submission runs when Gradle manifests or configuration change. `release.yml` publishes tagged releases, including alphas, as regular GitHub releases after acceptance tests pass. It attaches the signed Arch and F-Droid repositories as `nevumi-repositories.tar.gz` and then runs `website.yml`. `website.yml` also runs on pushes to `master` that change the landing page. It deploys the page together with the repositories from the newest release, so a website change doesn't need a new release. A release tag such as `v0.1.0` must match both platform versions, and Android's `versionCode` must be higher than in the previous release tag.

Configure these repository secrets and variables for signed releases:

| Name | Kind | Purpose |
| --- | --- | --- |
| `MANIDA_ANDROID_KEYSTORE_BASE64` | Secret | Base64-encoded release keystore |
| `MANIDA_ANDROID_KEYSTORE_PASSWORD` | Secret | Keystore password |
| `MANIDA_ANDROID_KEY_PASSWORD` | Secret | Signing-key password |
| `MANIDA_ANDROID_KEY_ALIAS` | Variable | Signing-key alias |
| `MANIDA_ARCH_SIGNING_KEY` | Secret | Armored private key for Arch packages |
| `MANIDA_ARCH_SIGNING_PASSPHRASE` | Secret | Arch signing-key passphrase |
| `MANIDA_ARCH_SIGNING_FINGERPRINT` | Variable | Full Arch signing-key fingerprint |
| `MANIDA_FDROID_KEYSTORE_BASE64` | Secret | Base64-encoded F-Droid repository keystore, alias `manida-fdroid` |
| `MANIDA_FDROID_KEYSTORE_PASSWORD` | Secret | F-Droid repository keystore and key password |

GitHub Pages uses GitHub Actions as its source, with `nevumi.app` as the custom domain. Configure its apex DNS records using GitHub Pages' custom-domain settings. Each deployment replaces the whole site, so the website workflow always includes the repositories from the newest release. F-Droid's landing page is `/fdroid`; its signed index and APKs are under `/fdroid/repo`. The `github-pages` environment must allow the `master` branch for website changes and release tags matching `v*`. Development builds do not require release signing secrets.

### Dependency updates

Dependabot updates Gradle dependencies, GitHub Actions, Go modules, the container images in `scripts/compose.yaml` and the F-Droid tooling. That tooling's direct pins are in `scripts/requirements.in`. `scripts/requirements.txt` locks every transitive dependency with its hashes. Regenerate it with the `pip-compile` command in its header.

`pins.yml` runs `scripts/update-pins.py` weekly for the pins Dependabot can't update: projectM, mpv, appimagetool, the MilkDrop presets, the Go version, NewPipe Extractor with its checksums in `gradle/verification-metadata.xml`, and the Android SDK packages. It opens a pull request for each update. GitHub starts no checks on these pull requests until you approve their workflows, or close and reopen them. To update a pin locally, run `python3 scripts/update-pins.py <pin>` with an authenticated `gh`. The Android pins also need `sdkmanager`.

### README badges

The SVGs in `website/badges` are published with the website at `nevumi.app/badges`, so a change goes live with the next website deployment. The README selects the light or dark variant to match the reader's theme.
