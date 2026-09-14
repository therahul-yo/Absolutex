# CI

`ci.yml` is the whole of continuous integration. It runs on GitHub-hosted `ubuntu-latest`
and is **device-free by construction** — no emulator, no attached hardware.

## Jobs

### `corpus` — seconds, no SDK

Runs `tools/make-corpus.py --check`: regenerates the §8 hostile corpus and compares the 13
pure-Python cases against the digests pinned in `tools/corpus-expected-sha256.json`. This is
the fastest signal that a refactor quietly changed what the corpus contains.

`--include-huge` is deliberately not passed. The ~2.25 GiB case would dominate the job for no
signal, and it is excluded from the pinned digests anyway.

### `android` — the real build

In order, with `--continue` so one failure does not hide the rest:

| Step | Command |
|---|---|
| detekt | `./gradlew --continue detekt` |
| Android Lint | `./gradlew --continue lintRelease` |
| Unit tests | `./gradlew --continue test testDebugUnitTest` |
| Release build | `./gradlew :app:assembleRelease` |
| APK size gate | `tools/check-apk-size.py` |

**Both unit-test task names are intentional.** `:core:model` and `:source:api` are
`kotlin-jvm` modules whose tests live under `test`; everything else is an Android module
using `testDebugUnitTest`. Running only the latter silently skips `NaturalOrderTest`.

detekt's SARIF is uploaded to code scanning so findings annotate the pull request diff
directly. That step is `continue-on-error` because pull requests from forks are not granted
`security-events: write`.

## SDK components

The runner ships an Android SDK, but not `compileSdk 37`, and never the exact NDK/CMake pair
`:source:libarchive` pins. The workflow installs them explicitly with `sdkmanager` rather
than relying on AGP's auto-download to guess right:

```
platforms;android-37   ndk;28.2.13676358   cmake;4.1.2
```

These are duplicated into `env:` at the top of `ci.yml` because `sdkmanager` runs before
Gradle can be asked. **Keep them in sync with `gradle/libs.versions.toml`** (`ndk`,
`compileSdk`) and `source/libarchive/build.gradle.kts` (`cmake.version`).

No third-party SDK-setup action is used — one less supply-chain surface, and `sdkmanager` is
already on the runner.

## The APK size gate

`tools/check-apk-size.py` breaks the release APK down by category (dex, native, resources,
assets) into the job summary on every run, and compares the total against
`.github/apk-size-baseline.json`. It fails when the APK exceeds the baseline by more than
**2 % or 64 KiB, whichever is larger** — the absolute floor keeps trivial churn from tripping
the gate.

> **The gate ships disarmed.** `total_bytes` is `null`, so the step reports but never fails.
> Nobody has built a release APK on trusted hardware yet, and inventing a baseline number
> would make the gate lie. Arm it with:
>
> ```sh
> ./gradlew :app:assembleRelease
> python3 tools/check-apk-size.py \
>   --apk app/build/outputs/apk/release/app-release-unsigned.apk --update
> ```
>
> then commit `.github/apk-size-baseline.json`. After that, raising the baseline is a
> reviewable diff with someone on the hook to explain it.

## What CI does *not* run

Both need the reference device (OnePlus 11R, SM8475, Android 16) and are run by hand:

- **Instrumented tests** — `:source:libarchive`'s `connectedAndroidTest`. These open real
  archives through JNI; an x86_64 emulator cannot run an `arm64-v8a`-only build.
- **The startup Macrobenchmark** — `:benchmark`. Timing numbers from a shared cloud runner
  are noise, and the §3 budget is defined against that specific device.

```sh
./gradlew :source:libarchive:connectedAndroidTest
./gradlew :benchmark:connectedBenchmarkAndroidTest
python3 tools/check-startup-budget.py --budget-ms 300
```

## Action versions

Every `uses:` is pinned to an exact tag, resolved against the live repository on 2026-09-14:

| Action | Version |
|---|---|
| `actions/checkout` | `v7.0.1` |
| `actions/setup-java` | `v6.0.1` |
| `actions/setup-python` | `v7.0.0` |
| `actions/upload-artifact` | `v7.0.1` |
| `gradle/actions/setup-gradle` | `v6.3.0` |
| `github/codeql-action/upload-sarif` | `v4.38.0` |
