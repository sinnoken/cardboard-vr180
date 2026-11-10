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

- Triggers: every push (including tags), pull requests, and manual runs from the Actions page (`workflow_dispatch`).
  Pushes and pull requests that change only `docs/` or `*.md` do not build. This rule does not apply to tags.
- Dedupe: if an earlier build of the same commit has succeeded or is still running, the whole build is skipped (for example, a fast-forward merge into `main`).
  Only earlier builds count, so two builds pushed at the same time do not skip each other. Tags and manual runs always build.
- Steps: install JDK 21, the Android SDK, and the pinned Gradle version (with cache); validate the two shaders in `render/Shaders.kt`
  with `glslangValidator` (they compile only at app runtime, so Gradle cannot catch syntax errors); compute the version number;
  run `gradle testDebugUnitTest assembleDebug`; upload `app/build/outputs/apk/debug/*.apk` as the artifact `VR180Player-debug`.
- Fixed signing key: when the secret `DEBUG_KEYSTORE_BASE64` is set, it is decoded to `~/.android/debug.keystore`, so every build has the same signature.
  For how to generate the key, see [README](../README.md#fixed-signing-key-optional).

## Releases

When a `vX.Y.Z` tag is pushed, CI renames the APK to `VR180Player-X.Y.Z-debug.apk` after the build and attaches it to the GitHub Release of the same name
(overwriting a file of the same name if the Release exists, or creating a pre-release if not). Without `DEBUG_KEYSTORE_BASE64` this step fails,
because an APK with a different signature cannot be installed over the old version.

To attach your own notes, create the Release first and let it create the tag:

```bash
gh release create vX.Y.Z --target <commit> --prerelease --title "vX.Y.Z (debug): …" --notes-file notes.md
```

Creating the tag triggers CI, and the APK is attached automatically.

Changes to release do not need a branch push and CI run first. Create the Release directly on the commit to release. The tag build both verifies (tests, shaders)
and produces the APK. After it passes, fast-forward merge into `main`; the `main` build is skipped by dedupe. Each release builds only once.
If the build fails, the Release has no APK. Fix it and release again with a new commit and a new version number.

## Test tools

`tools/make_test_video.sh [output file name]` uses ffmpeg to generate an equirectangular side-by-side VR180 test video:

- 1920×1920 per eye (180°×180°), 3840×1920 in total, 30 fps, 10 seconds, H.264 (High profile, level 5.1), no audio.
- Blue background for the left eye, red for the right, a grid line every 30°, a yellow crosshair marking straight ahead, labels `LEFT 60`, `RIGHT 60`, `UP 60`, `DOWN 60`,
  and elapsed seconds.
- Requires ffmpeg and the DejaVu font (the script hardcodes `/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf`).

For how users use this test video, see [the README's "Test video"](../README.md#test-video).
