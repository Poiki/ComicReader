# Testing

Use JDK 21 and the prepared official MEGA SDK. See [building](building.md) for prerequisites and commands.

## Automated coverage

| Suite | Coverage |
| --- | --- |
| JVM tests | 36 tests covering file identity, progress validation/conflicts, Drive requests, WebDAV parsing and bounds, transfer files, release metadata and RAR decoding |
| Android smoke runner | 15 groups covering library/reader behavior, first cloud download without a prior local import, MEGA metadata, filters/themes, cloud JSON, updater verification, cover caching/cancellation/budget, repeated startup checks and bilingual UI |
| Optional native checks | MEGA JNI loading, writable SDK cache and bounded remote callback behavior without real account credentials |
| Android lint | Run on the debug variant; warnings are reviewed rather than suppressed globally |
| Formatting | Pinned, checksum-verified google-java-format in AOSP mode |

The smoke runner uses an isolated emulator and synthetic data. Its intercepted HTTP tests verify local requests and parsing, not access to live cloud accounts. Native ARM64 tests run through the emulator's native bridge and do not replace physical ARM64 testing.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
.\scripts\format-java.ps1 -Check
```

Install the debug and instrumentation APKs on your test emulator, then run:

```powershell
adb -s emulator-5554 shell am instrument -w org.comicreader.app.test/org.comicreader.app.SmokeInstrumentation
```

Add `-e mega true -e network true` before the component to enable native checks that contact MEGA without a real account. Target the test emulator explicitly; the runner modifies its app's test library and settings.

## Archive fixtures

`app/src/test/resources/comics/` contains project-generated synthetic RAR4/RAR5 files, including solid compression, duplicate entries and encryption rejection. The same fixtures are packaged into Android tests. They contain no third-party comics. See their [fixture README](../app/src/test/resources/comics/README.txt) and [license notices](../THIRD_PARTY_NOTICES.md).

The reader bounds archive size, page count, entry size, dictionary size and total unpacked size. Tests cover natural ordering, repeated page access, unsupported/encrypted archives and malformed input. Solid RAR extraction validates predecessor pages rather than silently skipping decompression errors.

## Known verification limits

The 0.6.0 test suite has 36 JVM tests and 15 Android groups. The new first-download regression starts by moving a private-cache CBZ into a library that no local import has initialized. Cover tests use the actual decoder, persistent thumbnail cache and a simulated remote loader; they verify reuse, interruption and disk eviction. Network tests cover validated/unmetered/metered policy, and UI checks persist the mobile-download switch. Update checks use intercepted HTTP to prove that a recent attempt does not suppress the next foreground open. Language tests switch the actual activity to Spanish, open its localized reader and restore English while checking retained data.

Native MEGA checks verify JNI loading, private cache access and a real authentication-error callback for a reserved nonexistent account. They do not verify successful account login, thumbnail retrieval or comic transfer. Those account operations require user testing. ARM64 emulator results use native translation and do not establish physical-device performance.

A previous real GitHub update download passed digest, package, version and signing checks and opened Android's installer. Play Protect then rejected installation with `INSTALL_FAILED_VERIFICATION_FAILURE`. No protection was disabled, and end-to-end automatic installation is not claimed.

Live MEGA/Drive/WebDAV account workflows, real Wi-Fi/mobile handovers during transfers, Google OAuth approval, physical OnePlus Pad 2 behavior, energy consumption and long-session performance are not established by these tests. Local comic copies have no additional application-level content encryption. Do not interpret bounded caches as a measured battery improvement.
