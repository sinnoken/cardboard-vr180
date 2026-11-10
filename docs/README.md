# Documentation index

Each spec lives in exactly one document; other places link to it. Update this table when you add a document.

| Document | Specs it owns |
|---|---|
| [../README.md](../README.md) | User documentation: installation (GitHub Actions download, fixed signing key, Android Studio), network and permissions, first-time setup and steps for each calibration, **button mapping** (the "Controls" section; comments in `ControllerProfile.kt` point here), video sources, video list options, **file conventions** (extensions, resolution tags, scene calibration file name), conversion commands, test video usage, known limitations |
| [../README.zh-TW.md](../README.zh-TW.md) | Traditional Chinese version of the README. Same content as `README.md`; owns no specs of its own. Update it together with `README.md` |
| [architecture.md](architecture.md) | Components and responsibilities, threading model, playback data flow (Intent extras, buffering, variant selection and downgrade), head tracking, rendering pipeline (lens centers, distortion formula, scene rotation, test patterns, menu panel), input handling (key repeat, one-hand trigger dedupe, action dispatch, focus rules) |
| [data-formats.md](data-formats.md) | Fields, types, defaults, behavior on missing files and fields, and read/write timing of every JSON file in the app's private directory (`device.json`, `lenses.json`, `connection.json`, `folders.json`, `controller.json`, `browse.json`, `positions.json`, `scenes/`) and of the `*.vr180.json` next to videos; lens preset values |
| [development.md](development.md) | Source files for toolchain and dependency versions, key build settings, command-line build, unit test scope, versioning rules, CI pipeline, release process, output spec of `tools/make_test_video.sh` |

Version numbers (AGP, Gradle, SDK, libraries) are not copied into the docs. `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
and `app/build.gradle.kts` are authoritative.
