# Build and release

> **Single edition.** Since 14 November 2026 there is one all-inclusive app (direct updates,
> LAN cleartext playback, FFmpeg software decoders), minSdk 24, without Gemini Nano.
> See [VARIANTE_BUILD.md](VARIANTE_BUILD.md).

## Prerequisites

Use JDK 25, Android SDK 37 and the checked-in Gradle wrapper. Application bytecode
targets Java 17.

The Android SDK must be discoverable without a committed `local.properties`, because the
repository policy job fails on machine-local secret files. Export `ANDROID_HOME` (this
project's `scripts/` and CI flows rely on it) or create a local, git-ignored
`local.properties` containing `sdk.dir=<path to your Android SDK>`. Run:

```text
python3 scripts/verify-project-policy.py
python3 -m py_compile scripts/*.py
./gradlew --no-daemon test lint
```

CI builds the debug and release APKs and runs the API 37 instrumentation suites on phone and
Android TV emulators. The suite includes the real RTSPS ACTION_VIEW intent path.

## Release signing

Provide all four values outside source control:

- `OXIGEN_RELEASE_STORE_FILE`
- `OXIGEN_RELEASE_STORE_PASSWORD`
- `OXIGEN_RELEASE_KEY_ALIAS`
- `OXIGEN_RELEASE_KEY_PASSWORD`

Release artifact:

```text
./gradlew :app:lintRelease :app:assembleRelease
```

Distribute the signed `app/build/outputs/apk/release/app-release.apk` only through a trusted
channel. It bundles GPLv3 FFmpeg decoders and its own updater, so it is not a Google Play build.

## Direct-update metadata

`update.json` is intentionally shipped with `enabled: false`, an empty SHA-256 and zero size. The updater must reject this placeholder. It remains disabled until the final signed APK is uploaded to an
immutable GitHub Release path. Generate metadata from the exact artifact:

```text
python3 scripts/generate-update-metadata.py \
  app/build/outputs/apk/release/app-release.apk \
  --version-code 20 \
  --version-name 2.2.9 \
  --download-url https://github.com/flavyu22/OxigenPlayer/releases/download/v2.2.9/OxigenPlayer-v2.2.9.apk
```

Do not hand-edit size or hash values.

## GPL `full` artifacts

Every distributed `full` artifact must be accompanied by the exact corresponding
source and required notices. Create a portable archive with:

```text
python3 scripts/package-gpl-source.py \
  --version 2.2.9 \
  --nextlib-source /trusted/path/nextlib-d774e7e6d65204d409dc679ab173c67030d4a7d4.zip
```

Review [CODECS_AND_AI.md](CODECS_AND_AI.md),
[SOURCE_OFFER_FULL.md](SOURCE_OFFER_FULL.md), the generated archive and the exact
binary together. Legal review remains the distributor's responsibility.

## Dependency verification

Dependency locking is enabled for every configuration. The `dependency-baseline` CI job resolves the graph and uploads generated lock files plus `gradle/verification-metadata.xml` for review. Before publication, review and commit that exact baseline from a trusted connected run; release approval must fail operationally if the reviewed baseline is absent or changed. It cannot be generated correctly from an offline environment without resolved artifacts.

## Required physical-device checks

Before publication, test at least one phone and two Android TV/Google TV devices
with different chipsets. Cover local/USB/NAS playback, HTTPS/HLS/DASH,
Direct-edition HTTP, RTSP/RTSPS, subtitle formats and providers, translation,
quality caps, frame-rate matching, HDMI audio, seek/resume, sleep timer,
diagnostics, PiP, background controls, cold start, configuration changes and the
signed direct-update path.
