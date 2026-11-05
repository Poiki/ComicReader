# Building and testing

## Toolchain

Use JDK 21, Android SDK platform 37, Build Tools 36.0.0 and Docker with its Linux container engine. Set `JAVA_HOME` and `ANDROID_HOME`, or put `sdk.dir` in an untracked `local.properties`. The Gradle wrapper pins Gradle 9.5.0; the Android Gradle plugin is 9.3.1. Java source targets Java 17 with core-library desugaring. The app requires Android 9/API 28 and targets API 36.

## MEGA SDK

The SDK is mandatory. Build it once before building the app:

```powershell
docker desktop start --detach
.\scripts\build-mega-native.ps1
```

The Docker image uses Linux, OpenJDK 21 and NDK r28c. Sources are pinned to MEGA SDK `v10.21.0`, commit `d1c0cb76cb98598d93b9fb0bb89612daeb0fb39c`, and vcpkg `ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0`. The build keeps a named Docker cache volume and writes matching Java/Kotlin bindings, native libraries and dependency notices to `.local/mega-sdk`.

The Gradle `mega-sdk` module packages that output. A pre-build check rejects missing libraries or bindings; a types-only AAR cannot produce a functional app. JNI supports `arm64-v8a` and `x86_64`, uses a static C++ runtime and 16 KiB ELF alignment. MediaInfo and unused media integrations are disabled. Native libraries are built from pinned upstream source commits.

## RAR decoder

Build the pinned libarchive decoder before compiling the app:

```powershell
.\scripts\build-archive-native.ps1
```

The script verifies the libarchive 3.8.11 source archive's SHA-256, applies the project's safety patch and builds `libcomicarchive.so` for both Android ABIs with 16 KiB load alignment. It also builds Windows and Linux JNI libraries for host tests. Outputs stay in `.local/archive-native`; Gradle rejects missing or stale native inputs. No prebuilt decoder is committed.

Linux builders can run `scripts/archive-native-build.sh` with `ANDROID_NDK_HOME` and `JAVA_HOME` set, after installing CMake, Ninja, a C compiler, curl and patch. Set `ARCHIVE_SOURCE` to a prepared source tree for an offline build; the script verifies its version and exact patch applicability. Default source acquisition uses the pinned release hash. See [decoder architecture and limits](archive-reader.md).

## App

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

Debug APKs appear under `app/build/outputs/apk/debug/`; release APKs under `app/build/outputs/apk/release/`. Each has ARM64, x86_64 and universal variants. Release outputs use R8 and are unsigned until [publication](updates.md). To build the SDK AAR separately, run `:mega-sdk:assembleRelease`.

## Android checks

Use an empty, disposable emulator. Replace the serial and ABI below with those of that emulator:

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
```

The suite creates its own library and expects fresh test data. Do not clear or run it against a personal installation. Native MEGA checks are separate:

```powershell
adb -s emulator-5554 shell am instrument -w -e mega true -e network true org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
```

The network check uses only a reserved nonexistent account and refuses a saved real session. It verifies JNI, native file I/O and an authentication rejection, not a successful account login. See [coverage and limits](testing.md).

## Formatting and fixtures

```powershell
.\scripts\format-java.ps1
.\scripts\format-java.ps1 -Check
```

Java uses four-space AOSP formatting. The formatter is pinned to version 1.37.0 with a SHA-256 check and is cached outside Git.

RAR fixtures are checked in, so running tests does not require an archiver. To regenerate them, use `python scripts/create-cbr-fixtures.py --rar <path-to-official-rar>`. The script creates only synthetic BMP pages, stored RAR4 headers and compressed RAR5 fixtures. Neither the archiver nor its compression implementation is bundled with the app.

The additional compressed RAR4 text case is adapted from the prepared libarchive source with its BSD notice retained in the test resources. It exercises the raw adapter, not bitmap rendering. The fixture generator accepts `--libarchive <source-directory>` for that case.
