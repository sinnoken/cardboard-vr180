# Development and build

Toolchain, command-line build, and CI. User installation steps (Android Studio, downloading the APK from GitHub Actions, fixed signing key) are in
[the README's "Installation"](../README.md#installation).

## Toolchain and dependencies

Versions are recorded only in these files, which are authoritative:

| Item | Source |
|---|---|
| Android Gradle Plugin, Kotlin (used only for the Compose compiler plugin), Compose BOM, Media3, smbj, and other libraries | `gradle/libs.versions.toml` |
| Gradle | `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties`; the version CI uses is in `.github/workflows/build.yml` |
| compileSdk, targetSdk, minSdk, Java/JVM target, `versionCode`/`versionName` | `app/build.gradle.kts` |
| Permissions, required hardware (gyroscope, OpenGL ES 3.0), Activity settings | `app/src/main/AndroidManifest.xml` |

Key build settings:

- AGP 9 has built-in Kotlin support, so `app/build.gradle.kts` does not apply the `kotlin-android` plugin.
- Media3's `DataSource` and `MediaCodecSelector` are `@UnstableApi`; lint allows them with `disable += "UnsafeOptInUsageError"`.
- BouncyCastle, a dependency of smbj, brings duplicate `META-INF` files. They are excluded in `packaging.resources.excludes`.
- No Google Play services, analytics, ads, or crash reporting dependencies. Keep it that way when adding dependencies.

## Command-line build

The project does not include `gradlew`. Before the first command-line build, generate the wrapper locally with an installed Gradle, using
the version in `gradle-wrapper.properties`:

```bash
gradle wrapper --gradle-version <version from gradle-wrapper.properties>
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. When a local build passes no version, `versionName` is `0.0.0-dev` and `versionCode` is 1.

## Unit tests

`app/src/test/` holds JVM unit tests (JUnit 4). They cover pure logic that needs no Android runtime:

- Player calculations (seek distance, repeat steps, time format, angle conversion, lens vertical position): `player/PlayerMath.kt`.
- Resolution tags in file names and variant priority: `source/VariantNames.kt`.
- Controller button layouts and automatic selection: `input/ControllerProfile.kt`.
- Round-trip JSON conversion of profiles and defaults for missing fields: `model/Profiles.kt`.

The `org.json` in android.jar is only a stub, so the tests depend on `org.json:json` separately. To run them from the command line: `./gradlew testDebugUnitTest`.
UI, GL, and hardware behavior has no automated tests and must be verified on a device.

## Version numbers

CI computes them from git and passes them to `app/build.gradle.kts` as the Gradle properties `appVersionName` and `appVersionCode`:

- Take the latest `vX.Y.Z` tag and the number of commits after it, `n` (`git describe --tags --long`).
- `versionName`: `X.Y.Z` when `n = 0`, otherwise `X.Y.Z-dev.n`.
- `versionCode`: `(X × 10000 + Y × 100 + Z) × 100 + n`, with `n` capped at 99. A later version is always larger, so it can be installed over an earlier one.
- Without a tag: `0.0.0-dev` and 1.

## CI (.github/workflows/build.yml)

- Triggers: every branch push, pull requests, and manual runs from the Actions page (`workflow_dispatch`). Tag pushes do not build (see [Releases](#releases)).
  Pushes and pull requests that change only `docs/` or `*.md` do not build.
- Dedupe: if an earlier build of the same commit has succeeded or is still running, the whole build is skipped (for example, a fast-forward merge into `main`).
  Only earlier builds count, so two builds pushed at the same time do not skip each other. Manual runs always build.
  For pull requests the PR head commit is compared (the event's own SHA is GitHub's merge commit), so a branch push and its pull request build only once.
- Steps: install JDK 21, the Android SDK, and the pinned Gradle version (with cache); validate the two shaders in `render/Shaders.kt`
  with `glslangValidator` (they compile only at app runtime, so Gradle cannot catch syntax errors); compute the version number;
  run `gradle testDebugUnitTest assembleDebug`; upload `app/build/outputs/apk/debug/*.apk` as the artifact `VR180Player-debug`
  (`VR180Player-debug-random-key` when the fixed signing key is not available, so a release never picks it up).
- Fixed signing key: when the secret `DEBUG_KEYSTORE_BASE64` is set, it is decoded to `~/.android/debug.keystore`, so every build has the same signature.
  For how to generate the key, see [README](../README.md#fixed-signing-key-optional).

## Releases (.github/workflows/release.yml)

A release reuses the APK that CI already built for the commit; nothing is compiled again.

1. Push the changes to `main` and wait for **Build APK** to pass.
2. On the Actions page, choose **Release** › **Run workflow** on `main`. Every field is optional:
   - **bump** (default `patch`) and **version**: a blank version is computed from the latest `vX.Y.Z` tag by bumping the chosen part
     (`v0.1.16` → patch `0.1.17`, minor `0.2.0`, major `1.0.0`; `0.0.1` etc. when there is no tag). A filled-in version is used as is.
   - **notes**: blank notes are built from the commit subjects since the previous tag: `feat:` under "New", `fix:` under "Fixes"
     (the type prefix is removed; `ci:`, `docs:`, `chore:` are left out), plus a compare link. With neither, the notes say "Maintenance release.".
   - **prerelease** (default yes).
3. The workflow:
   - stops if the version is not `X.Y.Z`, `vX.Y.Z` already exists, or the secret `DEBUG_KEYSTORE_BASE64` is missing (an APK with a different signature cannot be installed over the old version);
   - finds the `VR180Player-debug` artifact uploaded by a successful Build APK run of the same commit, and stops if there is none
     (the build failed or is still running, the commit changed only docs, the build had no fixed signing key, or the artifact expired after 90 days).
     In that case run Build APK manually on `main` (manual runs always build) and release again;
   - renames it to `VR180Player-X.Y.Z-debug.apk` and creates the tag `vX.Y.Z` and the Release on that commit.

Tags created by the workflow do not trigger Build APK, so each commit builds only once.
Because the APK was built before the tag existed, its internal `versionName` is the development name of that commit (for example `0.1.16-dev.4`, shown only in the system's app info).
`versionCode` still grows with every commit, so the APK installs over the previous release, and later builds count from the new tag.

If a release needs different notes, edit the Release on GitHub afterwards. To redo a release, delete the Release and its tag first.

## Dependency updates (.github/dependabot.yml)

Dependabot checks Gradle dependencies (`gradle/libs.versions.toml`) and GitHub Actions weekly and opens pull requests; Build APK verifies them.
Minor and patch updates are grouped into one pull request, major updates get their own. Read the changelog before merging.
The Gradle version used by CI is pinned in `build.yml` (`setup-gradle`) and is not updated by Dependabot.

Dependabot runs cannot read repository secrets, so their APK is built with a random key and is not releasable.
After fast-forwarding a Dependabot change into `main`, the `main` build is skipped by dedupe; run Build APK manually before releasing that commit,
or add `DEBUG_KEYSTORE_BASE64` under Settings › Secrets and variables › Dependabot so those builds are signed too.

## Test tools

`tools/make_test_video.sh [output file name]` uses ffmpeg to generate an equirectangular side-by-side VR180 test video:

- 1920×1920 per eye (180°×180°), 3840×1920 in total, 30 fps, 10 seconds, H.264 (High profile, level 5.1), no audio.
- Blue background for the left eye, red for the right, a grid line every 30°, a yellow crosshair marking straight ahead, labels `LEFT 60`, `RIGHT 60`, `UP 60`, `DOWN 60`,
  and elapsed seconds.
- Requires ffmpeg and the DejaVu font (the script hardcodes `/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf`).

For how users use this test video, see [the README's "Test video"](../README.md#test-video).
