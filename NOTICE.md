# Third-party code

This project contains **unmodified copies** of the following directories from the Google
Cardboard SDK repository, licensed under the Apache License 2.0 (see `LICENSE-cardboard`):

| Folder here | Upstream path | Purpose |
|---|---|---|
| `sdk/` | `sdk/` | Native Cardboard SDK (head tracking, lens distortion, QR scanning) and its Gradle module |
| `java_api/` | `java_api/` | Java layer: `CardboardView`, `HeadTransform`, JNI source |
| `proto/` | `proto/` | Viewer-parameter protobuf definition used by the SDK build |
| `third_party/unity_plugin_api/` | `third_party/unity_plugin_api/` | Header files the SDK's CMake build includes |

- Upstream: https://github.com/googlevr/cardboard
- Commit: `8bd424da7f1e399255a1b6e7c02be8e281ecc974` (committed 2026-10-07)
- Left out: the iOS project file `sdk/sdk.xcodeproj`.
- `gradle/wrapper/gradle-wrapper.properties` is copied from the same commit.

The build setup in `build.gradle`, `app/build.gradle` and `app/CMakeLists.txt` follows the
upstream `paperscope` demo app's approach. To update the SDK, replace the four folders with a
newer upstream checkout and keep the Gradle/AGP versions in the root `build.gradle` in line with
upstream's.

Other libraries are downloaded by Gradle at build time: AndroidX (Apache 2.0), Material
Components (Apache 2.0), Media3 (Apache 2.0).

Icons on the start screen are from Material Icons by Google (Apache 2.0).
